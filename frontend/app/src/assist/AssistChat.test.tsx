import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { AssistChat, historyFrom, pageContext, plainText, safeRoute } from './AssistChat';
import { useAssistStore } from './assistStore';
import type { ChatReply } from '../api/llm';

const chatWithAssistant = jest.fn();

jest.mock('../api/llm', () => ({
  chatWithAssistant: (...args: unknown[]) => chatWithAssistant(...args),
}));

jest.mock('../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
}));

function reply(over: Partial<ChatReply> = {}): ChatReply {
  return { text: 'Hello!', cards: [], toolsUsed: [], model: 'gpt-test', personal: false, ...over };
}

function Where() {
  return <span data-testid="where">{useLocation().pathname}</span>;
}

function renderChat(path = '/catalog/demo-pg.salesdb.sales.customer', onUsed = jest.fn()) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  const tree = (children: ReactNode) => (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        {children}
        <Routes>
          <Route element={<Where />} path="*" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
  render(tree(<AssistChat onUsed={onUsed} />));
  return { onUsed };
}

function type(text: string) {
  const box = screen.getByLabelText(/Message NokRak/);
  fireEvent.change(box, { target: { value: text } });
  fireEvent.keyDown(box, { key: 'Enter' });
}

beforeEach(() => {
  chatWithAssistant.mockReset();
  useAssistStore.setState({ chat: [], ask: null, sql: null, policy: null, sourceId: null, open: true });
});

describe('the helpers', () => {
  it('reads the table off a catalogue page', () => {
    expect(pageContext('/catalog/demo-pg.salesdb.sales.%22odd%20name%22')).toEqual({
      path: '/catalog/demo-pg.salesdb.sales.%22odd%20name%22',
      assetFqn: 'demo-pg.salesdb.sales."odd name"',
    });
    expect(pageContext('/catalog').assetFqn).toBeNull();
    expect(pageContext('/query').assetFqn).toBeNull();
    // A broken escape is kept as it came rather than thrown.
    expect(pageContext('/catalog/a%E0%A4').assetFqn).toBe('a%E0%A4');
  });

  it('follows only paths inside the console', () => {
    expect(safeRoute('/query')).toBe('/query');
    expect(safeRoute('https://evil.example.test')).toBeNull();
    expect(safeRoute('//evil.example.test/x')).toBeNull();
    expect(safeRoute('/\\evil')).toBeNull();
    expect(safeRoute('javascript:alert(1)')).toBeNull();
    expect(safeRoute(null)).toBeNull();
  });

  it('takes the Markdown marks out of an answer, and nothing else', () => {
    expect(plainText('## Found\n**You can query**\n- `sales.customer`')).toBe(
      'Found\nYou can query\n- sales.customer'
    );
    expect(plainText('2 * 3 = 6, a_b_c')).toBe('2 * 3 = 6, a_b_c');
  });

  it('sends back only the conversation, never the console’s own errors', () => {
    const history = historyFrom([
      { role: 'user', text: 'hi' },
      { role: 'assistant', text: 'Hello!' },
      { role: 'error', text: 'The gateway did not answer' },
      { role: 'user', text: '  ' },
    ]);
    expect(history).toEqual([
      { role: 'user', content: 'hi' },
      { role: 'assistant', content: 'Hello!' },
    ]);
    const long = Array.from({ length: 30 }, (_, i) => ({ role: 'user' as const, text: `m${i}` }));
    expect(historyFrom(long)).toHaveLength(16);
    expect(historyFrom(long)[15].content).toBe('m29');
  });
});

