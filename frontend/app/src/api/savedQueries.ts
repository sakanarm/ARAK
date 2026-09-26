import axios from 'axios';
import { apiClient } from './client';

/**
 * Statements kept under a name in the query console.
 *
 * The text only, never what it returned. A query somebody shared is opened into
 * the editor and run, if at all, by the person opening it, as themselves: the
 * proxy and their own policies apply, so a shared query shows nobody anything
 * they could not have seen by typing it.
 */

export interface SavedQuery {
  id: string;
  owner: string;
  name: string;
  description: string | null;
  sourceId: string | null;
  sql: string;
  shared: boolean;
  /** Whether it is the caller's to change or delete. */
  mine: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface SavedQueryDraft {
  name: string;
  description?: string | null;
  sourceId?: string | null;
  sql: string;
  shared: boolean;
}

export const MAX_NAME = 120;
export const MAX_DESCRIPTION = 500;

/** The caller already has a query of that name; this is the one. */
export class NameTakenError extends Error {
  constructor(
    message: string,
    readonly existingId: string | null
  ) {
    super(message);
  }
}

export async function fetchSavedQueries(): Promise<SavedQuery[]> {
  const { data } = await apiClient.get<SavedQuery[]>('/v1/saved-queries');
  return data;
}

export async function createSavedQuery(draft: SavedQueryDraft): Promise<SavedQuery> {
  return nameChecked(() => apiClient.post<SavedQuery>('/v1/saved-queries', draft));
}

export async function updateSavedQuery(id: string, draft: SavedQueryDraft): Promise<SavedQuery> {
  return nameChecked(() => apiClient.put<SavedQuery>(`/v1/saved-queries/${encodeURIComponent(id)}`, draft));
}

export async function deleteSavedQuery(id: string): Promise<void> {
  await apiClient.delete(`/v1/saved-queries/${encodeURIComponent(id)}`);
}

async function nameChecked(call: () => Promise<{ data: SavedQuery }>): Promise<SavedQuery> {
  try {
    return (await call()).data;
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 409) {
      const body = error.response.data as { message?: string; existingId?: string } | undefined;
      throw new NameTakenError(body?.message ?? 'You already have a query with that name', body?.existingId ?? null);
    }
    throw error;
  }
}

/**
 * Statements that differ only in spacing are taken for the same one. Loose on
 * purpose -- spacing inside a literal counts as spacing too -- because it only
 * decides whether to mention an existing copy, never whether to save.
 */
export function sameStatement(a: string, b: string): boolean {
  return normalise(a) === normalise(b);
}

function normalise(sql: string): string {
  return sql.replace(/\s+/g, ' ').replace(/\s*;\s*$/, '').trim();
}
