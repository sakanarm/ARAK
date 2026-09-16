import {
  Activity,
  Database01,
  FileShield02,
  Home01,
  Server01,
  ShieldTick,
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
    label: 'Policies',
    href: '/policies',
    icon: ShieldTick,
    milestone: 'M3',
    description:
      'Subscription and data policies, from organisation scope down to a single column.',
  },
  {
    label: 'Simulator',
    href: '/simulator',
    icon: Activity,
    milestone: 'M4',
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
