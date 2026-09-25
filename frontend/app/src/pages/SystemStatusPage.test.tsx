import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, Navigate, Route, Routes } from 'react-router-dom';
import SystemStatusPage from './SystemStatusPage';

const fetchSyncStatus = jest.fn();
let isAdmin = false;

jest.mock('../api/client', () => ({
  fetchSystemVersion: jest.fn().mockResolvedValue({
    version: '0.1.0-SNAPSHOT',
    openMetadataBaseUrl: 'http://om.example:8585',
    openMetadataExpectedVersion: '2.0.1',
  }),
}));

jest.mock('../api/system', () => ({
  fetchSyncStatus: () => fetchSyncStatus(),
}));

jest.mock('../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) =>
    selector({ hasRole: () => isAdmin }),
}));

function renderAt(path = '/settings/system') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<SystemStatusPage />} path="/settings/system" />
          <Route element={<Navigate replace to="/settings/system" />} path="/system" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  isAdmin = false;
  fetchSyncStatus.mockReset();
});

test('shows the OpenMetadata instance it is pinned to, under Settings', async () => {
  renderAt();

  expect(await screen.findByText('http://om.example:8585')).toBeInTheDocument();
  expect(screen.getByText('2.0.1')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Settings' })).toHaveAttribute('href', '/settings');
});

test('the old address still lands on it', async () => {
  renderAt('/system');
  expect(await screen.findByRole('heading', { name: 'Service & build' })).toBeInTheDocument();
});

test('an administrator also sees how the crawl is keeping up', async () => {
  isAdmin = true;
  fetchSyncStatus.mockResolvedValue({
    source: 'openmetadata',
    status: 'FAILED',
    lastFullCrawlAt: null,
    lastReconcileAt: null,
    lastError: 'Connection refused',
  });
  renderAt();

  const crawl = await screen.findByRole('region', { name: 'OpenMetadata crawl' });
  expect(await within(crawl).findByText('Failed')).toBeInTheDocument();
  expect(within(crawl).getByText('Connection refused')).toBeInTheDocument();
  expect(within(crawl).getByRole('link', { name: 'Connection & sync' })).toHaveAttribute(
    'href',
    '/settings/openmetadata'
  );
});

test('nobody else is shown the crawl, nor is it asked for on their behalf', async () => {
  renderAt();
  await screen.findByText('http://om.example:8585');
  expect(screen.queryByRole('region', { name: 'OpenMetadata crawl' })).toBeNull();
  expect(fetchSyncStatus).not.toHaveBeenCalled();
});
