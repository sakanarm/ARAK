import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { AlertTriangle, Edit03, InfoCircle, Plus, Trash01, XClose } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { relativeTime } from '../../components/widgets';
import { apiErrorMessage } from '../../api/client';
import { fetchVocabulary, flatten } from '../../api/governance';
import {
  createTemplate,
  deleteTemplate,
  describeForm,
  fetchTemplateHistory,
  fetchTemplates,
  MAX_DAYS,
  TEMPLATES_KEY,
  updateTemplate,
  type RequestForm,
  type RequestTemplate,
  type TemplateDraft,
  type TemplateRow,
} from '../../api/requestTemplates';
import { useAuthStore } from '../../auth/authStore';
import { FIELD, Field } from '../policies/controls';
import { ScopePicker } from './pickers';

/**
 * Request templates: what the request access form asks, per table.
 *
 * <p>A template covers a scope, or the whole organisation, and optionally only
 * the tables under it that carry a tag, classification or glossary term. Of
 * the templates covering a table, one naming labels the table carries beats
 * one naming none, and then the deepest scope wins; with none, the built-in
 * form applies, so the page always ends with it.
 *
 * <p>A template decides what a requester must say and for how long they may
 * ask. It never decides who approves -- that is the workflow's -- and it never
 * grants anything. Guidance is plain text on both sides: it is typed here and
 * shown to the requester as text, never as HTML.
 */

const MAX_FACETS = 30;
const MAX_PURPOSES = 30;
const MAX_DURATIONS = 8;
const MAX_GUIDANCE = 2000;
const MAX_REASON_MINIMUM = 500;

/** What is being edited: a new template (no id) or an existing one. */
interface Editing {
  id: string | null;
  initial: RequestTemplate;
}

export default function RequestTemplatesPage() {
  const mayDesign = useAuthStore((state) =>
    state.hasRole('PLATFORM_ADMIN', 'POLICY_AUTHOR', 'DATA_OWNER')
  );
  const { data, isLoading, error } = useQuery({
    queryKey: TEMPLATES_KEY,
    queryFn: fetchTemplates,
    retry: false,
  });
  const [editing, setEditing] = useState<Editing | null>(null);

  const rows = [...(data?.templates ?? [])].sort((a, b) =>
    a.template.name.localeCompare(b.template.name)
  );
  const byLabel = rows.filter((row) => row.template.matchFacets.length > 0);
  const byScope = rows.filter((row) => row.template.matchFacets.length === 0);
  const canCreateDefault = data?.canCreateDefault ?? false;

  return (
    <div className="tw:flex tw:flex-col tw:gap-5">
      <header className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-4">
        <div>
          <h1 className="tw:text-xl tw:font-semibold tw:text-primary">Request templates</h1>
          <p className="tw:mt-1 tw:max-w-3xl tw:text-sm tw:text-tertiary">
            What somebody asking for access to a table must fill in: a purpose from a list, a
            reference such as a ticket or DPIA number, how long a reason, and how long they may ask
            for. A template naming tags or terms the table carries comes first, then the deepest
            scope. Who approves is the access workflow&rsquo;s to say, not the template&rsquo;s.
          </p>
        </div>
        {data && mayDesign && !editing && (
          <Button
            color="primary"
            iconLeading={Plus}
            onPress={() =>
              setEditing({
                id: null,
                initial: { ...data.builtIn, id: null, name: '', scopeFqn: null, enabled: true },
              })
            }
            size="sm">
            New template
          </Button>
        )}
      </header>

      {error && (
        <p
          className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
          role="alert">
          <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
          {apiErrorMessage(error, 'The templates could not be loaded.')}
        </p>
      )}
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading templates…</p>}

      {editing && editing.id === null && data && (
        <TemplateEditor
          canCreateDefault={canCreateDefault}
          editing={editing}
          onDone={() => setEditing(null)}
        />
      )}

      {data && (
        <>
          <Section
            blurb="Tables carrying one of the named tags, classifications or glossary terms, on a column or the table itself. These come first."
            heading="By tag or term">
            {byLabel.length === 0 ? (
              <Empty>No template is picked by a tag or term.</Empty>
            ) : (
              byLabel.map((row) => (
                <RowOrEditor editing={editing} key={row.template.id} onEdit={setEditing} row={row} />
              ))
            )}
          </Section>

          <Section
            blurb="Every table under a service, database, schema or table -- or, with no scope, the whole organisation. The deepest applies."
            heading="By scope">
            {byScope.length === 0 ? (
              <Empty>No scope has a template of its own.</Empty>
            ) : (
              byScope.map((row) => (
                <RowOrEditor editing={editing} key={row.template.id} onEdit={setEditing} row={row} />
              ))
            )}
          </Section>

          <Section
            blurb="Built into ARAK; it cannot be changed. An organisation-wide template replaces it."
            heading="When nothing else applies">
            <TemplateCard builtIn template={data.builtIn} />
          </Section>
        </>
      )}
    </div>
  );
}

