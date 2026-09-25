import { useQuery } from '@tanstack/react-query';
import { useAuthStore } from '../../auth/authStore';
import {
  fetchRequestNotices,
  NOTICES_KEY,
  type RequestNotice,
} from '../../api/accessRequests';

/**
 * What has happened to requests that this person should hear about.
 *
 * One query, read by the bell, the rail and the tabs of the requests page, so
 * the three never show different numbers. It polls because nothing pushes:
 * half a minute is quick enough that an owner sees an ask before the requester
 * gives up and messages them, and slow enough to cost nothing.
 */
export function useRequestNotices() {
  const signedIn = useAuthStore((state) => state.user !== null);
  return useQuery({
    queryKey: NOTICES_KEY,
    queryFn: () => fetchRequestNotices(),
    enabled: signedIn,
    refetchInterval: 30_000,
    retry: false,
  });
}

/** Where a notice goes: the right tab, with its request open. */
export function noticeHref(notice: RequestNotice): string {
  const tab = notice.side === 'INBOX' ? 'inbox' : 'mine';
  return `/requests?tab=${tab}&id=${encodeURIComponent(notice.requestId)}`;
}

/** The table's own name, which is what people call it. */
export function tableName(fqn: string): string {
  return fqn.split('.').pop() || fqn;
}

/** "asked for", "approved your request for" — the verb between the actor and the table. */
export function noticeVerb(notice: RequestNotice): string {
  switch (notice.kind) {
    case 'REQUESTED':
      return 'asked for access to';
    case 'ADVANCED':
      return 'passed on to you a request for';
    case 'TO_CONFIGURE':
      return 'approved, for you to configure, a request for';
    case 'COMPLETED':
      return 'set up your access to';
    case 'WITHDRAWN':
      return 'withdrew their request for';
    case 'APPROVED':
      return 'approved your request for';
    case 'REJECTED':
      return 'rejected your request for';
    default:
      return 'updated a request for';
  }
}

/** A badge count that stays two characters wide. */
export function countLabel(count: number): string {
  return count > 9 ? '9+' : String(count);
}
