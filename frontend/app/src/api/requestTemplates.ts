import { apiClient } from './client';

/**
 * Request templates: what the request access form asks, per table.
 *
 * A template covers a scope (or the whole organisation) and, optionally, only
 * the tables under it that carry certain tags, classifications or glossary
 * terms. Of the templates covering a table, one naming labels the table
 * carries beats one naming none, then the deepest scope wins. With none, the
 * built-in form: a reason and how long.
 *
 * The server holds every request to its table's template, so the checks here
 * are the friendly half only. A template never approves anything and never
 * changes who is asked.
 */

export interface RequestForm {
  /** Offered as a list; empty lets the requester type one, or none. */
  purposes: string[];
  purposeRequired: boolean;
  /** Day counts offered as one-click choices. */
  durations: number[];
  /** What the form starts on; null means until revoked. */
  defaultDays: number | null;
  /** The longest anybody may ask for; null means the platform's limit. */
  maxDays: number | null;
  allowUntilRevoked: boolean;
  /** "Change ticket", "DPIA no."; null asks for no reference. */
  referenceLabel: string | null;
  referenceRequired: boolean;
  minReasonLength: number;
  /** Plain text shown above the form. Never rendered as HTML. */
  guidance: string | null;
}

export interface RequestTemplate {
  /** Null for the built-in template. */
  id: string | null;
  name: string;
  description: string | null;
  /** Null for the whole organisation, and on the form a requester gets. */
  scopeFqn: string | null;
  /** Empty for every table under the scope, and on the form a requester gets. */
  matchFacets: string[];
  enabled: boolean;
  form: RequestForm;
}

export interface TemplateRow {
  template: RequestTemplate;
  createdBy: string;
  createdAt: string;
  updatedBy: string | null;
  updatedAt: string | null;
  canEdit: boolean;
}

export interface TemplateListing {
  templates: TemplateRow[];
  builtIn: RequestTemplate;
  canCreateDefault: boolean;
}

export interface TemplateDraft {
  name: string;
  description?: string | null;
  scopeFqn?: string | null;
  matchFacets?: string[];
  enabled?: boolean;
  form: RequestForm;
}

export interface TemplateChange {
  id: number;
  occurredAt: string;
  actor: string;
  action: string;
  scopeFqn: string | null;
}

/** The platform's longest request, the ceiling when a template sets none. */
export const MAX_DAYS = 365;
export const MAX_REFERENCE = 200;

export const TEMPLATES_KEY = ['request-templates'] as const;

export async function fetchTemplates(): Promise<TemplateListing> {
  const { data } = await apiClient.get<TemplateListing>('/v1/request-templates');
  return data;
}

/** The form a request on this table is asked on. Open to anyone signed in. */
export async function fetchEffectiveTemplate(assetFqn: string): Promise<RequestTemplate> {
  const { data } = await apiClient.get<RequestTemplate>(
    `/v1/request-templates/effective/${encodeURIComponent(assetFqn)}`
  );
  return data;
}

export async function createTemplate(draft: TemplateDraft): Promise<TemplateRow> {
  const { data } = await apiClient.post<TemplateRow>('/v1/request-templates', draft);
  return data;
}

export async function updateTemplate(id: string, draft: TemplateDraft): Promise<TemplateRow> {
  const { data } = await apiClient.put<TemplateRow>(
    `/v1/request-templates/${encodeURIComponent(id)}`,
    draft
  );
  return data;
}

export async function deleteTemplate(id: string): Promise<void> {
  await apiClient.delete(`/v1/request-templates/${encodeURIComponent(id)}`);
}

export async function fetchTemplateHistory(id: string): Promise<TemplateChange[]> {
  const { data } = await apiClient.get<TemplateChange[]>(
    `/v1/request-templates/${encodeURIComponent(id)}/history`
  );
  return data;
}

/** What the requester typed, as the form holds it. */
export interface Answers {
  reason: string;
  purpose: string | null;
  /** Null means until revoked. */
  days: number | null;
  reference: string;
}

/**
 * What is wrong with these answers under this form, or null.
 *
 * The same checks, in the same order, as the server's RequestTemplate.check,
 * so the form says it before the server has to.
 */
export function checkAnswers(form: RequestForm, answers: Answers): string | null {
  const reason = answers.reason.trim();
  if (reason.length === 0 || reason.length < form.minReasonLength) {
    return form.minReasonLength <= 1
      ? 'Say why you need it; the approvers decide on what you write here'
      : `Say why you need it in at least ${form.minReasonLength} characters`;
  }
  const purpose = answers.purpose?.trim() ?? '';
  if (purpose === '' && form.purposeRequired) {
    return 'Choose a purpose';
  }
  if (
    purpose !== '' &&
    form.purposes.length > 0 &&
    !form.purposes.some((p) => p.toLowerCase() === purpose.toLowerCase())
  ) {
    return `Choose one of the purposes offered: ${form.purposes.join(', ')}`;
  }
  if (answers.days === null && !form.allowUntilRevoked) {
    return 'Say how many days; access until revoked is not offered for this table';
  }
  const ceiling = form.maxDays ?? MAX_DAYS;
  if (answers.days !== null && (answers.days < 1 || answers.days > ceiling)) {
    return `Ask for between 1 and ${ceiling} days on this table`;
  }
  const reference = answers.reference.trim();
  if (reference === '' && form.referenceRequired) {
    return `Give the ${form.referenceLabel}`;
  }
  if (reference.length > MAX_REFERENCE) {
    return `Keep the ${form.referenceLabel ?? 'reference'} under ${MAX_REFERENCE} characters`;
  }
  return null;
}

/** The form in a sentence, for the list of templates. */
export function describeForm(form: RequestForm): string {
  const parts: string[] = [];
  if (form.purposes.length > 0) {
    parts.push(
      `${form.purposeRequired ? 'a purpose' : 'an optional purpose'} from ${form.purposes.length}`
    );
  } else if (form.purposeRequired) {
    parts.push('a purpose');
  }
  if (form.referenceLabel) {
    parts.push(`${form.referenceRequired ? '' : 'optional '}${form.referenceLabel}`.trim());
  }
  if (form.minReasonLength > 1) {
    parts.push(`a reason of ${form.minReasonLength}+ characters`);
  }
  const length = form.maxDays
    ? `up to ${form.maxDays} days`
    : form.allowUntilRevoked
      ? 'any length, or until revoked'
      : `up to ${MAX_DAYS} days`;
  return `Asks ${parts.length > 0 ? parts.join(', ') : 'a reason'}; ${length}.`;
}
