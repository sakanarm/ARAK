import axios from 'axios';
import { apiClient } from './client';

/**
 * Enforcement mode 5.2, from the browser (FR-6.3, 5.2a).
 *
 * The rows that come back have already been through the policy — the row filter
 * is in the statement and the masks are in its projection — so there is no
 * second, client-side filtering step here and there must never be one. The
 * `rewrittenSql` field comes back with them so a data owner can read what
 * actually ran instead of taking the platform's word for it (FR-5.4).
 */

export interface QueryAsk {
  sourceId: string;
  sql: string;
  /** Run as somebody else, to see a table the way they see it (FR-5.2). */
  asPrincipal?: string | null;
  maxRows?: number;
  purpose?: string | null;
  /**
   * Read the source even when the same enforced statement was answered
   * moments ago (FR-6.3 result cache).
   */
  fresh?: boolean;
}

/** A restriction the dialect could not express; it was tightened, not dropped. */
export interface Unenforceable {
  policyId: string | null;
  detail: string;
  suggestedMode: string | null;
}

/**
 * The same decision the rewritten statement carries, in words (FR-5.4).
 *
 * Rows that quietly went missing and a column that quietly reads `***` look
 * exactly like a broken pipeline until something names the policy responsible.
 */
export interface Explanation {
  asset: string;
  /** Column name to what was done to it. */
  maskedColumns: Record<string, string>;
  hiddenColumns: string[];
  /** ANDed together. */
  rowFilters: string[];
  policies: string[];
}

export interface QueryResult {
  columns: string[];
  columnTypes: string[];
  rows: unknown[][];
  truncated: boolean;
  millis: number;
  principal: string;
  assets: string[];
  rewrittenSql: string;
  explanations: Explanation[];
  unenforceable: Unenforceable[];
  /**
   * The rows are a read made moments ago for the same enforced statement,
   * not one made for this run. The policy was still applied to this run:
   * only the source was not asked again.
   */
  cached: boolean;
  /** When the source was read for these rows (ISO-8601). */
  readAt: string | null;
  /**
   * What the source's planner priced the statement at before it ran, in that
   * engine's own units (FR-6.3 cost guard). Null when it was not priced: the
   * guard is off for the engine, or the planner could not be asked.
   */
  estimatedCost?: number | null;
}

/** The service had no room for one more read; nothing was sent (FR-6.3). */
export interface Busy {
  message: string;
  retryAfterSeconds: number;
}

/**
 * The busy body of a failed query, when it is one.
 *
 * A 429 is told apart from a refusal because the advice is the opposite:
 * nothing about the statement or the person is wrong, and the same statement
 * a few seconds later will very likely run.
 */
export function busyOf(error: unknown): Busy | null {
  if (!axios.isAxiosError(error) || error.response?.status !== 429) {
    return null;
  }
  const body = error.response.data as Partial<Busy> | undefined;
  const header = Number.parseInt(String(error.response.headers?.['retry-after'] ?? ''), 10);
  const retry =
    typeof body?.retryAfterSeconds === 'number'
      ? body.retryAfterSeconds
      : Number.isFinite(header)
        ? header
        : 3;
  return {
    message:
      typeof body?.message === 'string'
        ? body.message
        : 'Too many queries are running right now. Try again in a few seconds.',
    retryAfterSeconds: Math.min(Math.max(retry, 1), 60),
  };
}

/** A planner estimate the way Job details prints it; null when there is none. */
export function formatCost(cost: number | null | undefined): string | null {
  if (cost === null || cost === undefined || !Number.isFinite(cost)) {
    return null;
  }
  return cost >= 100 ? Math.round(cost).toLocaleString('en-US') : cost.toFixed(2);
}

/** What the server will return at most, whatever this screen asks for. */
export const MAX_ROWS = 5_000;
export const DEFAULT_ROWS = 200;

export async function runQuery(ask: QueryAsk): Promise<QueryResult> {
  const { data } = await apiClient.post<QueryResult>('/v1/query', ask);
  return data;
}
