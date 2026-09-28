import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { AlertTriangle, Edit03, Plus } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { relativeTime } from '../../components/widgets';
import { apiErrorMessage } from '../../api/client';
import {
  createPurpose,
  fetchPurposeHistory,
  fetchPurposeUsage,
  LEGAL_BASES,
  legalBasisLabel,
  PURPOSE_KEY,
  PURPOSES_KEY,
  reinstatePurpose,
  retirePurpose,
  updatePurpose,
  usePurposes,
  type LegalBasis,
  type NewPurpose,
  type Purpose,
  type PurposeChange,
  type Usage,
} from '../../api/purposes';
import { useAuthStore } from '../../auth/authStore';
import { FIELD, Field, Select } from '../policies/controls';
import SensitiveDataSection from './SensitiveDataSection';

/**
 * The register of purposes (FR-21).
 *
 * <p>Policies, request templates, access requests and queries name a purpose
 * by key; this is where a key gets a name people pick by and the PDPA's
 * answers beside it -- the legal basis, whether special categories may be used
 * for it, who answers for it and how long access for it may last.
 *
 * <p>Nothing is deleted. A purpose is retired, with a reason, and what already
 * names it keeps the name; nothing new may ask for it. Every change is kept.
 */

const MAX_NAME = 120;
const MAX_DESCRIPTION = 2000;
const MAX_OWNER = 200;
const MAX_REASON = 500;
const MAX_PURPOSE_DAYS = 365;
const NO_BASIS = '';

/** What is being edited: a new purpose (no key yet) or an existing one. */
interface Editing {
  key: string | null;
  initial: NewPurpose;
}

const BLANK: NewPurpose = {
  key: '',
  name: '',
  description: null,
  legalBasis: null,
  sensitiveAllowed: false,
  owner: null,
  maxDays: null,
};

export default function PurposesPage() {
  const mayReview = useAuthStore((state) =>
    state.hasRole('PLATFORM_ADMIN', 'POLICY_AUTHOR', 'DATA_OWNER', 'AUDITOR')
  );
  const { data, isLoading, error } = usePurposes();
  const usage = useQuery({
    queryKey: [...PURPOSES_KEY, 'usage'],
    queryFn: fetchPurposeUsage,
    enabled: mayReview,
    retry: false,
  });
  const [editing, setEditing] = useState<Editing | null>(null);

  const canEdit = data?.canEdit ?? false;
  const all = [...(data?.purposes ?? [])].sort((a, b) => a.name.localeCompare(b.name));
  const active = all.filter((p) => p.status === 'ACTIVE');
  const retired = all.filter((p) => p.status === 'RETIRED');
  const unlisted = usage.data?.unlisted ?? [];

  return (
    <div className="tw:flex tw:flex-col tw:gap-5">
      <header className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-4">
        <div>
          <h1 className="tw:text-xl tw:font-semibold tw:text-primary">Purposes</h1>
          <p className="tw:mt-1 tw:max-w-3xl tw:text-sm tw:text-tertiary">
            What data may be used for. A policy can allow a table only for some of these, a request
            template offers them, and a request or a query names one. Each says what it rests on
            under the PDPA, whether sensitive data may be used for it, and how long access for it
            may last. A purpose is retired rather than deleted, so what already names it keeps the
            name.
          </p>
        </div>
        {canEdit && !editing && (
          <Button
            color="primary"
            iconLeading={Plus}
            onPress={() => setEditing({ key: null, initial: BLANK })}
            size="sm">
            New purpose
          </Button>
        )}
      </header>

      {error && (
        <p
          className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
          role="alert">
          <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
          {apiErrorMessage(error, 'The register could not be loaded.')}
        </p>
      )}
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading the register…</p>}

      {editing && editing.key === null && (
        <PurposeEditor editing={editing} onDone={() => setEditing(null)} />
      )}

      {data && (
        <>
          <Section
            blurb="What people may choose, on a policy, a template, a request or a query."
            heading="In use">
            {active.length === 0 ? (
              <Empty>The register lists no purpose yet.</Empty>
            ) : (
              active.map((purpose) => (
                <RowOrEditor
                  canEdit={canEdit}
                  editing={editing}
                  key={purpose.key}
                  mayReview={mayReview}
                  onEdit={setEditing}
                  purpose={purpose}
                  usage={usage.data?.listed[purpose.key]}
                />
              ))
            )}
          </Section>

          <SensitiveDataSection mayReview={mayReview} />

          {mayReview && unlisted.length > 0 && (
            <Section
              blurb="Named by a policy, a template, an open request or a recent query, but not in the register. What names them keeps working; nothing new may name them until they are listed."
              heading="Named but not listed">
              {unlisted.map((use) => (
                <Unlisted
                  canList={canEdit && !editing}
                  key={use.value}
                  onList={() =>
                    setEditing({
                      key: null,
                      initial: { ...BLANK, key: keyFor(use.value), name: use.value },
                    })
                  }
                  usage={use}
                />
              ))}
            </Section>
          )}

          {retired.length > 0 && (
            <Section
              blurb="Kept so what already names them still reads. Nothing new may ask for them."
              heading="Retired">
              {retired.map((purpose) => (
                <RowOrEditor
                  canEdit={canEdit}
                  editing={editing}
                  key={purpose.key}
                  mayReview={mayReview}
                  onEdit={setEditing}
                  purpose={purpose}
                  usage={usage.data?.listed[purpose.key]}
                />
              ))}
            </Section>
          )}
        </>
      )}
    </div>
  );
}

