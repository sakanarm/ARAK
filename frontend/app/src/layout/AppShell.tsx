import type { ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { LogOut01 } from '@untitledui/icons';
import { Avatar } from '@openmetadata/ui-core-components/components/base/avatar/avatar';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { NavList } from '@openmetadata/ui-core-components/components/application/app-navigation/base-components/nav-list';
import { MobileNavigationHeader } from '@openmetadata/ui-core-components/components/application/app-navigation/base-components/mobile-header';
import type { NavItemType } from '@openmetadata/ui-core-components/components/application/app-navigation/config';
import { useAuthStore } from '../auth/authStore';
import { NAV_SECTIONS } from './navigation';
import mark from '../assets/arak-mark.png';

/**
 * The signed-in frame: sidebar, account footer, content.
 *
 * It follows the OpenMetadata 2.0 shell so that somebody who administers the
 * catalog does not have to learn a second layout to administer access to it.
 */
export default function AppShell({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();

  const items: NavItemType[] = NAV_SECTIONS.map((section) => ({
    label: section.label,
    href: section.href,
    icon: section.icon,
    badge: section.milestone ?? undefined,
  }));

  const sidebar = <Sidebar activeUrl={pathname} items={items} />;

  return (
    <div className="tw:min-h-screen tw:bg-secondary">
      <div className="tw:lg:hidden">
        <MobileNavigationHeader>{sidebar}</MobileNavigationHeader>
      </div>

      <div className="tw:hidden tw:lg:fixed tw:lg:inset-y-0 tw:lg:left-0 tw:lg:z-40 tw:lg:block tw:lg:w-70">
        {sidebar}
      </div>

      <div className="tw:lg:pl-70">
        <main className="tw:mx-auto tw:max-w-6xl tw:px-4 tw:py-8 tw:sm:px-8">{children}</main>
      </div>
    </div>
  );
}

function Sidebar({
  activeUrl,
  items,
}: {
  activeUrl: string;
  items: NavItemType[];
}) {
  const user = useAuthStore((state) => state.user);
  const signOut = useAuthStore((state) => state.signOut);

  const name = user?.displayName || user?.username || 'Signed in';
  const roles = user?.roles ?? [];

  return (
    <div className="tw:flex tw:h-full tw:flex-col tw:justify-between tw:border-r tw:border-secondary tw:bg-primary">
      <div>
        <div className="tw:flex tw:items-center tw:gap-3 tw:px-5 tw:pt-6">
          <img alt="" aria-hidden className="tw:size-9 tw:shrink-0" src={mark} />
          <span>
            <p className="tw:text-md tw:font-semibold tw:text-primary">ARAK</p>
            <p className="tw:text-xs tw:text-tertiary">Data access control</p>
          </span>
        </div>

        <NavList activeUrl={activeUrl} items={items} />
      </div>

      <div className="tw:border-t tw:border-secondary tw:p-4">
        <div className="tw:flex tw:items-center tw:gap-3">
          <Avatar initials={initialsOf(name)} size="md" />
          <div className="tw:min-w-0 tw:flex-1">
            <p className="tw:truncate tw:text-sm tw:font-semibold tw:text-primary">{name}</p>
            <p className="tw:truncate tw:text-xs tw:text-tertiary">
              {user?.email || user?.username}
            </p>
          </div>
          <Button
            aria-label="Sign out"
            color="tertiary"
            iconLeading={LogOut01}
            onPress={signOut}
            size="sm"
          />
        </div>

        {roles.length > 0 && (
          <div className="tw:mt-3 tw:flex tw:flex-wrap tw:gap-1">
            {roles.map((role) => (
              <Badge color="gray" key={role} size="sm" type="pill-color">
                {humaniseRole(role)}
              </Badge>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/** Two letters at most, so a long display name does not overflow the circle. */
function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return '?';
  }
  if (parts.length === 1) {
    return parts[0].slice(0, 2).toUpperCase();
  }
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

export function humaniseRole(role: string): string {
  return role
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}
