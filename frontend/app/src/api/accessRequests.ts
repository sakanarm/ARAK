import axios from 'axios';
import { apiClient } from './client';
import type { Policy } from '../generated/entity/policy/policy';

/**
 * Asking for a table, and moving the ask through its workflow (FR-7).
 *
 * A request walks the stages of the workflow that covers its table -- stages
 * of one step in parallel, steps in sequence -- and once approved waits for
 * somebody to configure it: a grant written here, or a policy changed on the
 * policy pages. Who may answer is resolved by the server from the workflow's
 * seats and checked on every call; `mayDecide`, `mayVote` and `mayConfigure`
 * are there to draw the buttons, never to authorise them.
 */

export type RequestStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'REJECTED'
  | 'WITHDRAWN';

/** Waiting for somebody: approvers, or whoever configures it. */
export const OPEN_STATUSES: readonly RequestStatus[] = ['PENDING', 'APPROVED', 'IN_PROGRESS'];

export type SeatKind =
  | 'USER'
  | 'TEAM'
  | 'ROLE'
  | 'ASSET_OWNERS'
  | 'DATA_STEWARD'
  | 'DATA_CUSTODIAN';

/** Who a workflow asks: a person, a team, a role, or a part of the table itself. */
export interface Seat {
  kind: SeatKind;
  name?: string | null;
}

/** One person a seat resolved to, and through which seat. */
export interface Member {
  username: string;
  via: string;
}

export type StageRule = 'ALL' | 'ANY' | 'AT_LEAST';
export type OnReject = 'VETO' | 'QUORUM' | 'FIRST_RESPONSE';
export type StageStatus = 'WAITING' | 'OPEN' | 'APPROVED' | 'REJECTED' | 'CLOSED';

export interface VoteView {
  voter: string;
  decision: 'APPROVE' | 'REJECT' | string;
  /** An administrator answering for the stage's approvers. */
  override: boolean;
  note: string | null;
  votedAt: string;
}

/** One stage of one request, as the reader sees it. */
export interface StageView {
  idx: number;
  step: number;
  name: string;
  rule: StageRule;
  minApprovals: number | null;
  onReject: OnReject;
  approvers: Seat[];
  /** Who was asked; empty until the stage's step opens. */
  pool: Member[];
  /** No seat named anybody but the requester, so the administrators were asked. */
  fallback: boolean;
  status: StageStatus;
  openedAt: string | null;
  settledAt: string | null;
  votes: VoteView[];
  approvals: number;
  rejections: number;
  needed: number;
  stranded: boolean;
  mayVote: boolean;
}

export type Fulfilment = 'GRANT' | 'POLICY_UPDATED' | 'POLICY_CREATED';

/** Somebody who can say yes: a user or an OpenMetadata team, per the catalog. */
export interface Approver {
  type: 'user' | 'team' | string;
  name: string;
  direct: boolean;
  inheritedFrom: string | null;
}

export interface AccessRequest {
  id: string;
  /** What people quote and search for: REQ-000042. The id stays the key. */
  ticket: string;
  assetFqn: string;
  requesterId: string | null;
  requesterUsername: string;
  dataSourceId: string | null;
  reason: string;
  purpose: string | null;
  /** Null means "until revoked". */
  requestedDays: number | null;
  attemptedSql: string | null;
  deniedBy: string | null;
  status: RequestStatus;
  createdAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  grantId: string | null;
  /** The workflow it walks, by name as it was when the request was made. */
  workflowName?: string | null;
  currentStep?: number | null;
  /** Who took it to configure. */
  assignee?: string | null;
  assignedAt?: string | null;
  completedBy?: string | null;
  completedAt?: string | null;
  fulfilment?: Fulfilment | null;
  /** The policy a policy fulfilment points at. */
  fulfilmentRef?: string | null;
  fulfilmentNote?: string | null;
  configurers?: Seat[];
  /** Who may configure it once approved; empty before. */
  configurerPool?: Member[];
  configurersFallback?: boolean;
  stages?: StageView[];
  /** The table's owners today, as OpenMetadata records them. */
  approvers: Approver[];
  /** The reader may answer a stage now. */
  mayDecide: boolean;
  /** The reader may start, complete or decline it now. */
  mayConfigure?: boolean;
  /** Open, and nobody but the requester could move it. */
  stranded: boolean;
}

