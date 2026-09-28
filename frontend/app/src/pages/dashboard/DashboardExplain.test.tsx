import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import DashboardExplain from './DashboardExplain';

const assistExplainDashboard = jest.fn();
let assistReady = true;
const readyFor: string[] = [];

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: unknown, fallback: string) =>
    (error as { message?: string })?.message ?? fallback,
}));
jest.mock('../../api/llm', () => ({
  assistExplainDashboard: (...args: unknown[]) => assistExplainDashboard(...args),
}));
jest.mock('../../assist/useAssist', () => ({
  useAssistReady: (feature: string) => {
    readyFor.push(feature);
    return assistReady;
  },
}));

function renderPanel() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <DashboardExplain days={90} label="Confidential" />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  assistExplainDashboard.mockReset();
  assistReady = true;
  readyFor.length = 0;
});

describe('DashboardExplain', () => {
  it('is not drawn when the job is not offered', () => {
    assistReady = false;
    const { container } = renderPanel();

    expect(container).toBeEmptyDOMElement();
    expect(readyFor).toContain('EXPLAIN_DASHBOARD');
  });

  it('asks nothing until pressed, and says what it will and will not see', () => {
    renderPanel();

    expect(screen.getByRole('button', { name: 'Explain with NokRak' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'About' })).toHaveValue('ALL');
    expect(screen.getByText(/It sees no data, and no one's name/)).toBeInTheDocument();
    expect(assistExplainDashboard).not.toHaveBeenCalled();
  });

  it('reads the window, label and part asked about, and labels the answer as NokRak’s', async () => {
    assistExplainDashboard.mockResolvedValue({
      text: 'Two grants end this week.\nanalyst_b holds the first.',
      model: 'gpt-test',
      personal: false,
    });
    renderPanel();

    fireEvent.change(screen.getByRole('combobox', { name: 'About' }), {
      target: { value: 'ACCESS' },
    });
    fireEvent.change(screen.getByRole('combobox', { name: 'Explain in' }), {
      target: { value: 'Thai' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Explain with NokRak' }));

    expect(await screen.findByText(/Two grants end this week\./)).toBeInTheDocument();
    expect(assistExplainDashboard).toHaveBeenCalledWith({
      days: 90,
      label: 'Confidential',
      focus: 'ACCESS',
      language: 'Thai',
    });
    expect(screen.getByText(/it has not seen any data/)).toBeInTheDocument();
    expect(screen.getByText(/The numbers on the dashboard are what count/)).toBeInTheDocument();
    expect(screen.getByText(/gpt-test/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Explain again' })).toBeInTheDocument();
  });

  it('says why when it cannot', async () => {
    assistExplainDashboard.mockRejectedValue(new Error('Explain the dashboard is not offered to your role'));
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Explain with NokRak' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        'Explain the dashboard is not offered to your role'
      )
    );
  });
});