function Section({
  heading,
  blurb,
  children,
}: {
  heading: string;
  blurb: string;
  children: React.ReactNode;
}) {
  return (
    <section aria-label={heading} className="tw:flex tw:flex-col tw:gap-3">
      <div>
        <h2 className="tw:text-md tw:font-semibold tw:text-primary">{heading}</h2>
        <p className="tw:text-sm tw:text-tertiary">{blurb}</p>
      </div>
      {children}
    </section>
  );
}

function Empty({ children }: { children: React.ReactNode }) {
  return (
    <p className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-4 tw:py-3 tw:text-sm tw:text-tertiary">
      {children}
    </p>
  );
}

function RowOrEditor({
  row,
  editing,
  onEdit,
}: {
  row: TemplateRow;
  editing: Editing | null;
  onEdit: (editing: Editing | null) => void;
}) {
  if (editing && editing.id === row.template.id) {
    return <TemplateEditor canCreateDefault={false} editing={editing} onDone={() => onEdit(null)} />;
  }
  return (
    <TemplateCard
      onEdit={editing ? undefined : () => onEdit({ id: row.template.id, initial: row.template })}
      row={row}
      template={row.template}
    />
  );
}

// ------------------------------------------------------------------ reading

function TemplateCard({
  template,
  row,
  builtIn = false,
  onEdit,
}: {
  template: RequestTemplate;
  row?: TemplateRow;
  builtIn?: boolean;
  onEdit?: () => void;
}) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [history, setHistory] = useState(false);
  const [preview, setPreview] = useState(false);
  const remove = useMutation({
    mutationFn: () => deleteTemplate(template.id!),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: TEMPLATES_KEY }),
  });

  return (
    <article
      aria-label={`Template ${template.name}`}
      className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
      <div className="tw:flex tw:flex-wrap tw:items-start tw:gap-3 tw:px-4 tw:py-3">
        <div className="tw:min-w-0 tw:flex-1">
          <p className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <span className="tw:text-sm tw:font-semibold tw:text-primary">{template.name}</span>
            {builtIn && (
              <Badge color="gray" size="sm" type="pill-color">
                Built-in
              </Badge>
            )}
            {!template.enabled && (
              <Badge color="gray" size="sm" type="pill-color">
                Off
              </Badge>
            )}
          </p>
          <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
            {builtIn ? (
              'Every table no template covers'
            ) : template.scopeFqn ? (
              <>
                Tables under <span className="tw:font-mono">{template.scopeFqn}</span>
              </>
            ) : (
              'Every table in the organisation'
            )}
            {template.matchFacets.length > 0 &&
              (template.scopeFqn ? ' that carry one of' : ' that carries one of')}
            {!template.enabled && ' · off, so the next template applies instead'}
          </p>
          {template.matchFacets.length > 0 && (
            <p className="tw:mt-1.5 tw:flex tw:flex-wrap tw:gap-1.5">
              {template.matchFacets.map((facet) => (
                <Badge color="brand" key={facet} size="sm" type="pill-color">
                  {facet}
                </Badge>
              ))}
            </p>
          )}
          {template.description && (
            <p className="tw:mt-1 tw:text-sm tw:text-secondary">{template.description}</p>
          )}
        </div>
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <Button color="secondary" onPress={() => setPreview((open) => !open)} size="sm">
            {preview ? 'Hide preview' : 'Preview'}
          </Button>
          {row && (
            <Button color="secondary" onPress={() => setHistory((open) => !open)} size="sm">
              {history ? 'Hide history' : 'History'}
            </Button>
          )}
          {row?.canEdit && onEdit && (
            <>
              <Button color="secondary" iconLeading={Edit03} onPress={onEdit} size="sm">
                Edit
              </Button>
              <Button
                color="secondary-destructive"
                iconLeading={Trash01}
                onPress={() => setConfirming(true)}
                size="sm">
                Delete
              </Button>
            </>
          )}
        </div>
      </div>

      <p className="tw:border-t tw:border-secondary tw:px-4 tw:py-3 tw:text-sm tw:text-secondary">
        {describeForm(template.form)}
      </p>

      {preview && (
        <div className="tw:border-t tw:border-secondary tw:px-4 tw:py-3">
          <FormPreview form={template.form} name={builtIn ? null : template.name} />
        </div>
      )}

      {confirming && (
        <div
          aria-label="Delete the template"
          className="tw:flex tw:flex-wrap tw:items-center tw:gap-3 tw:border-t tw:border-secondary tw:bg-utility-error-50 tw:px-4 tw:py-3"
          role="alertdialog">
          <p className="tw:min-w-0 tw:flex-1 tw:text-sm tw:text-secondary">
            Delete “{template.name}”? Requests already made keep what they were asked; new ones use
            the next template that applies.
          </p>
          <Button color="secondary" onPress={() => setConfirming(false)} size="sm">
            Keep it
          </Button>
          <Button
            color="secondary-destructive"
            isDisabled={remove.isPending}
            onPress={() => remove.mutate()}
            size="sm">
            Delete template
          </Button>
          {remove.isError && (
            <p className="tw:w-full tw:text-sm tw:text-error-primary" role="alert">
              {apiErrorMessage(remove.error, 'The template was not deleted.')}
            </p>
          )}
        </div>
      )}

      {row && history && <History id={template.id!} />}

      {row && (
        <p className="tw:border-t tw:border-secondary tw:px-4 tw:py-2 tw:text-xs tw:text-quaternary">
          {row.updatedAt && row.updatedBy
            ? `Changed by ${row.updatedBy} ${relativeTime(row.updatedAt)}`
            : `Created by ${row.createdBy} ${relativeTime(row.createdAt)}`}
        </p>
      )}
    </article>
  );
}

