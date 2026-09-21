import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import PrincipalsPage from './PrincipalsPage';
import type { Principal } from '../../api/governance';

const fetchPrincipals = jest.fn();
const fetchAttributeVocabulary = jest.fn();

jest.mock('../../api/governance', () => ({
  fetchPrincipals: (...args: unknown[]) => fetchPrincipals(...args),
  fetchAttributeVocabulary: () => fetchAttributeVocabulary(),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}));

function principal(overrides: Partial<Principal>): Principal {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    principalType: 'USER',
    username: 'analyst_a',
    email: 'analyst_a@example.com',
    displayName: 'Analyst A',
    source: 'local',
    enabled: true,
    attributeCount: 3,
    memberCount: 0,
    groupCount: 1,
    appRoles: [],
    ...overrides,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <PrincipalsPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  fetchPrincipals.mockReset();
  fetchAttributeVocabulary.mockReset();
  fetchAttributeVocabulary.mockResolvedValue({
    keys: [
      {
        key: 'department',
        source: 'entra',
        principals: 4,
        values: [
          { value: 'FINANCE', principals: 3 },
          { value: 'HR', principals: 1 },
        ],
      },
      {
        key: 'clearance',
        source: 'local',
        principals: 2,
        values: [
          { value: 'L1', principals: 1 },
          { value: 'L2', principals: 1 },
        ],
      },
    ],
    appRoles: ['PLATFORM_ADMIN'],
  });
  fetchPrincipals.mockResolvedValue([principal({})]);
});

test('filters by a key alone when "any value" is clicked', async () => {
  renderPage();

  fireEvent.click(
    await screen.findByRole('button', { name: 'department · any value' })
  );

  await waitFor(() =>
    expect(fetchPrincipals).toHaveBeenLastCalledWith(
      expect.objectContaining({ attributes: [{ key: 'department', value: undefined }] })
    )
  );
  // "Carries the attribute at all" is a different question from "carries this
  // value", and the chip has to say which one is being asked.
  expect(
    screen.getByRole('button', { name: 'Remove filter department · any value' })
  ).toBeInTheDocument();
});

test('pins a value when a value is clicked, and ANDs a second condition', async () => {
  renderPage();

  fireEvent.click(
    await screen.findByRole('button', { name: 'department = FINANCE' })
  );
  fireEvent.click(await screen.findByRole('button', { name: 'clearance = L2' }));

  await waitFor(() =>
    expect(fetchPrincipals).toHaveBeenLastCalledWith(
      expect.objectContaining({
        attributes: [
          { key: 'department', value: 'FINANCE' },
          { key: 'clearance', value: 'L2' },
        ],
      })
    )
  );
  expect(screen.getByText('Carrying all of:')).toBeInTheDocument();
});

test('the rail shows how many people carry each value', async () => {
  renderPage();

  // The number is the point of the rail: a value nobody carries is a rule
  // that matches nobody, and it should be visible before it is chosen.
  const row = (await screen.findByTitle('department = FINANCE')) as HTMLElement;
  expect(within(row).getByText('3')).toBeInTheDocument();
});

test('clicking the same condition twice takes it off again', async () => {
  renderPage();

  const finance = await screen.findByRole('button', {
    name: 'department = FINANCE',
  });
  fireEvent.click(finance);
  fireEvent.click(finance);

  await waitFor(() =>
    expect(fetchPrincipals).toHaveBeenLastCalledWith(
      expect.objectContaining({ attributes: [] })
    )
  );
});

test('a condition can be taken off from its chip', async () => {
  renderPage();

  fireEvent.click(
    await screen.findByRole('button', { name: 'department = FINANCE' })
  );
  fireEvent.click(
    await screen.findByRole('button', {
      name: 'Remove filter department = FINANCE',
    })
  );

  await waitFor(() =>
    expect(fetchPrincipals).toHaveBeenLastCalledWith(
      expect.objectContaining({ attributes: [] })
    )
  );
});

test('says plainly that a filter matching nobody is a rule matching nobody', async () => {
  fetchPrincipals.mockResolvedValue([]);
  renderPage();

  fireEvent.click(
    await screen.findByRole('button', { name: 'department = FINANCE' })
  );

  // The quiet failure this page exists to prevent: a subject rule on an
  // attribute nobody carries denies everyone, and looks like nothing at all.
  expect(await screen.findByText(/Nobody carries all of those/)).toBeInTheDocument();
});

test('a group opens on its member count, and counts members — not groups', async () => {
  fetchPrincipals.mockResolvedValue([
    principal({
      id: '22222222-2222-2222-2222-222222222222',
      principalType: 'GROUP',
      username: 'Finance',
      displayName: 'Finance',
      email: null,
      memberCount: 3,
      groupCount: 0,
    }),
  ]);
  renderPage();

  const link = await screen.findByRole('link', { name: /3 members/ });
  expect(link).toHaveAttribute(
    'href',
    '/principals/22222222-2222-2222-2222-222222222222'
  );
});

test('a user row counts the groups they are in, not the members they have', async () => {
  // The shipped bug this replaces: every person read "0 groups", because the
  // row rendered member_count — which counts the people inside a principal and
  // is always zero for a person.
  fetchPrincipals.mockResolvedValue([
    principal({ memberCount: 0, groupCount: 2 }),
  ]);
  renderPage();

  expect(await screen.findByText('2 groups')).toBeInTheDocument();
});
