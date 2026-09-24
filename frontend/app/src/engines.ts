import { useQuery } from '@tanstack/react-query';
import { fetchEngines, type SourceEngine, type SourceEngineInfo } from './api/sources';

/**
 * The engines this build can govern, read from the server rather than retyped.
 *
 * Six screens used to carry their own copy of the list — two dropdowns, two
 * badge labels, a default-port table and a test fixture — so adding an engine
 * was one backend change and six frontend ones, and missing any of the six
 * produced either a dropdown offering a source the backend would refuse or a
 * PostgreSQL badge over a SQL Server host. The list is small, changes only when
 * the backend is deployed, and is needed in three unrelated folders, which is
 * exactly the shape of thing a query cache is for.
 *
 * Deliberately not fetched at startup and handed down through context: a
 * screen that does not name an engine should not wait for the list, and a
 * screen that does can render its skeleton while it arrives.
 */

const QUERY_KEY = ['source-engines'] as const;

export function useSourceEngines() {
  return useQuery({
    queryKey: QUERY_KEY,
    queryFn: fetchEngines,
    // It changes on deploy, never mid-session. Refetching it while somebody is
    // half-way through a connection form would only risk moving the dropdown
    // under their cursor.
    staleTime: Infinity,
  });
}

/**
 * What to call this engine on screen.
 *
 * Falls back to the id, which is a real answer rather than a placeholder: a
 * source stored with an engine the server no longer offers should read
 * `MYSQL`, not `Unknown` and certainly not `PostgreSQL`. The old code chose
 * between two names with a ternary, so anything that was not SQL Server was
 * labelled PostgreSQL — a wrong answer stated confidently.
 */
export function engineLabel(
  engines: SourceEngineInfo[] | undefined,
  id: SourceEngine | null | undefined
): string {
  if (!id) return '';
  return engines?.find((engine) => engine.id === id)?.displayName ?? id;
}

/** The port to offer for this engine, or undefined while the list is loading. */
export function enginePort(
  engines: SourceEngineInfo[] | undefined,
  id: SourceEngine | null | undefined
): number | undefined {
  if (!id) return undefined;
  return engines?.find((engine) => engine.id === id)?.defaultPort;
}

/** The engine list shaped for the Select control, in the server's order. */
export function engineOptions(
  engines: SourceEngineInfo[] | undefined
): { value: string; label: string }[] {
  return (engines ?? []).map((engine) => ({ value: engine.id, label: engine.displayName }));
}
