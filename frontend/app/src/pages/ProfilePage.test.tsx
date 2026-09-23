import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ProfilePage from './ProfilePage';
import { useAuthStore } from '../auth/authStore';
import { fetchPrincipalDetail } from '../api/governance';

/**
 * What the page tells somebody about themselves.
 *
 * <p>Tested here rather than in the browser because the one account that can
 * sign in to a development deployment carries no attributes at all, so the
 * populated layout -- the reason the page exists -- is unreachable by hand.
 */

jest.mock('../api/governance', () => ({
  fetchPrincipalDetail: jest.fn(),
}));

jest.mock('../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}));

const detail = fetchPrincipalDetail as jest.MockedFunction<
  typeof fetchPrincipalDetail
>;

function signedInAs(roles: string[]) {
  useAuthStore.setState({
    token: 'test-token',
    initialising: false,
    user: {
      id: '11111111-1111-1111-1111-111111111111',
      username: 'analyst_a',
      email: 'analyst_a@example.test',
      displayName: 'Analyst A',
      source: 'local',
      roles,
      scopes: [],
    },
  });
}

function show() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  detail.mockReset();
  signedInAs([]);
});

test('names every attribute value, and where it was synced from', async () => {
  detail.mockResolvedValue({
    principal: {
      id: '11111111-1111-1111-1111-111111111111',
      principalType: 'USER',
      username: 'analyst_a',
      email: 'analyst_a@example.test',
      displayName: 'Analyst A',
      source: 'local',
      enabled: true,
      attributeCount: 3,
      memberCount: 0,
      groupCount: 1,
      groups: [],
      appRoles: [],
    },
    attributes: [
      { key: 'department', value: 'FINANCE', source: 'entra' },
      // Two rows under one key: the page must not collapse them, because a
      // policy asking for L2 is satisfied by the second and not the first.
      { key: 'clearance', value: 'L1', source: 'entra' },
      { key: 'clearance', value: 'L2', source: 'local' },
    ],
    groups: [
      {
        id: '22222222-2222-2222-2222-222222222222',
        principalType: 'GROUP',
        username: 'finance',
        email: null,
        displayName: 'Finance',
        source: 'openmetadata',
        enabled: true,
        attributeCount: 0,
        memberCount: 4,
        groupCount: 0,
        groups: [],
        appRoles: [],
      },
    ],
    members: [],
  });

  show();

  // Scoped to the list, because the prose above it uses `clearance` as its
  // worked example and a bare text query would count that too.
  const list = within(await screen.findByLabelText('Your attributes'));
  expect(list.getByText('department')).toBeInTheDocument();
  expect(list.getByText('FINANCE')).toBeInTheDocument();
  expect(list.getAllByText('clearance')).toHaveLength(2);
  expect(list.getByText('L1')).toBeInTheDocument();
  expect(list.getByText('L2')).toBeInTheDocument();
  // Both sources shown: an attribute an administrator set here and one the
  // directory owns are not the same thing to whoever has to get it changed.
  expect(list.getByText('local')).toBeInTheDocument();
  expect(list.getAllByText('entra')).toHaveLength(2);

  expect(
    within(screen.getByLabelText('Your groups')).getByText('Finance')
  ).toBeInTheDocument();
});

test('says a missing attribute means refusal, not a free pass', async () => {
  detail.mockResolvedValue({
    principal: {
      id: '11111111-1111-1111-1111-111111111111',
      principalType: 'USER',
      username: 'analyst_a',
      email: null,
      displayName: 'Analyst A',
      source: 'local',
      enabled: true,
      attributeCount: 0,
      memberCount: 0,
      groupCount: 0,
      groups: [],
      appRoles: [],
    },
    attributes: [],
    groups: [],
    members: [],
  });

  show();

  // The wording matters more than its presence: somebody with no attributes
  // reading "none recorded" would reasonably conclude no policy applies to
  // them, which is the opposite of what the engine does.
  expect(
    await screen.findByText(/Any policy that tests one will refuse/)
  ).toBeInTheDocument();
});

test('does not claim a platform role grants access to data', async () => {
  signedInAs(['PLATFORM_ADMIN']);
  detail.mockResolvedValue({
    principal: {
      id: '11111111-1111-1111-1111-111111111111',
      principalType: 'USER',
      username: 'admin',
      email: null,
      displayName: 'Platform Administrator',
      source: 'local',
      enabled: true,
      attributeCount: 0,
      memberCount: 0,
      groupCount: 0,
      groups: [],
      appRoles: ['PLATFORM_ADMIN'],
    },
    attributes: [],
    groups: [],
    members: [],
  });

  show();

  expect(await screen.findByText('platform admin')).toBeInTheDocument();
  expect(
    screen.getByText(/do not grant access to any data/)
  ).toBeInTheDocument();
});
