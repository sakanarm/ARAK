import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import PolicyExplain from './PolicyExplain';

const assistExplainPolicy = jest.fn();
let assistReady = true;
const readyFor: string[] = [];

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: unknown, fallback: string) =>
    (error as { message?: string })?.message ?? fallback,
}));
jest.mock('../../api/llm', () => ({
  assistExplainPolicy: (...args: unknown[]) => assistExplainPolicy(...args),
}));
jest.mock('../../assist/useAssist', () => ({
  useAssistReady: (feature: string) => {
    readyFor.push(feature);
    return assistReady;
  },
}));

const ID = '33333333-3333-3333-3333-333333333333';

function renderPanel() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <PolicyExplain policyId={ID} />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  assistExplainPolicy.mockReset();
  assistReady = true;
  readyFor.length = 0;
});

describe('PolicyExplain', () => {
  it('is not drawn when the job is not offered', () => {
    assistReady = false;
    const { container } = renderPanel();

    expect(container).toBeEmptyDOMElement();
    expect(readyFor).toContain('EXPLAIN_POLICY');
  });

  it('asks nothing until pressed, and says what it will and will not see', () => {
    renderPanel();

    expect(screen.getByRole('button', { name: 'Explain with NokRak' })).toBeInTheDocument();
    expect(screen.getByText(/It sees no data/)).toBeInTheDocument();
    expect(assistExplainPolicy).not.toHaveBeenCalled();
  });

  it('shows the reading as NokRak’s, with the Simulator as what decides', async () => {
    assistExplainPolicy.mockResolvedValue({
      text: 'It hashes every PII column.\nA deny elsewhere still wins.',
      model: 'gpt-test',
      personal: false,
    });
    renderPanel();

    fireEvent.change(screen.getByRole('combobox', { name: 'Explain in' }), {
      target: { value: 'Thai' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Explain with NokRak' }));

    expect(await screen.findByText(/It hashes every PII column\./)).toBeInTheDocument();
    expect(assistExplainPolicy).toHaveBeenCalledWith({ policyId: ID, language: 'Thai' });
    expect(screen.getByText(/it has not seen any data/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Simulator' })).toHaveAttribute('href', '/simulator');
    expect(screen.getByText(/gpt-test/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Explain again' })).toBeInTheDocument();
  });

  it('says why when it cannot', async () => {
    assistExplainPolicy.mockRejectedValue(new Error('Explain a policy is not offered to your role'));
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Explain with NokRak' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        'Explain a policy is not offered to your role'
      )
    );
  });
});