/** One stage of the route a request on a table would walk. */
export interface RouteStage {
  step: number;
  name: string;
  rule: StageRule;
  minApprovals: number | null;
  onReject: OnReject;
  /** The seats, named for the page. */
  approvers: string[];
}

export interface Route {
  workflowName: string;
  stages: RouteStage[];
}

export interface NewAccessRequest {
  assetFqn: string;
  sourceId?: string | null;
  reason: string;
  purpose?: string | null;
  days?: number | null;
  attemptedSql?: string | null;
  deniedBy?: string | null;
}

/**
 * Whether a person can read a table, and if not, whether asking would help.
 *
 * `requestable` is the engine's answer to "would a grant from the owner open
 * this", not a guess from the refusal: a DENY, or a higher layer that refuses
 * this person, makes it false and `blockedBy` names the policy in the way.
 */
export interface Eligibility {
  assetFqn: string;
  readable: boolean;
  requestable: boolean;
  blockedBy: string | null;
  /** Empty means no owner is recorded, and a platform administrator decides. */
  approvers: Approver[];
  openRequestId: string | null;
  stranded?: boolean;
  /** The stages a request would walk; null when the table is readable already. */
  route?: Route | null;
}

/** What a refused query carries when the refusal names one table. */
export interface Refusal {
  message: string;
  assetFqn?: string;
  requestable?: boolean;
  blockedBy?: string | null;
  approvers?: Approver[];
  openRequestId?: string | null;
  stranded?: boolean;
  /** The stages a request would walk, when the server said. */
  route?: Route | null;
  /**
   * Whether the statement itself is what failed, so a corrected one is worth
   * suggesting (M26). Never true for a refusal a policy made.
   */
  fixable?: boolean;
}

/**
 * The refusal body of a failed query, when it is one.
 *
 * Only a 403 counts: a 400 for a statement that would not parse also has a
 * message, and offering to ask an owner about a typo is the wrong advice.
 */
export function refusalOf(error: unknown): Refusal | null {
  if (!axios.isAxiosError(error) || error.response?.status !== 403) {
    return null;
  }
  const body = error.response.data as Partial<Refusal> | undefined;
  if (!body || typeof body.message !== 'string') {
    return null;
  }
  return body as Refusal;
}

export async function requestAccess(ask: NewAccessRequest): Promise<AccessRequest> {
  const { data } = await apiClient.post<AccessRequest>('/v1/access-requests', ask);
  return data;
}

export async function fetchMyRequests(limit = 100): Promise<AccessRequest[]> {
  const { data } = await apiClient.get<AccessRequest[]>('/v1/access-requests/mine', {
    params: { limit },
  });
  return data;
}

export async function fetchRequestInbox(
  status?: RequestStatus | null,
  limit = 100
): Promise<AccessRequest[]> {
  const { data } = await apiClient.get<AccessRequest[]>('/v1/access-requests/inbox', {
    params: { limit, ...(status ? { status } : {}) },
  });
  return data;
}

/** Whether the caller can read each table now; the caller's own decisions only. */
export async function checkReadable(
  assetFqns: string[],
  purpose?: string | null
): Promise<Record<string, boolean>> {
  if (assetFqns.length === 0) {
    return {};
  }
  const { data } = await apiClient.post<Record<string, boolean>>(
    '/v1/access-requests/check',
    { assetFqns, purpose: purpose || null }
  );
  return data;
}

