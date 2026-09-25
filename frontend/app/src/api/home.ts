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
  // Access: grants about to lapse, and how often each table is asked for.
  | 'EXPIRING_ACCESS'
  | 'ACCESS_REQUEST_STATS'
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

/**
 * A platform role, seen as an audience for the home page (M12b).
 *
 * In the server's precedence order, strongest first. The order is part of the
 * contract rather than a presentation detail: somebody holding several roles is
 * served the first one of these that an administrator has arranged.
 */
export type HomePersonaRole =
  | 'PLATFORM_ADMIN'
  | 'POLICY_AUTHOR'
  | 'DATA_OWNER'
  | 'AUDITOR'
  | 'REQUESTER';

/** Where a served layout came from. */
export type HomeLayoutSource = 'PERSONAL' | 'ROLE' | 'BUILT_IN';

export interface HomeLayoutView {
  layout: HomeLayout;
  /** True when this account has never saved one, so the page can say so. */
  isDefault: boolean;
  /**
   * Which of the three sources it came from. `isDefault` stays because it is
   * the question the editor asks -- may I offer Reset? -- and that is true of
   * both kinds of default.
   */
  source: HomeLayoutSource;
  /** The role it came from when `source` is `ROLE`; null otherwise. */
  sourceRole: HomePersonaRole | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

/** One persona as the administrator's editor sees it. */
export interface HomePersonaLayout {
  role: HomePersonaRole;
  layout: HomeLayout;
  /**
   * False when nobody has arranged this role, in which case `layout` is the
   * built-in page -- the editor opens on what those people see today rather
   * than on an empty canvas.
   */
  configured: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
}

/** The five roles in the server's precedence order. */
export const HOME_PERSONA_ROLES: HomePersonaRole[] = [
  'PLATFORM_ADMIN',
  'POLICY_AUTHOR',
  'DATA_OWNER',
  'AUDITOR',
  'REQUESTER',
];

/** What each role is called on screen. */
export const HOME_PERSONA_LABELS: Record<HomePersonaRole, string> = {
  PLATFORM_ADMIN: 'Platform admin',
  POLICY_AUTHOR: 'Policy author',
  DATA_OWNER: 'Data owner',
  AUDITOR: 'Auditor',
  REQUESTER: 'Requester',
};

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

/** Forgets my arrangement and restores whatever I inherit. */
export async function resetHomeLayout(): Promise<HomeLayoutView> {
  const { data } = await apiClient.delete<HomeLayoutView>('/v1/home/layout');
  return data;
}

/**
 * Every persona, arranged or not. Platform admin only.
 *
 * All five come back always, because "what does a data owner see" has an answer
 * whether or not anybody has touched it.
 */
export async function fetchHomePersonas(): Promise<HomePersonaLayout[]> {
  const { data } = await apiClient.get<HomePersonaLayout[]>('/v1/home/personas');
  return data;
}

/** Arranges the page for one role. Platform admin only. */
export async function saveHomePersona(
  role: HomePersonaRole,
  layout: HomeLayout
): Promise<HomePersonaLayout> {
  const { data } = await apiClient.put<HomePersonaLayout>(
    `/v1/home/personas/${role}`,
    layout
  );
  return data;
}

/** Forgets the arrangement for one role, returning those people to the built-in page. */
export async function resetHomePersona(
  role: HomePersonaRole
): Promise<HomePersonaLayout> {
  const { data } = await apiClient.delete<HomePersonaLayout>(
    `/v1/home/personas/${role}`
  );
  return data;
}