/** A value somebody typed, as the key the register would give it. */
export function keyFor(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, '-')
    .replace(/^[^a-z0-9]+/, '')
    .replace(/-+$/, '')
    .slice(0, 63);
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
  purpose,
  usage,
  canEdit,
  mayReview,
  editing,
  onEdit,
}: {
  purpose: Purpose;
  usage: Usage | undefined;
  canEdit: boolean;
  mayReview: boolean;
  editing: Editing | null;
  onEdit: (editing: Editing | null) => void;
}) {
  if (editing && editing.key === purpose.key) {
    return <PurposeEditor editing={editing} onDone={() => onEdit(null)} />;
  }
  return (
    <PurposeCard
      canEdit={canEdit && !editing}
      mayReview={mayReview}
      onEdit={() => onEdit({ key: purpose.key, initial: purpose })}
      purpose={purpose}
      usage={usage}
    />
  );
}

// ------------------------------------------------------------------ reading

/** "2 policies · 1 template · 3 open requests · 14 queries in 90 days", or null. */
export function describeUsage(usage: Usage | undefined): string | null {
  if (!usage) return null;
  const parts = [
    counted(usage.policies, 'policy', 'policies'),
    counted(usage.templates, 'template', 'templates'),
    counted(usage.openRequests, 'open request', 'open requests'),
    counted(usage.recentQueries, 'query in 90 days', 'queries in 90 days'),
  ].filter(Boolean);
  return parts.length > 0 ? parts.join(' · ') : 'Not named anywhere yet';
}

function counted(n: number, one: string, many: string): string | null {
  if (n <= 0) return null;
  return `${n.toLocaleString('en')} ${n === 1 ? one : many}`;
}

function PurposeCard({
  purpose,
  usage,
  canEdit,
  mayReview,
  onEdit,
}: {
  purpose: Purpose;
  usage: Usage | undefined;
  canEdit: boolean;
  mayReview: boolean;
  onEdit: () => void;
}) {
  const [history, setHistory] = useState(false);
  const [changing, setChanging] = useState(false);
  const basis = legalBasisLabel(purpose.legalBasis);
  const retired = purpose.status === 'RETIRED';
  const used = describeUsage(usage);

  return (
    <article
      aria-label={`Purpose ${purpose.name}`}
      className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
      <div className="tw:flex tw:flex-wrap tw:items-start tw:gap-3 tw:px-4 tw:py-3">
        <div className="tw:min-w-0 tw:flex-1">
          <p className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <span className="tw:text-sm tw:font-semibold tw:text-primary">{purpose.name}</span>
            <span className="tw:font-mono tw:text-xs tw:text-tertiary">{purpose.key}</span>
            {retired && (
              <Badge color="gray" size="sm" type="pill-color">
                Retired
              </Badge>
            )}
          </p>
          <p className="tw:mt-1.5 tw:flex tw:flex-wrap tw:gap-1.5">
            <Badge color={basis ? 'brand' : 'warning'} size="sm" type="pill-color">
              {basis ?? 'No legal basis said'}
            </Badge>
            {purpose.sensitiveAllowed && (
              <Badge color="warning" size="sm" type="pill-color">
                Sensitive data allowed
              </Badge>
            )}
            {purpose.maxDays !== null && (
              <Badge color="gray" size="sm" type="pill-color">
                At most {purpose.maxDays} days
              </Badge>
            )}
          </p>
          {purpose.description && (
            <p className="tw:mt-1.5 tw:text-sm tw:whitespace-pre-line tw:text-secondary">
              {purpose.description}
            </p>
          )}
          <p className="tw:mt-1 tw:text-xs tw:text-tertiary">
            {purpose.owner ? `Answered for by ${purpose.owner}` : 'Nobody named to answer for it'}
            {used && ` · ${used}`}
          </p>
        </div>
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          {mayReview && (
            <Button color="secondary" onPress={() => setHistory((open) => !open)} size="sm">
              {history ? 'Hide history' : 'History'}
            </Button>
          )}
          {canEdit && (
            <>
              <Button color="secondary" iconLeading={Edit03} onPress={onEdit} size="sm">
                Edit
              </Button>
              <Button
                color={retired ? 'secondary' : 'secondary-destructive'}
                onPress={() => setChanging(true)}
                size="sm">
                {retired ? 'Reinstate' : 'Retire'}
              </Button>
            </>
          )}
        </div>
      </div>

      {changing && <StatusChange onDone={() => setChanging(false)} purpose={purpose} />}

      {history && <History purposeKey={purpose.key} />}

      <p className="tw:border-t tw:border-secondary tw:px-4 tw:py-2 tw:text-xs tw:text-quaternary">
        {purpose.updatedAt !== purpose.createdAt
          ? `Changed by ${purpose.updatedBy} ${relativeTime(purpose.updatedAt)}`
          : `Listed by ${purpose.createdBy} ${relativeTime(purpose.createdAt)}`}
      </p>
    </article>
  );
}

