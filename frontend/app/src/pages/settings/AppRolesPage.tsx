import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Chip as Badge } from '../../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../../api/client';
import {
  createLocalPrincipal,
  fetchPrincipals,
  fetchRoleGrants,
  grantAppRole,
  revokeAppRole,
  type Principal,
  type RoleGrant,
} from '../../api/governance';
import { useAuthStore } from '../../auth/authStore';
import { Field, Select, TextField } from '../policies/controls';
import { PrincipalPicker, ScopePicker } from './pickers';

/**
 * The five roles that decide who may operate ARAK.
 *
 * These are not data access. A platform role says who may write a policy, run
 * a crawl or register a source; it never says which rows or columns anybody
 * sees, and holding every one of them grants no data that a policy withholds.
 * The two get confused often enough that the page says so at the top rather
 * than trusting the heading to carry it.
 *
 * Every line under "may" below was read off an annotation or an authorisation
 * check in the service, not off the requirement document, and the file and
 * line are shown so the next reader can check the same thing. A roles page
 * that drifts from what the server enforces is worse than no roles page: it is
 * a document people plan around.
 *
 * Assignment lives here too, for administrators only. Two different things
 * are being written and the difference decides what is on offer: an app role
 * belongs to this platform outright, so it can be granted to anyone whatever
 * directory they came from, while an *account* can only be created when it is
 * local — an Entra one edited here would be undone by the next sync.
 *
 * A grant reaches this console at once and reaches enforcement at the holder's
 * next sign-in, because authorisation reads the roles baked into their token.
 * The page says so where the grant happens rather than in a footnote, because
 * an administrator who does not know it will conclude the button is broken.
 */

interface Capability {
  text: string;
  /** Where the server enforces it, as a file and line in the service. */
  source: string;
}

interface Role {
  id: string;
  title: string;
  /** The one sentence that decides whether somebody needs this role. */
  purpose: string;
  may: Capability[];
  /** Kept short: only the limits somebody would otherwise assume away. */
  mayNot: string[];
}

const ROLES: Role[] = [
  {
    id: 'PLATFORM_ADMIN',
    title: 'Platform admin',
    purpose:
      'Runs the platform itself: the OpenMetadata connection, the crawls, and which databases are registered.',
    may: [
      {
        text: 'Read and change the OpenMetadata connection settings',
        source: 'OpenMetadataSettingsResource.java:36',
      },
      { text: 'Trigger a sync or a reconcile', source: 'SyncResource.java:31' },
      {
        text: 'Register a data source and edit its connection',
        source: 'SourceResource.java:213',
      },
      {
        text: 'Write a policy at any scope, including organisation-wide',
        source: 'PolicyResource.java:187',
      },
      { text: 'Run a query through the platform', source: 'QueryResource.java:83' },
    ],
    mayNot: [
      'Approve a policy they wrote themselves — the lifecycle step looks for a second pair of eyes whatever the role.',
      'See data a policy withholds. Administering the platform is not an exemption from it.',
    ],
  },
  {
    id: 'POLICY_AUTHOR',
    title: 'Policy author',
    purpose:
      'Writes policy for the whole organisation, without the keys to the platform it runs on.',
    may: [
      {
        text: 'Create and edit a policy at any scope',
        source: 'PolicyResource.java:187',
      },
      { text: 'Run a query through the platform', source: 'QueryResource.java:83' },
    ],
    mayNot: [
      'Change the OpenMetadata connection, trigger a crawl, or register a source.',
      'Activate a policy they wrote. Somebody else moves it out of PENDING_APPROVAL.',
    ],
  },
  {
    id: 'DATA_OWNER',
    title: 'Data owner',
    purpose:
      'Writes policy for the assets they own, and only those. Ownership comes from OpenMetadata.',
    may: [
      {
        text: 'Create and edit a policy whose scope is at or below something they own',
        source: 'PolicyResource.java:197',
      },
      { text: 'Run a query through the platform', source: 'QueryResource.java:83' },
    ],
    mayNot: [
      'Write an organisation-wide policy — one with no scope at all is refused outright.',
      'Move a policy into their scope from outside it: an edit is authorised against both the old document and the new one, so neither end can be used as a way in.',
    ],
  },
  {
    id: 'AUDITOR',
    title: 'Auditor',
    purpose:
      'Reads everything and changes nothing — the role that answers "who could see this, and why".',
    may: [
      {
        text: 'Read policies, decisions and the audit log',
        source: 'PolicyResource.java:41',
      },
      { text: 'Run a query through the platform', source: 'QueryResource.java:83' },
    ],
    mayNot: ['Write or activate a policy. Writing needs POLICY_AUTHOR or DATA_OWNER.'],
  },
  {
    id: 'REQUESTER',
    title: 'Requester',
    purpose:
      'Everyone else. Signs in, reads the catalog and their own access, and asks for more.',
    may: [
      {
        text: 'Read the catalog, the governance vocabulary and their own grants',
        source: 'CatalogResource.java:31',
      },
    ],
    mayNot: [
      'Write a policy, or run a query through the platform — the query endpoint turns this role away.',
    ],
  },
];