/**
 * The form as a requester will meet it, drawn from the template alone.
 *
 * <p>Not the request form itself -- that one sends -- but the same fields in
 * the same order, so an author sees what they are asking of people before
 * anybody has to fill it in.
 */
function FormPreview({ form, name }: { form: RequestForm; name: string | null }) {
  const ceiling = form.maxDays ?? MAX_DAYS;
  return (
    <div
      aria-label="What the requester sees"
      className="tw:flex tw:flex-col tw:gap-3 tw:rounded-lg tw:bg-secondary tw:p-3"
      role="region">
      <p className="tw:flex tw:items-center tw:gap-2 tw:text-xs tw:font-semibold tw:text-tertiary">
        What the requester sees
        {name && (
          <Badge color="gray" size="sm" type="pill-color">
            {name}
          </Badge>
        )}
      </p>
      {form.guidance && (
        <p
          aria-label="Guidance"
          className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:whitespace-pre-line tw:text-secondary"
          role="note">
          <InfoCircle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-quaternary" />
          <span>{form.guidance}</span>
        </p>
      )}
      <dl className="tw:grid tw:gap-x-4 tw:gap-y-2 tw:text-sm tw:sm:grid-cols-[10rem_1fr]">
        <dt className="tw:text-tertiary">Reason</dt>
        <dd className="tw:text-primary">
          Required{form.minReasonLength > 1 && `, at least ${form.minReasonLength} characters`}
        </dd>
        {(form.purposes.length > 0 || form.purposeRequired) && (
          <>
            <dt className="tw:text-tertiary">Purpose</dt>
            <dd className="tw:text-primary">
              {form.purposes.length > 0
                ? `${form.purposeRequired ? 'One of' : 'Optionally one of'}: ${form.purposes.join(', ')}`
                : 'Required, typed'}
            </dd>
          </>
        )}
        {form.referenceLabel && (
          <>
            <dt className="tw:text-tertiary">{form.referenceLabel}</dt>
            <dd className="tw:text-primary">{form.referenceRequired ? 'Required' : 'Optional'}</dd>
          </>
        )}
        <dt className="tw:text-tertiary">How long</dt>
        <dd className="tw:flex tw:flex-wrap tw:items-center tw:gap-1.5 tw:text-primary">
          {form.durations.map((days) => (
            <span
              className={`tw:rounded-md tw:border tw:px-2 tw:py-0.5 tw:text-xs ${
                days === form.defaultDays
                  ? 'tw:border-brand tw:text-brand-secondary'
                  : 'tw:border-secondary tw:text-tertiary'
              }`}
              key={days}>
              {days} days
            </span>
          ))}
          {form.allowUntilRevoked && (
            <span
              className={`tw:rounded-md tw:border tw:px-2 tw:py-0.5 tw:text-xs ${
                form.defaultDays === null
                  ? 'tw:border-brand tw:text-brand-secondary'
                  : 'tw:border-secondary tw:text-tertiary'
              }`}>
              Until revoked
            </span>
          )}
          <span className="tw:text-xs tw:text-tertiary">
            {form.defaultDays === null ? 'starts on until revoked' : `starts on ${form.defaultDays} days`}
            {' · '}at most {ceiling} days{form.allowUntilRevoked ? ', or until revoked' : ''}
          </span>
        </dd>
      </dl>
    </div>
  );
}

