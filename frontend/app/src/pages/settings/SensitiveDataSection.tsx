import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { AlertTriangle, Edit03, Plus } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { relativeTime } from '../../components/widgets';
import { apiErrorMessage } from '../../api/client';
import { fetchVocabulary, type GovernanceValue, type Vocabulary } from '../../api/governance';
import {
  describeChange,
  fetchSensitiveHistory,
  kindLabel,
  LABEL_KINDS,
  MAX_LABEL_FQN,
  MODES,
  previewSensitiveData,
  ruleProblem,
  sameLabel,
  sameSettings,
  SENSITIVE_KEY,
  updateSensitiveData,
  useSensitiveData,
  type Coverage,
  type LabelKind,
  type SensitiveLabel,
  type SensitiveMode,
  type SensitiveRule,
  type SensitiveSettings,
} from '../../api/sensitiveData';
import { FIELD, Field, Select } from '../policies/controls';

/**
 * What counts as sensitive data (FR-21, M31b), on the purposes page because it
 * is what a purpose's "sensitive data may be used for it" is checked against.
 *
 * <p>Everybody reads the rule; the request form tells them of it. Somebody who
 * governs may measure how far it reaches and read how it came to be; a
 * platform admin or a policy author may change it, with a reason.
 */

const MAX_REASON = 500;

const MODE_COLOR: Record<SensitiveMode, 'gray' | 'warning' | 'error'> = {
  OFF: 'gray',
  WARN: 'warning',
  ENFORCE: 'error',
};

const BUILT_IN =
  'PII and PersonalData, and any classification or tag whose name says sensitive, confidential, restricted or secret -- but not one saying non-sensitive or public.';

export default function SensitiveDataSection({ mayReview }: { mayReview: boolean }) {
  const { data, isLoading, error } = useSensitiveData();
  const [editing, setEditing] = useState(false);
  const [panel, setPanel] = useState<'history' | 'coverage' | null>(null);

  const toggle = (which: 'history' | 'coverage') => setPanel((open) => (open === which ? null : which));

  return (
    <section aria-label="What counts as sensitive data" className="tw:flex tw:flex-col tw:gap-3">
      <div>
        <h2 className="tw:text-md tw:font-semibold tw:text-primary">What counts as sensitive data</h2>
        <p className="tw:max-w-3xl tw:text-sm tw:text-tertiary">
          A query or a request that names a purpose on a table holding sensitive data is checked
          against this rule. When the purpose does not allow sensitive data, or none is named, it is
          warned about or refused. Only labels somebody confirmed count; one OpenMetadata merely
          suggested does not.
        </p>
      </div>

      {error && (
        <p
          className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
          role="alert">
          <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
          {apiErrorMessage(error, 'The rule could not be loaded.')}
        </p>
      )}
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading the rule…</p>}

      {data &&
        (editing ? (
          <RuleEditor onDone={() => setEditing(false)} rule={data.rule} />
        ) : (
          <article
            aria-label="The sensitive data rule"
            className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
            <div className="tw:flex tw:flex-wrap tw:items-start tw:gap-3 tw:px-4 tw:py-3">
              <RuleSummary rule={data.rule} />
              <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
                {mayReview && (
                  <>
                    <Button color="secondary" onPress={() => toggle('coverage')} size="sm">
                      {panel === 'coverage' ? 'Hide coverage' : 'Measure coverage'}
                    </Button>
                    <Button color="secondary" onPress={() => toggle('history')} size="sm">
                      {panel === 'history' ? 'Hide history' : 'History'}
                    </Button>
                  </>
                )}
                {data.canEdit && (
                  <Button
                    color="secondary"
                    iconLeading={Edit03}
                    onPress={() => {
                      setPanel(null);
                      setEditing(true);
                    }}
                    size="sm">
                    Edit
                  </Button>
                )}
              </div>
            </div>

            {panel === 'coverage' && <CurrentCoverage />}
            {panel === 'history' && <RuleHistory />}

            <p className="tw:border-t tw:border-secondary tw:px-4 tw:py-2 tw:text-xs tw:text-quaternary">
              {data.rule.updatedBy && data.rule.updatedBy !== 'system' && data.rule.updatedAt
                ? `Changed by ${data.rule.updatedBy} ${relativeTime(data.rule.updatedAt)}`
                : 'As installed: nobody has changed it yet'}
            </p>
          </article>
        ))}
    </section>
  );
}

