import {
  Activity,
  BarChartSquare02,
  BookOpen01,
  ClipboardCheck,
  Database01,
  FileShield02,
  Home01,
  Inbox01,
  SearchRefraction,
  Settings01,
  ShieldTick,
  User03,
  Users01,
} from '@untitledui/icons';
import type { NavItemType } from '@openmetadata/ui-core-components/components/application/app-navigation/config';
import type { RailEntry } from '../api/rail';

/**
 * The console's sections, in the order the work lands.
 *
 * Sections whose milestone has not shipped route to a placeholder that says so,
 * and most of them carry their milestone as a badge in the rail: a dead link
 * that explains itself beats a menu that grows silently, and it keeps the shape
 * of the product visible to the people planning around it.
 *
 * That argument has a limit, and `hidden` is where it stops. A section with
 * nothing behind it yet reads to somebody being shown the product as a feature
 * that is broken rather than one that is coming, and two of them side by side
 * read as a console half built. Those are taken out of the rail until they do
 * something, and put back the moment they do. The route stays either way, so a
 * bookmark or a link from a document still lands somewhere that explains
 * itself rather than on the home page.
 */
export interface NavSection extends NavItemType {
  href: string;
  /** Null once the section is real. */
  milestone: string | null;
  /**
   * Kept out of the rail while there is nothing behind it.
   *
   * <p>Not a permission: it hides a section from everyone, including an
   * administrator, and hides nothing that anybody could otherwise reach. The
   * route stays live and still answers with the placeholder.
   */
  hidden?: boolean;
  description: string;
  /**
   * Who this section is offered to.
   *
   * <p>`'everyone'` means any signed-in account. Otherwise the roles that get
   * it, with PLATFORM_ADMIN implied throughout -- so an empty list is the way
   * to say "administrators only".
   *
   * <p>This is the menu, not the boundary. Every section listed here is also
   * refused at the API by the roles its resource declares, and this list is
   * kept to match them: a requester who never had People or Settings should
   * not be handed two links whose only outcome is a 403. Narrowing the rail
   * does not narrow anyone's rights, and widening it would not widen them.
   */
  visibleTo: 'everyone' | string[];
  /**
   * False for a section that is offered but not in the rail until somebody
   * puts it there. It is still reachable from wherever it lives otherwise
   * (Enforcement from Settings), and anybody who uses it daily can pin it with
   * Customize.
   */
  defaultShown?: boolean;
}

