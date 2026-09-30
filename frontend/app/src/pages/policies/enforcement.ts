import type { Policy } from '../../generated/entity/policy/policy';

/**
 * What each enforcement mode can actually carry out (FR-6.0b).
 *
 * The policy document is the same in all three modes; only where it is enforced
 * changes. But the three places are not equally capable, and the failure this
 * matrix exists to prevent is the quiet one: an apply that succeeds while a
 * masking rule the author wrote is silently dropped, leaving a column readable
 * that the policy page says is masked.
 *
 * So the builder computes this as the policy is typed, not at apply time. A
 * gap found while writing costs a change of mind; the same gap found after
 * apply costs an incident.
 */

export type EnforcementMode = 'PROXY' | 'SECURE_VIEW' | 'NATIVE_CONFIG';
/**
 * An engine id as the server spells it.
 *
 * Deliberately not a union of the two engines shipped today. The notes below
 * are prose about a specific product, so a note genuinely can be missing for an
 * engine — but a missing note has to read as a missing note, not as the note
 * for whichever product happened to be on the other side of a ternary.
 */
export type Engine = string;

export const MODES: {
  mode: EnforcementMode;
  title: string;
  requirement: string;
  summary: string;
}[] = [
  {
    mode: 'PROXY',
    title: 'Query API',
    requirement: 'Direct database access must be firewalled off',
    summary:
      'We sit in the middle: the SQL is rewritten before it reaches the source, the way Denodo does it. Nothing is changed on the source, and anyone who can still connect to it directly bypasses this entirely.',
  },
  {
    mode: 'SECURE_VIEW',
    title: 'Secure view',
    requirement: 'A database principal per person, and SELECT revoked on the base table',
    summary:
      'We create a view beside the table and an entitlement table it joins against. Readers are pointed at the view; the base table is revoked. BI tools connect normally.',
  },
  {
    mode: 'NATIVE_CONFIG',
    title: 'Native source config',
    requirement: 'Permission to ALTER production objects, and a database principal per person',
    summary:
      'We push the rules into the engine itself — row security policies, masking, column grants. Readers keep querying the original table name and change nothing.',
  },
];

export type Support = 'full' | 'partial' | 'none';

export interface CapabilityNote {
  mode: EnforcementMode;
  support: Support;
  /** Empty when the mode carries the whole policy. */
  gaps: string[];
}

interface Feature {
  /** Whether the policy uses this feature at all. */
  used: (policy: Policy) => boolean;
  /** Undefined means the mode carries it. */
  gap: Partial<Record<EnforcementMode, (engine: Engine) => string | undefined>>;
}

/**
 * Why native column masking is awkward, per engine.
 *
 * Keyed rather than branched. This used to be a ternary between the two
 * engines we ship, which meant a third engine would have been handed the SQL
 * Server sentence — a specific, confident, wrong claim about a product nobody
 * had checked. A lookup can be missing an entry; a ternary cannot.
 */
const NATIVE_MASK_NOTES: Record<string, string> = {
  POSTGRES:
    'PostgreSQL has no column masking in core. Without the anon extension the only native option is hiding the column entirely.',
  SQLSERVER:
    'SQL Server dynamic data masking is on or off per column, so the mask is the same for everyone who is not granted UNMASK. Granting UNMASK per column needs SQL Server 2022 or later.',
};

/**
 * What we can say about an engine whose native masking nobody has written up.
 *
 * Reported as a gap rather than passed over. Treating silence as "this works"
 * is the exact failure this matrix exists to prevent, and an engine we have no
 * notes for is the engine we are least sure about.
 */
const UNKNOWN_ENGINE_MASK_NOTE =
  'Native column masking on this engine has not been verified. Until it has, use a secure view or the query API for masking rather than assuming the source will apply it.';

/**
 * A mode that cannot be used on an engine at all, whatever the policy says.
 *
 * Prose about a product again, so keyed the same way. The server is what
 * refuses the mode; this is so the builder says it while the policy is being
 * written, in the same place it says everything else about a mode.
 */
const MODE_UNAVAILABLE: Record<string, Partial<Record<EnforcementMode, string>>> = {
  MYSQL: {
    SECURE_VIEW:
      'Secure views are not available on MySQL sources yet. Use the query API to enforce this policy.',
    NATIVE_CONFIG:
      'MySQL has no row-level security and no column masking of its own, so there is nothing to push these rules into. Use the query API to enforce this policy.',
  },
};

const FEATURES: Feature[] = [
  {
    used: (policy) => (policy.data?.rowFilters?.length ?? 0) > 0,
    // Row filtering is the one thing all three do well, which is why it is
    // usually the part people start with.
    gap: {},
  },
  {
    used: (policy) =>
      (policy.data?.columnRules ?? []).some((rule) => rule.action === 'MASK'),
    gap: {
      NATIVE_CONFIG: (engine) => NATIVE_MASK_NOTES[engine] ?? UNKNOWN_ENGINE_MASK_NOTE,
    },
  },
  {
    used: (policy) =>
      (policy.data?.columnRules ?? []).some((rule) => Boolean(rule.condition)),
    gap: {
      NATIVE_CONFIG: () =>
        'Cell masking — masking only on some rows — is not something native column masking expresses on any engine we support. This rule would not be applied.',
    },
  },
  {
    used: (policy) =>
      (policy.data?.columnRules ?? []).some((rule) => rule.action === 'HIDE'),
    gap: {},
  },
  {
    used: (policy) => Boolean(policy.subject?.time?.windows?.length),
    gap: {
      NATIVE_CONFIG: () =>
        'A time window has to be re-evaluated on every query. Natively it becomes a predicate over the clock of the source, so the source timezone has to match the one in the policy.',
      SECURE_VIEW: () =>
        'Enforced inside the view, against the clock of the source rather than ours.',
    },
  },
  {
    used: (policy) => Boolean(policy.subject?.context?.ipCidr?.length),
    gap: {
      NATIVE_CONFIG: () =>
        'The source sees the address of whoever connected, which is not something we can vouch for.',
      SECURE_VIEW: () =>
        'The source sees the address of whoever connected, which is not something we can vouch for.',
    },
  },
];

/** How well each mode would carry this policy, on this engine. */
export function capabilities(policy: Policy, engine: Engine): CapabilityNote[] {
  return MODES.map(({ mode }) => {
    const unavailable = MODE_UNAVAILABLE[engine]?.[mode];
    if (unavailable) {
      return { mode, support: 'none', gaps: [unavailable] };
    }
    const gaps: string[] = [];
    for (const feature of FEATURES) {
      if (!feature.used(policy)) continue;
      const gap = feature.gap[mode]?.(engine);
      if (gap) gaps.push(gap);
    }
    return {
      mode,
      support: gaps.length === 0 ? 'full' : gaps.length > 1 ? 'none' : 'partial',
      gaps,
    };
  });
}
