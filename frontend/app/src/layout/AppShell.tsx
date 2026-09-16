import type { ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { LogOut01, ShieldTick } from '@untitledui/icons';
import { Avatar } from '@openmetadata/ui-core-components/components/base/avatar/avatar';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { NavList } from '@openmetadata/ui-core-components/components/application/app-navigation/base-components/nav-list';
import { MobileNavigationHeader } from '@openmetadata/ui-core-components/components/application/app-navigation/base-components/mobile-header';
import type { NavItemType } from '@openmetadata/ui-core-components/components/application/app-navigation/config';
import { useAuthStore } from '../auth/authStore';
import { NAV_SECTIONS } from './navigation';

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
    <div className="min-h-screen bg-secondary">
      <div className="lg:hidden">
        <MobileNavigationHeader>{sidebar}</MobileNavigationHeader>
      </div>

      <div className="hidden lg:fixed lg:inset-y-0 lg:left-0 lg:z-40 lg:block lg:w-70">
        {sidebar}
      </div>

      <div className="lg:pl-70">
        <main className="mx-auto max-w-6xl px-4 py-8 sm:px-8">{children}</main>
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
    <div className="flex h-full flex-col justify-between border-r border-secondary bg-primary">
      <div>
        <div className="flex items-center gap-3 px-5 pt-6">
          <span className="flex size-9 items-center justify-center rounded-lg bg-brand-solid">
            <ShieldTick aria-hidden className="size-5 text-white" />
          </span>
          <span>
            <p className="text-md font-semibold text-primary">ARAK</p>
            <p className="text-xs text-tertiary">Data access control</p>
          </span>
        </div>

        <NavList activeUrl={activeUrl} items={items} />
      </div>

      <div className="border-t border-secondary p-4">
        <div className="flex items-center gap-3">
          <Avatar initials={initialsOf(name)} size="md" />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold text-primary">{name}</p>
            <p className="truncate text-xs text-tertiary">
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
          <div className="mt-3 flex flex-wrap gap-1">
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
