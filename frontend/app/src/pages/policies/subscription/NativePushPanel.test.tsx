import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import NativePushPanel from './NativePushPanel';
import { useAuthStore } from '../../../auth/authStore';
import type { SessionUser } from '../../../auth/session';
import type {
  NativeCheck,
  NativePolicy,
  NativePreview,
  NativeRole,
  NativeSource,
  NativeSources,
} from '../../../api/nativeSubscription';

const fetchNativeSources = jest.fn();
const fetchNativePolicy = jest.fn();
const planNative = jest.fn();
const applyNative = jest.fn();
const checkNative = jest.fn();
const planNativeRollback = jest.fn();
const rollbackNative = jest.fn();
const fetchNativeHistory = jest.fn();
const fetchNativeLogins = jest.fn();
const mapNativeLogin = jest.fn();
const unmapNativeLogin = jest.fn();
const setNativeCredential = jest.fn();
const deleteNativeCredential = jest.fn();
const fetchNativeSourceHistory = jest.fn();

jest.mock('../../../api/nativeSubscription', () => ({
  fetchNativeSources: (...args: unknown[]) => fetchNativeSources(...args),
  fetchNativePolicy: (...args: unknown[]) => fetchNativePolicy(...args),
  planNative: (...args: unknown[]) => planNative(...args),
  applyNative: (...args: unknown[]) => applyNative(...args),
  checkNative: (...args: unknown[]) => checkNative(...args),
  planNativeRollback: (...args: unknown[]) => planNativeRollback(...args),
  rollbackNative: (...args: unknown[]) => rollbackNative(...args),
  fetchNativeHistory: (...args: unknown[]) => fetchNativeHistory(...args),
  fetchNativeLogins: (...args: unknown[]) => fetchNativeLogins(...args),
  mapNativeLogin: (...args: unknown[]) => mapNativeLogin(...args),
  unmapNativeLogin: (...args: unknown[]) => unmapNativeLogin(...args),
  setNativeCredential: (...args: unknown[]) => setNativeCredential(...args),
  deleteNativeCredential: (...args: unknown[]) => deleteNativeCredential(...args),
  fetchNativeSourceHistory: (...args: unknown[]) => fetchNativeSourceHistory(...args),
}));

jest.mock('../../../api/client', () => ({
  apiErrorMessage: (error: unknown, fallback: string) =>
    error instanceof Error ? error.message : fallback,
}));

const POLICY = '55555555-5555-5555-5555-555555555555';
const SOURCE = '66666666-6666-6666-6666-666666666666';
const ROLE = 'arak_sub_55555555_66666666';

function user(roles: string[]): SessionUser {
  return {
    id: 'u-1',
    username: 'someone',
    email: null,
    displayName: null,
    source: 'local',
    roles,
    scopes: [],
  };
}

function source(overrides: Partial<NativeSource> = {}): NativeSource {
  return {
    id: SOURCE,
    name: 'demo-pg',
    engine: 'POSTGRES',
    enabled: true,
    database: 'salesdb',
    credential: {
      configured: true,
      scheme: 'fernet',
      updatedAt: '2026-10-01T03:00:00Z',
      updatedBy: 'admin',
    },
    logins: 2,
    roles: 0,
    ...overrides,
  };
}

function sources(list: NativeSource[] = [source()]): NativeSources {
  return { data: list, sweepMinutes: 10, populationLimit: 5000, decisionLimit: 20000 };
}

function role(overrides: Partial<NativeRole> = {}): NativeRole {
  return {
    id: 'r-1',
    policyId: POLICY,
    dataSourceId: SOURCE,
    roleName: ROLE,
    databaseName: 'salesdb',
    accessLevel: 'READ',
    status: 'APPLIED',
    appliedScript: null,
    members: ['alice_login'],
    tables: ['sales.orders'],
    lastAppliedAt: '2026-10-02T03:00:00Z',
    lastAppliedBy: 'admin',
    lastCheckedAt: null,
    lastError: null,
    detail: null,
    updatedAt: '2026-10-02T03:00:00Z',
    ...overrides,
  };
}

function policy(overrides: Partial<NativePolicy> = {}): NativePolicy {
  return {
    policyId: POLICY,
    name: 'tier1-readers',
    policyType: 'SUBSCRIPTION',
    effect: 'ALLOW',
    lifecycleState: 'ACTIVE',
    environment: 'default',
    unsupported: [],
    roles: [],
    ...overrides,
  };
}