export default function AppRolesPage() {
  const isAdmin = useAuthStore((state) => state.hasRole('PLATFORM_ADMIN'));

  const { data, isLoading, error } = useQuery({
    queryKey: ['principals', 'app-roles'],
    queryFn: () => fetchPrincipals({ limit: 500 }),
    staleTime: 60 * 1000,
  });

  // Only an administrator may read this, so asking as anybody else would draw
  // a 403 the page would then have to explain away.
  const grants = useQuery({
    queryKey: ['principals', 'role-grants'],
    queryFn: fetchRoleGrants,
    staleTime: 30 * 1000,
    enabled: isAdmin,
  });

  const holders = useMemo(() => {
    const byRole = new Map<string, Principal[]>();
    for (const principal of data ?? []) {
      for (const role of principal.appRoles) {
        const list = byRole.get(role);
        if (list) {
          list.push(principal);
        } else {
          byRole.set(role, [principal]);
        }
      }
    }
    return byRole;
  }, [data]);

  return (
    <>
      <header>
        <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
          Application roles
        </h1>
        <p className="tw:mt-2 tw:max-w-3xl tw:text-pretty tw:text-md tw:text-tertiary">
          Who may operate ARAK. A role here decides what somebody can do in this
          console — write a policy, run a crawl, register a source. It decides
          nothing about what data they see: that is what a policy is for, and no
          role on this page exempts anyone from one.
        </p>
      </header>

      <section className="tw:mt-6 tw:rounded-xl tw:border tw:border-secondary tw:bg-secondary tw:p-4">
        <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
          Why authoring and approving are separate
        </h2>
        <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
          A policy is written in DRAFT, sent for approval, and only then made
          ACTIVE. The step that activates it is checked against whoever wrote the
          draft, so the same person cannot do both — that is the reason there is
          a policy author role distinct from a platform admin. That step also
          takes a target state and nothing else, so a document change cannot be
          smuggled through the moment meant to be a second pair of eyes.
        </p>
      </section>

      {error && (
        <p className="tw:mt-6 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not load who holds these roles.')}
        </p>
      )}

      {isAdmin && (
        <Assignments
          error={grants.error}
          globalAdminCount={grants.data?.globalAdminCount ?? 0}
          grants={grants.data?.grants ?? []}
          loading={grants.isLoading}
        />
      )}

      <div className="tw:mt-6 tw:flex tw:flex-col tw:gap-4">
        {ROLES.map((role) => (
          <RoleCard
            holders={holders.get(role.id) ?? []}
            key={role.id}
            loading={isLoading}
            role={role}
          />
        ))}
      </div>

      <section className="tw:mt-8 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
        <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
          Where these people come from
        </h2>
        <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
          Accounts are Entra&rsquo;s, with local ones for service integrations
          and for people no directory holds. Roles are ours, and can be granted
          to any of them. Everyone the cache knows, and the attributes a policy
          can test them by, is on{' '}
          <Link className="tw:font-medium tw:text-brand-secondary" to="/principals">
            People &amp; attributes
          </Link>
          .
        </p>
      </section>
    </>
  );
}

