import type { GrantAccess, PersonAccess } from '../../api/access';

/**
 * The Access tab's two long lists, narrowed in the browser.
 *
 * <p>Everything here works on the one answer the page already holds: the
 * server sends every grant and every evaluated person in a single response, so
 * filtering, searching and paging are a matter of which rows to draw, and none
 * of them asks the server anything. Kept out of the component so the rules for
 * what a chip counts can be read — and tested — without rendering a page.
 */

/**
 * Where a grant stands. One status per grant, so the chips add up to the list.
 *
 * <p>`OVERRULED` is a live grant the engine lets nobody in through: the window
 * is open and an outer layer is refusing the person anyway. It has its own
 * chip, apart from `IN_FORCE`, because it is the row an owner most needs to
 * find and the one most easily mistaken for access.
 */
export type GrantStatus = 'IN_FORCE' | 'OVERRULED' | 'NOT_STARTED' | 'EXPIRED';

export const GRANT_STATUSES: ReadonlyArray<{ status: GrantStatus; label: string }> = [
  { status: 'IN_FORCE', label: 'In force' },
  { status: 'OVERRULED', label: 'Overruled' },
  { status: 'NOT_STARTED', label: 'Not started' },
  { status: 'EXPIRED', label: 'Expired' },
];

/**
 * What the grant list shows before anybody touches it: everything that is,
 * or is about to be, access. Expired grants are history, and on a table that
 * has been governed for a year they are most of the list. (Revoked ones are
 * not sent at all — they live in the Audit tab.)
 */
export const DEFAULT_GRANT_STATUSES: ReadonlySet<GrantStatus> = new Set([
  'IN_FORCE',
  'OVERRULED',
  'NOT_STARTED',
]);

/**
 * @param known false when the asset is not in the catalog cache. Nobody was
 *   evaluated then, so a count of zero says nothing about a policy refusing
 *   anyone, and the grant is not called overruled.
 */
export function grantStatus(grant: GrantAccess, known: boolean, now = Date.now()): GrantStatus {
  if (grant.live) {
    return known && grant.effectiveFor === 0 ? 'OVERRULED' : 'IN_FORCE';
  }
  if (grant.validFrom && Date.parse(grant.validFrom) > now) {
    return 'NOT_STARTED';
  }
  return 'EXPIRED';
}

/** The order the list is drawn in: what is access first, history last. */
const STATUS_RANK: Record<GrantStatus, number> = {
  OVERRULED: 0,
  IN_FORCE: 1,
  NOT_STARTED: 2,
  EXPIRED: 3,
};

export function countGrants(
  grants: GrantAccess[],
  known: boolean,
  now = Date.now()
): Record<GrantStatus, number> {
  const counts: Record<GrantStatus, number> = {
    IN_FORCE: 0,
    OVERRULED: 0,
    NOT_STARTED: 0,
    EXPIRED: 0,
  };
  for (const grant of grants) {
    counts[grantStatus(grant, known, now)] += 1;
  }
  return counts;
}

/** Lower-cased and trimmed, so a search is not defeated by a stray capital. */
function needle(search: string): string {
  return search.trim().toLocaleLowerCase();
}

function has(value: string | null | undefined, text: string): boolean {
  return Boolean(value) && value!.toLocaleLowerCase().includes(text);
}

/**
 * The grants to draw, overruled first, then in force, not started and ended.
 * Within a status the server's order stands.
 */
export function filterGrants(
  grants: GrantAccess[],
  {
    statuses,
    search,
    known,
    now = Date.now(),
  }: { statuses: ReadonlySet<GrantStatus>; search: string; known: boolean; now?: number }
): GrantAccess[] {
  const text = needle(search);
  return grants
    .map((grant, index) => ({ grant, index, status: grantStatus(grant, known, now) }))
    .filter(({ grant, status }) => {
      if (!statuses.has(status)) {
        return false;
      }
      return (
        !text ||
        has(grant.principal, text) ||
        has(grant.displayName, text) ||
        has(grant.reason, text) ||
        has(grant.grantedBy, text) ||
        has(grant.purpose, text)
      );
    })
    .sort((a, b) => STATUS_RANK[a.status] - STATUS_RANK[b.status] || a.index - b.index)
    .map(({ grant }) => grant);
}

/**
 * Which of the people to show, by where their access comes from.
 *
 * <p>`GRANT` and `POLICY` mean "any access that comes that way", so a person
 * let in by both is under either — an owner asking "who is here by a grant"
 * wants the ones a revoke would reach and the ones it would not. `BOTH` is the
 * narrower question: who would keep access after the grant is revoked.
 */
export type OriginFilter = 'ALL' | 'GRANT' | 'POLICY' | 'BOTH';

export const ORIGIN_FILTERS: ReadonlyArray<{ origin: OriginFilter; label: string }> = [
  { origin: 'ALL', label: 'Everyone' },
  { origin: 'GRANT', label: 'Direct grant' },
  { origin: 'POLICY', label: 'Via policy' },
  { origin: 'BOTH', label: 'Grant and policy' },
];

function fromOrigin(person: PersonAccess, origin: OriginFilter): boolean {
  switch (origin) {
    case 'ALL':
      return true;
    case 'GRANT':
      return person.origin === 'GRANT' || person.origin === 'BOTH';
    case 'POLICY':
      return person.origin === 'POLICY' || person.origin === 'BOTH';
    case 'BOTH':
      return person.origin === 'BOTH';
  }
}

export function countOrigins(people: PersonAccess[]): Record<OriginFilter, number> {
  const counts: Record<OriginFilter, number> = { ALL: 0, GRANT: 0, POLICY: 0, BOTH: 0 };
  for (const person of people) {
    for (const { origin } of ORIGIN_FILTERS) {
      if (fromOrigin(person, origin)) {
        counts[origin] += 1;
      }
    }
  }
  return counts;
}

/**
 * @param grantNames the principal each grant id names, so a search for the
 *   group a person came in through finds them.
 */
export function filterPeople(
  people: PersonAccess[],
  {
    origin,
    restrictedOnly,
    search,
    grantNames,
  }: {
    origin: OriginFilter;
    restrictedOnly: boolean;
    search: string;
    grantNames: ReadonlyMap<string, string>;
  }
): PersonAccess[] {
  const text = needle(search);
  return people.filter((person) => {
    if (!fromOrigin(person, origin) || (restrictedOnly && !person.restricted)) {
      return false;
    }
    return (
      !text ||
      has(person.principal, text) ||
      person.viaPolicies.some((name) => has(name, text)) ||
      person.viaGrants.some((id) => has(grantNames.get(id), text))
    );
  });
}

/** One page of a list, and the offset it really starts at once clamped. */
export function page<T>(rows: T[], offset: number, size: number): { rows: T[]; offset: number } {
  const last = Math.max(0, Math.floor((rows.length - 1) / size) * size);
  const start = Math.min(Math.max(0, offset), last);
  return { rows: rows.slice(start, start + size), offset: start };
}

/** "1–10 of 34", or "0 of 0" said as nothing at all. */
export function range(offset: number, shown: number, total: number): string {
  if (total === 0) {
    return '';
  }
  return `${offset + 1}–${offset + shown} of ${total}`;
}