export async function fetchEligibility(
  assetFqn: string,
  purpose?: string | null
): Promise<Eligibility> {
  const { data } = await apiClient.get<Eligibility>(
    `/v1/access-requests/eligibility/${encodeURIComponent(assetFqn)}`,
    { params: purpose ? { purpose } : {} }
  );
  return data;
}

/** One request, for its requester or someone who may decide it; 404 for anyone else. */
/** One request by its ticket number, with the same visibility as by id. */
export async function fetchRequestByTicket(ticket: string): Promise<AccessRequest> {
  const { data } = await apiClient.get<AccessRequest>(
    `/v1/access-requests/ticket/${encodeURIComponent(ticket)}`
  );
  return data;
}

/**
 * The number in what somebody typed -- REQ-000042, req-42, #42 or 42 -- or
 * null when it is not a ticket number. Mirrors the server's reading.
 */
export function ticketNumber(typed: string): number | null {
  const match = /^#?\s*(?:REQ[\s-]*)?0*(\d{1,12})$/i.exec(typed.trim());
  if (!match) return null;
  const number = Number(match[1]);
  return number > 0 ? number : null;
}

/** Whether a request answers a search: by ticket number, or by what it names. */
export function matchesSearch(request: AccessRequest, typed: string): boolean {
  const wanted = typed.trim().toLowerCase();
  if (!wanted) return true;
  const number = ticketNumber(wanted);
  if (number !== null && ticketNumber(request.ticket ?? '') === number) return true;
  return [request.ticket, request.assetFqn, request.requesterUsername, request.purpose, request.reason]
    .filter((text): text is string => Boolean(text))
    .some((text) => text.toLowerCase().includes(wanted));
}

export async function fetchRequest(id: string): Promise<AccessRequest> {
  const { data } = await apiClient.get<AccessRequest>(`/v1/access-requests/${encodeURIComponent(id)}`);
  return data;
}

/**
 * Approves one stage. How long access lasts is not set here: it is for whoever
 * configures the request, and the server refuses a length on an approval.
 *
 * @param decision.stageIdx the stage answered; needed only when the reader
 *     sits on more than one open stage, or is an administrator answering for one
 */
export async function approveRequest(
  id: string,
  decision: { note?: string | null; stageIdx?: number | null } = {}
): Promise<AccessRequest> {
  const { data } = await apiClient.post<AccessRequest>(`/v1/access-requests/${id}/approve`, {
    note: decision.note ?? null,
    stageIdx: decision.stageIdx ?? null,
  });
  return data;
}

export async function rejectRequest(
  id: string,
  note: string,
  stageIdx?: number | null
): Promise<AccessRequest> {
  const { data } = await apiClient.post<AccessRequest>(`/v1/access-requests/${id}/reject`, {
    note,
    stageIdx: stageIdx ?? null,
  });
  return data;
}

/** Takes an approved request to configure, so the other configurers see it is taken. */
export async function startRequest(id: string): Promise<AccessRequest> {
  const { data } = await apiClient.post<AccessRequest>(`/v1/access-requests/${id}/start`);
  return data;
}

/**
 * Says how an approved request was configured, and closes it.
 *
 * A GRANT is written by the server. A policy is only pointed at: it was
 * changed or written on the policy pages, where it is reviewed and activated
 * like any other, and completing the request never activates it.
 */
export async function completeRequest(
  id: string,
  how: {
    fulfilment: Fulfilment;
    days?: number | null;
    policyId?: string | null;
    note?: string | null;
  }
): Promise<AccessRequest> {
  const { data } = await apiClient.post<AccessRequest>(`/v1/access-requests/${id}/complete`, {
    fulfilment: how.fulfilment,
    days: how.days ?? null,
    policyId: how.policyId ?? null,
    note: how.note ?? null,
  });
  return data;
}

/** Refuses to configure an approved request; the requester reads the reason. */
export async function declineRequest(id: string, note: string): Promise<AccessRequest> {
  const { data } = await apiClient.post<AccessRequest>(`/v1/access-requests/${id}/decline`, {
    note,
  });
  return data;
}

