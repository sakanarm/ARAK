import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { AccessTab } from './AccessTab';
import { governs } from '../../auth/stewardship';
import { useAuthStore } from '../../auth/authStore';
import type { AssetAccess } from '../../api/access';
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

function access(): AssetAccess {
  return {
    assetFqn: FQN,
    known: true,
    grants: [
      {
        id: 'g-1',
        principal: 'analyst_a',
        displayName: null,
        principalType: 'USER',
        principalSource: 'local',
        validFrom: null,
        validUntil: null,
        reason: 'Quarter end',
        grantedBy: 'owner_o',
        grantedAt: '2026-09-24T03:00:00Z',
        live: true,
        effectiveFor: 1,
      },
    ],
    people: [],
    principalsKnown: 1,
    principalsEvaluated: 1,
    sampled: false,
    evaluatedAt: '2026-09-25T03:00:00Z',
  };
}

function renderAs(who: SessionUser) {
  useAuthStore.setState({ token: 'token', user: who, initialising: false });
  fetchAccess.mockResolvedValue(access());
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
  it('offers grant and revoke to the owner of the table', async () => {
    renderAs(user(['DATA_OWNER'], ['demo-pg.salesdb']));

    expect(await screen.findByText('Quarter end', { exact: false })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Grant access' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Revoke' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument();
  });

  it('edits a grant window with a reason, and sends no expiry as null', async () => {
    amend.mockResolvedValue({});
    renderAs(user(['DATA_OWNER'], ['demo-pg.salesdb']));

    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }));
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
    expect(screen.queryByRole('button', { name: 'Revoke' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
  });

  it('shows no controls to the owner of a different table', async () => {
    renderAs(user(['DATA_OWNER'], ['demo-pg.hrdb']));

    expect(await screen.findByText('Quarter end', { exact: false })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Grant access' })).toBeNull();
  });
});
