import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AccessTab } from './AccessTab';
import { governs } from '../../auth/stewardship';
import { useAuthStore } from '../../auth/authStore';
import type { AssetAccess, GrantAccess, PersonAccess } from '../../api/access';
import type { SessionUser } from '../../auth/session';

const fetchAccess = jest.fn();
const amend = jest.fn();

jest.mock('../../api/access', () => ({
  ...jest.requireActual('../../api/access'),
  fetchAssetAccess: (...args: unknown[]) => fetchAccess(...args),
  amendGrant: (...args: unknown[]) => amend(...args),
}));

// The decision chart has its own tests and its own query; this file is about grants.
jest.mock('./AccessDecision', () => ({ AccessDecision: () => null }));
// So is the panel about access outside ARAK.
jest.mock('./DirectAccessPanel', () => ({ DirectAccessPanel: () => null }));

// The register, as the grant list names a purpose by it.
jest.mock('../../api/purposes', () => ({
  ...jest.requireActual('../../api/purposes'),
  usePurposes: () => ({
    data: {
      canEdit: false,
      purposes: [
        {
          key: 'reporting',
          name: 'Regular reporting',
          description: null,
          legalBasis: 'CONTRACT',
          sensitiveAllowed: false,
          owner: null,
          maxDays: 30,
          status: 'ACTIVE',
          createdBy: 'system',
          createdAt: '2026-09-28T03:00:00Z',
          updatedBy: 'system',
          updatedAt: '2026-09-28T03:00:00Z',
        },
      ],
    },
    isLoading: false,
    isError: false,
  }),
}));

const FQN = 'demo-pg.salesdb.sales.customer';

function user(roles: string[], scopes: string[] = []): SessionUser {
  return {
    id: 'u-1',
    username: 'someone',
    email: null,
    displayName: null,
    source: 'local',
    roles,
    scopes,
  };
}

function grant(id: string, over: Partial<GrantAccess> = {}): GrantAccess {
  return {
    id,
    principal: id,
    displayName: null,
    principalType: 'USER',
    principalSource: 'local',
    validFrom: null,
    validUntil: null,
    reason: null,
    grantedBy: 'owner_o',
    grantedAt: '2026-09-24T03:00:00Z',
    live: true,
    effectiveFor: 1,
    purpose: null,
    ...over,
  };
}

function person(principal: string, over: Partial<PersonAccess> = {}): PersonAccess {
  return {
    principal,
    origin: 'POLICY',
    viaPolicies: [],
    viaGrants: [],
    restricted: false,
    maskedColumns: 0,
    hiddenColumns: 0,
    rowFilters: 0,
    ...over,
  };
}

function access(over: Partial<AssetAccess> = {}): AssetAccess {
  return {
    assetFqn: FQN,
    known: true,
    grants: [grant('g-1', { principal: 'analyst_a', reason: 'Quarter end' })],
    people: [],
    principalsKnown: 1,
    principalsEvaluated: 1,
    sampled: false,
    evaluatedAt: '2026-09-25T03:00:00Z',
    ...over,
  };
}

const OWNER = user(['DATA_OWNER'], ['demo-pg.salesdb']);

function renderAs(who: SessionUser, answer: AssetAccess = access()) {
  useAuthStore.setState({ token: 'token', user: who, initialising: false });
  fetchAccess.mockResolvedValue(answer);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AccessTab fqn={FQN} />
    </QueryClientProvider>
  );
}

describe('governs', () => {
  it('matches the server: unbounded roles everywhere, an owner under their scope', () => {
    expect(governs(user(['PLATFORM_ADMIN']), FQN)).toBe(true);
    expect(governs(user(['POLICY_AUTHOR']), FQN)).toBe(true);
    expect(governs(user(['DATA_OWNER'], ['demo-pg.salesdb']), FQN)).toBe(true);
    expect(governs(user(['DATA_OWNER'], [FQN]), FQN)).toBe(true);
    expect(governs(user(['DATA_OWNER'], ['demo-pg.hrdb']), FQN)).toBe(false);
    expect(governs(user(['DATA_OWNER'], ['demo-pg.sales']), FQN)).toBe(false);
    expect(governs(user(['DATA_OWNER']), FQN)).toBe(false);
    expect(governs(user(['AUDITOR']), FQN)).toBe(false);
    expect(governs(null, FQN)).toBe(false);
  });
});