function RoleCard({
  holders,
  loading,
  role,
}: {
  holders: Principal[];
  loading: boolean;
  role: Role;
}) {
  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <h2 className="tw:text-md tw:font-semibold tw:text-primary">{role.title}</h2>
        <Badge color="gray" size="sm" type="pill-color">
          {role.id}
        </Badge>
      </div>
      <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">{role.purpose}</p>

      <div className="tw:mt-4 tw:grid tw:gap-4 tw:lg:grid-cols-2">
        <div>
          <h3 className="tw:text-xs tw:font-semibold tw:tracking-wide tw:text-secondary tw:uppercase">
            May
          </h3>
          <ul className="tw:mt-2 tw:flex tw:flex-col tw:gap-1.5">
            {role.may.map((capability) => (
              <li className="tw:text-sm tw:text-primary" key={capability.text}>
                {capability.text}
                <span className="tw:ml-2 tw:text-xs tw:text-quaternary">
                  {capability.source}
                </span>
              </li>
            ))}
          </ul>
        </div>
        <div>
          <h3 className="tw:text-xs tw:font-semibold tw:tracking-wide tw:text-secondary tw:uppercase">
            May not
          </h3>
          <ul className="tw:mt-2 tw:flex tw:flex-col tw:gap-1.5">
            {role.mayNot.map((limit) => (
              <li className="tw:text-sm tw:text-tertiary" key={limit}>
                {limit}
              </li>
            ))}
          </ul>
        </div>
      </div>

      <div className="tw:mt-4 tw:border-t tw:border-secondary tw:pt-3">
        <h3 className="tw:text-xs tw:font-semibold tw:tracking-wide tw:text-secondary tw:uppercase">
          Held by
        </h3>
        <div className="tw:mt-2 tw:flex tw:flex-wrap tw:gap-2">
          {loading && <span className="tw:text-sm tw:text-tertiary">Loading…</span>}
          {!loading && holders.length === 0 && (
            <span className="tw:text-sm tw:text-tertiary">
              Nobody in the identity cache holds this role.
            </span>
          )}
          {holders.map((principal) => (
            <span
              className="tw:rounded-lg tw:border tw:border-secondary tw:px-2.5 tw:py-1 tw:text-sm tw:text-primary"
              key={principal.id}>
              {principal.displayName || principal.username}
              <span className="tw:ml-2 tw:text-xs tw:text-tertiary">
                {principal.principalType === 'GROUP' ? 'group' : principal.source}
              </span>
            </span>
          ))}
        </div>
      </div>
    </section>
  );
}

/**
 * Every role in force, and the two forms that change them.
 *
 * The table is flat rather than grouped by role because the question asked of
 * it is almost always about a person — "what does this account have" — and a
 * grouped list makes that the one thing you cannot read off in one pass.
 */