describe('AssistChat', () => {
  it('sends the message with the page it was asked on and shows the answer', async () => {
    chatWithAssistant.mockResolvedValue(reply());
    renderChat();

    type('what is in this table?');

    await screen.findByText('Hello!');
    expect(screen.getByText('what is in this table?')).toBeInTheDocument();
    expect(chatWithAssistant).toHaveBeenCalledWith({
      message: 'what is in this table?',
      history: [],
      path: '/catalog/demo-pg.salesdb.sales.customer',
      assetFqn: 'demo-pg.salesdb.sales.customer',
      sourceId: null,
    });
  });

  it('sends the earlier lines as history, not the new one twice', async () => {
    chatWithAssistant.mockResolvedValue(reply({ text: 'Second answer' }));
    useAssistStore.setState({
      chat: [
        { role: 'user', text: 'first' },
        { role: 'assistant', text: 'First answer' },
      ],
    });
    renderChat('/query');

    type('second');

    await screen.findByText('Second answer');
    expect(chatWithAssistant.mock.calls[0][0].history).toEqual([
      { role: 'user', content: 'first' },
      { role: 'assistant', content: 'First answer' },
    ]);
  });

  it('shows the model’s text as text, never as markup', async () => {
    chatWithAssistant.mockResolvedValue(
      reply({ text: '<img src=x onerror="alert(1)"><b>bold</b>\nline two' })
    );
    renderChat();

    type('hi');

    const answer = await screen.findByText(/<b>bold<\/b>/);
    expect(answer.querySelector('img')).toBeNull();
    expect(answer.querySelector('b')).toBeNull();
    expect(answer.className).toContain('whitespace-pre-line');
  });

  it('shows a refusal as the console’s error, and does not send it back', async () => {
    chatWithAssistant.mockRejectedValueOnce(new Error('Chat with ARAK is not offered to your role'));
    renderChat();

    type('hi');

    expect(await screen.findByRole('alert')).toHaveTextContent('not offered to your role');
    chatWithAssistant.mockResolvedValue(reply({ text: 'ok now' }));
    type('again');
    await screen.findByText('ok now');
    expect(chatWithAssistant.mock.calls[1][0].history).toEqual([{ role: 'user', content: 'hi' }]);
  });

  it('puts a statement in the editor, on its source, and does not run it', async () => {
    chatWithAssistant.mockResolvedValue(
      reply({
        text: 'Here you go.',
        cards: [
          {
            kind: 'sql',
            title: 'Customers by branch',
            text: 'SELECT branch_code, count(*) FROM sales.customer GROUP BY 1',
            route: '/query',
            sourceId: 'src-1',
            engine: 'POSTGRES',
            assetFqn: null,
            access: null,
          },
        ],
      })
    );
    const { onUsed } = renderChat('/catalog');

    type('customers by branch');
    fireEvent.click(await screen.findByRole('button', { name: 'Put it in the editor' }));

    expect(useAssistStore.getState().sql).toMatchObject({
      text: 'SELECT branch_code, count(*) FROM sales.customer GROUP BY 1',
      sourceId: 'src-1',
    });
    expect(screen.getByTestId('where')).toHaveTextContent('/query');
    expect(onUsed).toHaveBeenCalled();
  });

  it('loads a policy draft into the builder', async () => {
    chatWithAssistant.mockResolvedValue(
      reply({
        cards: [
          {
            kind: 'policy',
            title: 'Draft policy',
            text: '{"name":"Mask email","lifecycleState":"DRAFT"}',
            route: '/policies/new',
            sourceId: null,
            engine: null,
            assetFqn: null,
            access: null,
          },
        ],
      })
    );
    renderChat('/');

    type('mask email');
    fireEvent.click(await screen.findByRole('button', { name: 'Load into the builder' }));

    expect(useAssistStore.getState().policy?.text).toContain('Mask email');
    expect(screen.getByTestId('where')).toHaveTextContent('/policies/new');
  });

  it('marks each table it found as readable or to be requested', async () => {
    chatWithAssistant.mockResolvedValue(
      reply({
        text: 'Two tables.',
        cards: [
          {
            kind: 'asset',
            title: 'demo-pg.salesdb.sales.customer',
            text: 'Customers',
            route: '/catalog/demo-pg.salesdb.sales.customer',
            sourceId: null,
            engine: null,
            assetFqn: 'demo-pg.salesdb.sales.customer',
            access: 'READABLE',
          },
          {
            kind: 'asset',
            title: 'demo-pg.salesdb.hr.salary',
            text: null,
            route: '/catalog/demo-pg.salesdb.hr.salary',
            sourceId: null,
            engine: null,
            assetFqn: 'demo-pg.salesdb.hr.salary',
            access: 'REQUESTABLE',
          },
          {
            kind: 'link',
            title: 'Somewhere else',
            text: null,
            route: 'https://evil.example.test',
            sourceId: null,
            engine: null,
            assetFqn: null,
            access: null,
          },
        ],
      })
    );
    renderChat('/');

    type('find customer data');

    await screen.findByText('Two tables.');
    const cards = screen.getAllByTestId('chat-card-asset');
    expect(cards[0]).toHaveTextContent('You can query it');
    expect(cards[1]).toHaveTextContent('Request access');
    expect(screen.getByRole('link', { name: 'demo-pg.salesdb.sales.customer' })).toHaveAttribute(
      'href',
      '/catalog/demo-pg.salesdb.sales.customer'
    );
    // A route off the console is not drawn at all.
    expect(screen.queryByText(/Somewhere else/)).not.toBeInTheDocument();
  });

  it('sends a question asked from search as if it were typed', async () => {
    chatWithAssistant.mockResolvedValue(reply({ text: 'Found it.' }));
    renderChat('/catalog');

    act(() => useAssistStore.getState().askArak('where are phone numbers?'));

    await screen.findByText('Found it.');
    expect(chatWithAssistant.mock.calls[0][0].message).toBe('where are phone numbers?');
    expect(useAssistStore.getState().ask).toBeNull();
  });

  it('starts over', async () => {
    useAssistStore.setState({ chat: [{ role: 'user', text: 'old' }] });
    renderChat('/');

    fireEvent.click(screen.getByRole('button', { name: 'New conversation' }));

    await waitFor(() => expect(screen.queryByText('old')).not.toBeInTheDocument());
    expect(useAssistStore.getState().chat).toEqual([]);
  });
});
