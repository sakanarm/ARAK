import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import AssistDock from './AssistDock';
import { useAssistStore } from './assistStore';
import type { AssistFeature } from '../api/llm';

const fetchMyLlmSetting = jest.fn();
const fetchOfferedFeatures = jest.fn();

jest.mock('../api/llm', () => ({
  fetchMyLlmSetting: () => fetchMyLlmSetting(),
  fetchOfferedFeatures: () => fetchOfferedFeatures(),
  chatWithAssistant: jest.fn(),
  assistSql: jest.fn(),
  assistPolicy: jest.fn(),
}));

jest.mock('../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}));

function setUp(offered: AssistFeature[], available = true) {
  fetchMyLlmSetting.mockResolvedValue({ available, enabled: true });
  fetchOfferedFeatures.mockResolvedValue(offered);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AssistDock />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  fetchMyLlmSetting.mockReset();
  fetchOfferedFeatures.mockReset();
  window.sessionStorage.setItem('arak.nokrak.greeted', '1');
  useAssistStore.setState({ open: false, mode: 'idle', chat: [], ask: null, sourceId: null });
});

describe('AssistDock', () => {
  it('draws nothing for an account whose assistant does not answer', async () => {
    setUp(['CHAT'], false);
    await act(async () => {});
    expect(screen.queryByRole('button', { name: 'Open the assistant' })).not.toBeInTheDocument();
    expect(fetchOfferedFeatures).not.toHaveBeenCalled();
  });

  it('opens on the conversation where chat is offered', async () => {
    setUp(['CHAT']);
    fireEvent.click(await screen.findByRole('button', { name: 'Open the assistant' }));

    expect(await screen.findByLabelText(/Message NokRak/)).toBeInTheDocument();
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
  });

  it('gives both tabs on the query page, the writer first', async () => {
    useAssistStore.setState({ mode: 'sql' });
    setUp(['CHAT', 'WRITE_SQL']);
    fireEvent.click(await screen.findByRole('button', { name: 'Open the assistant' }));

    expect(await screen.findByRole('tab', { name: 'Write a query' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    expect(screen.getByLabelText('What do you want to know?')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'Chat' }));
    expect(screen.getByLabelText(/Message NokRak/)).toBeInTheDocument();
  });

  it('shows only the writer to a role offered only that', async () => {
    useAssistStore.setState({ mode: 'sql' });
    setUp(['WRITE_SQL']);
    fireEvent.click(await screen.findByRole('button', { name: 'Open the assistant' }));

    expect(await screen.findByLabelText('What do you want to know?')).toBeInTheDocument();
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Message NokRak/)).not.toBeInTheDocument();
  });

  it('says where it works when nothing on this page is offered', async () => {
    setUp(['WRITE_SQL']);
    fireEvent.click(await screen.findByRole('button', { name: 'Open the assistant' }));

    expect(await screen.findByText('The assistant works in two places:')).toBeInTheDocument();
  });

  it('turns to the conversation when a question comes from search', async () => {
    useAssistStore.setState({ mode: 'sql' });
    setUp(['CHAT', 'WRITE_SQL']);
    await screen.findByRole('button', { name: 'Open the assistant' });

    act(() => useAssistStore.getState().askArak('where are phone numbers?'));

    expect(await screen.findByRole('tab', { name: 'Chat' })).toHaveAttribute('aria-selected', 'true');
  });
});
