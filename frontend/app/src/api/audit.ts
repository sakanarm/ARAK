import { apiClient } from './client';

/**
 * The query log: every statement sent through the query proxy, what it became
 * and how it ended (FR-8.3).
 *
 * Each reader gets a different log, and the server draws it: an administrator,
 * policy author or auditor reads every row; a data owner reads their own and
 * the rows that touched a table they own; anyone else reads their own. On
 * somebody else's row an owner is given the tables that are theirs and a count
 * of the rest, and the statement only when every table in it is theirs --
 * `sqlHidden` says it was withheld, so the page can say so instead of drawing
 * an empty box. Nothing here carries the address a query came from.
 */

export type QueryOutcome = 'EXECUTED' | 'REJECTED' | 'FAILED';

/** Why a statement did not run, sorted from the proxy's own message. */
export type RefusalCategory =
  | 'POLICY_DENY'
  | 'UNGOVERNED'
  | 'CANNOT_ENFORCE'
  | 'ALL_COLUMNS_HIDDEN'
  | 'UNPARSEABLE'
  | 'NOT_READ_ONLY'
  | 'UNQUALIFIED'
  | 'UNSUPPORTED'
  | 'TOO_COSTLY'
  | 'SOURCE_UNAVAILABLE'
  | 'BUSY'
  | 'SOURCE_ERROR'
  | 'EMPTY'
  | 'OTHER';

export const CATEGORY_LABELS: Record<RefusalCategory, string> = {
  POLICY_DENY: 'Denied by policy',
  UNGOVERNED: 'Table not governed',
  CANNOT_ENFORCE: 'Policy cannot be enforced here',
  ALL_COLUMNS_HIDDEN: 'Every column hidden',
  UNPARSEABLE: 'Could not be parsed',
  NOT_READ_ONLY: 'Not a SELECT',
  UNQUALIFIED: 'Table name ambiguous',
  UNSUPPORTED: 'Not supported by the proxy',
  TOO_COSTLY: 'Too expensive to run',
  SOURCE_UNAVAILABLE: 'Source unavailable',
  BUSY: 'Too busy, not run',
  SOURCE_ERROR: 'Source returned an error',
  EMPTY: 'No SQL sent',
  OTHER: 'Other',
};

export interface QueryLogRow {
  id: number;
  occurredAt: string;
  /** Who it ran as. */
  principal: string;
  /** Who sent it, when that is not the principal it ran as. */
  runBy: string | null;
  sourceId: string | null;
  sourceName: string | null;
  outcome: QueryOutcome;
  /** Null for a statement that ran. */
  category: RefusalCategory | null;
  /** The proxy's own words; null where they would name a table the reader does not own. */
  rejectReason: string | null;
  originalSql: string | null;
  rewrittenSql: string | null;
  sqlHidden: boolean;
  rowCount: number | null;
  durationMs: number | null;
  /**
   * Answered from the result cache: the rows went back to the caller but the
   * source has no record of this read, which matters when reconciling this log
   * with the source's own.
   */
  fromCache: boolean;
  /**
   * A download of every row rather than a page on screen: the rows left the
   * platform as a file, so the row count is how many went into it.
   */
  exported?: boolean;
  /** The tables it touched that the reader oversees. */
  assets: string[];
  /** How many more it touched that the reader does not. */
  hiddenAssets: number;
  /** The reader ran it, or it was run as them. */
  own: boolean;
}

export interface QueryLogCounts {
  total: number;
  executed: number;
  rejected: number;
  failed: number;
}

/** Whose rows the reader was given. */
export type QueryLogScope = 'EVERYTHING' | 'OWNED' | 'OWN';

export interface QueryLogPage {
  since: string;
  until: string | null;
  scope: QueryLogScope;
  rows: QueryLogRow[];
  /** Pass as `before` for the next page; null on the last one. */
  nextBefore: number | null;
  /** The whole window by outcome, on the first page only. */
  counts: QueryLogCounts | null;
}

export interface QueryLogParams {
  outcome?: QueryOutcome | null;
  principal?: string | null;
  q?: string | null;
  assetFqn?: string | null;
  sourceId?: string | null;
  days?: number;
  before?: number | null;
  limit?: number;
}

export async function fetchQueryLog(params: QueryLogParams = {}): Promise<QueryLogPage> {
  const sent: Record<string, string | number> = {};
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') {
      sent[key] = value as string | number;
    }
  }
  const { data } = await apiClient.get<QueryLogPage>('/v1/audit/queries', { params: sent });
  return data;
}
