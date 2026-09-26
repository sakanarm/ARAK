import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { AskArakButton, catalogQuestion } from './AskArak';
import { useAssistStore } from './assistStore';

const fetchMyLlmSetting = jest.fn();
const fetchOfferedFeatures = jest.fn();

jest.mock('../api/llm', () => ({
  fetchMyLlmSetting: () => fetchMyLlmSetting(),
  fetchOfferedFeatures: () => fetchOfferedFeatures(),
}));

function renderButton(term: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AskArakButton term={term} />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  fetchMyLlmSetting.mockReset().mockResolvedValue({ available: true, enabled: true });
  fetchOfferedFeatures.mockReset().mockResolvedValue(['CHAT', 'CATALOG_SEARCH']);
  useAssistStore.setState({ open: false, ask: null });
});

describe('catalogQuestion', () => {
  it('turns a term into a question about what the tables hold', () => {
    expect(catalogQuestion('  phone numbers ')).toBe(
      'Find the tables in the catalogue that hold phone numbers. Tell me which I can query and which I would have to request.'
    );
    expect(catalogQuestion('   ')).toBe('');
  });
});

describe('AskArakButton', () => {
  it('hands the question to the chat and opens it', async () => {
    renderButton('phone numbers');

    fireEvent.click(await screen.findByRole('button', { name: 'Ask NokRak' }));

    const state = useAssistStore.getState();
    expect(state.open).toBe(true);
    expect(state.ask?.text).toContain('hold phone numbers');
  });

  it('only opens the chat when nothing was typed', async () => {
    renderButton('');

    fireEvent.click(await screen.findByRole('button', { name: 'Ask NokRak' }));

    expect(useAssistStore.getState().open).toBe(true);
    expect(useAssistStore.getState().ask).toBeNull();
  });

  it('is not drawn where catalogue search is not offered', async () => {
    fetchOfferedFeatures.mockResolvedValue(['CHAT']);
    renderButton('phone');
    await act(async () => {});
    expect(screen.queryByRole('button', { name: 'Ask NokRak' })).not.toBeInTheDocument();
  });

  it('is not drawn where the assistant does not answer', async () => {
    fetchMyLlmSetting.mockResolvedValue({ available: false, enabled: true });
    renderButton('phone');
    await act(async () => {});
    expect(screen.queryByRole('button', { name: 'Ask NokRak' })).not.toBeInTheDocument();
    expect(fetchOfferedFeatures).not.toHaveBeenCalled();
  });
});
