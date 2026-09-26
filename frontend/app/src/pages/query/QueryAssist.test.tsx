import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ExplanationCard, FixWithAi, useAssistReady, WriteWithNokRak } from './QueryAssist';
import type { Refusal } from '../../api/accessRequests';

const assistFix = jest.fn();
const assistSql = jest.fn();
const fetchMyLlmSetting = jest.fn();

jest.mock('../../api/llm', () => ({
  assistFix: (...args: unknown[]) => assistFix(...args),
  assistExplain: jest.fn(),
  assistSql: (...args: unknown[]) => assistSql(...args),
  fetchMyLlmSetting: () => fetchMyLlmSetting(),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
}));

const SQL = 'SELECT id, emial FROM sales.customer';

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function refusal(overrides: Partial<Refusal> = {}): Refusal {
  return {
    message: 'The source rejected the enforced statement: column "emial" does not exist',
    fixable: true,
    ...overrides,
  };
}

function renderFix(over: Partial<Refusal> = {}, onUse = jest.fn()) {
  render(
    wrap(
      <FixWithAi engine="POSTGRES" onUse={onUse} refusal={refusal(over)} sourceId="src-1" sql={SQL} />
    )
  );
  return onUse;
}

beforeEach(() => {
  assistFix.mockReset();
  assistSql.mockReset();
  fetchMyLlmSetting.mockReset();
});