export async function withdrawRequest(id: string): Promise<AccessRequest> {
  const { data } = await apiClient.post<AccessRequest>(`/v1/access-requests/${id}/withdraw`);
  return data;
}

// ------------------------------------------------------------------ review

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';
export type ConflictSeverity = 'BLOCKER' | 'WARNING' | 'INFO';
export type ColumnFateKind = 'VISIBLE' | 'MASKED' | 'HIDDEN';
export type SuggestionKind = 'GRANT' | 'UPDATE_POLICY' | 'CREATE_POLICY_DRAFT' | 'DECLINE';

export interface ColumnFate {
  name: string;
  dataType: string | null;
  fate: ColumnFateKind;
  /** The masking function, when masked. */
  masking: string | null;
  /** The policy that masks or hides it. */
  policy: string | null;
  /** Masked on some rows only (a cell mask). */
  conditional: boolean;
  sensitive: boolean;
  sensitiveTags: string[];
}

export interface RowFilter {
  kind: string;
  description: string;
  policy: string | null;
}

export interface ReviewReason {
  policy: string;
  effect: string;
  matched: boolean;
  scopeLevel: string | null;
  explanation: string | null;
}

/** One reading of what the requester would see. `columns` means something only when allowed. */
export interface ReviewAccess {
  allowed: boolean;
  blockedBy: string | null;
  blockedByPolicyId: string | null;
  columns: ColumnFate[];
  rowFilters: RowFilter[];
  reasons: ReviewReason[];
  unenforceable: string[];
}

export interface ReviewRequester {
  username: string;
  displayName: string | null;
  email: string | null;
  /** False when the person has been removed since they asked. */
  known: boolean;
  enabled: boolean;
  source: string | null;
  appRoles: string[];
  /** Direct groups and teams; `id` opens the group's page. */
  memberships: { id: string; name: string; displayName: string | null; kind: string; source: string }[];
  attributes: { key: string; value: string; source: string }[];
  grantsHere: {
    id: string;
    grantedTo: string;
    viaGroup: boolean;
    validUntil: string | null;
    grantedBy: string | null;
  }[];
  /** Other tables they hold a live grant on: counted, never listed. */
  grantsElsewhere: number;
  earlier: {
    id: string;
    status: RequestStatus;
    requestedDays: number | null;
    createdAt: string;
    decidedBy: string | null;
    note: string | null;
    fulfilment: Fulfilment | null;
  }[];
  recentRequests: number;
  recentRejected: number;
}

export interface ReviewTable {
  fqn: string;
  known: boolean;
  tiers: string[];
  domains: string[];
  owners: Approver[];
  columns: number;
  sensitiveColumns: number;
}

export interface PolicyCheck {
  id: string;
  name: string | null;
  displayName: string | null;
  lifecycleState: string | null;
  environment: string | null;
  version: number;
  /** It reaches this table where requests are decided, so activating it would apply here. */
  bound: boolean;
}

export interface ReviewConflict {
  severity: ConflictSeverity;
  code: string;
  detail: string;
  policyId: string | null;
  policyName: string | null;
}

/** One way of answering. Offered, never taken: a draft is not saved, let alone activated. */
export interface ReviewSuggestion {
  kind: SuggestionKind;
  title: string;
  detail: string;
  days: number | null;
  policyId: string | null;
  draft: Policy | null;
}

export type Verdict = 'APPROVE' | 'REVIEW' | 'REJECT' | 'DECLINE';

/**
 * Whether ARAK would approve, and how strongly: a lean, never an answer.
 *
 * <p>`score` is a neutral 50 plus the points on each signal, held to 0-100, so
 * every point can be read and disagreed with. Nothing is learned and nothing
 * is decided: approving still only records the reviewer's decision.
 */
export interface Recommendation {
  verdict: Verdict;
  score: number;
  summary: string;
  /** A shorter grant worth approving instead, or null. */
  suggestedDays: number | null;
  signals: { code: string; points: number; detail: string }[];
}

