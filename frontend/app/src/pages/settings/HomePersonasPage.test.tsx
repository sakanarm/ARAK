import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import HomePersonasPage from './HomePersonasPage';
import { useAuthStore } from '../../auth/authStore';
import {
  fetchHomePersonas,
  saveHomePersona,
  type HomeLayout,
  type HomePersonaLayout,
  type HomePersonaRole,
} from '../../api/home';

/**
 * The screen where one person arranges another person's starting page.
 *
 * What is defended here is the pair of promises the screen makes in prose, both
 * of which are easy to break without noticing:
 *
 * 1. Only a platform administrator sees it at all. This is the single editor in
 *    the product whose output is rendered into other people's sessions, so the
 *    gate is not a matter of tidiness.
 * 2. Every role is listed, arranged or not. A screen that showed only the rows
 *    that exist would answer "which personas has somebody configured" when the
 *    question an administrator arrived with is "what does a data owner see".
 */

jest.mock('../../api/home', () => ({
  ...jest.requireActual('../../api/home'),
  fetchHomePersonas: jest.fn(),
  saveHomePersona: jest.fn(),
  resetHomePersona: jest.fn(),
}));

jest.mock('../../api/policies', () => ({ fetchPolicies: jest.fn() }));
jest.mock('../../api/sources', () => ({ fetchSources: jest.fn() }));
jest.mock('../../api/governance', () => ({ fetchVocabulary: jest.fn() }));
jest.mock('../../api/search', () => ({
  ...jest.requireActual('../../api/search'),
  search: jest.fn(),
}));

jest.mock('../../api/client', () => ({
  apiClient: { get: jest.fn(), put: jest.fn(), delete: jest.fn() },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
  fetchCatalogSummary: jest.fn(),
  fetchSystemVersion: jest.fn(),
}));

const personasCall = fetchHomePersonas as jest.MockedFunction<
  typeof fetchHomePersonas
>;
const saveCall = saveHomePersona as jest.MockedFunction<typeof saveHomePersona>;

const ROLES: HomePersonaRole[] = [
  'PLATFORM_ADMIN',
  'POLICY_AUTHOR',
  'DATA_OWNER',
  'AUDITOR',
  'REQUESTER',
];

const NOTE_PAGE: HomeLayout = {
  preset: 'SINGLE',
  widgets: [
    {
      id: 'n1',
      type: 'NOTE',
      column: 0,
      title: null,
      config: { text: 'Welcome' },
    },
  ],
};

function signedInAs(roles: string[]) {
  useAuthStore.setState({
    token: 'test-token',
    initialising: false,
    user: {
      id: '11111111-1111-1111-1111-111111111111',
      username: 'admin',
      email: 'admin@example.test',
      displayName: 'Admin',
      source: 'local',
      roles,
      scopes: [],
    },
  });
}

/** Every role listed; only the ones named have been arranged. */
function personas(arranged: HomePersonaRole[]): HomePersonaLayout[] {
  return ROLES.map((role) => ({
    role,
    layout: NOTE_PAGE,
    configured: arranged.includes(role),
    updatedAt: arranged.includes(role) ? '2026-09-24T08:00:00Z' : null,
    updatedBy: arranged.includes(role) ? 'admin' : null,
  }));
}

function draw() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <HomePersonasPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  personasCall.mockResolvedValue(personas([]));
});

describe('HomePersonasPage', () => {
  it('is not shown to an account that cannot act on it', async () => {
    signedInAs(['POLICY_AUTHOR', 'REQUESTER']);

    draw();

    expect(
      await screen.findByText(/platform administrator's job/i)
    ).toBeInTheDocument();
    // And it does not go asking for the rows either. A screen that renders a
    // refusal but still fires the request spends a 403 per load.
    expect(personasCall).not.toHaveBeenCalled();
  });

  it('lists every role, arranged or not', async () => {
    signedInAs(['PLATFORM_ADMIN']);
    personasCall.mockResolvedValue(personas(['AUDITOR']));

    draw();

    expect(await screen.findByText('Auditor')).toBeInTheDocument();
    for (const label of [
      'Platform admin',
      'Policy author',
      'Data owner',
      'Requester',
    ]) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }

    // One arranged, four still on the page this product ships with.
    expect(screen.getAllByText('Arranged')).toHaveLength(1);
    expect(screen.getAllByText('Built-in')).toHaveLength(4);
  });

  it('says the arrangement is a starting point rather than an override', async () => {
    signedInAs(['PLATFORM_ADMIN']);

    draw();

    // The promise the server keeps by consulting home_role_layout at read time
    // instead of copying it into home_layout. If this sentence goes, somebody
    // will reasonably expect this screen to move a page that it cannot move.
    expect(
      await screen.findByText(/once a person arranges their own/i)
    ).toBeInTheDocument();
  });

  it('saves the role that was opened, and only that one', async () => {
    signedInAs(['PLATFORM_ADMIN']);
    saveCall.mockResolvedValue({
      role: 'REQUESTER',
      layout: NOTE_PAGE,
      configured: true,
      updatedAt: '2026-09-24T09:00:00Z',
      updatedBy: 'admin',
    });

    draw();

    await screen.findByText('Requester');
    // Five rows, five Arrange buttons; the last one is the requester's.
    const arrange = screen.getAllByRole('button', { name: /arrange/i });
    expect(arrange).toHaveLength(ROLES.length);
    fireEvent.click(arrange[arrange.length - 1]);

    expect(
      await screen.findByText(/arranging the requester page/i)
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() => expect(saveCall).toHaveBeenCalledTimes(1));
    expect(saveCall.mock.calls[0][0]).toBe('REQUESTER');
  });
});
