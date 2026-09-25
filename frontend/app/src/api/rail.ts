import { apiClient } from './client';

/**
 * The left-hand rail one person arranged (`/v1/me/rail`).
 *
 * The server stores a list and checks that it is a list of console routes; it
 * does not know which sections exist or who may open them. That is decided
 * here, by `arrangeRail` in layout/navigation, against the sections this account is offered.
 */

export interface RailEntry {
  href: string;
  shown: boolean;
}

/**
 * How large the rail is drawn. `comfortable` is the rail as it always was --
 * tall rows, large icons -- and the default; `compact` is narrower and shorter.
 */
export type RailDensity = 'comfortable' | 'compact';

export interface RailView {
  /** Null until this person arranges their rail: draw the default. */
  sections: RailEntry[] | null;
  /** Absent from an older server: read as comfortable. */
  density?: RailDensity;
  updatedAt: string | null;
}

export function railDensity(view: RailView | undefined): RailDensity {
  return view?.density === 'compact' ? 'compact' : 'comfortable';
}

export const RAIL_QUERY_KEY = ['me', 'rail'] as const;

export async function fetchRail(): Promise<RailView> {
  const { data } = await apiClient.get<RailView>('/v1/me/rail');
  return data;
}

export async function saveRail(
  sections: RailEntry[],
  density: RailDensity = 'comfortable'
): Promise<RailView> {
  const { data } = await apiClient.put<RailView>('/v1/me/rail', { sections, density });
  return data;
}

export async function resetRail(): Promise<RailView> {
  const { data } = await apiClient.delete<RailView>('/v1/me/rail');
  return data;
}