function preview(overrides: Partial<NativePreview> = {}): NativePreview {
  return {
    reviewId: 'review-1',
    expiresAt: '2026-10-03T04:00:00Z',
    policyId: POLICY,
    policyName: 'tier1-readers',
    lifecycleState: 'ACTIVE',
    dataSourceId: SOURCE,
    sourceName: 'demo-pg',
    role: ROLE,
    database: 'salesdb',
    level: 'READ',
    tables: ['sales.orders', 'sales.customer'],
    members: [{ login: 'alice_login', people: ['alice'] }],
    excluded: [
      {
        person: 'dave',
        login: 'dave_login',
        reasons: ['No contractors on customer.'],
        lost: ['sales.orders'],
      },
    ],
    unmapped: ['carol'],
    sharedRefused: [],
    exemptions: [],
    changes: [
      { step: 'CREATE_ROLE', target: ROLE, sql: `CREATE ROLE "${ROLE}" NOLOGIN;` },
      { step: 'GRANT_MEMBER', target: 'alice_login', sql: `GRANT "${ROLE}" TO "alice_login";` },
    ],
    applyScript: `CREATE ROLE "${ROLE}" NOLOGIN;\nGRANT "${ROLE}" TO "alice_login";`,
    rollbackScript: `DROP ROLE "${ROLE}";`,
    blockers: [],
    warnings: [],
    otherReaders: [],
    satisfied: false,
    principals: 4,
    serverVersionNum: 160000,
    ...overrides,
  };
}

function renderAs(roles: string[]) {
  useAuthStore.setState({ token: 'token', user: user(roles), initialising: false });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <NativePushPanel policyId={POLICY} />
    </QueryClientProvider>
  );
}

const ADMIN = ['PLATFORM_ADMIN'];
const AUTHOR = ['POLICY_AUTHOR'];

beforeEach(() => {
  jest.clearAllMocks();
  fetchNativeSources.mockResolvedValue(sources());
  fetchNativePolicy.mockResolvedValue(policy());
  fetchNativeHistory.mockResolvedValue([]);
  fetchNativeLogins.mockResolvedValue([]);
  fetchNativeSourceHistory.mockResolvedValue([]);
});

