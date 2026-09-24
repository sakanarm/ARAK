import axios from 'axios';
import { apiClient } from './client';

/**
 * Asking a table's owner for access, and answering (FR-7, the first slice of
 * the Phase 2 workflow).
 *
 * Who may decide is not a console role. It is the table's owner as
 * OpenMetadata records it, or a platform administrator, and the server checks
 * it on every answer — `mayDecide` on a request is there to draw the buttons,
 * never to authorise them.
 */

export type RequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';

/** Somebody who can say yes: a user or an OpenMetadata team, per the catalog. */
export interface Approver {
  type: 'user' | 'team' | string;
  name: string;
  direct: boolean;
  inheritedFrom: string | null;
}

export interface AccessRequest {
  id: string;
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
  approvers: Approver[];
  mayDecide: boolean;
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
}

/** What a refused query carries when the refusal names one table. */
export interface Refusal {
  message: string;
  assetFqn?: string;
  requestable?: boolean;
  blockedBy?: string | null;
  approvers?: Approver[];
  openRequestId?: string | null;
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
export async function fetchRequest(id: string): Promise<AccessRequest> {
  const { data } = await apiClient.get<AccessRequest>(`/v1/access-requests/${encodeURIComponent(id)}`);
  return data;
}

export async function approveRequest(
  id: string,
  decision: { days?: number | null; note?: string | null }
): Promise<AccessRequest> {
  const { data } = await apiClient.post<AccessRequest>(
    `/v1/access-requests/${id}/approve`,
    decision
  );
  return data;
}

export async function rejectRequest(id: string, note: string): Promise<AccessRequest> {
  const { data } = await apiClient.post<AccessRequest>(`/v1/access-requests/${id}/reject`, {
    note,
  });
  return data;
}

export async function withdrawRequest(id: string): Promise<AccessRequest> {
  const { data } = await apiClient.post<AccessRequest>(`/v1/access-requests/${id}/withdraw`);
  return data;
}

/** "owner_o", "team Finance", or who decides when the catalog names nobody. */
export function describeApprovers(approvers: Approver[] | undefined): string {
  if (!approvers || approvers.length === 0) {
    return 'No owner is recorded for this table, so a platform administrator decides.';
  }
  const names = approvers.map((a) => (a.type === 'team' ? `team ${a.name}` : a.name));
  return `Decided by ${names.join(', ')}.`;
}

/**
 * One thing that happened to a request, told to somebody who should hear it.
 *
 * `side` says which tab of the requests page it belongs on: INBOX when the
 * reader decides the table, MINE when it was the reader's own ask.
 */
export interface RequestNotice {
  id: number;
  kind: 'REQUESTED' | 'WITHDRAWN' | 'APPROVED' | 'REJECTED';
  side: 'INBOX' | 'MINE';
  requestId: string;
  assetFqn: string;
  actor: string;
  requesterUsername: string;
  note: string | null;
  occurredAt: string;
  unseen: boolean;
}

export interface RequestNotices {
  /** How many of the reader's notices are newer than the last time they looked. */
  unseen: number;
  /** Pending requests the reader may decide: the Inbox tab's count. */
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
