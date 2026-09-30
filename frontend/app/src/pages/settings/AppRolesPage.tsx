import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Chip as Badge } from '../../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../../api/client';
import {
  fetchPrincipals,
  fetchRoleGrants,
  grantAppRole,
  revokeAppRole,
  type Principal,
  type RoleGrant,
} from '../../api/governance';
import { useAuthStore } from '../../auth/authStore';
import { Field, Select, TextField } from '../policies/controls';
import { ROLES, type Role } from './appRoles';
import { LocalAccountForm } from './LocalAccountForm';
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
        <LocalAccountForm onDone={() => setOpen('none')} onSaved={refresh} />
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