describe('the Access tab', () => {
  it('offers grant, edit and revoke to the owner of the table', async () => {
    renderAs(OWNER);

    expect(await screen.findByText('Quarter end', { exact: false })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Grant access' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Actions for analyst_a' }));
    expect(await screen.findByRole('menuitem', { name: 'Edit' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Revoke' })).toBeInTheDocument();
  });

  it('revokes from the menu, and only once a reason is given', async () => {
    renderAs(OWNER);

    fireEvent.click(await screen.findByRole('button', { name: 'Actions for analyst_a' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Revoke' }));
    const revoke = screen.getByRole('button', { name: 'Revoke' });
    expect(revoke).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Why this grant is being revoked'), {
      target: { value: 'Left the team' },
    });
    expect(revoke).toBeEnabled();
  });

  it('edits a grant window with a reason, and sends no expiry as null', async () => {
    amend.mockResolvedValue({});
    renderAs(OWNER);

    fireEvent.click(await screen.findByRole('button', { name: 'Actions for analyst_a' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Edit' }));
    const save = screen.getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();

    fireEvent.click(screen.getByLabelText('No expiry'));
    expect(screen.getByText('Pick when it ends, or tick No expiry.')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Ends'), { target: { value: '2020-01-01T09:00' } });
    expect(screen.getByText(/already passed/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Ends'), { target: { value: '2099-01-01T09:00' } });
    fireEvent.change(screen.getByLabelText('Why this grant is being changed'), {
      target: { value: 'Project extended' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(amend).toHaveBeenCalled());
    const [id, change] = amend.mock.calls[0];
    expect(id).toBe('g-1');
    expect(change.reason).toBe('Project extended');
    expect(new Date(change.validUntil).getFullYear()).toBe(2099);
    expect(change).not.toHaveProperty('validFrom');
  });

  it('shows the list, without the controls, to anyone else', async () => {
    renderAs(user(['REQUESTER']));

    expect(await screen.findByText('Quarter end', { exact: false })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Grant access' })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Actions for/ })).toBeNull();
  });

  it('shows no controls to the owner of a different table', async () => {
    renderAs(user(['DATA_OWNER'], ['demo-pg.hrdb']));

    expect(await screen.findByText('Quarter end', { exact: false })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Grant access' })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Actions for/ })).toBeNull();
  });

  it('names what a grant was given for, by the register, and says when it was for nothing named', async () => {
    renderAs(
      user(['REQUESTER']),
      access({
        grants: [
          grant('g-1', { principal: 'analyst_a', reason: 'Quarter end', purpose: 'reporting' }),
          grant('g-2', { principal: 'analyst_b', reason: 'Year end' }),
        ],
      })
    );

    expect(await screen.findByText('Regular reporting')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Quarter end/ }));
    const purpose = screen.getByText('Purpose').nextElementSibling as HTMLElement;
    expect(purpose).toHaveTextContent('Regular reporting');
    expect(purpose).toHaveTextContent('Contract');

    fireEvent.click(screen.getByRole('button', { name: /Quarter end/ }));
    fireEvent.click(screen.getByRole('button', { name: /Year end/ }));
    expect(screen.getByText('Purpose').nextElementSibling).toHaveTextContent('None named');
  });

  it('opens a cut-short reason into the details, with who granted it', async () => {
    renderAs(user(['REQUESTER']));

    const reason = await screen.findByRole('button', { name: /Quarter end/ });
    expect(reason).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('Granted')).toBeNull();
    fireEvent.click(reason);
    expect(reason).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('Granted').nextElementSibling).toHaveTextContent('by owner_o');
  });

  it('shows a group grant in full, with the people it lets in', async () => {
    const long = 'Demo PO viewers of the regional procurement desk';
    renderAs(
      user(['REQUESTER']),
      access({
        grants: [
          grant('g-grp', {
            principal: 'demo-grp-po-viewers-regional',
            displayName: long,
            principalType: 'GROUP',
            reason: 'Line one\nLine two',
            effectiveFor: 2,
          }),
        ],
        people: [
          person('member_1', { origin: 'GRANT', viaGrants: ['g-grp'] }),
          person('member_2', { origin: 'BOTH', viaGrants: ['g-grp'], viaPolicies: ['P'] }),
          person('outsider'),
        ],
      })
    );

    const name = await screen.findByRole('button', { name: `Details for ${long}` });
    expect(name).toHaveAttribute('title', `${long} (demo-grp-po-viewers-regional)`);
    fireEvent.click(name);

    const details = document.getElementById(name.getAttribute('aria-controls')!)!;
    expect(details).toHaveTextContent(long);
    expect(details).toHaveTextContent('demo-grp-po-viewers-regional');
    expect(within(details).getByText('Group')).toBeInTheDocument();
    const letsIn = within(details).getByText('Lets in').nextElementSibling as HTMLElement;
    expect(letsIn).toHaveTextContent('member_1');
    expect(letsIn).toHaveTextContent('member_2');
    expect(letsIn).not.toHaveTextContent('outsider');
    // The reason as it was typed, line breaks and all.
    expect(within(details).getByText('Reason').nextElementSibling!.textContent).toBe(
      'Line one\nLine two'
    );
  });

  it('lets an owner change or end a grant that has not started yet', async () => {
    renderAs(
      OWNER,
      access({ grants: [grant('later', { live: false, validFrom: '2099-01-01T00:00:00Z' })] })
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Actions for later' }));
    expect(await screen.findByRole('menuitem', { name: 'Edit' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Revoke' })).toBeInTheDocument();
  });

  it('offers nothing to change on a grant that has expired', async () => {
    renderAs(
      OWNER,
      access({
        grants: [
          grant('gone', {
            live: false,
            validFrom: '2020-01-01T00:00:00Z',
            validUntil: '2020-06-01T00:00:00Z',
          }),
        ],
      })
    );

    const statuses = within(await screen.findByRole('group', { name: 'Grant status' }));
    fireEvent.click(statuses.getByRole('button', { name: /Expired/ }));
    expect(screen.getByText('gone')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Actions for gone' })).toBeNull();
  });
});

describe('a long grant list', () => {
  // Twelve in force, one overruled, one expired: more than a page.
  const many = access({
    grants: [
      ...Array.from({ length: 12 }, (_, i) =>
        grant(`user_${String(i + 1).padStart(2, '0')}`, {
          reason: i === 4 ? 'Month end close' : null,
        })
      ),
      grant('blocked_one', { effectiveFor: 0 }),
      grant('gone_one', {
        live: false,
        validFrom: '2020-01-01T00:00:00Z',
        validUntil: '2020-06-01T00:00:00Z',
      }),
    ],
  });

  function rows() {
    return within(screen.getByRole('list', { name: 'Grants' })).getAllByRole('listitem');
  }

  it('shows ten at a time, leaves expired grants out, and puts the overruled one first', async () => {
    renderAs(OWNER, many);

    expect(await screen.findByText('1–10 of 13')).toBeInTheDocument();
    expect(rows()).toHaveLength(10);
    expect(rows()[0]).toHaveTextContent('blocked_one');
    expect(rows()[0]).toHaveTextContent(/admits nobody/);
    expect(screen.queryByText('gone_one')).toBeNull();

    const pages = within(screen.getByRole('navigation', { name: 'Grant pages' }));
    fireEvent.click(pages.getByRole('button', { name: 'Next page' }));
    expect(screen.getByText('11–13 of 13')).toBeInTheDocument();
    expect(rows()).toHaveLength(3);
  });

  it('brings expired grants back with their chip', async () => {
    renderAs(OWNER, many);

    const statuses = within(await screen.findByRole('group', { name: 'Grant status' }));
    const expired = statuses.getByRole('button', { name: /Expired/ });
    expect(expired).toHaveAttribute('aria-pressed', 'false');
    expect(expired).toHaveTextContent('1');

    fireEvent.click(expired);
    expect(expired).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('1–10 of 14')).toBeInTheDocument();
  });

  it('searches the grants and says so when nothing matches', async () => {
    renderAs(OWNER, many);

    const search = await screen.findByRole('searchbox', { name: 'Search grants' });
    fireEvent.change(search, { target: { value: 'month END' } });
    expect(rows()).toHaveLength(1);
    expect(rows()[0]).toHaveTextContent('user_05');
    // One page: no pager and no range to read.
    expect(screen.queryByRole('navigation', { name: 'Grant pages' })).toBeNull();

    fireEvent.change(search, { target: { value: 'gone_one' } });
    expect(screen.getByText(/No grant here matches/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Show all 14' }));
    expect(search).toHaveValue('');
    expect(screen.getByText('1–10 of 14')).toBeInTheDocument();
  });

  it('narrows to the overruled grants from the strip at the top', async () => {
    renderAs(user(['AUDITOR']), many);

    const strip = within(await screen.findByRole('navigation', { name: 'Access at a glance' }));
    expect(strip.getByRole('button', { name: '13 grants in force' })).toBeInTheDocument();
    fireEvent.click(strip.getByRole('button', { name: '1 overruled' }));

    expect(rows()).toHaveLength(1);
    expect(rows()[0]).toHaveTextContent('blocked_one');
    const statuses = within(screen.getByRole('group', { name: 'Grant status' }));
    expect(statuses.getByRole('button', { name: /Overruled/ })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
    expect(statuses.getByRole('button', { name: /In force/ })).toHaveAttribute(
      'aria-pressed',
      'false'
    );
  });
});

describe('a long list of people', () => {
  const crowd = access({
    grants: [grant('g-team', { principal: 'finance_team', principalType: 'GROUP' })],
    people: [
      ...Array.from({ length: 30 }, (_, i) =>
        person(`reader_${String(i + 1).padStart(2, '0')}`)
      ),
      person('via_grant', { origin: 'GRANT', viaGrants: ['g-team'] }),
      person('via_both', {
        origin: 'BOTH',
        viaGrants: ['g-team'],
        viaPolicies: ['Finance reads'],
      }),
      person('masked_one', { restricted: true, maskedColumns: 2 }),
    ],
    principalsKnown: 33,
    principalsEvaluated: 33,
  });

  function names() {
    return within(screen.getByRole('table'))
      .getAllByRole('row')
      .slice(1)
      .map((row) => within(row).getAllByRole('cell')[0].textContent);
  }

  it('pages at twenty-five and narrows by where access comes from', async () => {
    renderAs(user(['REQUESTER']), crowd);

    expect(await screen.findByText('1–25 of 33')).toBeInTheDocument();
    expect(names()).toHaveLength(25);

    const origins = within(screen.getByRole('group', { name: 'Access from' }));
    fireEvent.click(origins.getByRole('button', { name: /Direct grant/ }));
    expect(names()).toEqual(['via_grant', 'via_both']);
    fireEvent.click(origins.getByRole('button', { name: /Grant and policy/ }));
    expect(names()).toEqual(['via_both']);
  });

  it('finds people by the grant that let them in, and shows only the restricted when asked', async () => {
    renderAs(user(['REQUESTER']), crowd);

    const search = await screen.findByRole('searchbox', { name: 'Search people' });
    fireEvent.change(search, { target: { value: 'finance_team' } });
    expect(names()).toEqual(['via_grant', 'via_both']);

    fireEvent.change(search, { target: { value: '' } });
    fireEvent.click(screen.getByLabelText('Only those who see less than all'));
    expect(names()).toEqual(['masked_one']);
  });

  it('jumps to the restricted people from the strip', async () => {
    renderAs(user(['REQUESTER']), crowd);

    const strip = within(await screen.findByRole('navigation', { name: 'Access at a glance' }));
    expect(strip.getByRole('button', { name: '33 can read now' })).toBeInTheDocument();
    fireEvent.click(strip.getByRole('button', { name: '1 see less than all' }));
    expect(screen.getByLabelText('Only those who see less than all')).toBeChecked();
    expect(names()).toEqual(['masked_one']);
  });
});
