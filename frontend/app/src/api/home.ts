import { apiClient } from './client';

/**
 * The personal home page API (M12).
 *
 * These types mirror the Java records one for one. The `config` map is
 * deliberately loose here as it is on the server: a widget's settings are read
 * by exactly one renderer, and the thing that decides whether they are safe is
 * the server's validator, not this file. Nothing here should be tempted to
 * clean anything — a second, weaker sanitiser in the browser is how a team ends
 * up arguing about which one was authoritative.
 */

/** How the page is divided. The names match the server's enum. */
export type HomePreset =
  | 'SINGLE'
  | 'HALVES'
  | 'WIDE_LEFT'
  | 'WIDE_RIGHT'
  | 'THIRDS';

export type HomeWidgetType =
  // Finding things — the way in rather than a readout.
  | 'SEARCH'
  // Reading widgets — the panels this page has always had.
  | 'RECENT_POLICIES'
  | 'GOVERNANCE_COVERAGE'
  | 'SOURCES'
  | 'VOCABULARY'
  | 'PLATFORM'
  // Charts over the same data.
  | 'CHART_ASSETS_BY_TYPE'
  | 'CHART_POLICIES_BY_STATE'
  | 'CHART_POLICIES_BY_SCOPE'
  | 'CHART_SOURCES_BY_MODE'
  // Authored content.
  | 'LINKS'
  | 'HTML'
  | 'VIDEO'
  | 'NOTE';

export interface HomeLink {
  label: string;
  url: string;
  note?: string;
}

export interface HomeWidget {
  id: string;
  type: HomeWidgetType;
  /** Zero-based. The server clamps it to a column the preset actually has. */
  column: number;
  /** Overrides the widget's own heading when set. */
  title: string | null;
  config: Record<string, unknown>;
}

export interface HomeLayout {
  preset: HomePreset;
  widgets: HomeWidget[];
}

export interface HomeLayoutView {
  layout: HomeLayout;
  /** True when this account has never saved one, so the page can say so. */
  isDefault: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
}

/** How many columns each preset has, and how they are weighted. */
export const PRESET_COLUMNS: Record<HomePreset, number> = {
  SINGLE: 1,
  HALVES: 2,
  WIDE_LEFT: 2,
  WIDE_RIGHT: 2,
  THIRDS: 3,
};

export async function fetchHomeLayout(): Promise<HomeLayoutView> {
  const { data } = await apiClient.get<HomeLayoutView>('/v1/home/layout');
  return data;
}

/** Saves my page. What comes back is what was stored, after cleaning. */
export async function saveHomeLayout(
  layout: HomeLayout
): Promise<HomeLayoutView> {
  const { data } = await apiClient.put<HomeLayoutView>('/v1/home/layout', layout);
  return data;
}

/** Forgets my arrangement and restores the default. */
export async function resetHomeLayout(): Promise<HomeLayoutView> {
  const { data } = await apiClient.delete<HomeLayoutView>('/v1/home/layout');
  return data;
}