describe('Fix with AI', () => {
  it('is not offered for a refusal a policy made', () => {
    renderFix({ fixable: false, assetFqn: 'demo-pg.salesdb.sales.customer' });
    expect(screen.queryByRole('button', { name: /Fix with AI/ })).not.toBeInTheDocument();
  });

  it('is not offered when the server did not say', () => {
    renderFix({ fixable: undefined });
    expect(screen.queryByRole('button', { name: /Fix with AI/ })).not.toBeInTheDocument();
  });

  it('sends the refused statement and the refusal, and puts the suggestion in the editor only when asked', async () => {
    const fixed = 'SELECT id, email FROM sales.customer';
    assistFix.mockResolvedValue({ sql: fixed, model: 'gpt-x', tables: [], problem: null, personal: false });
    const onUse = renderFix();

    fireEvent.click(screen.getByRole('button', { name: /Fix with AI/ }));
    const card = await screen.findByRole('region', { name: 'Suggested statement' });
    expect(assistFix).toHaveBeenCalledWith({
      sql: SQL,
      error: refusal().message,
      sourceId: 'src-1',
      engine: 'POSTGRES',
    });
    expect(card).toHaveTextContent(fixed);
    // Shown, not applied: nothing reaches the editor until the reader says so.
    expect(onUse).not.toHaveBeenCalled();

    fireEvent.click(within(card).getByRole('button', { name: 'Use this' }));
    expect(onUse).toHaveBeenCalledWith(fixed);
  });

  it('can be dismissed and asked again', async () => {
    assistFix.mockResolvedValue({ sql: 'SELECT 1', model: 'gpt-x', tables: [], problem: null, personal: false });
    renderFix();
    fireEvent.click(screen.getByRole('button', { name: /Fix with AI/ }));
    const card = await screen.findByRole('region', { name: 'Suggested statement' });
    fireEvent.click(within(card).getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByRole('region', { name: 'Suggested statement' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Fix with AI/ })).toBeInTheDocument();
  });

  it('says so when the assistant found nothing to change', async () => {
    assistFix.mockResolvedValue({
      sql: '',
      model: 'gpt-x',
      tables: [],
      problem: 'The assistant did not find anything to change in this statement.',
      personal: false,
    });
    renderFix();
    fireEvent.click(screen.getByRole('button', { name: /Fix with AI/ }));
    expect(await screen.findByText(/did not find anything to change/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Use this' })).not.toBeInTheDocument();
  });

  it('shows the server when it refuses', async () => {
    assistFix.mockRejectedValue(new Error('The assistant is switched off for this account.'));
    renderFix();
    fireEvent.click(screen.getByRole('button', { name: /Fix with AI/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('switched off');
  });
});

describe('Explanation', () => {
  const explanation = { text: 'Reads customers.\n- id\n- email', model: 'gpt-x', tables: [], personal: true };

  it('shows the text as text, never as markup', () => {
    render(
      <ExplanationCard
        about={SQL}
        current={SQL}
        error={null}
        explanation={{ ...explanation, text: '<img src=x onerror=alert(1)> reads rows' }}
        onDismiss={jest.fn()}
        pending={false}
      />
    );
    const card = screen.getByRole('region', { name: 'Explanation' });
    expect(card.querySelector('img')).toBeNull();
    expect(card).toHaveTextContent('<img src=x onerror=alert(1)> reads rows');
    expect(card).toHaveTextContent('your gateway');
  });

  it('says when the editor has moved on from what was explained', () => {
    render(
      <ExplanationCard
        about={SQL}
        current="SELECT 1"
        error={null}
        explanation={explanation}
        onDismiss={jest.fn()}
        pending={false}
      />
    );
    expect(screen.getByText(/About an earlier version/)).toBeInTheDocument();
  });

  it('draws nothing until asked, and can be dismissed', () => {
    const onDismiss = jest.fn();
    const { rerender } = render(
      <ExplanationCard about="" current={SQL} error={null} explanation={undefined} onDismiss={onDismiss} pending={false} />
    );
    expect(screen.queryByRole('region', { name: 'Explanation' })).not.toBeInTheDocument();
    rerender(
      <ExplanationCard about={SQL} current={SQL} error={null} explanation={explanation} onDismiss={onDismiss} pending={false} />
    );
    expect(screen.queryByText(/About an earlier version/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss the explanation' }));
    expect(onDismiss).toHaveBeenCalled();
  });
});

describe('useAssistReady', () => {
  function Probe() {
    return <span>{useAssistReady() ? 'ready' : 'not ready'}</span>;
  }

  it('is ready only when the server says the assistant is available', async () => {
    fetchMyLlmSetting.mockResolvedValue({ available: true });
    render(wrap(<Probe />));
    expect(screen.getByText('not ready')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('ready')).toBeInTheDocument());
  });

  it('stays off when the setting cannot be read', async () => {
    fetchMyLlmSetting.mockRejectedValue(new Error('403'));
    render(wrap(<Probe />));
    await waitFor(() => expect(fetchMyLlmSetting).toHaveBeenCalled());
    expect(screen.getByText('not ready')).toBeInTheDocument();
  });
});

describe('NokRak, write it', () => {
  function renderWrite(sourceId = 'src-1') {
    const onUse = jest.fn();
    const onClose = jest.fn();
    render(
      wrap(<WriteWithNokRak engine="POSTGRES" onClose={onClose} onUse={onUse} sourceId={sourceId} />)
    );
    return { onUse, onClose, box: screen.getByRole('textbox') };
  }

  it('asks with the question, the source and its dialect, and fills the editor only on Use this', async () => {
    assistSql.mockResolvedValue({
      sql: 'SELECT count(*) FROM sales.customer',
      model: 'test-model',
      tables: ['demo-pg.salesdb.sales.customer'],
      problem: null,
      personal: false,
    });
    const { onUse, box } = renderWrite();

    fireEvent.change(box, { target: { value: 'How many customers?' } });
    fireEvent.click(screen.getByRole('button', { name: 'Write it' }));

    const card = await screen.findByRole('region', { name: 'Statement NokRak wrote' });
    expect(assistSql).toHaveBeenCalledWith({
      question: 'How many customers?',
      sourceId: 'src-1',
      engine: 'POSTGRES',
    });
    expect(within(card).getByText('SELECT count(*) FROM sales.customer')).toBeInTheDocument();
    expect(within(card).getByText('demo-pg.salesdb.sales.customer')).toBeInTheDocument();
    // Shown is not used: nothing reaches the editor until the reader says so.
    expect(onUse).not.toHaveBeenCalled();

    fireEvent.click(within(card).getByRole('button', { name: 'Use this' }));
    expect(onUse).toHaveBeenCalledWith('SELECT count(*) FROM sales.customer');
  });

  it('says why when it could not write one, and offers nothing to use', async () => {
    assistSql.mockResolvedValue({
      sql: '',
      model: 'm',
      tables: [],
      problem: 'No table here has anything about invoices.',
      personal: false,
    });
    const { box } = renderWrite();

    fireEvent.change(box, { target: { value: 'invoices?' } });
    fireEvent.keyDown(box, { key: 'Enter' });

    expect(await screen.findByText('No table here has anything about invoices.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Use this' })).not.toBeInTheDocument();
  });

  it('asks for a source before it asks the assistant, without an alarm', () => {
    const { box } = renderWrite('');

    fireEvent.change(box, { target: { value: 'How many customers?' } });
    expect(screen.getByRole('button', { name: 'Write it' })).toBeDisabled();
    fireEvent.keyDown(box, { key: 'Enter' });

    expect(screen.getByText(/Choose a source first/)).toBeInTheDocument();
    // Said as guidance, not as an error: nothing has gone wrong yet.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(assistSql).not.toHaveBeenCalled();
  });

  it('closes on Escape', () => {
    const { onClose, box } = renderWrite();
    fireEvent.keyDown(box, { key: 'Escape' });
    expect(onClose).toHaveBeenCalled();
  });

  it('closes from its own button', () => {
    const { onClose } = renderWrite();
    fireEvent.click(screen.getByRole('button', { name: 'Close NokRak' }));
    expect(onClose).toHaveBeenCalled();
  });
});
