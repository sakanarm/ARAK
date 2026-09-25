import { apiClient } from './client';

/**
 * The LLM assist settings API (M11).
 *
 * Its own module rather than another slab on `client.ts`, because the two
 * halves of this feature answer to different people and the types are easier to
 * keep honest side by side:
 *
 * - everything named `provider` is the platform's optional shared gateway, and
 *   is administrator-only at the API;
 * - everything named `setting` is one account's own gateway — their address,
 *   their key, their model — and is the primary path.
 *
 * No type in this file has a field for a stored API key, and that is on
 * purpose. The server answers `hasOwnKey`, a boolean, and nothing derived from
 * the value; a key travels in exactly one direction, on the way in.
 */

/** The shared gateway, as the console is allowed to see it. */
export interface LlmProviderView {
  baseUrl: string | null;
  /** A pointer to the key, e.g. `env:LLM_API_KEY`. Never the key. */
  credentialRef: string | null;
  defaultModel: string | null;
  /** Whether the shared gateway may be used as a fallback. */
  enabled: boolean;
  /** Whether people may point the assistant at a gateway of their own. */
  allowPersonal: boolean;
  /** False before an administrator has ever saved a shared gateway. */
  configured: boolean;
  /** Whether the pointer resolves on the service process right now. */
  secretPresent: boolean;
  secretProblem: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface LlmProviderEdit {
  baseUrl?: string;
  credentialRef?: string;
  defaultModel?: string | null;
  enabled?: boolean;
  allowPersonal?: boolean;
}

/** What one account's assistant would do right now, and why. */
export interface LlmEffectiveSetting {
  /** On only when the account, a gateway and a model all agree. */
  available: boolean;
  enabled: boolean;
  model: string | null;
  effectiveModel: string | null;
  /** This account's own gateway, or null when it falls back to the shared one. */
  ownBaseUrl: string | null;
  hasOwnKey: boolean;
  /** True when a call would go to their own gateway rather than the shared one. */
  usingOwnGateway: boolean;
  platformEnabled: boolean;
  personalAllowed: boolean;
  sharedConfigured: boolean;
  defaultModel: string | null;
  problem: string | null;
}

/**
 * What a person submits for themselves.
 *
 * `apiKey` blank or absent means "leave the stored one alone", which is what
 * lets this form be submitted without ever having received the key. Forgetting
 * a gateway is `clearOwnGateway`, said out loud, because "unset it" and "do not
 * touch it" must not look alike.
 */
export interface LlmUserEdit {
  enabled?: boolean;
  model?: string | null;
  baseUrl?: string | null;
  apiKey?: string;
  clearOwnGateway?: boolean;
}

/** One row of the administrator's overview. Never carries a key. */
export interface LlmUserRow {
  principalId: string;
  username: string;
  displayName: string | null;
  source: string;
  enabled: boolean;
  model: string | null;
  ownBaseUrl: string | null;
  hasOwnKey: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface LlmModelList {
  available: boolean;
  /** Whether the list came from this account's own gateway. */
  personal?: boolean;
  models: string[];
  problem?: string;
}

export interface LlmCompletion {
  model: string;
  text: string;
  promptTokens: number;
  completionTokens: number;
  personal?: boolean;
}

export async function fetchLlmProvider(): Promise<LlmProviderView> {
  const { data } = await apiClient.get<LlmProviderView>('/v1/llm/provider');
  return data;
}

export async function saveLlmProvider(
  edit: LlmProviderEdit
): Promise<LlmProviderView> {
  const { data } = await apiClient.put<LlmProviderView>('/v1/llm/provider', edit);
  return data;
}

/**
 * Asks the shared gateway what it can serve. Slow by nature — it is a network
 * call, and deliberately the shared gateway rather than the caller's own, so an
 * administrator testing the fallback is testing the fallback.
 */
export async function probeLlmProvider(): Promise<{
  baseUrl: string | null;
  reachable: boolean;
  models: string[];
  modelCount?: number;
  problem?: string;
}> {
  const { data } = await apiClient.post('/v1/llm/provider/probe', {});
  return data;
}

/** The models behind whichever gateway this account would actually reach. */
export async function fetchLlmModels(): Promise<LlmModelList> {
  const { data } = await apiClient.get<LlmModelList>('/v1/llm/models');
  return data;
}

export async function fetchMyLlmSetting(): Promise<LlmEffectiveSetting> {
  const { data } = await apiClient.get<LlmEffectiveSetting>('/v1/llm/me');
  return data;
}

export async function saveMyLlmSetting(
  edit: LlmUserEdit
): Promise<LlmEffectiveSetting> {
  const { data } = await apiClient.put<LlmEffectiveSetting>('/v1/llm/me', edit);
  return data;
}

export async function fetchLlmUsers(): Promise<LlmUserRow[]> {
  const { data } = await apiClient.get<LlmUserRow[]>('/v1/llm/users');
  return data;
}

/**
 * An administrator switching the assistant on or off for somebody else.
 *
 * Only `enabled`: the server narrows this edit before it reaches the store, so
 * nobody — administrator included — writes another account's gateway or key.
 */
export async function saveLlmUserEnabled(
  principalId: string,
  enabled: boolean
): Promise<LlmEffectiveSetting> {
  const { data } = await apiClient.put<LlmEffectiveSetting>(
    `/v1/llm/users/${principalId}`,
    { enabled }
  );
  return data;
}

export async function completeWithLlm(
  prompt: string,
  model?: string
): Promise<LlmCompletion> {
  const { data } = await apiClient.post<LlmCompletion>('/v1/llm/complete', {
    prompt,
    model,
  });
  return data;
}

// ---------------------------------------------------------------- assistant

/**
 * A question about a source, on the way to becoming a statement.
 *
 * `sourceId` is required: the assistant writes against the tables of one
 * source, and a brief assembled from every source at once would invite joins
 * across databases that cannot be joined.
 */
export interface SqlAsk {
  question: string;
  sourceId: string;
  engine?: string;
  model?: string;
}

/**
 * What came back. `sql` empty with a `problem` set is the ordinary
 * "it could not answer that" case, not an error — the console shows the
 * sentence rather than an empty editor.
 */
export interface SqlDraft {
  sql: string;
  model: string;
  /** The tables the model was shown, so the reader can see what it read. */
  tables: string[];
  problem: string | null;
  /** True when the call went to this account's own gateway. */
  personal: boolean;
}

export interface PolicyAsk {
  intent: string;
  sourceId?: string | null;
  model?: string;
}

export interface PolicyDraft {
  /** A policy document as JSON text, always `lifecycleState: DRAFT`. */
  document: string;
  model: string;
  personal: boolean;
}

/**
 * Turns a question into one SELECT statement.
 *
 * Nothing is run here. What comes back is text for an editor the reader still
 * has to press Run on, and that run goes through the proxy and is rewritten
 * against their own policy like any other — so a statement the assistant wrote
 * gives away exactly as much as one they typed.
 */
export async function assistSql(ask: SqlAsk): Promise<SqlDraft> {
  const { data } = await apiClient.post<SqlDraft>('/v1/llm/assist/sql', ask);
  return data;
}

/**
 * Turns a sentence into a draft policy.
 *
 * Returned, never stored. It arrives as a draft for somebody to correct and
 * save under their own name, which is the separation of duty the platform is
 * built on (FR-2.6): the assistant may propose, and only a person may activate.
 */
export async function assistPolicy(ask: PolicyAsk): Promise<PolicyDraft> {
  const { data } = await apiClient.post<PolicyDraft>('/v1/llm/assist/policy', ask);
  return data;
}

// ------------------------------------------------------- fix and explain (M26)

/** A refused statement and what the refusal said, for a corrected draft. */
export interface FixAsk {
  sql: string;
  /** The refusal as the reader saw it. The server strips quoted values first. */
  error: string;
  sourceId: string;
  engine?: string;
  model?: string;
}

export interface ExplainAsk {
  sql: string;
  /** Optional: with a source the explanation can name the tables' columns. */
  sourceId?: string;
  engine?: string;
  model?: string;
}

/** Plain text, never markup: the console renders it as text. */
export interface SqlExplanation {
  text: string;
  model: string;
  tables: string[];
  personal: boolean;
}

/**
 * A corrected statement for one the proxy refused.
 *
 * Offered only for a refusal the server marked `fixable` -- a typo, a bare
 * table name, an error from the source -- never for one a policy made. What
 * comes back is placed in the editor at most; the reader still runs it, and it
 * is enforced like anything they typed.
 */
export async function assistFix(ask: FixAsk): Promise<SqlDraft> {
  const { data } = await apiClient.post<SqlDraft>('/v1/llm/assist/fix', ask);
  return data;
}

/** What a statement does, in a few sentences. The assistant sees no rows. */
export async function assistExplain(ask: ExplainAsk): Promise<SqlExplanation> {
  const { data } = await apiClient.post<SqlExplanation>('/v1/llm/assist/explain', ask);
  return data;
}
