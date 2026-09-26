import type { ScopeMatch, ScopeRule, TableScope } from '../../api/sources';

/**
 * The table scope, read and written the way the server reads it.
 *
 * `ruleMatches` and `scopeIncludes` repeat `TableScope.Rule.matches` and
 * `TableScope.includes` on the server so the form can say what a rule does as
 * it is typed. They are a preview, never the decision: the import applies the
 * server's copy, and "Check against the database" asks it directly.
 */

/** Mirrors the server's limits, so the form refuses before the request does. */
export const MAX_RULES = 50;
export const MAX_VALUE = 256;

export const MATCHES: { value: ScopeMatch; label: string; phrase: string }[] = [
  { value: 'STARTS_WITH', label: 'Starts with', phrase: 'starts with' },
  { value: 'ENDS_WITH', label: 'Ends with', phrase: 'ends with' },
  { value: 'CONTAINS', label: 'Contains', phrase: 'contains' },
  { value: 'EQUALS', label: 'Is exactly', phrase: 'is' },
];

export function ruleMatches(rule: ScopeRule, schema: string, name: string): boolean {
  const value = rule.value.trim().toLowerCase();
  const subject = (value.includes('.') ? `${schema}.${name}` : name).toLowerCase();

  switch (rule.match) {
    case 'STARTS_WITH':
      return subject.startsWith(value);
    case 'ENDS_WITH':
      return subject.endsWith(value);
    case 'CONTAINS':
      return subject.includes(value);
    case 'EQUALS':
      return subject === value;
  }
}

export function scopeIncludes(scope: TableScope, schema: string, name: string): boolean {
  if (scope.mode === 'ONLY' && !scope.include.some((rule) => ruleMatches(rule, schema, name))) {
    return false;
  }
  return !scope.exclude.some((rule) => ruleMatches(rule, schema, name));
}

/** True when nothing is left out — the scope a source has until one is set. */
export function scansEverything(scope: TableScope | null | undefined): boolean {
  return !scope || (scope.mode === 'ALL' && scope.exclude.length === 0);
}

/** The rules that have an effect: include rules count only when naming tables. */
export function ruleCount(scope: TableScope): number {
  return (scope.mode === 'ONLY' ? scope.include.length : 0) + scope.exclude.length;
}

/**
 * Why a rule cannot be added, or null when it can.
 *
 * Checked in the order somebody would fix them: nothing typed, too long, a
 * repeat, a full list.
 */
export function ruleProblem(rules: ScopeRule[], candidate: ScopeRule): string | null {
  const value = candidate.value.trim();
  if (!value) return 'Type some text to compare table names with.';
  if (value.length > MAX_VALUE) return `A rule can be at most ${MAX_VALUE} characters long.`;
  // Tab and newline are the ones a paste brings in; the server refuses them all.
  if (/[\u0000-\u001f\u007f]/.test(value)) {
    return 'A rule cannot contain line breaks or control characters.';
  }
  if (
    rules.some(
      (rule) => rule.match === candidate.match && rule.value.toLowerCase() === value.toLowerCase()
    )
  ) {
    return 'That rule is already in the list.';
  }
  if (rules.length >= MAX_RULES) return `Keep the list to ${MAX_RULES} rules or fewer.`;
  return null;
}

/** The scope as it will be sent: trimmed, and with no include rules it would ignore. */
export function scopeToSend(scope: TableScope): TableScope {
  const trim = (rules: ScopeRule[]) => rules.map((rule) => ({ ...rule, value: rule.value.trim() }));
  return {
    mode: scope.mode,
    include: scope.mode === 'ONLY' ? trim(scope.include) : [],
    exclude: trim(scope.exclude),
  };
}

/** Null when the scope can be saved; otherwise the sentence the server would answer with. */
export function scopeProblem(scope: TableScope): string | null {
  if (scope.mode === 'ONLY' && scope.include.length === 0) {
    return 'Name at least one table to scan, or choose to scan all tables.';
  }
  return null;
}

/** One rule as a phrase: `name starts with “tmp_”`, or `schema.table …` for a dotted rule. */
export function describeRule(rule: ScopeRule): string {
  const phrase = MATCHES.find((entry) => entry.value === rule.match)?.phrase ?? rule.match;
  const subject = rule.value.includes('.') ? 'schema.table' : 'name';
  return `${subject} ${phrase} “${rule.value}”`;
}

function either(rules: ScopeRule[]): string {
  const phrases = rules.map(describeRule);
  if (phrases.length <= 1) return phrases.join('');
  return `${phrases.slice(0, -1).join(', ')} or ${phrases[phrases.length - 1]}`;
}

/** The whole scope as one sentence, for the preview line and a source's card. */
export function describeScope(scope: TableScope | null | undefined): string {
  if (!scope || scansEverything(scope)) {
    return 'All tables and views this login can read are in scope.';
  }
  if (scope.mode === 'ALL') {
    return `All tables and views this login can read, except where the ${either(scope.exclude)}.`;
  }
  if (scope.include.length === 0) {
    return 'No table is named yet, so nothing would be read.';
  }
  const only = `Only tables and views whose ${either(scope.include)}`;
  return scope.exclude.length === 0
    ? `${only}.`
    : `${only}, less any whose ${either(scope.exclude)}.`;
}

/** Short enough for a badge. */
export function scopeBadge(scope: TableScope | null | undefined): string {
  if (!scope || scansEverything(scope)) return 'Scanning all';
  const count = ruleCount(scope);
  return `${count} ${count === 1 ? 'rule' : 'rules'}`;
}