const ACTIONS: Record<string, string> = {
  CREATE: 'created it',
  UPDATE: 'changed it',
  DELETE: 'deleted it',
};

function History({ id }: { id: string }) {
  const { data, isLoading, error } = useQuery({
    queryKey: [...TEMPLATES_KEY, 'history', id],
    queryFn: () => fetchTemplateHistory(id),
  });
  return (
    <div aria-label="History" className="tw:border-t tw:border-secondary tw:px-4 tw:py-3" role="region">
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading…</p>}
      {error && (
        <p className="tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'The history could not be loaded.')}
        </p>
      )}
      {data && data.length === 0 && <p className="tw:text-sm tw:text-tertiary">No changes recorded.</p>}
      {data && data.length > 0 && (
        <ul className="tw:flex tw:flex-col tw:gap-1">
          {data.map((change) => (
            <li className="tw:text-sm tw:text-secondary" key={change.id}>
              <span className="tw:font-medium tw:text-primary">{change.actor}</span>{' '}
              {ACTIONS[change.action] ?? change.action.toLowerCase()}
              {change.scopeFqn && (
                <>
                  {' '}
                  on <span className="tw:font-mono tw:text-xs">{change.scopeFqn}</span>
                </>
              )}{' '}
              <span className="tw:text-xs tw:text-tertiary">{relativeTime(change.occurredAt)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ------------------------------------------------------------------ editing

/** Whole days typed into a box: "" is null, anything else its digits. */
function daysOf(text: string): number | null {
  const digits = text.replace(/[^0-9]/g, '');
  return digits === '' ? null : Number.parseInt(digits, 10);
}

/** "7, 30, 90" as the durations it names, sorted, repeats dropped. */
export function parseDurations(text: string): number[] {
  const days = text
    .split(/[\s,]+/)
    .map((part) => part.replace(/[^0-9]/g, ''))
    .filter((part) => part !== '')
    .map((part) => Number.parseInt(part, 10));
  return [...new Set(days)].sort((a, b) => a - b);
}

/** One purpose a line, blanks and repeats (in any case) dropped. */
export function parsePurposes(text: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const line of text.split(String.fromCharCode(10))) {
    const purpose = line.trim();
    if (purpose && !seen.has(purpose.toLowerCase())) {
      seen.add(purpose.toLowerCase());
      out.push(purpose);
    }
  }
  return out;
}

/** The first thing the server would refuse, said the same way, or null. */
export function problemOf(draft: TemplateDraft, scopeRequired: boolean): string | null {
  const form = draft.form;
  if (!draft.name.trim()) return 'Name the template';
  if (scopeRequired && !draft.scopeFqn) return 'Choose the tables it applies to';
  if ((draft.matchFacets ?? []).length > MAX_FACETS) return `Match at most ${MAX_FACETS} tags or terms`;
  if (form.purposes.some((purpose) => purpose.length > 100)) return 'Keep each purpose under 100 characters';
  if (form.purposes.length > MAX_PURPOSES) return `Offer at most ${MAX_PURPOSES} purposes`;
  if (form.maxDays !== null && (form.maxDays < 1 || form.maxDays > MAX_DAYS)) {
    return `The longest a request may ask for is between 1 and ${MAX_DAYS} days`;
  }
  const ceiling = form.maxDays ?? MAX_DAYS;
  const outside = form.durations.find((days) => days < 1 || days > ceiling);
  if (outside !== undefined) {
    return `Every suggested duration must be between 1 and ${ceiling} days; ${outside} is not`;
  }
  if (form.durations.length > MAX_DURATIONS) return `Suggest at most ${MAX_DURATIONS} durations`;
  if (form.maxDays !== null && form.allowUntilRevoked) {
    return `A request cannot both be limited to ${form.maxDays} days and last until revoked; choose one`;
  }
  if (form.defaultDays !== null && (form.defaultDays < 1 || form.defaultDays > ceiling)) {
    return `The duration the form starts on must be between 1 and ${ceiling} days`;
  }
  if (form.defaultDays === null && !form.allowUntilRevoked) {
    return 'Choose the duration the form starts on';
  }
  if (form.referenceLabel && form.referenceLabel.length > 60) {
    return "Keep the reference's label under 60 characters";
  }
  if (!form.referenceLabel && form.referenceRequired) {
    return 'Name the reference before making it required';
  }
  if (form.minReasonLength < 1 || form.minReasonLength > MAX_REASON_MINIMUM) {
    return `The shortest reason must be between 1 and ${MAX_REASON_MINIMUM} characters`;
  }
  if (form.guidance && form.guidance.length > MAX_GUIDANCE) {
    return `Keep the guidance under ${MAX_GUIDANCE.toLocaleString('en')} characters`;
  }
  return null;
}

function TemplateEditor({
  editing,
  canCreateDefault,
  onDone,
}: {
  editing: Editing;
  /** An administrator's template may cover the whole organisation. */
  canCreateDefault: boolean;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const isNew = editing.id === null;
  const start = editing.initial;
  const orgWide = !isNew && start.scopeFqn === null;
  const [name, setName] = useState(start.name);
  const [description, setDescription] = useState(start.description ?? '');
  const [scopeFqn, setScopeFqn] = useState<string | null>(start.scopeFqn || null);
  const [facets, setFacets] = useState<string[]>(start.matchFacets);
  const [enabled, setEnabled] = useState(start.enabled);
  const [purposes, setPurposes] = useState(start.form.purposes.join(String.fromCharCode(10)));
  const [purposeRequired, setPurposeRequired] = useState(start.form.purposeRequired);
  const [durations, setDurations] = useState(start.form.durations.join(', '));
  const [defaultDays, setDefaultDays] = useState(start.form.defaultDays === null ? '' : String(start.form.defaultDays));
  const [maxDays, setMaxDays] = useState(start.form.maxDays === null ? '' : String(start.form.maxDays));
  const [untilRevoked, setUntilRevoked] = useState(start.form.allowUntilRevoked);
  const [referenceLabel, setReferenceLabel] = useState(start.form.referenceLabel ?? '');
  const [referenceRequired, setReferenceRequired] = useState(start.form.referenceRequired);
  const [minReason, setMinReason] = useState(String(start.form.minReasonLength));
  const [guidance, setGuidance] = useState(start.form.guidance ?? '');

  const form: RequestForm = {
    purposes: parsePurposes(purposes),
    purposeRequired,
    durations: parseDurations(durations),
    defaultDays: daysOf(defaultDays),
    maxDays: daysOf(maxDays),
    allowUntilRevoked: untilRevoked,
    referenceLabel: referenceLabel.trim() || null,
    referenceRequired: referenceRequired && referenceLabel.trim() !== '',
    minReasonLength: daysOf(minReason) ?? 0,
    guidance: guidance.trim() || null,
  };
  const draft: TemplateDraft = {
    name: name.trim(),
    description: description.trim() || null,
    scopeFqn: orgWide ? null : scopeFqn,
    matchFacets: facets,
    enabled,
    form,
  };
  const problem = problemOf(draft, !orgWide && !canCreateDefault);

  const save = useMutation({
    mutationFn: () => (isNew ? createTemplate(draft) : updateTemplate(editing.id!, draft)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: TEMPLATES_KEY });
      onDone();
    },
  });

  const title = isNew ? 'New template' : `Edit ${start.name}`;
  return (
    <form
      aria-label={title}
      className="tw:rounded-xl tw:border tw:border-brand tw:bg-primary tw:shadow-md"
      onSubmit={(event) => {
        event.preventDefault();
        if (!problem && !save.isPending) save.mutate();
      }}>
      <div className="tw:border-b tw:border-secondary tw:px-4 tw:py-3">
        <p className="tw:text-sm tw:font-semibold tw:text-primary">{title}</p>
      </div>

      <div className="tw:grid tw:gap-5 tw:px-4 tw:py-4 tw:xl:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="tw:flex tw:min-w-0 tw:flex-col tw:gap-4">
          <div className="tw:grid tw:gap-4 tw:md:grid-cols-2">
            <Field label="Name">
              <input
                aria-label="Template name"
                className={FIELD}
                onChange={(event) => setName(event.target.value)}
                placeholder="PII tables"
                value={name}
              />
            </Field>
            <div className="tw:flex tw:flex-col tw:gap-1.5">
              <span className="tw:text-sm tw:font-medium tw:text-secondary">Applies to</span>
              {orgWide ? (
                <p className="tw:text-sm tw:text-tertiary">Every table in the organisation.</p>
              ) : (
                <>
                  <ScopePicker onChange={setScopeFqn} value={scopeFqn} />
                  <span className="tw:text-xs tw:text-tertiary">
                    {canCreateDefault
                      ? 'Leave empty for the whole organisation. A scope covers everything under it.'
                      : 'A service, database, schema or table you govern; it covers everything under it.'}
                  </span>
                </>
              )}
            </div>
          </div>

          <FacetsEditor onChange={setFacets} value={facets} />

          <Field label="Description">
            <textarea
              aria-label="Template description"
              className={`${FIELD} tw:min-h-14 tw:resize-y`}
              onChange={(event) => setDescription(event.target.value)}
              placeholder="When this template is the right one, for whoever reads the list"
              value={description}
            />
          </Field>
          <label className="tw:flex tw:items-center tw:gap-2 tw:text-sm tw:text-secondary">
            <input checked={enabled} onChange={(event) => setEnabled(event.target.checked)} type="checkbox" />
            On — while off, requests use the next template that applies
          </label>

          <fieldset className="tw:flex tw:flex-col tw:gap-3">
            <legend className="tw:mb-2 tw:text-sm tw:font-medium tw:text-secondary">What it asks</legend>
            <div className="tw:grid tw:gap-4 tw:md:grid-cols-2">
              <Field
                hint="One a line. Empty lets the requester type one, or none."
                label="Purposes offered">
                <textarea
                  aria-label="Purposes offered"
                  className={`${FIELD} tw:min-h-24 tw:resize-y`}
                  onChange={(event) => setPurposes(event.target.value)}
                  placeholder={['Fraud investigation', 'Regulatory report'].join(String.fromCharCode(10))}
                  value={purposes}
                />
              </Field>
              <div className="tw:flex tw:flex-col tw:gap-3">
                <label className="tw:flex tw:items-center tw:gap-2 tw:text-sm tw:text-secondary">
                  <input
                    checked={purposeRequired}
                    onChange={(event) => setPurposeRequired(event.target.checked)}
                    type="checkbox"
                  />
                  A purpose is required
                </label>
                <Field hint="The shortest reason the form accepts." label="Reason, at least (characters)">
                  <input
                    aria-label="Shortest reason"
                    className={`${FIELD} tw:w-28`}
                    inputMode="numeric"
                    onChange={(event) => setMinReason(event.target.value.replace(/[^0-9]/g, ''))}
                    value={minReason}
                  />
                </Field>
              </div>
            </div>
            <div className="tw:grid tw:gap-4 tw:md:grid-cols-2">
              <Field hint='"Change ticket", "DPIA number". Empty asks for none.' label="Reference">
                <input
                  aria-label="Reference label"
                  className={FIELD}
                  onChange={(event) => setReferenceLabel(event.target.value)}
                  value={referenceLabel}
                />
              </Field>
              <label className="tw:flex tw:items-center tw:gap-2 tw:self-center tw:text-sm tw:text-secondary">
                <input
                  checked={referenceRequired && referenceLabel.trim() !== ''}
                  disabled={referenceLabel.trim() === ''}
                  onChange={(event) => setReferenceRequired(event.target.checked)}
                  type="checkbox"
                />
                The reference is required
              </label>
            </div>
          </fieldset>

          <fieldset className="tw:flex tw:flex-col tw:gap-3">
            <legend className="tw:mb-2 tw:text-sm tw:font-medium tw:text-secondary">How long</legend>
            <div className="tw:grid tw:gap-4 tw:md:grid-cols-3">
              <Field hint="Days, offered as one-click choices." label="Suggested durations">
                <input
                  aria-label="Suggested durations"
                  className={FIELD}
                  onChange={(event) => setDurations(event.target.value)}
                  placeholder="7, 30, 90"
                  value={durations}
                />
              </Field>
              <Field hint={`Empty for the platform's ${MAX_DAYS} days.`} label="At most (days)">
                <input
                  aria-label="Longest duration"
                  className={FIELD}
                  inputMode="numeric"
                  onChange={(event) => {
                    const next = event.target.value.replace(/[^0-9]/g, '');
                    setMaxDays(next);
                    // A limit and "until revoked" contradict each other.
                    if (next !== '') setUntilRevoked(false);
                  }}
                  value={maxDays}
                />
              </Field>
              <Field
                hint={untilRevoked ? 'Empty starts on until revoked.' : 'What the form starts on.'}
                label="Starts on (days)">
                <input
                  aria-label="Default duration"
                  className={FIELD}
                  inputMode="numeric"
                  onChange={(event) => setDefaultDays(event.target.value.replace(/[^0-9]/g, ''))}
                  value={defaultDays}
                />
              </Field>
            </div>
            <label className="tw:flex tw:items-center tw:gap-2 tw:text-sm tw:text-secondary">
              <input
                checked={untilRevoked}
                disabled={maxDays !== ''}
                onChange={(event) => setUntilRevoked(event.target.checked)}
                type="checkbox"
              />
              Access until revoked may be asked for
              {maxDays !== '' && (
                <span className="tw:text-xs tw:text-tertiary">— not with a longest duration</span>
              )}
            </label>
          </fieldset>

          <Field
            hint={`Shown above the form as plain text, never as HTML. Up to ${MAX_GUIDANCE.toLocaleString('en')} characters.`}
            label="Guidance for the requester">
            <textarea
              aria-label="Guidance"
              className={`${FIELD} tw:min-h-20 tw:resize-y`}
              onChange={(event) => setGuidance(event.target.value)}
              placeholder="What the approvers look for, and where to find the reference"
              value={guidance}
            />
          </Field>

          {save.isError && (
            <p
              className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
              role="alert">
              <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
              <span>{apiErrorMessage(save.error, 'The template was not saved.')}</span>
            </p>
          )}
        </div>

        <div className="tw:flex tw:flex-col tw:gap-2">
          <FormPreview form={form} name={draft.name || null} />
          <p className="tw:text-xs tw:text-tertiary">{describeForm(form)}</p>
        </div>
      </div>

      <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-end tw:gap-2 tw:border-t tw:border-secondary tw:px-4 tw:py-3">
        {problem && <span className="tw:mr-auto tw:text-xs tw:text-tertiary">{problem}.</span>}
        <Button color="secondary" onPress={onDone} size="sm">
          Cancel
        </Button>
        <Button color="primary" isDisabled={!!problem || save.isPending} size="sm" type="submit">
          {save.isPending ? 'Saving…' : isNew ? 'Create template' : 'Save template'}
        </Button>
      </div>
    </form>
  );
}

/**
 * The tags, classifications and glossary terms a template is picked by.
 *
 * <p>Typed, with the governance vocabulary offered, because a name is matched
 * segment by segment when a request is made -- "PII" covers "PII.Sensitive" --
 * and a classification that is not synced yet may still be worth naming.
 */
function FacetsEditor({ value, onChange }: { value: string[]; onChange: (facets: string[]) => void }) {
  const [text, setText] = useState('');
  const { data } = useQuery({
    queryKey: ['governance-vocabulary'],
    queryFn: fetchVocabulary,
    staleTime: 60 * 1000,
    retry: false,
  });
  const offered = data
    ? [...flatten(data.classifications), ...flatten(data.glossaries)].filter((v) => !v.disabled)
    : [];
  const add = () => {
    const facet = text.trim();
    if (facet && !value.some((v) => v.toLowerCase() === facet.toLowerCase()) && value.length < MAX_FACETS) {
      onChange([...value, facet]);
    }
    setText('');
  };
  return (
    <div className="tw:flex tw:flex-col tw:gap-1.5">
      <span className="tw:text-sm tw:font-medium tw:text-secondary">Only tables carrying</span>
      {value.length > 0 && (
        <ul aria-label="Tags and terms" className="tw:flex tw:flex-wrap tw:gap-1.5">
          {value.map((facet) => (
            <li
              className="tw:flex tw:items-center tw:gap-1 tw:rounded-md tw:border tw:border-secondary tw:bg-secondary tw:py-0.5 tw:pr-1 tw:pl-2 tw:text-sm tw:text-primary"
              key={facet}>
              {facet}
              <button
                aria-label={`Remove ${facet}`}
                className="tw:flex tw:size-5 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded tw:text-quaternary tw:hover:text-secondary"
                onClick={() => onChange(value.filter((v) => v !== facet))}
                type="button">
                <XClose className="tw:size-3.5" />
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <input
          aria-label="Add a tag or term"
          className={`${FIELD} tw:w-72`}
          list="template-facet-options"
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              add();
            }
          }}
          placeholder="PII, PII.Sensitive, Finance.CustomerIdentity"
          value={text}
        />
        <datalist id="template-facet-options">
          {offered.map((option) => (
            <option key={option.fqn} value={option.fqn}>
              {option.displayName ?? option.name}
            </option>
          ))}
        </datalist>
        <Button color="secondary" iconLeading={Plus} isDisabled={!text.trim()} onPress={add} size="sm">
          Add
        </Button>
      </div>
      <span className="tw:text-xs tw:text-tertiary">
        Empty for every table in scope. Otherwise a table, or one of its columns, must carry one of
        these; a classification covers every tag under it.
      </span>
    </div>
  );
}
