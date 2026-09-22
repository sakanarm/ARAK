import { apiClient } from './client';
import { ENFORCED_ENVIRONMENT } from './policies';
import type {
  DecisionReason,
  PolicyDecision,
  ResolvedColumnMask,
  ResolvedRowPredicate,
  Unenforceable,
} from '../generated/api/policyDecision';

/**
 * "What would this person see?" — the simulator (FR-5.2).
 *
 * The endpoint runs the same engine the query proxy and the compilers run, so
 * what this screen shows is the decision itself rather than a rendering of
 * what the decision is expected to be. That distinction is the whole value: a
 * simulator that approximates enforcement is worse than none, because people
 * trust it and act on it.
 */

export interface DecisionAsk {
  /** Whose access is being asked about; empty means the caller's own. */
  principal: string;
  assetFqn: string;
  /**
   * ISO-8601 instant. Supplying it is how a policy with an 08:00–18:00 window
   * is tested at 20:00 without waiting until evening.
   */
  at?: string | null;
  /** Tests `context.ipCidr` without moving the person to another network. */
  ip?: string | null;
  purpose?: string | null;
  environment?: string;
}

export async function simulate(ask: DecisionAsk): Promise<PolicyDecision> {
  const { data } = await apiClient.post<PolicyDecision>('/v1/decisions', {
    ...ask,
    environment: ask.environment ?? ENFORCED_ENVIRONMENT,
  });
  return data;
}

export type {
  DecisionReason,
  PolicyDecision,
  ResolvedColumnMask,
  ResolvedRowPredicate,
  Unenforceable,
};
