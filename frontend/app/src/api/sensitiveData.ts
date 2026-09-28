import { useEffect, useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { apiClient } from './client';

/**
 * What counts as sensitive data, and what happens when a purpose that does not
 * allow it is named on a table holding some (FR-21, M31b).
 *
 * One rule, kept in one place, answers for the query proxy, the request form,
 * an access review and the dashboard. It counts the built-in names (PII,
 * PersonalData, and anything calling itself sensitive, confidential,
 * restricted or secret) unless that is turned off, plus whatever "counts"
 * lists, minus whatever "never counts" lists. Only confirmed labels count; one
 * OpenMetadata merely suggested does not.
 *
 * Everybody signed in reads the rule, because the request form tells them of
 * it. Changing it takes a platform admin or a policy author, with a reason;
 * measuring a draft and reading its history take somebody who governs.
 */

/** Off checks nothing; warn lets it go ahead and says so; enforce refuses. */
export type SensitiveMode = 'OFF' | 'WARN' | 'ENFORCE';

export type LabelKind = 'CLASSIFICATION' | 'TAG' | 'GLOSSARY' | 'TERM';

export interface SensitiveLabel {
  kind: LabelKind;
  fqn: string;
}

export interface SensitiveSettings {
  builtIn: boolean;
  include: SensitiveLabel[];
  exclude: SensitiveLabel[];
  mode: SensitiveMode;
}

export interface SensitiveRule extends SensitiveSettings {
  updatedBy: string | null;
  updatedAt: string | null;
}

export interface SensitiveCurrent {
  rule: SensitiveRule;
  canEdit: boolean;
}

export interface SensitiveEdit extends SensitiveSettings {
  reason: string;
}

/** A table the rule covers, with how many of its columns; zero when only the table is labelled. */
export interface Covered {
  fqn: string;
  columns: number;
}

export interface LabelUse {
  label: string;
  tables: number;
  columns: number;
}

export interface Coverage {
  tables: number;
  columns: number;
  /** Tables and views in the catalog at all, for scale. */
  catalogTables: number;
  examples: Covered[];
  labels: LabelUse[];
}

export interface SensitiveChange {
  id: number;
  at: string;
  actor: string;
  reason: string | null;
  before: SensitiveSettings | null;
  after: SensitiveSettings;
}

/** What naming a purpose on a table meets. */
export interface Concern {
  mode: SensitiveMode;
  table: string;
  labels: string[];
  /** The purpose as stored; null when none was named. */
  purpose: string | null;
  purposeName: string | null;
  /** The same words whether it warns or refuses. */
  message: string;
}

export const LABEL_KINDS: { value: LabelKind; label: string; hint: string }[] = [
  { value: 'CLASSIFICATION', label: 'Classification', hint: 'Every tag in it' },
  { value: 'TAG', label: 'Tag', hint: 'The tag and every tag beneath it' },
  { value: 'GLOSSARY', label: 'Glossary', hint: 'Every term in it' },
  { value: 'TERM', label: 'Term', hint: 'The term and every term beneath it' },
];

export const MODES: { value: SensitiveMode; label: string; hint: string }[] = [
  {
    value: 'OFF',
    label: 'Off',
    hint: 'Nothing is checked. The purpose is recorded as it always was.',
  },
  {
    value: 'WARN',
    label: 'Warn',
    hint: 'A query or request goes ahead, the person is told, and the record is marked.',
  },
  {
    value: 'ENFORCE',
    label: 'Enforce',
    hint: 'A query or request is refused, and the refusal names the purpose.',
  },
];

export function kindLabel(kind: LabelKind): string {
  return LABEL_KINDS.find((k) => k.value === kind)?.label ?? kind;
}

export function modeLabel(mode: SensitiveMode): string {
  return MODES.find((m) => m.value === mode)?.label ?? mode;
}

/** Up to how many labels each list may hold; a classification covers every tag in it. */
export const MAX_LABELS = 100;
export const MAX_LABEL_FQN = 256;

export const SENSITIVE_KEY = ['sensitive-data'] as const;

export async function fetchSensitiveData(): Promise<SensitiveCurrent> {
  const { data } = await apiClient.get<SensitiveCurrent>('/v1/sensitive-data');
  return data;
}

export async function updateSensitiveData(edit: SensitiveEdit): Promise<SensitiveCurrent> {
  const { data } = await apiClient.put<SensitiveCurrent>('/v1/sensitive-data', edit);
  return data;
}

/** How far a rule would reach, without saving it; null measures the rule in force. */
export async function previewSensitiveData(
  draft: Pick<SensitiveSettings, 'builtIn' | 'include' | 'exclude'> | null
): Promise<Coverage> {
  const { data } = await apiClient.post<Coverage>('/v1/sensitive-data/preview', draft);
  return data;
}

export async function fetchSensitiveHistory(): Promise<SensitiveChange[]> {
  const { data } = await apiClient.get<SensitiveChange[]>('/v1/sensitive-data/history');
  return data;
}

/** Null when the purpose may be used there, or nothing there is sensitive, or the rule is off. */
export async function checkPurpose(asset: string, purpose: string | null): Promise<Concern | null> {
  const params = new URLSearchParams({ asset });
  if (purpose) params.set('purpose', purpose);
  const { data } = await apiClient.get<{ concern: Concern | null }>(
    `/v1/sensitive-data/check?${params}`
  );
  return data.concern ?? null;
}

export function useSensitiveData() {
  return useQuery({
    queryKey: SENSITIVE_KEY,
    queryFn: fetchSensitiveData,
    staleTime: 60 * 1000,
    retry: false,
  });
}

/**
 * What naming this purpose on these tables meets, for a request form to say
 * before it is sent: one concern per table that has one. The purpose is asked
 * about once typing pauses; a check that fails says nothing, and the server
 * still decides.
 */
export function usePurposeConcerns(
  assets: (string | null | undefined)[],
  purpose: string | null | undefined
): Concern[] {
  const named = useSettled(purpose?.trim() || null, 300);
  const tables = [...new Set(assets.map((asset) => asset?.trim()).filter((asset): asset is string => !!asset))];
  const checks = useQueries({
    queries: tables.map((table) => ({
      queryKey: [...SENSITIVE_KEY, 'check', table, named],
      queryFn: () => checkPurpose(table, named),
      staleTime: 30 * 1000,
      retry: false,
    })),
  });
  return checks.flatMap((check) => (check.data ? [check.data] : []));
}

function useSettled<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return settled;
}

