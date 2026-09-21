import { useEffect, useState, type ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { NAV_SECTIONS, type NavSection } from './navigation';
import TopNav from './TopNav';

export { humaniseRole } from './TopNav';

/** Remembered across visits: hiding the rail is a working preference, not a mood. */
const COLLAPSE_KEY = 'arak.sidebar.collapsed';

/**
 * The signed-in frame: header, sidebar, content.
 *
 * It follows the OpenMetadata 2.0 shell so that somebody who administers the
 * catalog does not have to learn a second layout to administer access to it:
 * a full-width header that stays put, a rail under it that says where you are,
 * and the page itself in the remaining space.
 *
 * The two bars answer different questions on purpose. The header carries the
 * product's name and what you can do from anywhere — search the estate, start a
 * policy, sign out. The rail carries only where you can go, so that collapsing
 * it costs navigation and nothing else.
 */
export default function AppShell({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const [collapsed, setCollapsed] = useState(() => readCollapsed());

  useEffect(() => {
    try {
      window.localStorage.setItem(COLLAPSE_KEY, collapsed ? '1' : '0');
    } catch {
      // A browser that refuses storage still gets a working toggle; it just
      // forgets the choice on reload.
    }
  }, [collapsed]);

  return (
    <div className="tw:flex tw:min-h-screen tw:flex-col tw:bg-secondary">
      <TopNav
        collapsed={collapsed}
        drawer={<Sidebar activeUrl={pathname} collapsed={false} />}
        onToggleSidebar={() => setCollapsed((previous) => !previous)}
      />

      <div className="tw:flex tw:flex-1">
        {/*
          Collapsing narrows the rail to its icons rather than removing it, and
          the width is animated. Both are deliberate: an icon rail keeps every
          section one click away instead of two, and a rail that snaps between
          two widths makes the content beside it look like it jumped to a
          different page. 400ms is slow enough to read as one thing moving.
        */}
        <div
          className={`tw:hidden tw:shrink-0 tw:transition-[width] tw:duration-400 tw:ease-in-out tw:lg:block ${
            collapsed ? 'tw:w-18' : 'tw:w-70'
          }`}>
          <div
            className={`tw:fixed tw:top-16 tw:bottom-0 tw:transition-[width] tw:duration-400 tw:ease-in-out ${
              collapsed ? 'tw:w-18' : 'tw:w-70'
            }`}>
            <Sidebar activeUrl={pathname} collapsed={collapsed} />
          </div>
        </div>

        <main className="tw:w-full tw:min-w-0 tw:flex-1">
          <div className="tw:mx-auto tw:max-w-7xl tw:px-4 tw:py-8 tw:sm:px-8">
            {children}
          </div>
        </main>
      </div>
    </div>
  );
}

function readCollapsed(): boolean {
  try {
    return window.localStorage.getItem(COLLAPSE_KEY) === '1';
  } catch {
    return false;
  }
}

function Sidebar({
  activeUrl,
  collapsed,
}: {
  activeUrl: string;
  collapsed: boolean;
}) {
  return (
    /*
      The rail itself never scrolls. It used to: the whole column was one
      scroll box, so the copyright line at the bottom counted towards the
      height, and on a laptop-height window the twelve links plus that line
      came to a few pixels more than the space between the header and the
      bottom of the screen. The result was a full-length scrollbar with a
      full-length thumb -- a control that moved nothing, on a list that was
      already entirely visible.

      Now only the list scrolls and the footer is pinned outside it. On any
      window tall enough for the links there is no scrollbar at all, and on one
      that is not, the bar appears over the links alone, which is the only part
      of the rail that has anywhere to go.
    */
    <nav
      aria-label="Sections"
      className="tw:flex tw:h-full tw:flex-col tw:overflow-hidden tw:border-r tw:border-secondary tw:bg-primary tw:pt-3 tw:pb-4">
      <ul className="tw:flex tw:min-h-0 tw:flex-1 tw:flex-col tw:gap-0.5 tw:overflow-x-hidden tw:overflow-y-auto tw:px-3">
        {NAV_SECTIONS.map((section) => (
          <li key={section.href}>
            <NavItem
              collapsed={collapsed}
              current={isCurrent(activeUrl, section.href)}
              section={section}
            />
          </li>
        ))}
      </ul>

      {/*
        Hidden when the rail is collapsed: at eighteen rem there is no width for
        it, and a notice nobody can read is just a narrower rail.
      */}
      {!collapsed && (
        <div className="tw:shrink-0 tw:px-3 tw:pt-5">
          <div className="tw:mx-3.5 tw:h-px tw:bg-border-secondary" />
          {/*
            Left-aligned on the same 3.5 gutter as the nav labels above it, so
            the rail reads as one column. Centred, it floated free of
            everything else in the rail and drew more attention than a
            copyright line deserves.
          */}
          <p className="tw:px-3.5 tw:pt-4 tw:text-xs tw:font-semibold tw:text-tertiary">
            &copy; 2026 MFEC
          </p>
          <p className="tw:px-3.5 tw:pt-0.5 tw:text-xs tw:text-quaternary">
            All rights reserved
          </p>
        </div>
      )}
    </nav>
  );
}

function NavItem({
  section,
  current,
  collapsed,
}: {
  section: NavSection;
  current: boolean;
  collapsed: boolean;
}) {
  const Icon = section.icon;

  return (
    <NavLink
      // The label has to reach a screen reader even when it is not drawn, and
      // the title is what tells a sighted reader which icon is which.
      aria-label={collapsed ? section.label : undefined}
      className={`tw:group tw:flex tw:h-11 tw:items-center tw:rounded-lg tw:outline-focus-ring tw:transition tw:duration-100 tw:focus-visible:outline-2 ${
        collapsed ? 'tw:w-11 tw:justify-center' : 'tw:gap-3 tw:px-3.5'
      } ${
        current
          ? 'tw:bg-brand-solid tw:hover:bg-brand-solid_hover'
          : 'tw:hover:bg-primary_hover'
      }`}
      end={section.href === '/'}
      title={collapsed ? section.label : undefined}
      to={section.href}>
      {Icon && (
        <Icon
          aria-hidden
          className={`tw:size-6 tw:shrink-0 ${
            current ? 'tw:text-fg-white' : 'tw:text-fg-quaternary'
          }`}
        />
      )}

      {!collapsed && (
        <>
          {/* Only the current section is bold; the rest read as a plain list. */}
          <span
            className={`tw:flex-1 tw:truncate tw:text-md ${
              current
                ? 'tw:font-semibold tw:text-fg-white'
                : 'tw:font-normal tw:text-secondary'
            }`}>
            {section.label}
          </span>

          {section.milestone && (
            <Badge color="gray" size="sm" type="pill-color">
              {section.milestone}
            </Badge>
          )}
        </>
      )}
    </NavLink>
  );
}

/**
 * Whether this section owns the page being shown.
 *
 * Prefix matching, so that an asset page keeps Catalog lit rather than lighting
 * nothing. Home is exact, or it would be current everywhere.
 */
function isCurrent(pathname: string, href: string): boolean {
  if (href === '/') {
    return pathname === '/';
  }
  const section = href.split('/')[1];
  return pathname === `/${section}` || pathname.startsWith(`/${section}/`);
}