test('says so when there is no PostgreSQL source', async () => {
  fetchNativeSources.mockResolvedValue(sources([]));
  renderAs(AUTHOR);

  expect(await screen.findByText(/No PostgreSQL source is registered/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Plan' })).not.toBeInTheDocument();
});

test('an author plans and reads who is in, who is kept out and why, but cannot apply', async () => {
  planNative.mockResolvedValue(preview());
  renderAs(AUTHOR);

  fireEvent.click(await screen.findByRole('button', { name: 'Plan' }));

  await screen.findByText(/nothing has changed on the source/);
  expect(planNative).toHaveBeenCalledWith(POLICY, SOURCE, 'READ');
  expect(screen.getByText('alice_login')).toBeInTheDocument();
  expect(screen.getByText('No contractors on customer.')).toBeInTheDocument();
  expect(screen.getByText(/Loses the whole role, including sales.orders/)).toBeInTheDocument();
  expect(screen.getByText(/carol\. An administrator maps a login/)).toBeInTheDocument();
  expect(screen.getByText(/GRANT "arak_sub_55555555_66666666" TO "alice_login";/)).toBeInTheDocument();
  expect(screen.getByText('Only a platform administrator can apply a plan.')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Apply this plan' })).not.toBeInTheDocument();
  // The push account and the login map are an administrator's.
  expect(screen.queryByText(/Source setup for/)).not.toBeInTheDocument();
});

test('an administrator applies exactly the plan that was shown', async () => {
  planNative.mockResolvedValue(preview());
  applyNative.mockResolvedValue({
    role: role(),
    statements: 2,
    script: '',
    warnings: [],
  });
  renderAs(ADMIN);

  fireEvent.click(await screen.findByRole('button', { name: 'Plan' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Apply this plan' }));

  expect(await screen.findByText(/Applied: 2 statements ran/)).toBeInTheDocument();
  expect(applyNative).toHaveBeenCalledWith(POLICY, SOURCE, 'review-1');
});

test('a plan with a blocker cannot be applied', async () => {
  planNative.mockResolvedValue(
    preview({ blockers: ['The push account cannot create roles (no CREATEROLE).'] })
  );
  renderAs(ADMIN);

  fireEvent.click(await screen.findByRole('button', { name: 'Plan' }));

  expect(await screen.findByText(/no CREATEROLE/)).toBeInTheDocument();
  expect(screen.getByText('This plan cannot be applied')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Apply this plan' })).toBeDisabled();
});

test('a plan the source already matches has nothing to apply', async () => {
  planNative.mockResolvedValue(preview({ satisfied: true, changes: [], applyScript: '' }));
  renderAs(ADMIN);

  fireEvent.click(await screen.findByRole('button', { name: 'Plan' }));

  expect(await screen.findByText(/already matches this policy/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Apply this plan' })).toBeDisabled();
});

test('a refused apply clears the spent plan and says why', async () => {
  planNative.mockResolvedValue(preview());
  applyNative.mockRejectedValue(new Error('The population changed since the plan.'));
  renderAs(ADMIN);

  fireEvent.click(await screen.findByRole('button', { name: 'Plan' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Apply this plan' }));

  expect(await screen.findByText('The population changed since the plan.')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Apply this plan' })).not.toBeInTheDocument();
});

test('the level starts at the one the role was applied at, and Browse can be chosen', async () => {
  fetchNativePolicy.mockResolvedValue(policy({ roles: [role({ accessLevel: 'BROWSE' })] }));
  planNative.mockResolvedValue(preview({ level: 'READ' }));
  renderAs(AUTHOR);

  const browse = await screen.findByRole('button', { name: 'Browse' });
  expect(browse).toHaveAttribute('aria-pressed', 'true');

  fireEvent.click(screen.getByRole('button', { name: 'Read' }));
  fireEvent.click(screen.getByRole('button', { name: 'Plan' }));
  await waitFor(() => expect(planNative).toHaveBeenCalledWith(POLICY, SOURCE, 'READ'));
});

test('a policy a role cannot carry says why and cannot be planned', async () => {
  fetchNativePolicy.mockResolvedValue(
    policy({ unsupported: ['It has a time window; a role is either granted or not.'] })
  );
  renderAs(ADMIN);

  expect(await screen.findByText('A database role cannot carry this policy')).toBeInTheDocument();
  expect(screen.getByText(/It has a time window/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Plan' })).toBeDisabled();
});

test('a draft policy is told it lets nobody in yet', async () => {
  fetchNativePolicy.mockResolvedValue(policy({ lifecycleState: 'DRAFT' }));
  renderAs(AUTHOR);

  expect(await screen.findByText(/This policy is draft, so it lets nobody in yet/)).toBeInTheDocument();
});

test('a check finds a role changed by hand and says only a person repairs it', async () => {
  fetchNativePolicy.mockResolvedValue(policy({ roles: [role()] }));
  const result: NativeCheck = {
    role: role({ status: 'DRIFTED' }),
    status: 'DRIFTED',
    drifted: true,
    satisfied: false,
    changes: [],
    applyScript: `GRANT SELECT ON TABLE "sales"."orders" TO "${ROLE}";`,
    blockers: [],
    warnings: [],
  };
  checkNative.mockResolvedValue(result);
  renderAs(AUTHOR);

  fireEvent.click(await screen.findByRole('button', { name: 'Check' }));

  expect(await screen.findByText(/changed the role on the source by hand/)).toBeInTheDocument();
  expect(screen.getByText(/GRANT SELECT ON TABLE "sales"."orders"/)).toBeInTheDocument();
  expect(checkNative).toHaveBeenCalledWith(POLICY, SOURCE);
});

test('only an administrator rolls back, after reading what will run', async () => {
  fetchNativePolicy.mockResolvedValue(policy({ roles: [role()] }));
  planNativeRollback.mockResolvedValue({
    role: ROLE,
    database: 'salesdb',
    changes: [],
    script: `DROP ROLE "${ROLE}";`,
    notes: ['Grants somebody else made are left alone.'],
  });
  rollbackNative.mockResolvedValue({ role: role({ status: 'ROLLED_BACK' }), statements: 3, script: '', warnings: [] });
  renderAs(ADMIN);

  fireEvent.click(await screen.findByRole('button', { name: 'Roll back' }));
  expect(await screen.findByText(`DROP ROLE "${ROLE}";`)).toBeInTheDocument();
  expect(rollbackNative).not.toHaveBeenCalled();

  fireEvent.click(screen.getByRole('button', { name: 'Drop the role' }));
  expect(await screen.findByText(/Rolled back: 3 statements ran/)).toBeInTheDocument();
  expect(rollbackNative).toHaveBeenCalledWith(POLICY, SOURCE);
});

test('an author sees no roll back', async () => {
  fetchNativePolicy.mockResolvedValue(policy({ roles: [role()] }));
  renderAs(AUTHOR);

  await screen.findByRole('button', { name: 'Check' });
  expect(screen.queryByRole('button', { name: 'Roll back' })).not.toBeInTheDocument();
});

test('the push account is sent once and the password leaves the page', async () => {
  fetchNativeSources.mockResolvedValue(
    sources([source({ credential: { configured: false, scheme: null, updatedAt: null, updatedBy: null } })])
  );
  setNativeCredential.mockResolvedValue({
    configured: true,
    scheme: 'fernet',
    updatedAt: '2026-10-03T03:00:00Z',
    updatedBy: 'someone',
  });
  renderAs(ADMIN);

  // With no account set, the setup opens by itself.
  const login = await screen.findByLabelText('Push login');
  const password = screen.getByLabelText('Push password');
  expect(password).toHaveAttribute('type', 'password');
  fireEvent.change(login, { target: { value: 'push_account' } });
  fireEvent.change(password, { target: { value: 'not-a-real-secret' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save' }));

  expect(await screen.findByText('Push account saved.')).toBeInTheDocument();
  expect(setNativeCredential).toHaveBeenCalledWith(SOURCE, {
    username: 'push_account',
    password: 'not-a-real-secret',
  });
  expect(screen.getByLabelText('Push password')).toHaveValue('');
  expect(screen.queryByDisplayValue('not-a-real-secret')).not.toBeInTheDocument();
});

test('a push account can be given as a reference', async () => {
  setNativeCredential.mockResolvedValue({
    configured: true,
    scheme: 'env',
    updatedAt: null,
    updatedBy: null,
  });
  renderAs(ADMIN);

  fireEvent.click(await screen.findByRole('button', { name: /Source setup for demo-pg/ }));
  fireEvent.click(screen.getByRole('button', { name: 'Reference' }));
  fireEvent.change(screen.getByLabelText('Credential reference'), {
    target: { value: 'env:PUSH_LOGIN' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Replace' }));

  await waitFor(() =>
    expect(setNativeCredential).toHaveBeenCalledWith(SOURCE, { credentialRef: 'env:PUSH_LOGIN' })
  );
});

test('an administrator maps and unmaps database logins', async () => {
  fetchNativeLogins.mockResolvedValue([
    {
      principalId: 'p-1',
      username: 'alice',
      displayName: 'Alice Example',
      principalType: 'USER',
      login: 'alice_login',
    },
  ]);
  mapNativeLogin.mockResolvedValue({});
  unmapNativeLogin.mockResolvedValue(['alice_login']);
  renderAs(ADMIN);

  fireEvent.click(await screen.findByRole('button', { name: /Source setup for demo-pg/ }));
  const row = (await screen.findByText('Alice Example (alice)')).closest('tr')!;
  expect(within(row).getByText('alice_login')).toBeInTheDocument();

  fireEvent.change(screen.getByLabelText('ARAK username'), { target: { value: 'bob' } });
  fireEvent.change(screen.getByLabelText('Database login'), { target: { value: 'bob_login' } });
  fireEvent.click(screen.getByRole('button', { name: 'Map' }));
  await waitFor(() => expect(mapNativeLogin).toHaveBeenCalledWith(SOURCE, 'bob', 'bob_login'));

  fireEvent.click(within(row).getByRole('button', { name: 'Unmap alice' }));
  await waitFor(() => expect(unmapNativeLogin).toHaveBeenCalledWith(SOURCE, 'alice'));
});

test('history names the sweep and the plan in words', async () => {
  fetchNativeHistory.mockResolvedValue([
    {
      id: 2,
      occurredAt: '2026-10-03T03:10:00Z',
      actor: 'system:native-sweep',
      action: 'EXPIRE',
      outcome: 'APPLIED',
      reviewId: null,
      statements: 1,
      detail: 'Took out 1 login that no longer qualifies.',
    },
    {
      id: 1,
      occurredAt: '2026-10-03T03:00:00Z',
      actor: 'someone',
      action: 'DRY_RUN',
      outcome: 'REVIEWED',
      reviewId: 'review-1',
      statements: null,
      detail: null,
    },
  ]);
  renderAs(AUTHOR);

  fireEvent.click(await screen.findByRole('button', { name: 'History' }));

  const table = await screen.findByRole('table');
  expect(within(table).getByText('Sweep')).toBeInTheDocument();
  expect(within(table).getByText('Plan')).toBeInTheDocument();
  expect(within(table).getByText('Took out 1 login that no longer qualifies.')).toBeInTheDocument();
  expect(fetchNativeHistory).toHaveBeenCalledWith(POLICY, SOURCE);
});
