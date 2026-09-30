/**
 * The five platform roles, as the roles page describes them and the account
 * form offers them. Kept apart from both so neither imports the other.
 */

export interface Capability {
  text: string;
  /** Where the server enforces it, as a file and line in the service. */
  source: string;
}

export interface Role {
  id: string;
  title: string;
  /** The one sentence that decides whether somebody needs this role. */
  purpose: string;
  may: Capability[];
  /** Kept short: only the limits somebody would otherwise assume away. */
  mayNot: string[];
}

export const ROLES: Role[] = [
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