/**
 * What a reviewer reads before answering: who asked, what a grant would
 * open column by column, what stands in its way, and ways to answer.
 */
export interface AccessReview {
  requestId: string;
  assetFqn: string;
  status: RequestStatus;
  requestedDays: number | null;
  purpose: string | null;
  reviewedAt: string;
  /** The request recorded where it came from. The address itself is never sent. */
  addressKnown: boolean;
  requester: ReviewRequester;
  table: ReviewTable;
  now: ReviewAccess;
  ifGranted: ReviewAccess;
  ifPolicy: ReviewAccess | null;
  policy: PolicyCheck | null;
  risk: { level: RiskLevel; factors: { level: RiskLevel; code: string; detail: string }[] };
  conflicts: ReviewConflict[];
  suggestions: ReviewSuggestion[];
  recommendation: Recommendation;
}

/**
 * The review of one request, for the people deciding or configuring it; the
 * requester is refused one (403), anybody else is told it does not exist.
 *
 * @param policyId a policy the reader is thinking of configuring it with:
 *     the review then says whether that policy reaches the table and would
 *     let them in. It is only read, never activated.
 */
export async function fetchAccessReview(
  id: string,
  policyId?: string | null
): Promise<AccessReview> {
  const { data } = await apiClient.get<AccessReview>(
    `/v1/access-requests/${encodeURIComponent(id)}/review`,
    { params: policyId ? { policyId } : {} }
  );
  return data;
}

/**
 * "owner_o", "team Finance", or who decides when the catalog names nobody.
 *
 * `stranded` is the server saying that nobody but the requester could decide:
 * they are the only owner, or there is no owner and they are the only
 * administrator. Nobody decides their own request, so the page says it will
 * wait for nobody rather than promising somebody who cannot act.
 */
export function describeApprovers(approvers: Approver[] | undefined, stranded = false): string {
  if (stranded) {
    return !approvers || approvers.length === 0
      ? 'No owner is recorded for this table and there is no other platform administrator, so nobody can decide this yet. Record an owner in OpenMetadata or add another administrator.'
      : 'Only the requester owns this table, and nobody decides their own request, so nobody can decide this yet. Record another owner in OpenMetadata or add another administrator.';
  }
  if (!approvers || approvers.length === 0) {
    return 'No owner is recorded for this table, so a platform administrator decides.';
  }
  const names = approvers.map((a) => (a.type === 'team' ? `team ${a.name}` : a.name));
  return `Decided by ${names.join(', ')}.`;
}

/** "ann", "Team Finance", "Owners of the table": a seat as the page names it. */
const ROLE_LABELS: Record<string, string> = {
  PLATFORM_ADMIN: 'Platform administrator',
  POLICY_AUTHOR: 'Policy author',
  DATA_OWNER: 'Data owner',
  AUDITOR: 'Auditor',
  REQUESTER: 'Requester',
};

/** "Data owner" for DATA_OWNER, as the server words it. */
export function roleLabel(role: string | null | undefined): string {
  if (!role) return '?';
  return ROLE_LABELS[role.toUpperCase()] ?? role;
}

export function describeSeat(seat: Seat): string {
  switch (seat.kind) {
    case 'USER':
      return seat.name ?? '?';
    case 'TEAM':
      return `Team ${seat.name ?? '?'}`;
    case 'ROLE':
      return `Role ${roleLabel(seat.name)}`;
    case 'ASSET_OWNERS':
      return 'Owners of the table';
    case 'DATA_STEWARD':
      return 'Data steward';
    case 'DATA_CUSTODIAN':
      return 'Data custodian';
    default:
      return '?';
  }
}