// ------------------------------------------------------------------ reading

function RuleSummary({ rule }: { rule: SensitiveRule }) {
  const mode = MODES.find((m) => m.value === rule.mode);
  return (
    <div className="tw:flex tw:min-w-0 tw:flex-1 tw:flex-col tw:gap-2">
      <p className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <Badge color={MODE_COLOR[rule.mode]} size="sm" type="pill-color">
          {mode?.label ?? rule.mode}
        </Badge>
        <span className="tw:text-sm tw:text-secondary">{mode?.hint}</span>
      </p>
      <div className="tw:text-sm tw:text-secondary">
        <span className="tw:font-medium tw:text-primary">Counts: </span>
        {rule.builtIn ? 'the built-in names' : 'not the built-in names'}
        {rule.include.length > 0 && ', and'}
        {rule.builtIn && (
          <span className="tw:block tw:text-xs tw:text-tertiary">{BUILT_IN}</span>
        )}
        {rule.include.length > 0 && <Labels labels={rule.include} />}
      </div>
      {rule.exclude.length > 0 && (
        <div className="tw:text-sm tw:text-secondary">
          <span className="tw:font-medium tw:text-primary">Never counts:</span>
          <Labels labels={rule.exclude} />
        </div>
      )}
      {!rule.builtIn && rule.include.length === 0 && rule.mode !== 'OFF' && (
        <p className="tw:text-xs tw:text-warning-primary">
          Nothing counts as sensitive, so nothing is checked.
        </p>
      )}
    </div>
  );
}

function Labels({ labels }: { labels: SensitiveLabel[] }) {
  return (
    <span className="tw:mt-1 tw:flex tw:flex-wrap tw:gap-1.5">
      {labels.map((label) => (
        <Badge color="gray" key={`${label.kind}:${label.fqn}`} size="sm" type="pill-color">
          {label.fqn} · {kindLabel(label.kind).toLowerCase()}
        </Badge>
      ))}
    </span>
  );
}

function CurrentCoverage() {
  const { data, isLoading, error } = useQuery({
    queryKey: [...SENSITIVE_KEY, 'coverage'],
    queryFn: () => previewSensitiveData(null),
    retry: false,
  });
  return (
    <div aria-label="Coverage" className="tw:border-t tw:border-secondary tw:px-4 tw:py-3" role="region">
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Measuring…</p>}
      {error && (
        <p className="tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'The coverage could not be measured.')}
        </p>
      )}
      {data && <CoverageReport coverage={data} />}
    </div>
  );
}

/** "Covers 12 of 340 tables and views, 31 columns in them". */
export function describeCoverage(coverage: Coverage): string {
  const tables = coverage.tables.toLocaleString('en');
  const of = coverage.catalogTables.toLocaleString('en');
  const columns = coverage.columns.toLocaleString('en');
  if (coverage.tables === 0) return `Covers none of the ${of} tables and views`;
  return `Covers ${tables} of ${of} tables and views, ${columns} ${
    coverage.columns === 1 ? 'column' : 'columns'
  } in them`;
}