function Assignments({
  grants,
  globalAdminCount,
  loading,
  error,
}: {
  grants: RoleGrant[];
  globalAdminCount: number;
  loading: boolean;
  error: unknown;
}) {
  const [open, setOpen] = useState<'none' | 'grant' | 'account'>('none');
  const queryClient = useQueryClient();

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['principals'] });
  };

  const revoke = useMutation({
    mutationFn: (grant: RoleGrant) =>
      revokeAppRole(grant.principalId, {
        appRole: grant.appRole,
        scopeFqn: grant.scopeFqn,
      }),
    onSuccess: refresh,
  });

  const lastAdmin = globalAdminCount <= 1;

  return (
    <section className="tw:mt-6 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary">
      <header className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-3 tw:border-b tw:border-secondary tw:p-5">
        <div>
          <h2 className="tw:text-md tw:font-semibold tw:text-primary">
            Who holds what
          </h2>
          <p className="tw:mt-0.5 tw:max-w-2xl tw:text-sm tw:text-tertiary">
            A change here shows in this console immediately. It reaches
            enforcement when the person next signs in, because their token
            carries the roles they had at login — up to an hour.
          </p>
        </div>
        <div className="tw:flex tw:flex-none tw:gap-2">
          <Button
            color="secondary"
            onPress={() => setOpen(open === 'account' ? 'none' : 'account')}
            size="sm">
            Add local account
          </Button>
          <Button
            color="primary"
            onPress={() => setOpen(open === 'grant' ? 'none' : 'grant')}
            size="sm">
            Grant a role
          </Button>
        </div>
      </header>

      {open === 'grant' && (
        <GrantForm onDone={() => setOpen('none')} onSaved={refresh} />
      )}
      {open === 'account' && (
        <AccountForm onDone={() => setOpen('none')} onSaved={refresh} />
      )}

      {error !== null && error !== undefined && (
        <p className="tw:m-5 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-3 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not load the assignments.')}
        </p>
      )}
      {revoke.error && (
        <p className="tw:m-5 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-3 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(revoke.error, 'Could not withdraw that role.')}
        </p>
      )}

      {loading && <p className="tw:p-5 tw:text-sm tw:text-tertiary">Loading…</p>}

      {!loading && grants.length === 0 && (
        <p className="tw:p-5 tw:text-sm tw:text-tertiary">
          Nobody holds a role yet.
        </p>
      )}

      {grants.length > 0 && (
        <table className="tw:w-full tw:text-sm">
          <thead>
            <tr className="tw:border-b tw:border-secondary tw:text-left tw:text-xs tw:tracking-wide tw:text-secondary tw:uppercase">
              <th className="tw:px-5 tw:py-2 tw:font-semibold">Who</th>
              <th className="tw:px-5 tw:py-2 tw:font-semibold">Role</th>
              <th className="tw:px-5 tw:py-2 tw:font-semibold">Scope</th>
              <th className="tw:px-5 tw:py-2 tw:font-semibold">Granted</th>
              <th className="tw:px-5 tw:py-2" />
            </tr>
          </thead>
          <tbody>
            {grants.map((grant) => {
              // The server refuses this too; saying so before the press is the
              // difference between a guard rail and an error message.
              const protectedGrant =
                lastAdmin && grant.appRole === 'PLATFORM_ADMIN' && !grant.scopeFqn;
              return (
                <tr
                  className="tw:border-b tw:border-secondary tw:last:border-0"
                  key={grant.id}>
                  <td className="tw:px-5 tw:py-3">
                    <Link
                      className="tw:font-medium tw:text-primary tw:hover:text-brand-secondary"
                      to={`/principals/${grant.principalId}`}>
                      {grant.displayName || grant.username}
                    </Link>
                    <span className="tw:block tw:text-xs tw:text-tertiary">
                      {grant.username} · {grant.source}
                      {grant.enabled ? '' : ' · disabled'}
                    </span>
                  </td>
                  <td className="tw:px-5 tw:py-3">
                    <Badge color="gray" size="sm" type="pill-color">
                      {grant.appRole}
                    </Badge>
                  </td>
                  <td className="tw:px-5 tw:py-3 tw:text-tertiary">
                    {grant.scopeFqn ?? 'whole platform'}
                  </td>
                  <td className="tw:px-5 tw:py-3 tw:text-tertiary">
                    {grant.grantedAt ? grant.grantedAt.slice(0, 10) : '—'}
                    {grant.grantedBy && (
                      <span className="tw:block tw:text-xs tw:text-quaternary">
                        by {grant.grantedBy}
                      </span>
                    )}
                  </td>
                  <td className="tw:px-5 tw:py-3 tw:text-right">
                    {protectedGrant ? (
                      <span className="tw:text-xs tw:text-tertiary">
                        Last administrator
                      </span>
                    ) : (
                      <Button
                        color="tertiary"
                        isDisabled={revoke.isPending}
                        onPress={() => revoke.mutate(grant)}
                        size="sm">
                        Withdraw
                      </Button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </section>
  );
}

/**
 * Grants one role to one principal.
 *
 * DATA_OWNER is the only role that takes a scope, and it is picked from the
 * catalog rather than typed: an FQN one character wrong grants ownership of
 * nothing, and nothing on any screen afterwards would say so.
 */
function GrantForm({
  onDone,
  onSaved,
}: {
  onDone: () => void;
  onSaved: () => void;
}) {
  const [principal, setPrincipal] = useState<Principal | null>(null);
  const [appRole, setAppRole] = useState('POLICY_AUTHOR');
  const [scopeFqn, setScopeFqn] = useState<string | null>(null);
  const [reason, setReason] = useState('');

  const scoped = appRole === 'DATA_OWNER';

  const grant = useMutation({
    mutationFn: () =>
      grantAppRole(principal?.id ?? '', {
        appRole,
        scopeFqn: scoped ? scopeFqn : null,
        reason: reason.trim() || null,
      }),
    onSuccess: () => {
      onSaved();
      onDone();
    },
  });

  const ready = principal !== null && (!scoped || Boolean(scopeFqn));

  return (
    <div className="tw:border-b tw:border-secondary tw:bg-secondary tw:p-5">
      <h3 className="tw:text-sm tw:font-semibold tw:text-primary">Grant a role</h3>
      <div className="tw:mt-4 tw:grid tw:gap-4 tw:lg:grid-cols-2">
        <Field hint="Any account the cache knows, from any directory." label="Who">
          <PrincipalPicker onChange={setPrincipal} value={principal} />
        </Field>
        <Field
          hint="What they may do in this console. It grants no data a policy withholds."
          label="Role">
          <Select
            onChange={(next) => {
              setAppRole(next);
              if (next !== 'DATA_OWNER') {
                setScopeFqn(null);
              }
            }}
            options={ROLES.map((role) => ({
              value: role.id,
              label: role.title,
              hint: role.purpose,
            }))}
            value={appRole}
          />
        </Field>
        {scoped && (
          <Field
            hint="The asset they own. Everything at or below it comes with it."
            label="Scope">
            <ScopePicker onChange={setScopeFqn} value={scopeFqn} />
          </Field>
        )}
        <Field
          hint="Kept in the identity audit trail. The part a review actually reads."
          label="Reason">
          <TextField
            onChange={setReason}
            placeholder="Why this person needs it"
            value={reason}
          />
        </Field>
      </div>

      {grant.error && (
        <p className="tw:mt-4 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-3 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(grant.error, 'Could not grant that role.')}
        </p>
      )}

      <div className="tw:mt-4 tw:flex tw:gap-2">
        <Button
          color="primary"
          isDisabled={!ready || grant.isPending}
          onPress={() => grant.mutate()}
          size="sm">
          {grant.isPending ? 'Granting…' : 'Grant'}
        </Button>
        <Button color="tertiary" onPress={onDone} size="sm">
          Cancel
        </Button>
      </div>
    </div>
  );
}

/**
 * Creates a local account.
 *
 * Local only, and the form says why rather than offering a directory choice
 * that would be a lie: an Entra account created here would exist until the
 * next sync and then not.
 */
function AccountForm({
  onDone,
  onSaved,
}: {
  onDone: () => void;
  onSaved: () => void;
}) {
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [principalType, setPrincipalType] = useState<'USER' | 'SERVICE'>('USER');
  const [password, setPassword] = useState('');
  const [appRole, setAppRole] = useState('');
  const [scopeFqn, setScopeFqn] = useState<string | null>(null);

  const scoped = appRole === 'DATA_OWNER';

  const create = useMutation({
    mutationFn: () =>
      createLocalPrincipal({
        username: username.trim(),
        displayName: displayName.trim(),
        email: email.trim() || null,
        principalType,
        password,
        roles: appRole ? [{ appRole, scopeFqn: scoped ? scopeFqn : null }] : [],
      }),
    onSuccess: () => {
      onSaved();
      onDone();
    },
  });

  // The display name is required by the server, and every screen in this
  // console lists people by it. Leaving it out of this check is how the form
  // came to offer a Create button that could only answer 400.
  const ready =
    username.trim().length >= 2 &&
    displayName.trim().length >= 2 &&
    password.length >= 10 &&
    (!scoped || Boolean(scopeFqn));

  return (
    <div className="tw:border-b tw:border-secondary tw:bg-secondary tw:p-5">
      <h3 className="tw:text-sm tw:font-semibold tw:text-primary">
        Add a local account
      </h3>
      <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
        For service integrations, tests, and people no directory holds. The
        password below is a first one only: the holder is asked to choose their
        own the first time they sign in.
      </p>

      <div className="tw:mt-4 tw:grid tw:gap-4 tw:lg:grid-cols-2">
        <Field
          hint="Letters, digits, dot, dash, underscore or @. Case is ignored at sign-in."
          label="Username">
          <TextField onChange={setUsername} placeholder="analyst_a" value={username} />
        </Field>
        <Field
          hint="What the console shows instead of the username. Required."
          label="Display name">
          <TextField
            onChange={setDisplayName}
            placeholder="Analyst A"
            value={displayName}
          />
        </Field>
        <Field label="Kind">
          <Select
            onChange={(next) => setPrincipalType(next as 'USER' | 'SERVICE')}
            options={[
              { value: 'USER', label: 'Person', hint: 'Somebody who signs in.' },
              {
                value: 'SERVICE',
                label: 'Service account',
                hint: 'A job or integration that calls the API.',
              },
            ]}
            value={principalType}
          />
        </Field>
        <Field
          hint="Optional. Only used to recognise the same person elsewhere."
          label="Email">
          <TextField
            onChange={setEmail}
            placeholder="analyst_a@example.com"
            type="email"
            value={email}
          />
        </Field>
        <Field hint="Ten characters at least, and not the username." label="First password">
          <TextField
            onChange={setPassword}
            placeholder="A passphrase they will replace"
            type="password"
            value={password}
          />
        </Field>
        <Field hint="Optional — more can be granted afterwards." label="Role to start with">
          <Select
            onChange={(next) => {
              setAppRole(next);
              if (next !== 'DATA_OWNER') {
                setScopeFqn(null);
              }
            }}
            options={[
              { value: '', label: 'None', hint: 'Signs in and reads the catalog.' },
              ...ROLES.map((role) => ({
                value: role.id,
                label: role.title,
                hint: role.purpose,
              })),
            ]}
            placeholder="None"
            value={appRole}
          />
        </Field>
        {scoped && (
          <Field hint="The asset this owner owns." label="Scope">
            <ScopePicker onChange={setScopeFqn} value={scopeFqn} />
          </Field>
        )}
      </div>

      {create.error && (
        <p className="tw:mt-4 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-3 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(create.error, 'Could not create that account.')}
        </p>
      )}

      <div className="tw:mt-4 tw:flex tw:gap-2">
        <Button
          color="primary"
          isDisabled={!ready || create.isPending}
          onPress={() => create.mutate()}
          size="sm">
          {create.isPending ? 'Creating…' : 'Create account'}
        </Button>
        <Button color="tertiary" onPress={onDone} size="sm">
          Cancel
        </Button>
      </div>
    </div>
  );
}
