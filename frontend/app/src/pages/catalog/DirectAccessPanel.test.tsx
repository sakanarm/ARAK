import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { DirectAccessPanel } from './DirectAccessPanel';
import type { DirectAccessCheck } from '../../api/access';

const check = jest.fn();

jest.mock('../../api/access', () => ({
  ...jest.requireActual('../../api/access'),
  checkDirectAccess: (...args: unknown[]) => check(...args),
}));

const FQN = 'demo-pg.salesdb.sales.customer';

function result(overrides: Partial<DirectAccessCheck> = {}): DirectAccessCheck {
  return {
    assetFqn: FQN,
    source: 'salesdb',
    engine: 'POSTGRES',
    mode: 'PROXY',
    secureViewInstalled: false,
    guarded: true,
    schema: 'sales',
    table: 'customer',
    connectedAs: 'arak_reader',
    holders: [
      {
        name: 'analysts',
        login: false,
        via: ['GRANT'],
        members: ['ann'],
        memberCount: 3,
        self: false,
      },
      { name: 'arak_reader', login: true, via: ['GRANT'], members: [], memberCount: 0, self: true },
    ],
    bypass: 1,
    verdict: 'EXPOSED',
    message: '1 principal can read sales.customer without the platform.',
    checkedAt: '2026-09-27T08:00:00Z',
    millis: 4,
    ...overrides,
  };
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <DirectAccessPanel fqn={FQN} />
    </QueryClientProvider>
  );
}

beforeEach(() => check.mockReset());

it('asks the source only when told to', async () => {
  check.mockResolvedValue(result());
  renderPanel();
  expect(check).not.toHaveBeenCalled();

  fireEvent.click(screen.getByRole('button', { name: /check at the source/i }));

  await waitFor(() => expect(check).toHaveBeenCalledWith(FQN));
  expect(await screen.findByTestId('direct-verdict')).toHaveTextContent('without the platform');
});

it('marks everyone but ARAK as a way around it when the mode relies on the table being shut', async () => {
  check.mockResolvedValue(result());
  renderPanel();
  fireEvent.click(screen.getByRole('button', { name: /check at the source/i }));

  expect(await screen.findByText('Bypasses ARAK')).toBeInTheDocument();
  expect(screen.getAllByText('Bypasses ARAK')).toHaveLength(1);
  expect(screen.getByText('ARAK itself')).toBeInTheDocument();
  expect(screen.getByText(/Logins that inherit it: ann and 2 more/)).toBeInTheDocument();
});

it('does not call direct readers a bypass where direct reads are the design', async () => {
  check.mockResolvedValue(result({ guarded: false, verdict: 'OPEN', mode: 'NATIVE_CONFIG' }));
  renderPanel();
  fireEvent.click(screen.getByRole('button', { name: /check at the source/i }));

  expect(await screen.findByText('analysts')).toBeInTheDocument();
  expect(screen.queryByText('Bypasses ARAK')).not.toBeInTheDocument();
});

it('says PUBLIC is everybody rather than nobody', async () => {
  check.mockResolvedValue(
    result({
      holders: [
        { name: 'PUBLIC', login: false, via: ['PUBLIC'], members: [], memberCount: 0, self: false },
      ],
    })
  );
  renderPanel();
  fireEvent.click(screen.getByRole('button', { name: /check at the source/i }));

  expect(await screen.findByText('Every login on the database.')).toBeInTheDocument();
});

it('shows why the source could not be asked', async () => {
  check.mockRejectedValue(new Error('boom'));
  renderPanel();
  fireEvent.click(screen.getByRole('button', { name: /check at the source/i }));

  expect(
    await screen.findByText(/could not be asked who holds this table|boom/)
  ).toBeInTheDocument();
});
