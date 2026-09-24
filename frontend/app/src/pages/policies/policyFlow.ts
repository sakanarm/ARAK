import type { Policy } from '../../generated/entity/policy/policy';
import {
  describeColumnRule,
  describeCondition,
  describeMasking,
  describePrincipal,
  describeRowFilter,
  describeSelector,
} from './policyLanguage';

/**
 * Reads a policy back as the path a request takes through it.
 *
 * {@link ./policyLanguage} already turns a policy into prose, and prose is the
 * right answer for somebody reading the whole thing carefully. It is the wrong
 * answer for the question people actually arrive with, which is "does this do
 * what I meant" — and that question is about order. A policy is a sequence of
 * gates: the asset has to match, then the caller has to match, and only then
 * does anything happen. A paragraph states all three as equal clauses; a chart
 * shows that failing the first one means the rest never runs.
 *
 * <h2>Gates come before outcomes, and the asset comes before the person</h2>
 *
 * The order here is the order the engine uses, not a nicer one. Binding is
 * resolved against the asset first, so a policy whose selector matches nothing
 * is inert no matter how carefully its subject was written — which is exactly
 * the mistake a chart should make visible on the first box rather than the
 * last.
 *
 * <h2>Every gate says where the "no" branch goes</h2>
 *
 * A gate without a stated alternative reads as a requirement, and a reader
 * fills in the missing branch with whatever they were already assuming. Both
 * assumptions are made here — "this policy does not apply", which is not the
 * same as "access is denied", since another policy may still be the one that
 * decides.
 *
 * <h2>Why this is a model and not a component</h2>
 *
 * The chart and the form have to agree, forever, without anybody keeping them
 * in step by hand. Both are built from the same policy document, and the labels
 * inside the boxes come from the same `describe*` functions the sentence uses.
 * A chart with its own idea of what `contains` means would be worse than no
 * chart: a second, prettier, wrong explanation of a document somebody is about
 * to publish.
 */

/** Which part of the policy authored a step — shown as the box's eyebrow. */
export type FlowLane = 'who' | 'which' | 'what';

/**
 * How a step should read at a glance.
 *
 * `open` is deliberately separate from `set`. A gate that lets everyone through
 * is fully authored and completely permissive, and colouring it the same as a
 * narrow one is how a policy gets published wider than it was meant.
 */
export type FlowTone = 'set' | 'empty' | 'open' | 'deny';

export interface FlowItem {
  text: string;
  /** Set when this line is the reason the step is not as narrow as it looks. */
  caution?: boolean;
}

export interface FlowStep {
  id: string;
  /** A gate can be failed and drawn with a branch; an outcome always happens. */
  kind: 'gate' | 'outcome';
  lane: FlowLane;
  title: string;
  items: FlowItem[];
  tone: FlowTone;
  /** Shown when there is nothing to list — says what the emptiness means. */
  note?: string;
  /** Where the "no" branch leads. Gates only. */
  otherwise?: string;
  /** The numbered step in the form that writes this, for click-to-edit. */
  step?: number;
}

export interface FlowModel {
  kind: string;
  effect: string;
  /** Where this policy sits in the stack, shown above the chart. */
  scope: string;
  /** The event the chart starts from. */
  entry: string;
  steps: FlowStep[];
  /** Where the chart ends when every gate was passed. */
  exit: string;
  /** What a lower layer or a direct grant can do about this policy. */
  gate: string;
  gateOpen: boolean;
}

export const LANE_TITLES: Record<FlowLane, string> = {
  who: 'Who',
  which: 'Which assets',
  what: 'What happens',
};

function scopeLine(policy: Policy): string {
  if (policy.scopeLevel === 'ORG') {
    return 'Across the whole organisation';
  }
  const where = policy.scopeFqn?.trim();
  return where
    ? `Within ${where}`
    : `Within this ${String(policy.scopeLevel ?? '').toLowerCase() || 'scope'}`;
}

/** The asset gate: what this policy is bound to. */
function whichGate(policy: Policy): FlowStep {
  const selector = policy.selector;
  const items: FlowItem[] = [];

  if (selector?.condition) {
    items.push({ text: describeCondition(selector.condition) });
  }
  for (const child of selector?.and ?? []) {
    items.push({ text: `and ${describeSelector(child)}` });
  }
  if (selector?.or?.length) {
    items.push({
      text: `any of: ${selector.or.map((child) => describeSelector(child)).join(' · ')}`,
    });
  }
  if (selector?.not) {
    items.push({ text: `except ${describeSelector(selector.not)}` });
  }

  return {
    id: 'gate-which',
    kind: 'gate',
    lane: 'which',
    title: 'Is the asset one of these?',
    items,
    tone: items.length ? 'set' : 'empty',
    note: items.length
      ? undefined
      : 'Nothing yet. An empty selector binds to no assets at all, so every request leaves here and the policy never has an effect.',
    otherwise: 'This policy is not bound to it, and nothing below happens.',
    step: 3,
  };
}

/**
 * The caller gate.
 *
 * Identity, attributes, expression, time and connection each become their own
 * line because they are ANDed: a reader who sees them run together as one
 * phrase tends to read the list as alternatives, which is the wider reading and
 * therefore the dangerous one.
 */
