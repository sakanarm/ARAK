import { useQuery } from '@tanstack/react-query';
import { apiClient } from './client';

/**
 * The register of purposes data may be used for (FR-21).
 *
 * A purpose has a key that policies, request templates, requests and queries
 * store, and a name people pick by. The PDPA's questions are answered beside
 * it: the legal basis, whether special categories (section 26) may be used for
 * it, who answers for it, and how long access for it may last.
 *
 * Everybody signed in reads the register, because everybody picks from it.
 * Changing it takes a platform admin or a policy author. Nothing is deleted: a
 * purpose is retired, and what already names it keeps the name.
 */

export type LegalBasis =
  | 'CONSENT'
  | 'CONTRACT'
  | 'LEGAL_OBLIGATION'
  | 'VITAL_INTEREST'
  | 'PUBLIC_TASK'
  | 'LEGITIMATE_INTEREST'
  | 'RESEARCH_OR_STATISTICS';

export type PurposeStatus = 'ACTIVE' | 'RETIRED';

export interface Purpose {
  key: string;
  name: string;
  description: string | null;
  legalBasis: LegalBasis | null;
  sensitiveAllowed: boolean;
  owner: string | null;
  maxDays: number | null;
  status: PurposeStatus;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
}

export interface PurposeListing {
  purposes: Purpose[];
  canEdit: boolean;
}

/** Where one value is named. Recent queries are the last 90 days. */
export interface Usage {
  value: string;
  policies: number;
  templates: number;
  openRequests: number;
  recentQueries: number;
}

export interface Uses {
  /** Keyed by the register's key. */
  listed: Record<string, Usage>;
  /** Values in use that the register does not have. */
  unlisted: Usage[];
}

export interface PurposeChange {
  id: number;
  occurredAt: string;
  actor: string;
  action: string;
  reason: string | null;
  before: Partial<Purpose> | null;
  after: Partial<Purpose> | null;
}

/** Everything but the key and the status. */
export interface PurposeDetails {
  name: string;
  description: string | null;
  legalBasis: LegalBasis | null;
  sensitiveAllowed: boolean;
  owner: string | null;
  maxDays: number | null;
}

export interface NewPurpose extends PurposeDetails {
  key: string;
}

/** Section 24 of the PDPA, and consent under section 19, in the order the Act gives them. */
export const LEGAL_BASES: { value: LegalBasis; label: string; hint: string }[] = [
  { value: 'CONSENT', label: 'Consent', hint: 'ม.19 — the data subject agreed to this use' },
  { value: 'CONTRACT', label: 'Contract', hint: 'ม.24(3) — needed to perform a contract with them' },
  { value: 'LEGAL_OBLIGATION', label: 'Legal obligation', hint: 'ม.24(6) — a law requires it' },
  { value: 'VITAL_INTEREST', label: 'Vital interest', hint: 'ม.24(2) — to prevent danger to life or health' },
  { value: 'PUBLIC_TASK', label: 'Public task', hint: 'ม.24(4) — a task in the public interest or official authority' },
  { value: 'LEGITIMATE_INTEREST', label: 'Legitimate interest', hint: 'ม.24(5) — ours, weighed against theirs' },
  {
    value: 'RESEARCH_OR_STATISTICS',
    label: 'Research or statistics',
    hint: 'ม.24(1) — archives, research or statistics, with safeguards',
  },
];

export function legalBasisLabel(basis: LegalBasis | null | undefined): string | null {
  return LEGAL_BASES.find((b) => b.value === basis)?.label ?? null;
}

/** The same rule as the server and the table's CHECK. */
export const PURPOSE_KEY = /^[a-z0-9][a-z0-9._-]{0,62}$/;

export const PURPOSES_KEY = ['purposes'] as const;

export async function fetchPurposes(): Promise<PurposeListing> {
  const { data } = await apiClient.get<PurposeListing>('/v1/purposes');
  return data;
}

export async function fetchPurposeUsage(): Promise<Uses> {
  const { data } = await apiClient.get<Uses>('/v1/purposes/usage');
  return data;
}

export async function fetchPurposeHistory(key: string): Promise<PurposeChange[]> {
  const { data } = await apiClient.get<PurposeChange[]>(
    `/v1/purposes/${encodeURIComponent(key)}/history`
  );
  return data;
}

export async function createPurpose(purpose: NewPurpose): Promise<Purpose> {
  const { data } = await apiClient.post<Purpose>('/v1/purposes', purpose);
  return data;
}

export async function updatePurpose(key: string, details: PurposeDetails): Promise<Purpose> {
  const { data } = await apiClient.put<Purpose>(`/v1/purposes/${encodeURIComponent(key)}`, details);
  return data;
}

export async function retirePurpose(key: string, reason: string): Promise<Purpose> {
  const { data } = await apiClient.post<Purpose>(
    `/v1/purposes/${encodeURIComponent(key)}/retire`,
    { reason }
  );
  return data;
}

export async function reinstatePurpose(key: string, reason: string): Promise<Purpose> {
  const { data } = await apiClient.post<Purpose>(
    `/v1/purposes/${encodeURIComponent(key)}/reinstate`,
    { reason }
  );
  return data;
}

/** The register, shared by every picker on the page. */
export function usePurposes() {
  return useQuery({
    queryKey: PURPOSES_KEY,
    queryFn: fetchPurposes,
    staleTime: 60 * 1000,
    retry: false,
  });
}

/** The purpose a stored value names, by key, ignoring case -- the way the server looks it up. */
export function findPurpose(purposes: Purpose[] | undefined, value: string | null | undefined): Purpose | undefined {
  if (!value) return undefined;
  const wanted = value.trim().toLowerCase();
  return purposes?.find((p) => p.key.toLowerCase() === wanted);
}

/** What to show for a stored value: the register's name, or the value itself. */
export function purposeName(purposes: Purpose[] | undefined, value: string): string {
  return findPurpose(purposes, value)?.name ?? value;
}

/** The purpose a value names, if it may still be chosen. */
export function usablePurpose(purposes: Purpose[] | undefined, value: string | null | undefined): Purpose | undefined {
  const found = findPurpose(purposes, value);
  return found?.status === 'ACTIVE' ? found : undefined;
}

export function activePurposes(purposes: Purpose[] | undefined): Purpose[] {
  return (purposes ?? [])
    .filter((p) => p.status === 'ACTIVE')
    .sort((a, b) => a.name.localeCompare(b.name));
}