/** Two labels are the same when their kind is and their names are, whatever the case. */
export function sameLabel(a: SensitiveLabel, b: SensitiveLabel): boolean {
  return a.kind === b.kind && a.fqn.trim().toLowerCase() === b.fqn.trim().toLowerCase();
}

/** The first thing the server would refuse, said the same way, or null. */
export function ruleProblem(settings: SensitiveSettings, reason: string, maxReason: number): string | null {
  for (const label of settings.include) {
    if (settings.exclude.some((x) => sameLabel(x, label))) {
      return `${label.fqn} is in both lists; keep it in one`;
    }
  }
  if (settings.include.length > MAX_LABELS || settings.exclude.length > MAX_LABELS) {
    return `Keep each list to ${MAX_LABELS} labels; a classification covers every tag in it`;
  }
  const why = reason.trim();
  if (!why) return 'Say why: the reason for changing the rule is kept';
  if (why.length > maxReason) return `Keep the reason to ${maxReason} characters`;
  return null;
}

/** Whether two settings say the same thing, as the server compares them. */
export function sameSettings(a: SensitiveSettings, b: SensitiveSettings): boolean {
  const sameList = (x: SensitiveLabel[], y: SensitiveLabel[]) =>
    x.length === y.length && x.every((label, i) => sameLabel(label, y[i]) && label.fqn.trim() === y[i].fqn.trim());
  return (
    a.builtIn === b.builtIn &&
    a.mode === b.mode &&
    sameList(a.include, b.include) &&
    sameList(a.exclude, b.exclude)
  );
}

/** What a change did, in words: "enforce instead of warn", "PII (classification) counted", … */
export function describeChange(change: Pick<SensitiveChange, 'before' | 'after'>): string[] {
  const { before, after } = change;
  if (!before) return ['set the rule'];
  const out: string[] = [];
  if (before.mode !== after.mode) {
    out.push(`${modeLabel(after.mode).toLowerCase()} instead of ${modeLabel(before.mode).toLowerCase()}`);
  }
  if (before.builtIn !== after.builtIn) {
    out.push(after.builtIn ? 'built-in names counted again' : 'built-in names no longer counted');
  }
  const named = (label: SensitiveLabel) => `${label.fqn} (${kindLabel(label.kind).toLowerCase()})`;
  const added = (from: SensitiveLabel[], to: SensitiveLabel[]) =>
    to.filter((label) => !from.some((x) => sameLabel(x, label)));
  for (const label of added(before.include, after.include)) out.push(`${named(label)} counted`);
  for (const label of added(after.include, before.include)) out.push(`${named(label)} no longer counted`);
  for (const label of added(before.exclude, after.exclude)) out.push(`${named(label)} never counted`);
  for (const label of added(after.exclude, before.exclude)) out.push(`${named(label)} no longer excluded`);
  return out;
}
