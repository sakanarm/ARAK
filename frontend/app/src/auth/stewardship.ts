import type { SessionUser } from './session';

/**
 * Whether this person may change who reaches a table: grant on it, revoke on
 * it. The same answer as the server's {@code Stewardship.governs} — an
 * administrator or policy author everywhere, a data owner on their scopes and
 * what lies under them — so the page does not offer a button the server will
 * refuse.
 *
 * Segment by segment, as the server compares: owning {@code prod.Sales} is not
 * owning {@code prod.SalesArchive}.
 */
export function governs(user: SessionUser | null | undefined, fqn: string): boolean {
  if (!user) return false;
  const roles = user.roles ?? [];
  if (roles.includes('PLATFORM_ADMIN') || roles.includes('POLICY_AUTHOR')) return true;
  if (!roles.includes('DATA_OWNER')) return false;
  const target = fqn.trim();
  if (!target) return false;
  return (user.scopes ?? []).some((scope) => {
    const owned = scope?.trim();
    return Boolean(owned) && (target === owned || target.startsWith(`${owned}.`));
  });
}
