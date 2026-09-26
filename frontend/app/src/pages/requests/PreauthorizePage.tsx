import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { ArrowLeft, InfoCircle, Plus, ShieldTick, Trash01 } from '@untitledui/icons';
import { apiErrorMessage } from '../../api/client';
import {
  measurePreauthorization,
  requestAccess,
  type PreauthAttribute,
  type PreauthCondition,
  type PreauthFacet,
  type PreauthPrincipal,
  type PreauthTarget,
} from '../../api/accessRequests';
import { fetchAttributeVocabulary, fetchPrincipals, fetchVocabulary, flatten, type Vocabulary } from '../../api/governance';
import {
  checkAnswers,
  fetchEffectiveTemplate,
  MAX_DAYS,
  MAX_REFERENCE,
  TEMPLATES_KEY,
  type RequestForm,
} from '../../api/requestTemplates';
import { FIELD, Select, Step, TextField } from '../policies/controls';
import { BUILT_IN_TEMPLATE } from '../query/RequestAccess';
import { ScopePicker } from '../settings/pickers';

/**
 * Asking, ahead of need, for a class of tables for a group of people.
 *
 * <p>An ordinary request names one table and is for the person asking. This one
 * names a scope, what the tables under it carry, and who it is for; the people
 * who decide it draft a policy from it, and only the policy lifecycle ever
 * activates that. While it is being filled in, the page says how many tables
 * and people it reaches today -- counts only: the names are for the reviewers.
 */

const FACETS: { value: PreauthFacet; label: string }[] = [
  { value: 'tags', label: 'Tag' },
  { value: 'classifications', label: 'Classification' },
  { value: 'terms', label: 'Glossary term' },
  { value: 'glossaries', label: 'Glossary' },
  { value: 'domains', label: 'Domain' },
  { value: 'dataProducts', label: 'Data product' },
  { value: 'tier', label: 'Tier' },
  { value: 'certification', label: 'Certification' },
];

const ATTRIBUTE_OPERATORS: { value: PreauthAttribute['operator']; label: string }[] = [
  { value: 'eq', label: 'is' },
  { value: 'ne', label: 'is not' },
  { value: 'gte', label: 'at least' },
  { value: 'lte', label: 'at most' },
];

const DURATIONS = [30, 90, 180, 365];
const NO_PURPOSE = '__none__';

/**
 * The lengths offered: the scope's template's own, or the usual ones up to its
 * longest. What the page starts on is the template's default when it offers
 * one, else 90 days when that is allowed.
 */
export function lengthsFor(form: RequestForm): { offered: number[]; start: number | null } {
  const ceiling = form.maxDays ?? MAX_DAYS;
  const offered = (form.durations.length > 0 ? form.durations : DURATIONS).filter((d) => d >= 1 && d <= ceiling);
  const start =
    form.defaultDays !== null && form.defaultDays <= ceiling
      ? form.defaultDays
      : offered.includes(90)
        ? 90
        : (offered[offered.length - 1] ?? (form.allowUntilRevoked ? null : ceiling));
  return { offered: offered.length > 0 ? offered : [ceiling], start };
}

/** What the vocabulary offers for one facet, as values to type or pick. */
export function valuesFor(facet: PreauthFacet, vocabulary: Vocabulary | undefined): string[] {
  if (!vocabulary) return [];
  const classes = flatten(vocabulary.classifications);
  const glossary = flatten(vocabulary.glossaries);
  const under = (root: string) => classes.filter((v) => v.fqn.startsWith(`${root}.`)).map((v) => v.fqn);
  switch (facet) {
    case 'classifications':
      return vocabulary.classifications.map((v) => v.fqn);
    case 'tags':
      return classes.filter((v) => v.depth > 0).map((v) => v.fqn);
    case 'glossaries':
      return vocabulary.glossaries.map((v) => v.fqn);
    case 'terms':
      return glossary.filter((v) => v.depth > 0).map((v) => v.fqn);
    case 'domains':
      return flatten(vocabulary.domains).map((v) => v.fqn);
    case 'dataProducts':
      return flatten(vocabulary.dataProducts).map((v) => v.fqn);
    case 'tier':
      return under('Tier');
    case 'certification':
      return under('Certification');
  }
}

