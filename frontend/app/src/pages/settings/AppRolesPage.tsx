import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { apiErrorMessage } from '../../api/client';
import { fetchPrincipals, type Principal } from '../../api/governance';

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
 * Read-only, because assignment has nowhere to land yet: PrincipalResource is
 * deliberately read-only while Entra owns this content, and a form here would
 * be reverted by the next sync. The page says where assignment will live
 * instead of offering a control that does nothing.
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
  const { data, isLoading, error } = useQuery({
    queryKey: ['principals', 'app-roles'],
    queryFn: () => fetchPrincipals({ limit: 500 }),
    staleTime: 60 * 1000,
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
        <p className="tw:mt-2 tw:max-w-3xl tw:text-md tw:text-tertiary">
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
        <p className="tw:mt-1 tw:max-w-3xl tw:text-sm tw:text-tertiary">
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
          Assigning a role
        </h2>
        <p className="tw:mt-1 tw:max-w-3xl tw:text-sm tw:text-tertiary">
          Not from here yet. Roles arrive with the identity sync, and the
          identity cache is read-only while Entra owns it — a form on this page
          would be reverted by the next crawl. Until local assignment lands, a
          role is granted through the Entra group it is mapped to. Who holds
          what today is listed above, and the people themselves are on{' '}
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
      <p className="tw:mt-1 tw:max-w-3xl tw:text-sm tw:text-tertiary">{role.purpose}</p>

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
