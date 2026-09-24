import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import PrincipalDetailPage from './PrincipalDetailPage';
import type { Principal, PrincipalDetail } from '../../api/governance';

const fetchPrincipalDetail = jest.fn();
const fetchAttributeVocabulary = jest.fn();
const addPrincipalAttribute = jest.fn();
const removePrincipalAttribute = jest.fn();

let isAdmin = false;

jest.mock('../../api/governance', () => ({
  fetchPrincipalDetail: (...args: unknown[]) => fetchPrincipalDetail(...args),
  fetchAttributeVocabulary: () => fetchAttributeVocabulary(),
  addPrincipalAttribute: (...args: unknown[]) => addPrincipalAttribute(...args),
  removePrincipalAttribute: (...args: unknown[]) =>
    removePrincipalAttribute(...args),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}));

jest.mock('../../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) =>
    selector({ hasRole: () => isAdmin }),
}));

const GROUP_ID = '22222222-2222-2222-2222-222222222222';
const USER_ID = '11111111-1111-1111-1111-111111111111';

function principal(overrides: Partial<Principal>): Principal {
  return {
    id: USER_ID,
    principalType: 'USER',
    username: 'analyst_a',
    email: 'analyst_a@example.com',
    displayName: 'Analyst A',
    source: 'local',
    enabled: true,
    attributeCount: 2,
    memberCount: 0,
    groups: [],
    groupCount: 1,
    appRoles: [],
    ...overrides,
  };
}