function CoverageReport({ coverage }: { coverage: Coverage }) {
  return (
    <div className="tw:flex tw:flex-col tw:gap-2 tw:text-sm tw:text-secondary">
      <p className="tw:font-medium tw:text-primary">{describeCoverage(coverage)}</p>
      {coverage.labels.length > 0 && (
        <ul aria-label="By label" className="tw:flex tw:flex-col tw:gap-0.5">
          {coverage.labels.map((use) => (
            <li key={use.label}>
              <span className="tw:font-mono tw:text-xs">{use.label}</span>{' '}
              <span className="tw:text-xs tw:text-tertiary">
                {use.tables.toLocaleString('en')} {use.tables === 1 ? 'table' : 'tables'},{' '}
                {use.columns.toLocaleString('en')} {use.columns === 1 ? 'column' : 'columns'}
              </span>
            </li>
          ))}
        </ul>
      )}
      {coverage.examples.length > 0 && (
        <div>
          <p className="tw:text-xs tw:text-tertiary">For example</p>
          <ul aria-label="Examples" className="tw:flex tw:flex-col tw:gap-0.5">
            {coverage.examples.map((covered) => (
              <li className="tw:text-xs" key={covered.fqn}>
                <span className="tw:font-mono">{covered.fqn}</span>{' '}
                <span className="tw:text-tertiary">
                  {covered.columns === 0
                    ? 'the table is labelled'
                    : `${covered.columns} ${covered.columns === 1 ? 'column' : 'columns'}`}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function RuleHistory() {
  const { data, isLoading, error } = useQuery({
    queryKey: [...SENSITIVE_KEY, 'history'],
    queryFn: fetchSensitiveHistory,
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
          {data.map((change) => (
            <li className="tw:text-sm tw:text-secondary" key={change.id}>
              <span className="tw:font-medium tw:text-primary">{change.actor}</span>{' '}
              {describeChange(change).join('; ') || 'saved it unchanged'}{' '}
              <span className="tw:text-xs tw:text-tertiary">{relativeTime(change.at)}</span>
              {change.reason && (
                <span className="tw:block tw:text-xs tw:text-tertiary">“{change.reason}”</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ------------------------------------------------------------------ editing

/**
 * The names a list may pick from for a kind: classifications and glossaries
 * are the top of the vocabulary, tags and terms everything beneath them.
 */
export function labelOptions(vocabulary: Vocabulary | undefined, kind: LabelKind): string[] {
  if (!vocabulary) return [];
  const roots =
    kind === 'CLASSIFICATION' || kind === 'TAG' ? vocabulary.classifications : vocabulary.glossaries;
  if (kind === 'CLASSIFICATION' || kind === 'GLOSSARY') {
    return roots.filter((value) => !value.disabled).map((value) => value.fqn);
  }
  const out: string[] = [];
  const walk = (values: GovernanceValue[]) => {
    for (const value of values) {
      if (value.disabled) continue;
      out.push(value.fqn);
      walk(value.children ?? []);
    }
  };
  for (const root of roots) {
    if (!root.disabled) walk(root.children ?? []);
  }
  return out;
}

function RuleEditor({ rule, onDone }: { rule: SensitiveRule; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [builtIn, setBuiltIn] = useState(rule.builtIn);
  const [include, setInclude] = useState<SensitiveLabel[]>(rule.include);
  const [exclude, setExclude] = useState<SensitiveLabel[]>(rule.exclude);
  const [mode, setMode] = useState<SensitiveMode>(rule.mode);
  const [reason, setReason] = useState('');
  const vocabulary = useQuery({
    queryKey: ['vocabulary'],
    queryFn: fetchVocabulary,
    staleTime: 5 * 60 * 1000,
    retry: false,
  });

  const settings: SensitiveSettings = { builtIn, include, exclude, mode };
  const unchanged = sameSettings(settings, rule);
  const problem = unchanged
    ? 'Nothing has changed yet'
    : ruleProblem(settings, reason, MAX_REASON);

  const preview = useMutation({
    mutationFn: () => previewSensitiveData({ builtIn, include, exclude }),
  });
  const save = useMutation({
    mutationFn: () => updateSensitiveData({ ...settings, reason: reason.trim() }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SENSITIVE_KEY });
      onDone();
    },
  });

  return (
    <form
      aria-label="Edit what counts as sensitive data"
      className="tw:rounded-xl tw:border tw:border-brand tw:bg-primary tw:shadow-md"
      onSubmit={(event) => {
        event.preventDefault();
        if (!problem && !save.isPending) save.mutate();
      }}>
      <div className="tw:border-b tw:border-secondary tw:px-4 tw:py-3">
        <p className="tw:text-sm tw:font-semibold tw:text-primary">Edit what counts as sensitive data</p>
      </div>

      <div className="tw:flex tw:flex-col tw:gap-4 tw:px-4 tw:py-4">
        <label className="tw:flex tw:items-start tw:gap-2 tw:text-sm tw:text-secondary">
          <input
            checked={builtIn}
            className="tw:mt-0.5"
            onChange={(event) => setBuiltIn(event.target.checked)}
            type="checkbox"
          />
          <span>
            Count the built-in names
            <span className="tw:block tw:text-xs tw:text-tertiary">{BUILT_IN}</span>
          </span>
        </label>

        <LabelList
          heading="Also counts"
          hint="A classification or glossary covers everything in it; a tag or term covers what is beneath it too."
          labels={include}
          onChange={setInclude}
          vocabulary={vocabulary.data}
        />
        <LabelList
          heading="Never counts"
          hint="Wins over the built-in names and over what counts, for a label that only looks sensitive."
          labels={exclude}
          onChange={setExclude}
          vocabulary={vocabulary.data}
        />

        <fieldset className="tw:flex tw:flex-col tw:gap-2">
          <legend className="tw:mb-1 tw:text-sm tw:font-medium tw:text-secondary">
            When a purpose does not allow sensitive data
          </legend>
          {MODES.map((choice) => (
            <label className="tw:flex tw:items-start tw:gap-2 tw:text-sm tw:text-secondary" key={choice.value}>
              <input
                checked={mode === choice.value}
                className="tw:mt-0.5"
                name="sensitive-mode"
                onChange={() => setMode(choice.value)}
                type="radio"
                value={choice.value}
              />
              <span>
                {choice.label}
                <span className="tw:block tw:text-xs tw:text-tertiary">{choice.hint}</span>
              </span>
            </label>
          ))}
          {mode === 'ENFORCE' && rule.mode !== 'ENFORCE' && (
            <p className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-warning-50 tw:px-3 tw:py-2 tw:text-xs tw:text-warning-primary">
              <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
              Queries and requests on the tables this rule covers will be refused unless they name
              a purpose sensitive data may be used for. Measure what it covers first.
            </p>
          )}
        </fieldset>

        <div className="tw:flex tw:flex-col tw:gap-2 tw:rounded-lg tw:bg-secondary tw:px-3 tw:py-2">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <Button
              color="secondary"
              isDisabled={preview.isPending}
              onPress={() => preview.mutate()}
              size="sm">
              {preview.isPending ? 'Measuring…' : 'Measure what it covers'}
            </Button>
            <span className="tw:text-xs tw:text-tertiary">Nothing is saved by measuring.</span>
          </div>
          {preview.isError && (
            <p className="tw:text-sm tw:text-error-primary" role="alert">
              {apiErrorMessage(preview.error, 'The coverage could not be measured.')}
            </p>
          )}
          {preview.data && <CoverageReport coverage={preview.data} />}
        </div>

        <Field hint={`Kept in the history. Up to ${MAX_REASON} characters.`} label="Why">
          <textarea
            aria-label="Why"
            className={`${FIELD} tw:min-h-14 tw:resize-y`}
            onChange={(event) => setReason(event.target.value)}
            value={reason}
          />
        </Field>

        {save.isError && (
          <p
            className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
            role="alert">
            <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
            <span>{apiErrorMessage(save.error, 'The rule was not saved.')}</span>
          </p>
        )}
      </div>

      <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-end tw:gap-2 tw:border-t tw:border-secondary tw:px-4 tw:py-3">
        {problem && <span className="tw:mr-auto tw:text-xs tw:text-tertiary">{problem}.</span>}
        <Button color="secondary" onPress={onDone} size="sm">
          Cancel
        </Button>
        <Button color="primary" isDisabled={!!problem || save.isPending} size="sm" type="submit">
          {save.isPending ? 'Saving…' : 'Save rule'}
        </Button>
      </div>
    </form>
  );
}

function LabelList({
  heading,
  hint,
  labels,
  onChange,
  vocabulary,
}: {
  heading: string;
  hint: string;
  labels: SensitiveLabel[];
  onChange: (labels: SensitiveLabel[]) => void;
  vocabulary: Vocabulary | undefined;
}) {
  const [kind, setKind] = useState<LabelKind>('CLASSIFICATION');
  const [fqn, setFqn] = useState('');
  const listId = `sensitive-${heading.toLowerCase().replace(/\s+/g, '-')}-options`;
  const name = fqn.trim();
  const candidate: SensitiveLabel = { kind, fqn: name };
  const listed = labels.some((label) => sameLabel(label, candidate));
  const tooLong = name.length > MAX_LABEL_FQN;

  const add = () => {
    if (!name || listed || tooLong) return;
    onChange([...labels, candidate]);
    setFqn('');
  };

  return (
    <div aria-label={heading} className="tw:flex tw:flex-col tw:gap-2" role="group">
      <div>
        <p className="tw:text-sm tw:font-medium tw:text-secondary">{heading}</p>
        <p className="tw:text-xs tw:text-tertiary">{hint}</p>
      </div>
      {labels.length === 0 ? (
        <p className="tw:text-xs tw:text-quaternary">Nothing listed.</p>
      ) : (
        <ul className="tw:flex tw:flex-wrap tw:gap-1.5">
          {labels.map((label) => (
            <li
              className="tw:flex tw:items-center tw:gap-1 tw:rounded-full tw:border tw:border-secondary tw:bg-secondary tw:py-0.5 tw:pr-1 tw:pl-2.5 tw:text-xs tw:text-secondary"
              key={`${label.kind}:${label.fqn}`}>
              <span>
                {label.fqn} · {kindLabel(label.kind).toLowerCase()}
              </span>
              <button
                aria-label={`Remove ${label.fqn} from ${heading.toLowerCase()}`}
                className="tw:rounded-full tw:px-1 tw:text-tertiary tw:hover:bg-primary tw:hover:text-primary"
                onClick={() => onChange(labels.filter((x) => x !== label))}
                type="button">
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <div className="tw:w-44">
          <Select
            ariaLabel={`${heading}: kind`}
            onChange={(value) => setKind(value as LabelKind)}
            options={LABEL_KINDS.map((k) => ({ value: k.value, label: k.label, hint: k.hint }))}
            value={kind}
          />
        </div>
        <input
          aria-label={`${heading}: name`}
          className={`${FIELD} tw:w-64`}
          list={listId}
          onChange={(event) => setFqn(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              add();
            }
          }}
          placeholder={kind === 'CLASSIFICATION' ? 'PII' : kind === 'TAG' ? 'PII.Sensitive' : 'Finance'}
          value={fqn}
        />
        <datalist id={listId}>
          {labelOptions(vocabulary, kind).map((option) => (
            <option key={option} value={option} />
          ))}
        </datalist>
        <Button
          color="secondary"
          iconLeading={Plus}
          isDisabled={!name || listed || tooLong}
          onPress={add}
          size="sm">
          Add
        </Button>
        {listed && <span className="tw:text-xs tw:text-tertiary">Already listed.</span>}
        {tooLong && (
          <span className="tw:text-xs tw:text-tertiary">Keep a name to {MAX_LABEL_FQN} characters.</span>
        )}
      </div>
    </div>
  );
}
