import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import AppRolesPage from './AppRolesPage';
import type { RoleGrant } from '../../api/governance';

/**
 * What the roles screen must not get wrong.
 *
 * Two of these are guard rails rather than features. The last global
 * administrator must not be offered a Withdraw button, because the button
 * would be a 409 dressed as an action — and worse, on a deployment where the
 * server guard were ever relaxed, it would be the click that locks everyone
 * out. And a DATA_OWNER grant must not be sendable without a scope, because
 * the server's refusal is the only thing that would otherwise catch it and an
 * administrator reading a "grant succeeded" toast would believe the wrong
 * thing.
 */

const fetchPrincipals = jest.fn();
const fetchRoleGrants = jest.fn();
const grantAppRole = jest.fn();
const revokeAppRole = jest.fn();
const createLocalPrincipal = jest.fn();
const fetchAssets = jest.fn();

let isAdmin = true;

jest.mock('../../api/governance', () => ({
  fetchPrincipals: (...args: unknown[]) => fetchPrincipals(...args),
  fetchRoleGrants: () => fetchRoleGrants(),
  grantAppRole: (...args: unknown[]) => grantAppRole(...args),
  revokeAppRole: (...args: unknown[]) => revokeAppRole(...args),
  createLocalPrincipal: (...args: unknown[]) => createLocalPrincipal(...args),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
  fetchAssets: (...args: unknown[]) => fetchAssets(...args),
}));

jest.mock('../../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) =>
    selector({ hasRole: () => isAdmin }),
}));

function grant(overrides: Partial<RoleGrant>): RoleGrant {
  return {
    id: 'g1',
    principalId: '11111111-1111-1111-1111-111111111111',
    username: 'admin',
    displayName: 'Admin',
    principalType: 'USER',
    source: 'local',
    enabled: true,
    appRole: 'PLATFORM_ADMIN',
    scopeFqn: null,
    grantedBy: 'bootstrap',
    grantedAt: '2026-09-01T00:00:00Z',
    ...overrides,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AppRolesPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  isAdmin = true;
  fetchPrincipals.mockReset().mockResolvedValue([]);
  fetchRoleGrants.mockReset();
  grantAppRole.mockReset().mockResolvedValue(true);
  revokeAppRole.mockReset().mockResolvedValue(true);
  createLocalPrincipal.mockReset().mockResolvedValue({});
  fetchAssets.mockReset().mockResolvedValue({
    items: [
      {
        id: 'a1',
        fqn: 'prod-pg.SalesDB.dbo.customer',
        name: 'customer',
        displayName: null,
        assetType: 'TABLE',
        parentFqn: null,
        description: null,
        tier: null,
        certification: null,
        dataSource: null,
        columnCount: 4,
        childCount: 0,
        taggedColumnCount: 1,
        facets: [],
        owners: [],
      },
    ],
    total: 1,
    limit: 20,
    offset: 0,
  });
});

describe('the last administrator', () => {
  it('cannot be withdrawn while they are the only one', async () => {
    fetchRoleGrants.mockResolvedValue({
      grants: [grant({})],
      appRoles: ['PLATFORM_ADMIN'],
      globalAdminCount: 1,
    });
    renderPage();

    const row = await screen.findByRole('row', { name: /Admin/ });
    expect(within(row).getByText('Last administrator')).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: 'Withdraw' })).toBeNull();
  });

  it('can be withdrawn once a second one exists', async () => {
    fetchRoleGrants.mockResolvedValue({
      grants: [
        grant({}),
        grant({ id: 'g2', principalId: 'p2', username: 'admin2', displayName: 'Admin Two' }),
      ],
      appRoles: ['PLATFORM_ADMIN'],
      globalAdminCount: 2,
    });
    renderPage();

    const row = await screen.findByRole('row', { name: /Admin Two/ });
    fireEvent.click(within(row).getByRole('button', { name: 'Withdraw' }));

    await waitFor(() =>
      expect(revokeAppRole).toHaveBeenCalledWith('p2', {
        appRole: 'PLATFORM_ADMIN',
        scopeFqn: null,
      })
    );
  });

  it('keeps its Withdraw button on a scoped grant, which is not the one at stake', async () => {
    fetchRoleGrants.mockResolvedValue({
      grants: [
        grant({
          id: 'g3',
          appRole: 'DATA_OWNER',
          scopeFqn: 'prod-pg.SalesDB',
          displayName: 'Owner',
        }),
      ],
      appRoles: ['DATA_OWNER'],
      globalAdminCount: 1,
    });
    renderPage();

    const row = await screen.findByRole('row', { name: /Owner/ });
    expect(within(row).getByRole('button', { name: 'Withdraw' })).toBeInTheDocument();
  });
});

describe('granting', () => {
  beforeEach(() => {
    fetchRoleGrants.mockResolvedValue({
      grants: [],
      appRoles: [],
      globalAdminCount: 2,
    });
  });

  it('will not send a data owner without a scope, and takes the scope from the catalog', async () => {
    fetchPrincipals.mockResolvedValue([
      {
        id: 'p9',
        principalType: 'USER',
        username: 'analyst_a',
        email: null,
        displayName: 'Analyst A',
        source: 'local',
        enabled: true,
        attributeCount: 0,
        memberCount: 0,
        groupCount: 0,
        appRoles: [],
      },
    ]);
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Grant a role' }));
    fireEvent.click(await screen.findByRole('button', { name: /Analyst A/ }));

    // The select is the design system's listbox, so the role is chosen the way
    // a person chooses it rather than by setting a value.
    fireEvent.click(screen.getByRole('button', { name: /Role/ }));
    fireEvent.click(await screen.findByRole('option', { name: /Data owner/ }));

    expect(screen.getByRole('button', { name: 'Grant' })).toBeDisabled();

    fireEvent.click(
      await screen.findByRole('button', { name: /prod-pg\.SalesDB\.dbo\.customer/ })
    );
    fireEvent.click(screen.getByRole('button', { name: 'Grant' }));

    await waitFor(() =>
      expect(grantAppRole).toHaveBeenCalledWith('p9', {
        appRole: 'DATA_OWNER',
        scopeFqn: 'prod-pg.SalesDB.dbo.customer',
        reason: null,
      })
    );
  });
});

describe('somebody who is not an administrator', () => {
  it('sees the role descriptions and no way to change anything', async () => {
    isAdmin = false;
    renderPage();

    expect(await screen.findByText('Platform admin')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Grant a role' })).toBeNull();
    expect(fetchRoleGrants).not.toHaveBeenCalled();
  });
});