function whoGate(policy: Policy): FlowStep {
  const subject = policy.subject;
  const items: FlowItem[] = [];

  for (const match of subject?.principals ?? []) {
    items.push({ text: describePrincipal(match) });
  }
  for (const match of subject?.requiredPrincipals ?? []) {
    items.push({ text: `and also ${describePrincipal(match)}` });
  }
  for (const attribute of subject?.attributes ?? []) {
    const value = attribute.values?.length
      ? attribute.values.join(', ')
      : String(attribute.value ?? '');
    items.push({ text: `${attribute.key} ${attribute.operator} ${value || '…'}` });
  }
  if (subject?.expression) {
    items.push({ text: subject.expression });
  }
  for (const window of subject?.time?.windows ?? []) {
    const days = window.days?.length ? `${window.days.join(', ')} ` : '';
    items.push({
      text: `${days}${window.from}–${window.to} ${window.timezone ?? ''}`.trim(),
    });
  }
  if (subject?.context?.ipCidr?.length) {
    items.push({ text: `connecting from ${subject.context.ipCidr.join(', ')}` });
  }
  if (subject?.context?.purpose?.length) {
    items.push({ text: `for ${subject.context.purpose.join(' or ')}` });
  }

  const open = items.length === 0;
  return {
    id: 'gate-who',
    kind: 'gate',
    lane: 'who',
    title: open ? 'Everyone gets through here' : 'Is the caller all of these?',
    items,
    // No subject at all means everyone, which is a decision rather than a gap —
    // and on a DENY policy it is the widest thing this page can say.
    tone: open ? 'open' : 'set',
    note: open
      ? policy.effect === 'DENY'
        ? 'Nothing narrows this, so every caller who reaches it is denied.'
        : 'Nothing narrows this, so every caller who reaches it is let through.'
      : undefined,
    otherwise: open
      ? undefined
      : 'This policy has nothing to say about them. Another policy may still decide.',
    step: 4,
  };
}

/**
 * The outcomes, in the order they are applied.
 *
 * A subscription policy has exactly one and a data policy has as many as it has
 * rules, so this is the part that grows. Each rule is its own box because each
 * is enforced independently — row filters are ANDed together and column rules
 * resolve strictest-wins, and one merged box would hide both of those from the
 * person checking the policy.
 */
function outcomes(policy: Policy): FlowStep[] {
  if (policy.policyType === 'SUBSCRIPTION') {
    const denied = policy.effect === 'DENY';
    return [
      {
        id: 'outcome-subscription',
        kind: 'outcome',
        lane: 'what',
        title: denied ? 'Access is denied' : 'Access is allowed',
        items: [
          {
            text: denied
              ? 'They do not reach these assets, whatever else allows them.'
              : 'They reach these assets, subject to every layer above this one.',
            caution: denied,
          },
        ],
        tone: denied ? 'deny' : 'set',
        step: 4,
      },
    ];
  }

  const steps: FlowStep[] = [];

  (policy.data?.rowFilters ?? []).forEach((filter, index) => {
    steps.push({
      id: `outcome-row-${index}`,
      kind: 'outcome',
      lane: 'what',
      title: 'Keep only the rows where',
      items: [
        { text: describeRowFilter(filter), caution: filter.kind === 'ALWAYS_FALSE' },
      ],
      tone: 'set',
      step: 5,
    });
  });

  (policy.data?.columnRules ?? []).forEach((rule, index) => {
    const columns = describeSelector(rule.columns);
    const blank = columns === 'nothing';
    const outcome =
      rule.action === 'HIDE'
        ? 'removed from the results entirely'
        : rule.action === 'ALLOW'
          ? 'left readable'
          : describeMasking(rule.masking);
    const items: FlowItem[] = [
      { text: blank ? 'no columns selected yet' : columns },
      { text: outcome, caution: rule.action === 'ALLOW' },
    ];
    if (rule.condition) {
      items.push({ text: `only where ${rule.condition}` });
    }
    steps.push({
      id: `outcome-column-${index}`,
      kind: 'outcome',
      lane: 'what',
      title: 'Then these columns are',
      items,
      tone: blank ? 'empty' : 'set',
      // Kept so a reader can check the chart against the sentence without
      // switching modes; the sentence is the fuller of the two.
      note: describeColumnRule(rule),
      step: 5,
    });
  });

  if (!steps.length) {
    steps.push({
      id: 'outcome-empty',
      kind: 'outcome',
      lane: 'what',
      title: 'Nothing is restricted',
      items: [],
      tone: 'empty',
      note: 'No row filter and no column rule. The policy binds assets but changes nothing about what is read from them.',
      step: 5,
    });
  }
  return steps;
}

function exitLine(policy: Policy): string {
  if (policy.policyType === 'SUBSCRIPTION') {
    return policy.effect === 'DENY'
      ? 'The request is refused and nothing is read.'
      : 'The request continues to whatever the data policies leave of it.';
  }
  return 'What is left is what they see.';
}

export function buildFlow(policy: Policy): FlowModel {
  return {
    kind: policy.policyType === 'SUBSCRIPTION' ? 'Subscription' : 'Data',
    effect: policy.effect ?? 'ALLOW',
    scope: scopeLine(policy),
    entry: 'Someone asks to read an asset',
    steps: [whichGate(policy), whoGate(policy), ...outcomes(policy)],
    exit: exitLine(policy),
    gate: policy.allowLocalOverride
      ? 'A grant, or a policy on one table, may let somebody past this. Each time that happens it is written to the audit log.'
      : 'Nothing below this layer can relax it. A grant on a single table will read as in force and still admit nobody.',
    gateOpen: Boolean(policy.allowLocalOverride),
  };
}
