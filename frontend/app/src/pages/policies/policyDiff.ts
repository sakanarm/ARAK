import type { Policy } from '../../generated/entity/policy/policy';
import {
  describeColumnRule,
  describeRowFilter,
  describeSelector,
  describeSubject,
} from './policyLanguage';

/**
 * What differs between two versions of a policy, field by field (FR-9.2).
 *
 * Compared on meaning, not on text: two documents that say the same thing in a
 * different key order, or with a false flag spelled out in one and left off in
 * the other, are the same policy and show no difference. Each side is then
 * read back through the same sentences the rest of the page uses, so whoever
 * restores a version is shown what it does rather than the JSON it is made of.
 *
 * Only the author's fields are compared. Version, state, environment and the
 * updated-by stamp belong to the store, change on every save, and would bury
 * the one line that matters under several that always differ.
 */

export interface PolicyFieldChange {
  /** Which part of the policy, as the page labels it. */
  field: string;
  /** How that part reads in the older version. Empty when it was not set. */
  before: string[];
  /** How it reads in the newer one. Empty when it is not set. */
  after: string[];
}

interface Section {
  field: string;
  /** The part of the document this section is about, compared on meaning. */
  pick: (policy: Policy) => unknown;
  /** The same part as the lines shown on screen. */
  read: (policy: Policy) => string[];
}

const text = (value: string | undefined | null): string[] =>
  value?.trim() ? [value.trim()] : [];

const SECTIONS: Section[] = [
  { field: 'Name', pick: (p) => p.name, read: (p) => text(p.name) },
  { field: 'Display name', pick: (p) => p.displayName, read: (p) => text(p.displayName) },
  { field: 'Description', pick: (p) => p.description, read: (p) => text(p.description) },
  { field: 'Type', pick: (p) => p.policyType, read: (p) => text(p.policyType) },
  {
    field: 'Scope',
    pick: (p) => [p.scopeLevel, p.scopeLevel === 'ORG' ? null : p.scopeFqn],
    read: (p) =>
      p.scopeLevel === 'ORG'
        ? ['The whole organisation']
        : [`${p.scopeLevel} ${p.scopeFqn || '(not set)'}`],
  },
  {
    field: 'Assets',
    pick: (p) => p.selector,
    read: (p) => [describeSelector(p.selector)],
  },
  {
    field: 'Who',
    pick: (p) => p.subject,
    read: (p) => [describeSubject(p.subject)],
  },
  {
    field: 'Effect',
    pick: (p) => (p.policyType === 'SUBSCRIPTION' ? (p.effect ?? 'ALLOW') : null),
    read: (p) => (p.policyType === 'SUBSCRIPTION' ? [p.effect ?? 'ALLOW'] : []),
  },
  {
    field: 'Row filters',
    pick: (p) => p.data?.rowFilters,
    read: (p) => (p.data?.rowFilters ?? []).map(describeRowFilter),
  },
  {
    field: 'Column rules',
    pick: (p) => p.data?.columnRules,
    read: (p) => (p.data?.columnRules ?? []).map(describeColumnRule),
  },
  {
    field: 'Exemptions',
    pick: (p) => p.exemptions,
    read: (p) =>
      (p.exemptions ?? []).map(
        (e) => `${e.principal} until ${e.expiresAt} — ${e.reason}`
      ),
  },
  {
    field: 'In force',
    pick: (p) => [p.validFrom, p.validUntil],
    read: (p) =>
      p.validFrom || p.validUntil
        ? [`from ${p.validFrom ?? 'now'} until ${p.validUntil ?? 'withdrawn'}`]
        : [],
  },
  {
    field: 'Lower layers may relax it',
    pick: (p) => Boolean(p.allowLocalOverride),
    read: (p) => [p.allowLocalOverride ? 'Yes' : 'No'],
  },
  {
    field: 'Needs approval',
    pick: (p) => [Boolean(p.requiresApproval), p.approvers],
    read: (p) =>
      p.requiresApproval
        ? [
            p.approvers?.length
              ? `Yes, by ${p.approvers
                  .map((a) => a.displayName || a.name || a.fullyQualifiedName || a.id)
                  .join(', ')}`
              : 'Yes',
          ]
        : ['No'],
  },
];

/**
 * A value with everything that does not change its meaning taken out: keys
 * sorted, and null, undefined, false and empty lists dropped. Every boolean in
 * the policy schema defaults to false, so an explicit false and an absent one
 * are the same instruction.
 */
export function canonical(value: unknown): unknown {
  if (Array.isArray(value)) {
    const items = value.map(canonical).filter((v) => v !== undefined);
    return items.length ? items : undefined;
  }
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const key of Object.keys(value).sort()) {
      const inner = canonical((value as Record<string, unknown>)[key]);
      if (inner !== undefined) out[key] = inner;
    }
    return out;
  }
  if (value === null || value === undefined || value === false) return undefined;
  if (typeof value === 'string' && !value.trim()) return undefined;
  return value;
}

function same(a: unknown, b: unknown): boolean {
  return JSON.stringify(canonical(a)) === JSON.stringify(canonical(b));
}

/**
 * Every part of the policy that differs, in the order the page lists them.
 * Empty when the two versions say the same thing.
 */
export function diffPolicies(before: Policy, after: Policy): PolicyFieldChange[] {
  const changes: PolicyFieldChange[] = [];
  for (const section of SECTIONS) {
    const was = section.pick(before);
    const now = section.pick(after);
    if (same(was, now)) continue;

    let beforeLines = section.read(before);
    let afterLines = section.read(after);
    // The sentences leave some fields unsaid (whether Suggested tags count, a
    // masking regex), so two different rules can read alike. Showing a change
    // whose sides look identical would say nothing changed while something
    // did; the raw value is ugly, but it is the difference.
    if (JSON.stringify(beforeLines) === JSON.stringify(afterLines)) {
      beforeLines = [JSON.stringify(canonical(was) ?? null)];
      afterLines = [JSON.stringify(canonical(now) ?? null)];
    }
    changes.push({ field: section.field, before: beforeLines, after: afterLines });
  }
  return changes;
}
