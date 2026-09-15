import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import SystemStatusPage from './SystemStatusPage';

jest.mock('../api/client', () => ({
  fetchSystemVersion: jest.fn().mockResolvedValue({
    version: '0.1.0-SNAPSHOT',
    openMetadataBaseUrl: 'http://om.example:8585',
    openMetadataExpectedVersion: '2.0.1',
  }),
}));

test('shows the OpenMetadata instance it is pinned to', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <SystemStatusPage />
    </QueryClientProvider>
  );

  expect(await screen.findByText('http://om.example:8585')).toBeInTheDocument();
  expect(screen.getByText('2.0.1')).toBeInTheDocument();
});
