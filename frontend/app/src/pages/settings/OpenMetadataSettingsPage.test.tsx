import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import OpenMetadataSettingsPage from './OpenMetadataSettingsPage';
import type { OpenMetadataSettings } from '../../api/system';

/**
 * What the connection form must not get wrong.
 *
 * Only one of these is a feature. The rest are the ways a form over a stored
 * credential destroys one: a secret box left alone must send nothing at all,
 * because an empty string reads as "clear it" on the way in and an estate that
 * moved its URL would arrive at a catalog it can no longer authenticate to. A
 * test must reach the address on screen rather than the one in force, or it
 * can only report that the crawl has already stopped. And a discard has to put
 * back what the server said, not what the last render happened to hold.
 */

const fetchOpenMetadataSettings = jest.fn();
const saveOpenMetadataSettings = jest.fn();
const testOpenMetadata = jest.fn();
const startCrawl = jest.fn();
const fetchSyncSchedule = jest.fn();
const saveSyncSchedule = jest.fn();

let isAdmin = true;

jest.mock('../../api/system', () => ({
  fetchOpenMetadataSettings: () => fetchOpenMetadataSettings(),
  saveOpenMetadataSettings: (...args: unknown[]) =>
    saveOpenMetadataSettings(...args),
  testOpenMetadata: (...args: unknown[]) => testOpenMetadata(...args),
  startCrawl: () => startCrawl(),
  fetchSyncSchedule: () => fetchSyncSchedule(),
  saveSyncSchedule: (...args: unknown[]) => saveSyncSchedule(...args),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}));

jest.mock('../../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) =>
    selector({ hasRole: () => isAdmin }),
}));

function settings(
  overrides: Partial<OpenMetadataSettings> = {}
): OpenMetadataSettings {
  return {
    baseUrl: 'https://om.example.com',
    expectedVersion: '2.0.1',
    failOnVersionMismatch: false,
    connectTimeoutMs: 5000,
    readTimeoutMs: 30000,
    tokenConfigured: true,
    tokenSource: 'stored on this platform',
    webhookSecretConfigured: true,
    webhookSecretSource: 'stored on this platform',
    webhookPath: '/api/v1/webhooks/openmetadata',
    webhookSignatureHeader: 'X-OM-Signature',
    pollEnabled: true,
    pollIntervalSeconds: 60,
    maxEventsPerPoll: 500,
    reconcileEnabled: true,
    reconcileAt: '02:30:00',
    reconcileZone: 'Asia/Bangkok',
    editable: true,
    source: 'stored',
    isDefault: false,
    updatedAt: '2026-09-24T01:00:00Z',
    updatedBy: 'admin',
    configSource: 'set on this screen; the file remains the fallback',
    sync: { status: 'IDLE' },
    ...overrides,
  } as OpenMetadataSettings;
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <OpenMetadataSettingsPage />
    </QueryClientProvider>
  );
}

/** Opens the editor and returns the base-URL box. */
async function openEditor(): Promise<HTMLInputElement> {
  fireEvent.click(await screen.findByRole('button', { name: 'Edit' }));
  return (await screen.findByLabelText('Base URL')) as HTMLInputElement;
}

beforeEach(() => {
  isAdmin = true;
  fetchOpenMetadataSettings.mockReset().mockResolvedValue(settings());
  saveOpenMetadataSettings
    .mockReset()
    .mockImplementation(async () => settings());
  testOpenMetadata.mockReset().mockResolvedValue({
    baseUrl: 'https://new.example.com',
    reachable: true,
    version: '2.0.1',
    expectedVersion: '2.0.1',
    versionMatches: true,
    tookMs: 12,
    message: 'Connected.',
  });
  startCrawl.mockReset().mockResolvedValue({});
  fetchSyncSchedule.mockReset().mockResolvedValue({
    at: '02:30:00',
    zone: 'Asia/Bangkok',
    configuredAt: '02:30:00',
    configuredZone: 'Asia/Bangkok',
    enabled: true,
    managed: true,
    isDefault: true,
    nextRunAt: null,
    updatedAt: null,
    updatedBy: null,
  });
  saveSyncSchedule.mockReset();
});

it('leaves a secret alone rather than clearing it', async () => {
  renderPage();
  const url = await openEditor();

  fireEvent.change(url, { target: { value: 'https://new.example.com' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save the connection' }));

  await waitFor(() => expect(saveOpenMetadataSettings).toHaveBeenCalled());
  const sent = saveOpenMetadataSettings.mock.calls[0][0];
  expect(sent.baseUrl).toBe('https://new.example.com');
  // Not '' — an empty string is an instruction to forget the token.
  expect(sent.jwtToken).toBeUndefined();
  expect(sent.webhookSecret).toBeUndefined();
});

it('sends a secret that was actually typed', async () => {
  renderPage();
  await openEditor();

  fireEvent.change(screen.getByLabelText('Bot token'), {
    target: { value: 'a-new-token' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Save the connection' }));

  await waitFor(() => expect(saveOpenMetadataSettings).toHaveBeenCalled());
  const sent = saveOpenMetadataSettings.mock.calls[0][0];
  expect(sent.jwtToken).toBe('a-new-token');
  expect(sent.webhookSecret).toBeUndefined();
});

it('tests the address on screen, and saves nothing', async () => {
  renderPage();
  const url = await openEditor();

  fireEvent.change(url, { target: { value: 'https://new.example.com' } });
  fireEvent.click(screen.getByRole('button', { name: 'Test before saving' }));

  await waitFor(() => expect(testOpenMetadata).toHaveBeenCalled());
  expect(testOpenMetadata.mock.calls[0][0].baseUrl).toBe(
    'https://new.example.com'
  );
  expect(saveOpenMetadataSettings).not.toHaveBeenCalled();
  expect(await screen.findByText(/Nothing has been saved yet/)).toBeTruthy();
});

it('puts back what the server said when the edit is discarded', async () => {
  renderPage();
  const url = await openEditor();

  fireEvent.change(url, { target: { value: 'https://typo.example.com' } });
  fireEvent.click(screen.getByRole('button', { name: 'Discard' }));

  await waitFor(() =>
    expect((screen.getByLabelText('Base URL') as HTMLInputElement).value).toBe(
      'https://om.example.com'
    )
  );
});

it('keeps a non-admin out of the connection entirely', async () => {
  isAdmin = false;
  renderPage();

  expect(
    await screen.findByText(/Only a platform admin can see the connection/)
  ).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
});
