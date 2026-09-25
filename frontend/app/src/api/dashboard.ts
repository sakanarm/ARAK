import { apiClient } from './client';
import type { QueryLogCounts, RefusalCategory } from './audit';

/**
 * The access-control dashboard (M10, FR-8.5): the whole estate in one answer.
 *
 * Only for an administrator, policy author or auditor; the server refuses
 * anyone else. It counts rather than quotes: no statement text, no client
 * address and no sync error text is in it.
 */

export interface SensitiveTable {
  fqn: string;
  /** Columns carrying the label; zero when only the table does. */
  sensitiveColumns: number;
  /** Active data policies (masks, row filters) bound to it or its columns. */
  dataPolicies: number;
  /** Active ALLOW subscription policies that let people in. */
  subscriptionPolicies: number;
  activeGrants: number;
  /** Distinct people who read it through the proxy in the window. */
  readers: number;
  /** Queries on it the proxy refused in the window. */
  refused: number;
  owners: string[];
}

export interface Coverage {
  /** Every table and view in the catalogue. */
  tables: number;
  sensitive: number;
  protectedTables: number;
  /** Sensitive, unprotected, and somebody can get in. */
  exposed: number;
  /** Sensitive, unprotected, and read in the window. */
  readUnprotected: number;
  /** Unprotected first; at most a hundred, the counts cover all of them. */
  rows: SensitiveTable[];
}

export interface EndingGrant {
  principal: string;
  principalType: 'USER' | 'GROUP' | string;
  assetFqn: string;
  validUntil: string;
  source: string;
}

export interface GrantPicture {
  active: number;
  endingCount: number;
  /** Soonest first, at most ten. */
  endingSoon: EndingGrant[];
  openEnded: number;
  onSensitive: number;
  /** Held by a person for over ninety days without a query on that table. */
  unused: number;
}

export interface DashboardDay {
  date: string;
  executed: number;
  rejected: number;
  failed: number;
}

export interface Busiest {
  name: string;
  queries: number;
  refused: number;
  /** People who read a table, or tables a person read. */
  people: number;
  lastAt: string | null;
}

export interface Decisions {
  total: number;
  denied: number;
  fromCache: number;
  p50Ms: number | null;
  p95Ms: number | null;
}

export interface Activity {
  counts: QueryLogCounts;
  perDay: DashboardDay[];
  refusals: { category: RefusalCategory; count: number }[];
  busiestTables: Busiest[];
  busiestPeople: Busiest[];
  decisions: Decisions;
}

export interface RequestPicture {
  pending: number;
  approved: number;
  inProgress: number;
  oldestOpenAt: string | null;
  asked: number;
  completed: number;
  rejected: number;
  withdrawn: number;
  medianHoursToClose: number | null;
}

export interface Health {
  sources: number;
  sourcesEnabled: number;
  /** Enforced objects by status. */
  enforcement: Record<string, number>;
  /** "SUBSCRIPTION ACTIVE" and the like, to a count. */
  policies: Record<string, number>;
  syncStatus: string | null;
  lastCrawlAt: string | null;
  syncFailed: boolean;
}

export type AttentionKind =
  | 'UNPROTECTED_READ'
  | 'UNPROTECTED_REACHABLE'
  | 'ENFORCEMENT_FAULT'
  | 'SYNC_FAILED'
  | 'SYNC_STALE'
  | 'REQUESTS_WAITING'
  | 'GRANTS_ENDING'
  | 'UNPROTECTED_CLOSED'
  | 'GRANTS_UNUSED'
  | 'GRANTS_OPEN_ENDED';

export type Severity = 'HIGH' | 'MEDIUM' | 'LOW';

export interface Attention {
  kind: AttentionKind;
  severity: Severity;
  count: number;
  /** The first thing it is about, where one names it: a table, a person, a number of days. */
  subject: string | null;
}

export interface Dashboard {
  generatedAt: string;
  since: string;
  days: number;
  label: string;
  coverage: Coverage;
  grants: GrantPicture;
  activity: Activity;
  requests: RequestPicture;
  health: Health;
  /** Most urgent first. */
  attention: Attention[];
}

export async function fetchDashboard(days: number, label: string): Promise<Dashboard> {
  const { data } = await apiClient.get<Dashboard>('/v1/dashboard', { params: { days, label } });
  return data;
}