/** Retiring or reinstating, which always says why. */
function StatusChange({ purpose, onDone }: { purpose: Purpose; onDone: () => void }) {
  const queryClient = useQueryClient();
  const retiring = purpose.status === 'ACTIVE';
  const [reason, setReason] = useState('');
  const change = useMutation({
    mutationFn: () =>
      retiring
        ? retirePurpose(purpose.key, reason.trim())
        : reinstatePurpose(purpose.key, reason.trim()),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: PURPOSES_KEY });
      onDone();
    },
  });
  const tooLong = reason.trim().length > MAX_REASON;

  return (
    <div
      aria-label={retiring ? 'Retire the purpose' : 'Reinstate the purpose'}
      className={`tw:flex tw:flex-col tw:gap-2 tw:border-t tw:border-secondary tw:px-4 tw:py-3 ${
        retiring ? 'tw:bg-utility-error-50' : 'tw:bg-secondary'
      }`}
      role="alertdialog">
      <p className="tw:text-sm tw:text-secondary">
        {retiring
          ? `Retire “${purpose.name}”? Policies, templates and requests that name it keep the name, but nothing new may ask for it and a query naming it is refused.`
          : `Reinstate “${purpose.name}”? It can be chosen again everywhere.`}
      </p>
      <Field hint={`Kept in the history. Up to ${MAX_REASON} characters.`} label="Why">
        <textarea
          aria-label="Why"
          className={`${FIELD} tw:min-h-14 tw:resize-y`}
          onChange={(event) => setReason(event.target.value)}
          value={reason}
        />
      </Field>
      <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-end tw:gap-2">
        {tooLong && (
          <span className="tw:mr-auto tw:text-xs tw:text-tertiary">
            Keep the reason to {MAX_REASON} characters.
          </span>
        )}
        <Button color="secondary" onPress={onDone} size="sm">
          Cancel
        </Button>
        <Button
          color={retiring ? 'secondary-destructive' : 'primary'}
          isDisabled={!reason.trim() || tooLong || change.isPending}
          onPress={() => change.mutate()}
          size="sm">
          {retiring ? 'Retire purpose' : 'Reinstate purpose'}
        </Button>
      </div>
      {change.isError && (
        <p className="tw:text-sm tw:text-error-primary" role="alert">
          {apiErrorMessage(change.error, retiring ? 'The purpose was not retired.' : 'The purpose was not reinstated.')}
        </p>
      )}
    </div>
  );
}

const ACTIONS: Record<string, string> = {
  CREATE: 'listed it',
  UPDATE: 'changed',
  RETIRE: 'retired it',
  REINSTATE: 'reinstated it',
};

const FIELD_NAMES: [keyof Purpose, string][] = [
  ['name', 'the name'],
  ['description', 'the description'],
  ['legalBasis', 'the legal basis'],
  ['sensitiveAllowed', 'sensitive data'],
  ['owner', 'who answers for it'],
  ['maxDays', 'the longest access'],
];

