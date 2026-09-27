import type { GrantAccess } from '../../api/access';
import type { AppliedPolicy } from '../../api/policies';
import type { Policy } from '../../generated/entity/policy/policy';
import {
  describeColumnRule,
  describeRowFilter,
  describeSubject,
} from '../policies/policyLanguage';

/**
 * One table's access decision, drawn as the checks the engine runs in order.
 *
 * A policy's own chart answers "what does this policy do". The question on a
 * table is different — "what happens to somebody who asks for this" — and its
 * answer is several policies and every grant, taken in the engine's order:
 *
 * <ol>
 *   <li>any matched DENY ends it, at whatever layer it sits;
 *   <li>then every layer that holds an ALLOW is a gate the caller has to pass,
 *       outermost first, because layers intersect rather than replace;
 *   <li>a direct grant opens the table on its own only when nothing gates it,
 *       and otherwise only past a gate whose policies all consented to being
 *       relaxed;
 *   <li>and once in, every data policy whose subject matches narrows what is
 *       read: row filters ANDed, the strictest mask on a column winning.
 * </ol>
 *
 * The order mirrors {@code PolicyEngine} on the server. This page never
 * decides anything itself; the verdicts in "Who can read this now" are the
 * engine's. The chart is how a steward sees why those verdicts are what they
 * are, and where to change them.
 */

export type DecisionTone = 'check' | 'deny' | 'allow' | 'restrict' | 'empty';

export interface DecisionItem {
  text: string;
  /** Smaller, secondary line under the item. */
  detail?: string;
}

export interface DecisionStep {
  id: string;
  /** A check has a yes and a no; an outcome just happens. */
  kind: 'check' | 'outcome';
  /** Where this came from — "Organisation", "Schema", "Direct grants". */
  layer: string;
  title: string;
  items: DecisionItem[];
  tone: DecisionTone;
  /** Where "yes" leads, when it does not simply carry on down. */
  yes?: string;
  yesTone?: 'deny' | 'allow' | 'restrict';
  /** Where "no" leads, when it does not simply carry on down. */
  no?: string;
  noTone?: 'deny' | 'allow' | 'skip';
  /** A policy to open, when the step is one policy. */
  policyId?: string;
  /** Whom the step is about, in words, for a line on the diagram. */
  who?: string;
  /** A gate a more specific layer or a direct grant can get somebody past. */
  passable?: boolean;
}

export interface DecisionFlow {
  steps: DecisionStep[];
  /** Where somebody who passed every check ends up. */
  exit: string;
  exitTone: 'allow' | 'restrict' | 'deny';
}

const LEVEL_ORDER = ['ORG', 'DOMAIN', 'SERVICE', 'DATABASE', 'SCHEMA', 'TABLE', 'COLUMN'];

const LEVEL_LABEL: Record<string, string> = {
  ORG: 'Organisation',
  DOMAIN: 'Domain',
  SERVICE: 'Service',
  DATABASE: 'Database',
  SCHEMA: 'Schema',
  TABLE: 'Table',
  COLUMN: 'Column',
};

/** Sub-domains sort by depth inside DOMAIN, the way the engine layers them. */
function layerKey(policy: Policy): string {
  const level = policy.scopeLevel ?? 'ORG';
  if (level === 'DOMAIN') {
    const depth = (policy.scopeFqn ?? '').split('.').filter(Boolean).length;
    return `DOMAIN:${depth}`;
  }
  return level;
}

function layerRank(key: string): number {
  const [level, depth] = key.split(':');
  const index = LEVEL_ORDER.indexOf(level);
  return (index < 0 ? LEVEL_ORDER.length : index) * 100 + Number(depth ?? 0);
}

function layerLabel(policy: Policy): string {
  const label = LEVEL_LABEL[policy.scopeLevel ?? 'ORG'] ?? String(policy.scopeLevel);
  if (policy.scopeLevel === 'ORG' || !policy.scopeFqn) {
    return label;
  }
  return `${label} · ${policy.scopeFqn}`;
}

function nameOf(policy: Policy): string {
  return policy.displayName?.trim() || policy.name;
}

function grantWho(grant: GrantAccess): string {
  const name = grant.displayName?.trim() || grant.principal;
  return grant.principalType === 'group' ? `anyone in the group ${name}` : name;
}

/** One line per data rule, so a reader can see each restriction on its own. */
function restrictions(policy: Policy, columns: string[]): DecisionItem[] {
  const items: DecisionItem[] = [];
  for (const filter of policy.data?.rowFilters ?? []) {
    items.push({ text: `Rows: ${describeRowFilter(filter)}` });
  }
  for (const rule of policy.data?.columnRules ?? []) {
    items.push({ text: `Columns: ${describeColumnRule(rule)}` });
  }
  if (columns.length) {
    items.push({ text: `On this table that reaches ${columns.join(', ')}` });
  }
  return items;
}

function columnsReached(applied: AppliedPolicy): string[] {
  return applied.columns
    .filter((column) => column.kind === 'COLUMN')
    .map((column) => column.name ?? column.fqn.split('.').pop() ?? column.fqn);
}

