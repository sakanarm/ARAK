import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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
    memberCount: 1,
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
      { key: 'department', source: 'entra', principals: 4, values: ['FINANCE', 'HR'] },
      { key: 'clearance', source: 'local', principals: 2, values: ['L1', 'L2'] },
    ],
    appRoles: ['PLATFORM_ADMIN'],
  });
  fetchPrincipals.mockResolvedValue([principal({})]);
});

test('filters by a key alone when the key itself is clicked', async () => {
  renderPage();

  fireEvent.click(await screen.findByRole('button', { name: 'department' }));

  await waitFor(() =>
    expect(fetchPrincipals).toHaveBeenLastCalledWith(
      expect.objectContaining({ attributes: [{ key: 'department', value: undefined }] })
    )
  );
  // "Carries the attribute at all" is a different question from "carries this
  // value", and the chip has to say which one is being asked.
  expect(screen.getByText('any value')).toBeInTheDocument();
});

test('pins a value when a value is clicked, and ANDs a second condition', async () => {
  renderPage();

  fireEvent.click(await screen.findByRole('button', { name: 'FINANCE' }));
  fireEvent.click(await screen.findByRole('button', { name: 'L2' }));

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

test('the same condition twice stays one condition', async () => {
  renderPage();

  const finance = await screen.findByRole('button', { name: 'FINANCE' });
  fireEvent.click(finance);
  fireEvent.click(finance);

  await waitFor(() =>
    expect(fetchPrincipals).toHaveBeenLastCalledWith(
      expect.objectContaining({ attributes: [{ key: 'department', value: 'FINANCE' }] })
    )
  );
});

test('a condition can be taken off again', async () => {
  renderPage();

  fireEvent.click(await screen.findByRole('button', { name: 'FINANCE' }));
  fireEvent.click(
    await screen.findByRole('button', { name: 'Remove filter department' })
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

  fireEvent.click(await screen.findByRole('button', { name: 'FINANCE' }));

  // The quiet failure this page exists to prevent: a subject rule on an
  // attribute nobody carries denies everyone, and looks like nothing at all.
  expect(await screen.findByText(/Nobody carries all of those/)).toBeInTheDocument();
});