/** Which details a change touched, in the order the form shows them. */
export function changedFields(change: Pick<PurposeChange, 'before' | 'after'>): string[] {
  if (!change.before || !change.after) return [];
  return FIELD_NAMES.filter(
    ([field]) => (change.before?.[field] ?? null) !== (change.after?.[field] ?? null)
  ).map(([, label]) => label);
}

function History({ purposeKey }: { purposeKey: string }) {
  const { data, isLoading, error } = useQuery({
    queryKey: [...PURPOSES_KEY, 'history', purposeKey],
    queryFn: () => fetchPurposeHistory(purposeKey),
    retry: false,
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
        <ul className="tw:flex tw:flex-col tw:gap-1.5">
          {data.map((change) => {
            const fields = change.action === 'UPDATE' ? changedFields(change) : [];
            return (
              <li className="tw:text-sm tw:text-secondary" key={change.id}>
                <span className="tw:font-medium tw:text-primary">{change.actor}</span>{' '}
                {ACTIONS[change.action] ?? change.action.toLowerCase()}
                {change.action === 'UPDATE' && (fields.length > 0 ? ` ${fields.join(', ')}` : ' it')}{' '}
                <span className="tw:text-xs tw:text-tertiary">{relativeTime(change.occurredAt)}</span>
                {change.reason && (
                  <span className="tw:block tw:text-xs tw:text-tertiary">“{change.reason}”</span>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function Unlisted({
  usage,
  canList,
  onList,
}: {
  usage: Usage;
  canList: boolean;
  onList: () => void;
}) {
  return (
    <article
      aria-label={`Unlisted ${usage.value}`}
      className="tw:flex tw:flex-wrap tw:items-center tw:gap-3 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-4 tw:py-3 tw:shadow-xs">
      <div className="tw:min-w-0 tw:flex-1">
        <p className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <span className="tw:text-sm tw:font-semibold tw:text-primary">{usage.value}</span>
          <Badge color="warning" size="sm" type="pill-color">
            Not in the register
          </Badge>
        </p>
        <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">{describeUsage(usage)}</p>
      </div>
      {canList && (
        <Button color="secondary" iconLeading={Plus} onPress={onList} size="sm">
          List it
        </Button>
      )}
    </article>
  );
}

// ------------------------------------------------------------------ editing

/** The first thing the server would refuse, said the same way, or null. */
export function purposeProblem(draft: NewPurpose, isNew: boolean): string | null {
  const key = draft.key.trim().toLowerCase();
  if (isNew && !key) return 'Give it a key: it is what policies and requests store';
  if (isNew && !PURPOSE_KEY.test(key)) {
    return 'A key is up to 63 lower-case letters, digits, dots, dashes and underscores, starting with a letter or digit';
  }
  const name = draft.name.trim();
  if (!name) return 'Name it: the name is what people pick from';
  if (name.length > MAX_NAME) return `Keep the name to ${MAX_NAME} characters`;
  if ((draft.description ?? '').trim().length > MAX_DESCRIPTION) {
    return `Keep the description to ${MAX_DESCRIPTION.toLocaleString('en')} characters`;
  }
  if ((draft.owner ?? '').trim().length > MAX_OWNER) return `Keep the owner to ${MAX_OWNER} characters`;
  if (draft.maxDays !== null && (draft.maxDays < 1 || draft.maxDays > MAX_PURPOSE_DAYS)) {
    return `The longest access for a purpose is between 1 and ${MAX_PURPOSE_DAYS} days`;
  }
  return null;
}

function PurposeEditor({ editing, onDone }: { editing: Editing; onDone: () => void }) {
  const queryClient = useQueryClient();
  const isNew = editing.key === null;
  const start = editing.initial;
  const [key, setKey] = useState(start.key);
  const [name, setName] = useState(start.name);
  const [description, setDescription] = useState(start.description ?? '');
  const [legalBasis, setLegalBasis] = useState<string>(start.legalBasis ?? NO_BASIS);
  const [sensitive, setSensitive] = useState(start.sensitiveAllowed);
  const [owner, setOwner] = useState(start.owner ?? '');
  const [maxDays, setMaxDays] = useState(start.maxDays === null ? '' : String(start.maxDays));

  const draft: NewPurpose = {
    key: key.trim().toLowerCase(),
    name: name.trim(),
    description: description.trim() || null,
    legalBasis: legalBasis === NO_BASIS ? null : (legalBasis as LegalBasis),
    sensitiveAllowed: sensitive,
    owner: owner.trim() || null,
    maxDays: maxDays === '' ? null : Number.parseInt(maxDays, 10),
  };
  const problem = purposeProblem(draft, isNew);

  const save = useMutation({
    mutationFn: () => {
      if (isNew) return createPurpose(draft);
      return updatePurpose(editing.key!, {
        name: draft.name,
        description: draft.description,
        legalBasis: draft.legalBasis,
        sensitiveAllowed: draft.sensitiveAllowed,
        owner: draft.owner,
        maxDays: draft.maxDays,
      });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: PURPOSES_KEY });
      onDone();
    },
  });

  const title = isNew ? 'New purpose' : `Edit ${start.name}`;
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

      <div className="tw:flex tw:flex-col tw:gap-4 tw:px-4 tw:py-4">
        <div className="tw:grid tw:gap-4 tw:md:grid-cols-2">
          <Field hint="What people pick from." label="Name">
            <input
              aria-label="Purpose name"
              className={FIELD}
              onChange={(event) => setName(event.target.value)}
              placeholder="Fraud analysis"
              value={name}
            />
          </Field>
          {isNew ? (
            <Field
              hint="What policies, requests and queries store. Lower case; it cannot change later."
              label="Key">
              <input
                aria-label="Purpose key"
                className={`${FIELD} tw:font-mono`}
                onChange={(event) => setKey(event.target.value)}
                placeholder="fraud-analysis"
                value={key}
              />
            </Field>
          ) : (
            <div className="tw:flex tw:flex-col tw:gap-1.5">
              <span className="tw:text-sm tw:font-medium tw:text-secondary">Key</span>
              <span className="tw:font-mono tw:text-sm tw:text-primary">{editing.key}</span>
              <span className="tw:text-xs tw:text-tertiary">
                Fixed, because policies and requests store it.
              </span>
            </div>
          )}
        </div>

        <Field label="What it is for">
          <textarea
            aria-label="Purpose description"
            className={`${FIELD} tw:min-h-16 tw:resize-y`}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="Investigating suspected fraud on a customer's account"
            value={description}
          />
        </Field>

        <div className="tw:grid tw:gap-4 tw:md:grid-cols-2">
          <Field
            hint={
              LEGAL_BASES.find((b) => b.value === legalBasis)?.hint ??
              'Section 24 of the PDPA, or consent under section 19.'
            }
            label="Legal basis">
            <Select
              ariaLabel="Legal basis"
              onChange={setLegalBasis}
              options={[
                { value: NO_BASIS, label: 'Not said yet' },
                ...LEGAL_BASES.map((b) => ({ value: b.value, label: b.label, hint: b.hint })),
              ]}
              value={legalBasis}
            />
          </Field>
          <Field hint="A person or a team. Shown to approvers." label="Answered for by">
            <input
              aria-label="Purpose owner"
              className={FIELD}
              onChange={(event) => setOwner(event.target.value)}
              placeholder="Risk team"
              value={owner}
            />
          </Field>
        </div>

        <div className="tw:grid tw:gap-4 tw:md:grid-cols-2">
          <Field
            hint={`Empty for no limit of its own. A request for this purpose may ask for at most this many days, and not until revoked. Up to ${MAX_PURPOSE_DAYS}.`}
            label="Longest access (days)">
            <input
              aria-label="Longest access"
              className={`${FIELD} tw:w-28`}
              inputMode="numeric"
              onChange={(event) => setMaxDays(event.target.value.replace(/[^0-9]/g, ''))}
              value={maxDays}
            />
          </Field>
          <label className="tw:flex tw:items-start tw:gap-2 tw:self-center tw:text-sm tw:text-secondary">
            <input
              checked={sensitive}
              className="tw:mt-0.5"
              onChange={(event) => setSensitive(event.target.checked)}
              type="checkbox"
            />
            <span>
              Sensitive data may be used for it
              <span className="tw:block tw:text-xs tw:text-tertiary">
                Section 26: health, religion, biometrics, criminal records and the like.
              </span>
            </span>
          </label>
        </div>

        {save.isError && (
          <p
            className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
            role="alert">
            <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
            <span>{apiErrorMessage(save.error, 'The purpose was not saved.')}</span>
          </p>
        )}
      </div>

      <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-end tw:gap-2 tw:border-t tw:border-secondary tw:px-4 tw:py-3">
        {problem && <span className="tw:mr-auto tw:text-xs tw:text-tertiary">{problem}.</span>}
        <Button color="secondary" onPress={onDone} size="sm">
          Cancel
        </Button>
        <Button color="primary" isDisabled={!!problem || save.isPending} size="sm" type="submit">
          {save.isPending ? 'Saving…' : isNew ? 'List purpose' : 'Save purpose'}
        </Button>
      </div>
    </form>
  );
}