export function accessFlow(applied: AppliedPolicy[], grants: GrantAccess[]): DecisionFlow {
  const live = grants.filter((grant) => grant.live);
  const subscriptions = applied.filter((a) => a.policy.document.policyType === 'SUBSCRIPTION');
  const data = applied.filter((a) => a.policy.document.policyType === 'DATA');

  const byLayer = (a: AppliedPolicy, b: AppliedPolicy) =>
    layerRank(layerKey(a.policy.document)) - layerRank(layerKey(b.policy.document));

  const denies = subscriptions.filter((a) => a.policy.document.effect === 'DENY').sort(byLayer);
  const allows = subscriptions.filter((a) => a.policy.document.effect !== 'DENY').sort(byLayer);
  const steps: DecisionStep[] = [];

  // 1. A matched deny ends it, wherever it sits.
  for (const { policy } of denies) {
    const document = policy.document;
    steps.push({
      id: `deny-${policy.id}`,
      kind: 'check',
      layer: layerLabel(document),
      title: `Is the caller ${describeSubject(document.subject)}?`,
      items: [{ text: nameOf(document), detail: 'Deny policy' }],
      tone: 'deny',
      yes: document.allowLocalOverride
        ? 'Denied — unless a more specific layer allows them (this policy lets it)'
        : 'Denied. A deny beats every allow and every grant.',
      yesTone: 'deny',
      policyId: policy.id,
      who: describeSubject(document.subject),
    });
  }

  // 2. Each layer holding an allow is a gate.
  const gates = new Map<string, AppliedPolicy[]>();
  for (const entry of allows) {
    const key = layerKey(entry.policy.document);
    gates.set(key, [...(gates.get(key) ?? []), entry]);
  }
  const grantNames = live.map(grantWho);

  for (const [key, members] of gates) {
    const relaxable = members.every((m) => Boolean(m.policy.document.allowLocalOverride));
    steps.push({
      id: `gate-${key}`,
      kind: 'check',
      layer: layerLabel(members[0].policy.document),
      title:
        members.length === 1
          ? `Is the caller ${describeSubject(members[0].policy.document.subject)}?`
          : 'Does the caller match any one of these?',
      items: members.map(({ policy }) => ({
        text: members.length === 1 ? nameOf(policy.document) : describeSubject(policy.document.subject),
        detail: members.length === 1 ? 'Allow policy' : nameOf(policy.document),
      })),
      tone: 'check',
      no: relaxable
        ? grantNames.length
          ? `Carry on only if a more specific layer or a direct grant admits them (${grantNames.join(', ')})`
          : 'Carry on only if a more specific layer admits them — otherwise denied'
        : 'Denied. Every layer must allow, and this one does not let a grant past it.',
      noTone: 'deny',
      policyId: members.length === 1 ? members[0].policy.id : undefined,
      who: members.map(({ policy }) => describeSubject(policy.document.subject)).join(' or '),
      passable: relaxable,
    });
  }

  // 3. With no gate, a grant is the whole answer.
  if (gates.size === 0) {
    steps.push({
      id: 'grants',
      kind: 'check',
      layer: 'Direct grants',
      title: live.length ? 'Is the caller named in a direct grant?' : 'Nothing opens this table',
      items: live.map((grant) => ({
        text: grantWho(grant),
        detail: grant.validUntil ? `until ${grant.validUntil.slice(0, 10)}` : 'no expiry',
      })),
      tone: live.length ? 'check' : 'empty',
      no: 'Denied by default. No policy and no grant lets them in.',
      noTone: 'deny',
    });
  }

  // 4. Once in, data policies narrow what is read.
  let restricted = false;
  for (const entry of data.sort(byLayer)) {
    const document = entry.policy.document;
    const items = restrictions(document, columnsReached(entry));
    restricted ||= items.length > 0;
    steps.push({
      id: `data-${entry.policy.id}`,
      kind: 'check',
      layer: layerLabel(document),
      title: document.subject
        ? `Is the caller ${describeSubject(document.subject)}?`
        : `${nameOf(document)} applies to everyone`,
      items: [{ text: nameOf(document), detail: 'Data policy' }, ...items],
      tone: 'restrict',
      yes: items.length ? 'Apply these restrictions, then carry on' : 'Nothing to apply',
      yesTone: 'restrict',
      no: document.subject ? 'This policy is skipped for them' : undefined,
      noTone: 'skip',
      policyId: entry.policy.id,
      who: document.subject ? describeSubject(document.subject) : 'everyone',
    });
  }

  const nobodyIn = gates.size === 0 && live.length === 0;
  const exitTone = nobodyIn ? 'deny' : restricted ? 'restrict' : 'allow';
  const exit = nobodyIn
    ? 'Nobody reaches the table'
    : restricted
      ? 'They read the table, with every restriction that applied — row filters ANDed, the strictest mask on each column'
      : 'They read the table in full';

  return { steps, exit, exitTone };
}

