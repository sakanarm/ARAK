import { useQueries } from '@tanstack/react-query';
import { useMemo } from 'react';
import {
  fetchEligibilities,
  MAX_ELIGIBILITY,
  type EligibilityBrief,
} from '../../api/accessRequests';

/**
 * Where the signed-in person stands on each table in a page of catalog rows,
 * for the "You …" badges beside them.
 *
 * One batched call per {@link MAX_ELIGIBILITY} tables, never one per row;
 * containers are left out, since access is asked for and given on tables.
 * The keys sit under `access-requests`, so sending a request -- which
 * invalidates that prefix -- turns "You can request" into "You requested
 * access" without a reload.
 *
 * A failure is quiet: the badges are extra, and a catalog that stops working
 * because they could not be fetched would be a worse catalog.
 *
 * @returns each table's answer by FQN; a table still loading, or not asked
 *     about, is absent
 */
export function useMyAccess(
  rows: ReadonlyArray<{ fqn: string; assetType: string }>
): Map<string, EligibilityBrief> {
  const chunks = useMemo(() => {
    const tables = Array.from(
      new Set(rows.filter((r) => r.assetType === 'TABLE' || r.assetType === 'VIEW').map((r) => r.fqn))
    ).sort();
    const out: string[][] = [];
    for (let i = 0; i < tables.length; i += MAX_ELIGIBILITY) {
      out.push(tables.slice(i, i + MAX_ELIGIBILITY));
    }
    return out;
  }, [rows]);

  const answers = useQueries({
    queries: chunks.map((fqns) => ({
      queryKey: ['access-requests', 'eligibility-batch', fqns],
      queryFn: () => fetchEligibilities(fqns),
      staleTime: 30_000,
      retry: false,
    })),
  });

  // The answers array is new on every render; when each answer last changed
  // is what says whether the map needs building again.
  const stamp = answers.map((a) => a.dataUpdatedAt).join(',');
  return useMemo(() => {
    const byFqn = new Map<string, EligibilityBrief>();
    for (const answer of answers) {
      for (const brief of answer.data ?? []) {
        byFqn.set(brief.assetFqn, brief);
      }
    }
    return byFqn;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stamp, chunks]);
}