export const NAV_SECTIONS: NavSection[] = [
  {
    label: 'Home',
    visibleTo: 'everyone',
    href: '/',
    icon: Home01,
    milestone: null,
    description: 'What you own and what applies to you.',
  },
  {
    label: 'Dashboard',
    visibleTo: ['POLICY_AUTHOR', 'AUDITOR'],
    href: '/dashboard',
    icon: BarChartSquare02,
    milestone: null,
    description:
      'The whole estate at once: coverage of sensitive data, who holds access, what the proxy ran and refused, and what wants a decision.',
  },
  {
    label: 'Catalog',
    visibleTo: 'everyone',
    href: '/catalog',
    icon: Database01,
    milestone: null,
    description:
      'Assets synced from OpenMetadata with their tags, terms, domains and owners.',
  },
  {
    label: 'Governance',
    visibleTo: ['POLICY_AUTHOR', 'DATA_OWNER', 'AUDITOR'],
    href: '/governance',
    icon: BookOpen01,
    milestone: null,
    description:
      'Classifications, tags, glossaries, domains and custom properties, with what each one covers.',
  },
  {
    label: 'People',
    visibleTo: [],
    href: '/principals',
    icon: User03,
    milestone: null,
    description:
      'The identity cache a subject rule is written against, and the attributes it offers.',
  },
  {
    label: 'Policies',
    visibleTo: ['POLICY_AUTHOR', 'DATA_OWNER', 'AUDITOR'],
    href: '/policies',
    icon: ShieldTick,
    milestone: null,
    description:
      'Subscription and data policies, from organisation scope down to a single column.',
  },
  {
    label: 'Query',
    visibleTo: 'everyone',
    href: '/query',
    icon: SearchRefraction,
    milestone: null,
    description:
      'Run SQL through the platform: the policy is compiled into the statement before it reaches the source.',
  },
  {
    // Everyone: each reader gets a different log, drawn by the server -- the
    // whole of it for the roles that oversee everything, their own rows and
    // their tables' for an owner, and their own for anyone else.
    label: 'Query log',
    visibleTo: 'everyone',
    href: '/audit',
    icon: ClipboardCheck,
    milestone: null,
    description:
      'What was sent through the query proxy, what it became once policy was compiled in, and how it ended.',
  },
  {
    // Everyone: anybody can be refused and ask, and whether somebody may
    // decide is the table's owner in OpenMetadata, not a console role. The
    // server scopes the inbox; the rail does not need to guess at it.
    label: 'Requests',
    visibleTo: 'everyone',
    href: '/requests',
    icon: Inbox01,
    milestone: null,
    description:
      'Ask a table owner for access, and decide what is asked of the tables you own.',
  },
  {
    label: 'Simulator',
    visibleTo: ['POLICY_AUTHOR', 'DATA_OWNER', 'AUDITOR'],
    href: '/simulator',
    icon: Activity,
    milestone: null,
    description:
      'See a table as another person sees it before a policy reaches production.',
  },
  {
    // Out of the rail by default and into Settings -> Data source connections:
    // applying a secure view is a setup step done a few times per table, not
    // a place anybody goes every day. Customize puts it back for whoever does.
    label: 'Enforcement',
    visibleTo: ['POLICY_AUTHOR', 'DATA_OWNER'],
    href: '/enforcement',
    icon: FileShield02,
    milestone: null,
    defaultShown: false,
    description:
      'Review, apply and roll back secure views on registered sources.',
  },
  {
    label: 'Access',
    visibleTo: ['POLICY_AUTHOR', 'DATA_OWNER', 'AUDITOR'],
    href: '/access',
    icon: Users01,
    milestone: 'M8',
    // Hidden for the same reason. Grants themselves do work -- they are issued
    // and revoked from the asset page, and "who can reach this" is the Access
    // tab there -- but this rail entry has no page of its own, so it promises
    // a screen that is not there. Put it back with the M8 overview.
    hidden: true,
    description: 'Grants, expiry and who can reach what.',
  },
  // Sources and System are not in this rail at all. Both are setup screens, reached
  // from Settings -> Data source connections, the same place the OpenMetadata
  // connection and the app roles live. A second door on the top-level rail
  // made the rail read as though registering a database or checking the build
  // were daily work, which it is not.
  {
    // Policy authors and data owners too, now that Enforcement is reached from
    // here. The page itself shows each of them only the cards they can open.
    label: 'Settings',
    visibleTo: ['POLICY_AUTHOR', 'DATA_OWNER'],
    href: '/settings',
    icon: Settings01,
    milestone: null,
    description:
      'Where the metadata comes from, who may operate this platform, and which databases it enforces policy in.',
  },
];

export function findSection(pathname: string): NavSection | undefined {
  return NAV_SECTIONS.find((section) => section.href === pathname);
}

/**
 * The sections one account is offered -- everything Customize lists, whether
 * or not it is in the rail right now.
 *
 * @param hasRole the store's check, which already treats PLATFORM_ADMIN as
 *     holding every role
 */
export function sectionsFor(
  hasRole: (...roles: string[]) => boolean
): NavSection[] {
  return NAV_SECTIONS.filter(
    (section) =>
      !section.hidden &&
      (section.visibleTo === 'everyone' || hasRole(...section.visibleTo))
  );
}

/** One line of the rail as this person arranged it. */
export interface RailItem {
  section: NavSection;
  shown: boolean;
}

/**
 * The rail one account is drawn: the sections it is offered, in the order it
 * saved, showing the ones it chose.
 *
 * Saved entries this account is not offered -- a section since removed, or
 * one a role it no longer holds opened -- are dropped silently: the list is a
 * preference, and a stale line in it must never become a link. A section it
 * is offered but never arranged -- new in this release, or new to it with a
 * role -- goes in right after the section it follows by default, shown or not
 * as it would be by default, so nobody has to open Customize to find it.
 *
 * @param saved null when this person never arranged the rail
 */
export function arrangeRail(offered: NavSection[], saved: RailEntry[] | null | undefined): RailItem[] {
  const byDefault = (section: NavSection): RailItem => ({
    section,
    shown: section.defaultShown !== false,
  });
  if (!saved) {
    return offered.map(byDefault);
  }
  const byHref = new Map(offered.map((section) => [section.href, section]));
  const items: RailItem[] = [];
  for (const entry of saved) {
    const section = byHref.get(entry.href);
    if (section && !items.some((item) => item.section === section)) {
      items.push({ section, shown: entry.shown });
    }
  }
  offered.forEach((section, index) => {
    if (items.some((item) => item.section === section)) {
      return;
    }
    let at = 0;
    for (let before = index - 1; before >= 0; before -= 1) {
      const found = items.findIndex((item) => item.section === offered[before]);
      if (found >= 0) {
        at = found + 1;
        break;
      }
    }
    items.splice(at, 0, byDefault(section));
  });
  return items;
}