function renderAt(id: string, detail: PrincipalDetail) {
  fetchPrincipalDetail.mockResolvedValue(detail);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/principals/${id}`]}>
        <Routes>
          <Route element={<PrincipalDetailPage />} path="/principals/:id" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  isAdmin = false;
  fetchPrincipalDetail.mockReset();
  fetchAttributeVocabulary.mockReset();
  fetchAttributeVocabulary.mockResolvedValue({ keys: [], appRoles: [] });
  addPrincipalAttribute.mockReset();
  removePrincipalAttribute.mockReset();
});

test('a group lists the people in it, each one openable', async () => {
  renderAt(GROUP_ID, {
    principal: principal({
      id: GROUP_ID,
      principalType: 'GROUP',
      username: 'Finance',
      displayName: 'Finance',
      email: null,
      attributeCount: 0,
      memberCount: 2,
      groupCount: 0,
    }),
    attributes: [],
    groups: [],
    members: [
      principal({}),
      principal({ id: USER_ID.replace(/1/g, '3'), username: 'analyst_b', displayName: 'Analyst B' }),
    ],
  });

  expect(await screen.findByRole('link', { name: /Analyst A/ })).toHaveAttribute(
    'href',
    `/principals/${USER_ID}`
  );
  expect(screen.getByText('2 people, as of the last sync.')).toBeInTheDocument();
});

test('an empty group is named as the denial it is', async () => {
  renderAt(GROUP_ID, {
    principal: principal({
      id: GROUP_ID,
      principalType: 'GROUP',
      username: 'Finance',
      displayName: 'Finance',
      email: null,
      attributeCount: 0,
      memberCount: 0,
      groupCount: 0,
    }),
    attributes: [],
    groups: [],
    members: [],
  });

  expect(
    await screen.findByText(/matches no one/)
  ).toBeInTheDocument();
});

test("a group's attributes are marked as not reaching its members", async () => {
  // The engine reads each person's own attribute rows. A page that let this
  // pass unsaid would have people writing rules that never match.
  renderAt(GROUP_ID, {
    principal: principal({
      id: GROUP_ID,
      principalType: 'GROUP',
      username: 'Finance',
      displayName: 'Finance',
      email: null,
      attributeCount: 1,
      memberCount: 1,
      groupCount: 0,
    }),
    attributes: [{ key: 'costCentre', value: 'CC-100', source: 'entra' }],
    groups: [],
    members: [principal({})],
  });

  expect(
    await screen.findByText(/will\s+not match the members listed above/)
  ).toBeInTheDocument();
});

test('a user shows their attributes and the groups that reach them', async () => {
  renderAt(USER_ID, {
    principal: principal({ appRoles: ['POLICY_AUTHOR'] }),
    attributes: [
      { key: 'department', value: 'FINANCE', source: 'entra' },
      { key: 'clearance', value: 'L2', source: 'local' },
    ],
    groups: [
      principal({
        id: GROUP_ID,
        principalType: 'GROUP',
        username: 'Finance',
        displayName: 'Finance',
        email: null,
        memberCount: 3,
      }),
    ],
    members: [],
  });

  expect(await screen.findByText('FINANCE')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /Finance/ })).toHaveAttribute(
    'href',
    `/principals/${GROUP_ID}`
  );
  // The platform role belongs to this page but not to the data question, and
  // the page has to say which of the two it is answering.
  expect(screen.getByText('policy author')).toBeInTheDocument();
});

test('an administrator can give somebody an attribute from this screen', async () => {
  // The point of the feature: clearance is the attribute a data policy is
  // written against and the one no directory carries, so without this form the
  // rule saves, activates, and matches nobody.
  isAdmin = true;
  const after: PrincipalDetail = {
    principal: principal({ attributeCount: 1 }),
    attributes: [{ key: 'clearance', value: 'L2', source: 'local' }],
    groups: [],
    members: [],
  };
  addPrincipalAttribute.mockResolvedValue({ changed: true, detail: after });

  renderAt(USER_ID, {
    principal: principal({ attributeCount: 0 }),
    attributes: [],
    groups: [],
    members: [],
  });

  fireEvent.change(await screen.findByLabelText('Attribute'), {
    target: { value: 'clearance' },
  });
  fireEvent.change(screen.getByLabelText('Value'), { target: { value: 'L2' } });
  fireEvent.click(screen.getByRole('button', { name: 'Add' }));

  await waitFor(() =>
    expect(addPrincipalAttribute).toHaveBeenCalledWith(USER_ID, {
      key: 'clearance',
      value: 'L2',
      reason: '',
    })
  );
  expect(await screen.findByText('L2')).toBeInTheDocument();
});

test('a value a directory owns offers no way to take it away here', async () => {
  // Closing an Entra row locally would hold exactly until the next sync put it
  // back, and nothing on the screen would say so. The server refuses it; the
  // button must not be there to click in the first place.
  isAdmin = true;
  renderAt(USER_ID, {
    principal: principal({ attributeCount: 2 }),
    attributes: [
      { key: 'department', value: 'FINANCE', source: 'entra' },
      { key: 'clearance', value: 'L2', source: 'local' },
    ],
    groups: [],
    members: [],
  });

  expect(
    await screen.findByRole('button', { name: 'Withdraw clearance L2' })
  ).toBeInTheDocument();
  expect(
    screen.queryByRole('button', { name: 'Withdraw department FINANCE' })
  ).not.toBeInTheDocument();
  expect(screen.getByText('entra owns this')).toBeInTheDocument();
});

test('withdrawing a local value sends both the key and the value', async () => {
  // An attribute is multi-valued. Asking to withdraw "clearance" without
  // saying which one would be ambiguous, and the ambiguity would be resolved
  // by the database rather than by the person clicking.
  isAdmin = true;
  const after: PrincipalDetail = {
    principal: principal({ attributeCount: 1 }),
    attributes: [{ key: 'clearance', value: 'L1', source: 'local' }],
    groups: [],
    members: [],
  };
  removePrincipalAttribute.mockResolvedValue({ changed: true, detail: after });

  renderAt(USER_ID, {
    principal: principal({ attributeCount: 2 }),
    attributes: [
      { key: 'clearance', value: 'L1', source: 'local' },
      { key: 'clearance', value: 'L2', source: 'local' },
    ],
    groups: [],
    members: [],
  });

  fireEvent.click(
    await screen.findByRole('button', { name: 'Withdraw clearance L2' })
  );

  await waitFor(() =>
    expect(removePrincipalAttribute).toHaveBeenCalledWith(USER_ID, {
      key: 'clearance',
      value: 'L2',
    })
  );
});

test('somebody without the platform role is offered no form at all', async () => {
  renderAt(USER_ID, {
    principal: principal({ attributeCount: 1 }),
    attributes: [{ key: 'clearance', value: 'L2', source: 'local' }],
    groups: [],
    members: [],
  });

  expect(await screen.findByText('L2')).toBeInTheDocument();
  expect(screen.queryByLabelText('Attribute')).not.toBeInTheDocument();
  expect(
    screen.queryByRole('button', { name: /Withdraw/ })
  ).not.toBeInTheDocument();
});