/** The target as it would be sent, or null while it is not yet a whole one. */
export function targetOf(
  conditions: PreauthCondition[],
  kind: 'GROUP' | 'ATTRIBUTE',
  principals: PreauthPrincipal[],
  attributes: PreauthAttribute[]
): PreauthTarget | null {
  const facets = conditions.filter((c) => c.value.trim() !== '').map((c) => ({ ...c, value: c.value.trim() }));
  const who = principals.filter((p) => p.name.trim() !== '').map((p) => ({ ...p, name: p.name.trim() }));
  const attrs = attributes
    .filter((a) => a.key.trim() !== '' && a.value.trim() !== '')
    .map((a) => ({ ...a, key: a.key.trim(), value: a.value.trim() }));
  if (facets.length === 0) return null;
  if (kind === 'GROUP' ? who.length === 0 : attrs.length === 0) return null;
  return {
    conditions: facets,
    subject: kind === 'GROUP' ? { kind, principals: who, attributes: [] } : { kind, principals: [], attributes: attrs },
  };
}

function useDebounced<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return settled;
}

export default function PreauthorizePage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [scope, setScope] = useState<string | null>(null);
  const [conditions, setConditions] = useState<PreauthCondition[]>([
    { facet: 'tags', operator: 'contains', value: '' },
  ]);
  const [kind, setKind] = useState<'GROUP' | 'ATTRIBUTE'>('GROUP');
  const [principals, setPrincipals] = useState<PreauthPrincipal[]>([{ type: 'team', name: '' }]);
  const [attributes, setAttributes] = useState<PreauthAttribute[]>([{ key: '', operator: 'eq', value: '' }]);
  const [reason, setReason] = useState('');
  const [days, setDays] = useState<number | null>(90);
  const [purpose, setPurpose] = useState('');
  const [reference, setReference] = useState('');

  // The lists behind the value boxes. Either may fail; the boxes still take text.
  const vocabulary = useQuery({ queryKey: ['governance', 'vocabulary'], queryFn: fetchVocabulary, retry: false });
  const groups = useQuery({
    queryKey: ['principals', 'groups'],
    queryFn: () => fetchPrincipals({ type: 'GROUP', limit: 200 }),
    retry: false,
  });
  const attributeKeys = useQuery({
    queryKey: ['principals', 'attributes'],
    queryFn: fetchAttributeVocabulary,
    retry: false,
  });

  // The scope's request template: the server holds a pre-authorization to it
  // as to any request under the scope, so the form asks what it asks.
  const templated = useQuery({
    queryKey: [...TEMPLATES_KEY, 'effective', scope],
    queryFn: () => fetchEffectiveTemplate(scope!),
    enabled: !!scope,
    retry: false,
    staleTime: 60_000,
  });
  const template = (scope && templated.data) || BUILT_IN_TEMPLATE;
  const form = template.form;
  // The built-in form's short lengths suit one table for one person; a class of
  // tables for a group keeps the usual ones unless a template says otherwise.
  const lengths = lengthsFor(template.id ? form : { ...form, durations: [], defaultDays: 90 });
  const [shapedBy, setShapedBy] = useState<string | null>(null);
  if (shapedBy !== template.id) {
    setShapedBy(template.id);
    setDays(lengths.start);
    setPurpose('');
  }
  const listed = form.purposes.length > 0;

  const target = useMemo(
    () => targetOf(conditions, kind, principals, attributes),
    [conditions, kind, principals, attributes]
  );
  const asked = useDebounced(scope && target ? { scope, target } : null, 400);
  const coverage = useQuery({
    queryKey: ['access-requests', 'preauthorization', 'coverage', asked],
    queryFn: () => measurePreauthorization(asked!.scope, asked!.target),
    enabled: asked !== null,
    retry: false,
  });

  const send = useMutation({
    mutationFn: () =>
      requestAccess({
        assetFqn: scope!,
        sourceId: null,
        reason: reason.trim(),
        purpose: purpose.trim() || null,
        days,
        attemptedSql: null,
        deniedBy: null,
        kind: 'PREAUTHORIZATION',
        target: target!,
        ...(form.referenceLabel ? { reference: reference.trim() || null } : {}),
      }),
    onSuccess: (request) => {
      void queryClient.invalidateQueries({ queryKey: ['access-requests'] });
      navigate(`/requests/${encodeURIComponent(request.ticket)}`);
    },
  });

  const problem = !scope
    ? 'Choose the service, database, schema or table it is under.'
    : !target
      ? kind === 'GROUP'
        ? 'Name at least one thing the tables carry, and a group or team.'
        : 'Name at least one thing the tables carry, and an attribute with its value.'
      : reason.trim().length === 0
        ? 'Say why these people need these tables ahead of time.'
        : checkAnswers(form, {
            reason,
            purpose: purpose.trim() || null,
            days,
            reference: form.referenceLabel ? reference : '',
          });
  const ready = problem === null && !send.isPending;

  const principalType = (p: PreauthPrincipal) => (p.type === 'team' ? 'Team' : 'Group');
  const keys = attributeKeys.data?.keys ?? [];

  return (
    <form
      aria-label="Pre-authorize"
      className="tw:flex tw:flex-col tw:gap-4"
      onSubmit={(event) => {
        event.preventDefault();
        if (ready) send.mutate();
      }}>
      <header className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-6 tw:py-5 tw:shadow-xs">
        <Link
          className="tw:inline-flex tw:items-center tw:gap-1 tw:text-sm tw:font-medium tw:text-tertiary tw:hover:text-primary"
          to="/requests">
          <ArrowLeft className="tw:size-4" /> Access requests
        </Link>
        <div className="tw:mt-3 tw:flex tw:min-w-0 tw:items-start tw:gap-4">
          <span
            aria-hidden
            className="tw:flex tw:size-12 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-xl tw:bg-utility-brand-50">
            <ShieldTick className="tw:size-6 tw:text-fg-brand-primary" />
          </span>
          <div className="tw:min-w-0">
            <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">Pre-authorize</h1>
            <p className="tw:mt-1 tw:max-w-2xl tw:text-pretty tw:text-sm tw:text-tertiary">
              Ask ahead of need for every table under a scope that carries some tags, terms or domains,
              for a group of people. The scope&apos;s workflow decides it, and what they set up is a
              policy, saved as a draft and activated like any other: never a grant.
            </p>
          </div>
        </div>
      </header>

      <Step description="The service, database, schema or table the tables are under." step={1} title="Where">
        <div className="tw:max-w-xl">
          <ScopePicker onChange={setScope} value={scope} />
        </div>
      </Step>

      <Step
        description="Every condition must hold. Contains takes a tag's children and a domain's sub-domains with it."
        step={2}
        title="Which tables">
        <ul aria-label="Conditions" className="tw:flex tw:flex-col tw:gap-2">
          {conditions.map((c, i) => {
            const list = `preauth-values-${i}`;
            const update = (next: Partial<PreauthCondition>) =>
              setConditions((all) => all.map((x, j) => (j === i ? { ...x, ...next } : x)));
            return (
              <li className="tw:flex tw:flex-wrap tw:items-center tw:gap-2" key={i}>
                <Select
                  ariaLabel={`Condition ${i + 1} facet`}
                  className="tw:w-44"
                  onChange={(v) => update({ facet: v as PreauthFacet, value: '' })}
                  options={FACETS}
                  value={c.facet}
                />
                <Select
                  ariaLabel={`Condition ${i + 1} operator`}
                  className="tw:w-32"
                  onChange={(v) => update({ operator: v as PreauthCondition['operator'] })}
                  options={[
                    { value: 'contains', label: 'contains' },
                    { value: 'eq', label: 'is exactly' },
                  ]}
                  value={c.operator}
                />
                <TextField
                  ariaLabel={`Condition ${i + 1} value`}
                  className="tw:min-w-0 tw:flex-1 tw:sm:max-w-sm"
                  list={list}
                  onChange={(value) => update({ value })}
                  placeholder="PII.Sensitive"
                  value={c.value}
                />
                <datalist id={list}>
                  {valuesFor(c.facet, vocabulary.data).map((v) => (
                    <option key={v} value={v} />
                  ))}
                </datalist>
                {conditions.length > 1 && (
                  <Button
                    aria-label={`Remove condition ${i + 1}`}
                    color="tertiary"
                    iconLeading={Trash01}
                    onPress={() => setConditions((all) => all.filter((_, j) => j !== i))}
                    size="sm"
                  />
                )}
              </li>
            );
          })}
        </ul>
        <Button
          className="tw:mt-3"
          color="link-color"
          iconLeading={Plus}
          onPress={() => setConditions((all) => [...all, { facet: 'tags', operator: 'contains', value: '' }])}
          size="sm">
          Add a condition
        </Button>
      </Step>

      <Step description="Named groups and teams, or whoever holds some attributes." step={3} title="For whom">
        <div aria-label="Subject" className="tw:flex tw:flex-wrap tw:gap-1.5" role="group">
          {(['GROUP', 'ATTRIBUTE'] as const).map((k) => (
            <button
              aria-pressed={kind === k}
              className={chip(kind === k)}
              key={k}
              onClick={() => setKind(k)}
              type="button">
              {k === 'GROUP' ? 'Groups and teams' : 'People with attributes'}
            </button>
          ))}
        </div>

        {kind === 'GROUP' ? (
          <>
            <ul aria-label="Groups and teams" className="tw:mt-4 tw:flex tw:flex-col tw:gap-2">
              {principals.map((p, i) => {
                const update = (next: Partial<PreauthPrincipal>) =>
                  setPrincipals((all) => all.map((x, j) => (j === i ? { ...x, ...next } : x)));
                return (
                  <li className="tw:flex tw:flex-wrap tw:items-center tw:gap-2" key={i}>
                    <Select
                      ariaLabel={`Who ${i + 1} kind`}
                      className="tw:w-32"
                      onChange={(v) => update({ type: v as PreauthPrincipal['type'] })}
                      options={[
                        { value: 'team', label: 'Team' },
                        { value: 'group', label: 'Group' },
                      ]}
                      value={p.type}
                    />
                    <TextField
                      ariaLabel={`${principalType(p)} ${i + 1}`}
                      className="tw:min-w-0 tw:flex-1 tw:sm:max-w-sm"
                      list={p.type === 'group' ? 'preauth-groups' : undefined}
                      onChange={(name) => update({ name })}
                      placeholder={p.type === 'team' ? 'Finance' : 'fraud-analysts'}
                      value={p.name}
                    />
                    {principals.length > 1 && (
                      <Button
                        aria-label={`Remove ${principalType(p).toLowerCase()} ${i + 1}`}
                        color="tertiary"
                        iconLeading={Trash01}
                        onPress={() => setPrincipals((all) => all.filter((_, j) => j !== i))}
                        size="sm"
                      />
                    )}
                  </li>
                );
              })}
            </ul>
            <datalist id="preauth-groups">
              {(groups.data ?? []).map((g) => (
                <option key={g.id} value={g.username} />
              ))}
            </datalist>
            <Button
              className="tw:mt-3"
              color="link-color"
              iconLeading={Plus}
              onPress={() => setPrincipals((all) => [...all, { type: 'team', name: '' }])}
              size="sm">
              Add a group or team
            </Button>
            <p className="tw:mt-2 tw:text-xs tw:text-tertiary">Anybody in any one of them.</p>
          </>
        ) : (
          <>
            <ul aria-label="Attributes" className="tw:mt-4 tw:flex tw:flex-col tw:gap-2">
              {attributes.map((a, i) => {
                const update = (next: Partial<PreauthAttribute>) =>
                  setAttributes((all) => all.map((x, j) => (j === i ? { ...x, ...next } : x)));
                const known = keys.find((k) => k.key === a.key);
                return (
                  <li className="tw:flex tw:flex-wrap tw:items-center tw:gap-2" key={i}>
                    <TextField
                      ariaLabel={`Attribute ${i + 1}`}
                      className="tw:w-44"
                      list="preauth-attribute-keys"
                      onChange={(key) => update({ key })}
                      placeholder="clearance"
                      value={a.key}
                    />
                    <Select
                      ariaLabel={`Attribute ${i + 1} operator`}
                      className="tw:w-32"
                      onChange={(v) => update({ operator: v as PreauthAttribute['operator'] })}
                      options={ATTRIBUTE_OPERATORS}
                      value={a.operator}
                    />
                    <TextField
                      ariaLabel={`Attribute ${i + 1} value`}
                      className="tw:min-w-0 tw:flex-1 tw:sm:max-w-xs"
                      list={`preauth-attribute-values-${i}`}
                      onChange={(value) => update({ value })}
                      placeholder="L2"
                      value={a.value}
                    />
                    <datalist id={`preauth-attribute-values-${i}`}>
                      {(known?.values ?? []).map((v) => (
                        <option key={v.value} value={v.value} />
                      ))}
                    </datalist>
                    {attributes.length > 1 && (
                      <Button
                        aria-label={`Remove attribute ${i + 1}`}
                        color="tertiary"
                        iconLeading={Trash01}
                        onPress={() => setAttributes((all) => all.filter((_, j) => j !== i))}
                        size="sm"
                      />
                    )}
                  </li>
                );
              })}
            </ul>
            <datalist id="preauth-attribute-keys">
              {keys.map((k) => (
                <option key={k.key} value={k.key} />
              ))}
            </datalist>
            <Button
              className="tw:mt-3"
              color="link-color"
              iconLeading={Plus}
              onPress={() => setAttributes((all) => [...all, { key: '', operator: 'eq', value: '' }])}
              size="sm">
              Add an attribute
            </Button>
            <p className="tw:mt-2 tw:text-xs tw:text-tertiary">
              Everybody holding all of them, today and whoever comes to hold them later.
            </p>
          </>
        )}
      </Step>

      <section
        aria-label="What it reaches today"
        aria-live="polite"
        className="tw:rounded-xl tw:border tw:border-secondary tw:bg-secondary tw:px-5 tw:py-4">
        <h2 className="tw:text-sm tw:font-semibold tw:text-primary">What it reaches today</h2>
        {asked === null ? (
          <p className="tw:mt-1 tw:text-sm tw:text-tertiary">Fill in where, which tables and for whom to see.</p>
        ) : coverage.isError ? (
          <p className="tw:mt-1 tw:text-sm tw:text-error-primary" role="alert">
            {apiErrorMessage(coverage.error, 'What it reaches could not be worked out.')}
          </p>
        ) : !coverage.data ? (
          <p className="tw:mt-1 tw:text-sm tw:text-tertiary">Counting…</p>
        ) : (
          <>
            <p className="tw:mt-1 tw:text-sm tw:text-secondary">
              <b className="tw:font-semibold tw:text-primary">
                {coverage.data.tables} {coverage.data.tables === 1 ? 'table' : 'tables'}
              </b>{' '}
              under this {coverage.data.scopeType.toLowerCase()} ·{' '}
              <b className="tw:font-semibold tw:text-primary">
                {coverage.data.peopleAtLeast ? 'at least ' : ''}
                {coverage.data.people} {coverage.data.people === 1 ? 'person' : 'people'}
              </b>
            </p>
            {coverage.data.tableSample.length > 0 && (
              <ul aria-label="Tables it reaches" className="tw:mt-2 tw:flex tw:flex-col tw:gap-0.5">
                {coverage.data.tableSample.map((fqn) => (
                  <li className="tw:truncate tw:font-mono tw:text-xs tw:text-tertiary" key={fqn} title={fqn}>
                    {fqn}
                  </li>
                ))}
              </ul>
            )}
            <p className="tw:mt-2 tw:flex tw:items-start tw:gap-1.5 tw:text-xs tw:text-quaternary">
              <InfoCircle aria-hidden className="tw:mt-px tw:size-3.5 tw:shrink-0" />
              Tables tagged the same way later, and people who join later, are reached too. Who they are is
              shown to the people deciding.
            </p>
          </>
        )}
      </section>

      <Step description="What the people deciding read first." step={4} title="Why, and for how long">
        <div className="tw:flex tw:max-w-2xl tw:flex-col tw:gap-4">
          {form.guidance && (
            // Plain text: whoever wrote the template does not get to put markup here.
            <div
              aria-label="Guidance"
              className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-secondary tw:px-3 tw:py-2.5 tw:text-sm tw:text-secondary"
              role="note">
              <InfoCircle aria-hidden className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
              <p className="tw:min-w-0 tw:whitespace-pre-line tw:break-words">{form.guidance}</p>
            </div>
          )}
          {(listed || form.purposeRequired) && (
            <div className="tw:flex tw:flex-col tw:gap-1.5">
              <span className="tw:text-sm tw:font-medium tw:text-secondary">
                Purpose {form.purposeRequired && <span className="tw:text-error-primary">*</span>}
              </span>
              {listed ? (
                <Select
                  ariaLabel="Purpose"
                  className="tw:max-w-sm"
                  onChange={(value) => setPurpose(value === NO_PURPOSE ? '' : value)}
                  options={[
                    ...(form.purposeRequired ? [] : [{ value: NO_PURPOSE, label: 'No particular purpose' }]),
                    ...form.purposes.map((p) => ({ value: p, label: p })),
                  ]}
                  placeholder="Choose a purpose"
                  value={purpose || (form.purposeRequired ? '' : NO_PURPOSE)}
                />
              ) : (
                <TextField
                  ariaLabel="Purpose"
                  className="tw:max-w-sm"
                  onChange={setPurpose}
                  placeholder="What the data is for"
                  value={purpose}
                />
              )}
            </div>
          )}
          <label className="tw:flex tw:flex-col tw:gap-1.5">
            <span className="tw:text-sm tw:font-medium tw:text-secondary">
              Why they need it <span className="tw:text-error-primary">*</span>
            </span>
            <textarea
              aria-label="Why they need it"
              className={`${FIELD} tw:min-h-20 tw:resize-y`}
              onChange={(event) => setReason(event.target.value)}
              placeholder="What these people do with these tables, and why it cannot wait for each request"
              value={reason}
            />
            {form.minReasonLength > 1 && (
              <span className="tw:text-xs tw:text-tertiary">
                At least {form.minReasonLength} characters · {reason.trim().length} so far
              </span>
            )}
          </label>
          {form.referenceLabel && (
            <label className="tw:flex tw:flex-col tw:gap-1.5">
              <span className="tw:text-sm tw:font-medium tw:text-secondary">
                {form.referenceLabel}{' '}
                {form.referenceRequired ? (
                  <span className="tw:text-error-primary">*</span>
                ) : (
                  <span className="tw:font-normal tw:text-tertiary">(optional)</span>
                )}
              </span>
              <TextField
                ariaLabel={form.referenceLabel}
                className="tw:max-w-sm"
                onChange={(value) => setReference(value.slice(0, MAX_REFERENCE))}
                value={reference}
              />
            </label>
          )}
          <div className="tw:flex tw:flex-col tw:gap-1.5">
            <span className="tw:text-sm tw:font-medium tw:text-secondary">For how long</span>
            <div aria-label="Durations" className="tw:flex tw:flex-wrap tw:gap-1.5" role="group">
              {lengths.offered.map((option) => (
                <button
                  aria-pressed={days === option}
                  className={chip(days === option)}
                  key={option}
                  onClick={() => setDays(option)}
                  type="button">
                  {option} days
                </button>
              ))}
              {form.allowUntilRevoked && (
                <button
                  aria-pressed={days === null}
                  className={chip(days === null)}
                  onClick={() => setDays(null)}
                  type="button">
                  Until revoked
                </button>
              )}
            </div>
            <span className="tw:text-xs tw:text-tertiary">
              The policy&apos;s end date, counted from the day it is drafted.
            </span>
          </div>
        </div>
      </Step>

      <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-end tw:gap-3">
        {send.isError ? (
          <p className="tw:text-sm tw:text-error-primary" role="alert">
            {apiErrorMessage(send.error, 'The request was not sent.')}
          </p>
        ) : (
          problem && <p className="tw:text-sm tw:text-tertiary">{problem}</p>
        )}
        <Button isDisabled={!ready} isLoading={send.isPending} size="md" type="submit">
          Send for approval
        </Button>
      </div>
    </form>
  );
}

function chip(active: boolean) {
  return `tw:cursor-pointer tw:rounded-md tw:border tw:px-2.5 tw:py-1 tw:text-sm ${
    active
      ? 'tw:border-brand tw:bg-brand-primary tw:text-brand-secondary'
      : 'tw:border-secondary tw:text-tertiary tw:hover:text-primary'
  }`;
}
