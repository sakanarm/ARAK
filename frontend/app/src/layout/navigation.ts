import {
  Activity,
  BookOpen01,
  Database01,
  FileShield02,
  Home01,
  SearchRefraction,
  Server01,
  Settings01,
  ShieldTick,
  User03,
  Users01,
} from '@untitledui/icons';
import type { NavItemType } from '@openmetadata/ui-core-components/components/application/app-navigation/config';

/**
 * The console's sections, in the order the work lands.
 *
 * Sections whose milestone has not shipped carry their milestone as a badge and
 * route to a placeholder that says so. Hiding them would be tidier but would
 * also hide the shape of the product from the people who have to plan around
 * it, and a dead link that explains itself beats a menu that grows silently.
 */
export interface NavSection extends NavItemType {
  href: string;
  /** Null once the section is real. */
  milestone: string | null;
  description: string;
}

export const NAV_SECTIONS: NavSection[] = [
  {
    label: 'Home',
    href: '/',
    icon: Home01,
    milestone: null,
    description: 'What you own and what applies to you.',
  },
  {
    label: 'Catalog',
    href: '/catalog',
    icon: Database01,
    milestone: null,
    description:
      'Assets synced from OpenMetadata with their tags, terms, domains and owners.',
  },
  {
    label: 'Governance',
    href: '/governance',
    icon: BookOpen01,
    milestone: null,
    description:
      'Classifications, tags, glossaries, domains and custom properties, with what each one covers.',
  },
  {
    label: 'People',
    href: '/principals',
    icon: User03,
    milestone: null,
    description:
      'The identity cache a subject rule is written against, and the attributes it offers.',
  },
  {
    label: 'Policies',
    href: '/policies',
    icon: ShieldTick,
    milestone: null,
    description:
      'Subscription and data policies, from organisation scope down to a single column.',
  },
  {
    label: 'Query',
    href: '/query',
    icon: SearchRefraction,
    milestone: null,
    description:
      'Run SQL through the platform: the policy is compiled into the statement before it reaches the source.',
  },
  {
    label: 'Simulator',
    href: '/simulator',
    icon: Activity,
    milestone: null,
    description:
      'See a table as another person sees it before a policy reaches production.',
  },
  {
    label: 'Enforcement',
    href: '/enforcement',
    icon: FileShield02,
    milestone: 'M5',
    description:
      'Native source config, secure views and the query API, with drift detection.',
  },
  {
    label: 'Access',
    href: '/access',
    icon: Users01,
    milestone: 'M8',
    description: 'Grants, expiry and who can reach what.',
  },
  // Sources is not in this rail. It is a setup screen, reached from Settings
  // -> Data sources, the same place the OpenMetadata connection and the app
  // roles live. A second door on the top-level rail made the rail read as
  // though registering a database were daily work, which it is not.
  {
    label: 'Settings',
    href: '/settings',
    icon: Settings01,
    milestone: null,
    description:
      'Where the metadata comes from, who may operate this platform, and which databases it enforces policy in.',
  },
  {
    label: 'System',
    href: '/system',
    icon: Server01,
    milestone: null,
    description: 'Service build and the OpenMetadata instance it is pinned to.',
  },
];

export function findSection(pathname: string): NavSection | undefined {
  return NAV_SECTIONS.find((section) => section.href === pathname);
}
