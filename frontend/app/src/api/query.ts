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
}

/** A restriction the dialect could not express; it was tightened, not dropped. */
export interface Unenforceable {
  policyId: string | null;
  detail: string;
  suggestedMode: string | null;
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
  unenforceable: Unenforceable[];
}

/** What the server will return at most, whatever this screen asks for. */
export const MAX_ROWS = 5_000;
export const DEFAULT_ROWS = 200;

export async function runQuery(ask: QueryAsk): Promise<QueryResult> {
  const { data } = await apiClient.post<QueryResult>('/v1/query', ask);
  return data;
}