/** "Any one approves", "All 3 approve", "At least 2 of 4 approve". */
export function describeRule(
  rule: StageRule,
  minApprovals: number | null | undefined,
  asked?: number
): string {
  const counted = asked !== undefined && asked > 0;
  switch (rule) {
    case 'ALL':
      return counted ? `All ${asked} approve` : 'Everyone asked approves';
    case 'ANY':
      return 'Any one approves';
    case 'AT_LEAST':
      return `At least ${minApprovals ?? 1}${counted ? ` of ${asked}` : ''} approve`;
    default:
      return rule;
  }
}

/** What a rejection does to the stage, in the words the editor offers. */
export function describeOnReject(onReject: OnReject): string {
  switch (onReject) {
    case 'VETO':
      return 'One rejection rejects the request';
    case 'QUORUM':
      return 'A rejection counts only once the approvals can no longer come';
    case 'FIRST_RESPONSE':
      return 'The first answer decides';
    default:
      return onReject;
  }
}

/**
 * One thing that happened to a request, told to somebody who should hear it.
 *
 * `side` says which tab of the requests page it belongs on: INBOX when the
 * reader decides the table, MINE when it was the reader's own ask.
 */
export interface RequestNotice {
  id: number;
  /**
   * INBOX: REQUESTED (a stage asks the reader), ADVANCED (a step passed and the
   * next asks the reader), TO_CONFIGURE (approved; the reader configures it),
   * WITHDRAWN. MINE: APPROVED, REJECTED, COMPLETED.
   */
  kind:
    | 'REQUESTED'
    | 'ADVANCED'
    | 'TO_CONFIGURE'
    | 'WITHDRAWN'
    | 'APPROVED'
    | 'REJECTED'
    | 'COMPLETED';
  side: 'INBOX' | 'MINE';
  requestId: string;
  assetFqn: string;
  actor: string;
  requesterUsername: string;
  note: string | null;
  occurredAt: string;
  unseen: boolean;
  /** For ADVANCED: the step that opened. */
  step?: number | null;
}

export interface RequestNotices {
  /** How many of the reader's notices are newer than the last time they looked. */
  unseen: number;
  /** Open requests waiting on the reader, to answer or to configure: the Inbox tab's count. */
  inboxPending: number;
  /** The reader's own requests still waiting: the My requests tab's count. */
  minePending: number;
  seenAt: string | null;
  items: RequestNotice[];
}

/** Shared by the bell, the rail and the requests page, so one fetch feeds all three. */
export const NOTICES_KEY = ['access-requests', 'notifications'] as const;

export async function fetchRequestNotices(limit = 20): Promise<RequestNotices> {
  const { data } = await apiClient.get<RequestNotices>('/v1/access-requests/notifications', {
    params: { limit },
  });
  return data;
}

export async function markRequestNoticesSeen(): Promise<void> {
  await apiClient.post('/v1/access-requests/notifications/seen');
}

/**
 * How often one table was asked for in the window, and how the asks ended.
 *
 * `rejected` is an approver's no; `declined` is the configurer's, after
 * approval. `medianHoursToClose` is null when nothing in the window has ended.
 */
export interface TableRequestStats {
  assetFqn: string;
  asked: number;
  open: number;
  completed: number;
  rejected: number;
  declined: number;
  withdrawn: number;
  requesters: number;
  medianHoursToClose: number | null;
  lastAskedAt: string | null;
}

export interface RequestStatsTotals {
  tables: number;
  asked: number;
  open: number;
  completed: number;
  rejected: number;
  declined: number;
  withdrawn: number;
}

/**
 * Requests per table (M9 slice 2c). Counted over the tables the reader
 * oversees; a requester gets an empty answer, not an error. `total` and
 * `totals` are before `limit` cut the list.
 */
export interface RequestStats {
  since: string;
  days: number;
  total: number;
  totals: RequestStatsTotals;
  tables: TableRequestStats[];
}

export async function fetchRequestStats(days = 90, limit = 50): Promise<RequestStats> {
  const { data } = await apiClient.get<RequestStats>('/v1/access-requests/stats', {
    params: { days, limit },
  });
  return data;
}
