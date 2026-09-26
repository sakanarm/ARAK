import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle,
  FilePlus02,
  InfoCircle,
  SearchLg,
  Send01,
  XClose,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage, fetchAssets, type AssetSummary } from '../../api/client';
import {
  fetchEligibility,
  requestAccess,
  type AccessRequest,
  type Eligibility,
} from '../../api/accessRequests';
import {
  checkAnswers,
  fetchEffectiveTemplate,
  MAX_DAYS,
  MAX_REFERENCE,
  mergeForms,
  TEMPLATES_KEY,
  type Answers,
  type RequestTemplate,
} from '../../api/requestTemplates';
import { FIELD, Select, Step, TextField } from '../policies/controls';
import { BUILT_IN_TEMPLATE } from '../query/RequestAccess';

/**
 * Asking for several tables at once, from the Requests page.
 *
 * <p>One form, one reason, but one request per table: each goes to its own
 * table's workflow, is decided on its own, and is held by the server to its
 * own table's template. The form is the strictest of the tables' templates, so
 * answers that pass it pass each of them. Tables the requester can read
 * already, has asked for already, or that a policy would refuse whatever an
 * owner granted, are shown and left out rather than sent to be refused.
 */

/** Enough for a project's worth of tables; past it, a pre-authorization says it better. */
export const MAX_TABLES = 20;

export type Standing =
  | { kind: 'checking' }
  | { kind: 'failed'; message: string }
  | { kind: 'readable' }
  | { kind: 'requested'; requestId: string }
  | { kind: 'blocked'; by: string | null }
  | { kind: 'ready' };

/** Whether a table goes out with the request, and if not, why not. */
export function standingOf(eligibility: Eligibility | undefined, error: unknown): Standing {
  if (error) return { kind: 'failed', message: apiErrorMessage(error, 'Could not check this table') };
  if (!eligibility) return { kind: 'checking' };
  if (eligibility.readable) return { kind: 'readable' };
  if (eligibility.openRequestId) return { kind: 'requested', requestId: eligibility.openRequestId };
  if (!eligibility.requestable) return { kind: 'blocked', by: eligibility.blockedBy };
  return { kind: 'ready' };
}

type Outcome = { fqn: string; sent: AccessRequest } | { fqn: string; failed: string };

const NO_PURPOSE = '__none__';

function useDebounced<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return settled;
}

export default function NewRequestPage() {
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const typed = useDebounced(search.trim(), 250);
  const [chosen, setChosen] = useState<AssetSummary[]>([]);
  const [outcomes, setOutcomes] = useState<Outcome[] | null>(null);

  const found = useQuery({
    queryKey: ['catalog', 'assets', 'new-request', typed],
    queryFn: () => fetchAssets({ search: typed || undefined, assetType: 'TABLE', limit: 50 }),
    staleTime: 30_000,
  });

  const eligibility = useQueries({
    queries: chosen.map((asset) => ({
      queryKey: ['access-requests', 'eligibility', asset.fqn],
      queryFn: () => fetchEligibility(asset.fqn),
      retry: false,
      staleTime: 30_000,
    })),
  });
  const templates = useQueries({
    queries: chosen.map((asset) => ({
      queryKey: [...TEMPLATES_KEY, 'effective', asset.fqn],
      queryFn: () => fetchEffectiveTemplate(asset.fqn),
      retry: false,
      staleTime: 60_000,
    })),
  });

  const standings = chosen.map((_, i) => standingOf(eligibility[i]?.data, eligibility[i]?.error));
  // A template that would not load leaves its table on the built-in form here;
  // the server still holds the request to the real one.
  const templateOf = (i: number): RequestTemplate => templates[i]?.data ?? BUILT_IN_TEMPLATE;
  const going = chosen
    .map((asset, i) => ({ asset, template: templateOf(i), standing: standings[i] }))
    .filter((row) => row.standing.kind === 'ready');
  const checking =
    standings.some((s) => s.kind === 'checking') || templates.some((t) => t.isPending);

  const merged = mergeForms(going.map((row) => row.template.form));
  const form = going.length > 0 ? merged.form : BUILT_IN_TEMPLATE.form;

  const [reason, setReason] = useState('');
  const [days, setDays] = useState(daysText(form.defaultDays));
  const [purpose, setPurpose] = useState('');
  const [reference, setReference] = useState('');
  // When the tables change, so may the form: start its choices where it says,
  // keep what was typed as the reason and reference.
  const shape = JSON.stringify([form.defaultDays, form.purposes]);
  const [shapedBy, setShapedBy] = useState(shape);
  if (shapedBy !== shape) {
    setShapedBy(shape);
    setDays(daysText(form.defaultDays));
    if (form.purposes.length > 0 && !form.purposes.includes(purpose)) setPurpose('');
  }

  const listed = form.purposes.length > 0;
  const parsedDays = days.trim() === '' ? null : Number.parseInt(days, 10);
  const ceiling = form.maxDays ?? MAX_DAYS;
  const answers: Answers = {
    reason,
    purpose: purpose.trim() || null,
    days: parsedDays,
    reference: form.referenceLabel ? reference : '',
  };
  const problem = problemWith();

  function problemWith(): string | null {
    if (chosen.length === 0) return 'Choose the tables you need';
    if (checking) return 'Checking the tables…';
    if (going.length === 0) return 'None of the chosen tables can be asked for';
    if (merged.conflicts.length > 0) return merged.conflicts[0];
    const overall = checkAnswers(form, answers);
    if (overall) return overall;
    for (const row of going) {
      const own = row.template.form;
      const answer = checkAnswers(own, { ...answers, reference: own.referenceLabel ? reference : '' });
      if (answer) return `${row.asset.name}: ${answer}`;
    }
    return null;
  }

  const send = useMutation({
    mutationFn: async () => {
      const done: Outcome[] = [];
      // One after another, so a table that fails does not stop the rest and
      // each table's workflow sees one request, not a burst.
      for (const row of going) {
        const own = row.template.form;
        try {
          const sent = await requestAccess({
            assetFqn: row.asset.fqn,
            sourceId: null,
            reason: reason.trim(),
            purpose: answers.purpose,
            days: parsedDays,
            ...(own.referenceLabel ? { reference: reference.trim() || null } : {}),
          });
          done.push({ fqn: row.asset.fqn, sent });
        } catch (error) {
          done.push({ fqn: row.asset.fqn, failed: apiErrorMessage(error, 'The request was not sent.') });
        }
      }
      return done;
    },
    onSuccess: (done) => {
      void queryClient.invalidateQueries({ queryKey: ['access-requests'] });
      setOutcomes(done);
    },
  });

  function toggle(asset: AssetSummary) {
    setChosen((now) =>
      now.some((a) => a.fqn === asset.fqn)
        ? now.filter((a) => a.fqn !== asset.fqn)
        : now.length >= MAX_TABLES
          ? now
          : [...now, asset]
    );
  }

  function startOver() {
    setChosen([]);
    setReason('');
    setReference('');
    setOutcomes(null);
    send.reset();
  }

  const ready = problem === null && !send.isPending;
  const trimmed = reason.trim().length;
  const results = found.data?.items ?? [];

  return (
    <form
      aria-label="New request"
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
            <FilePlus02 className="tw:size-6 tw:text-fg-brand-primary" />
          </span>
          <div className="tw:min-w-0">
            <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">New request</h1>
            <p className="tw:mt-1 tw:max-w-2xl tw:text-pretty tw:text-sm tw:text-tertiary">
              Ask for one table or several with one reason. Each table gets its own request, decided
              by its own workflow, so one can be approved while another is still waiting.
            </p>
          </div>
        </div>
      </header>

      {outcomes ? (
        <Results onAgain={startOver} outcomes={outcomes} />
      ) : (
        <>
          <Step
            description={`Search the catalog and tick the tables you need, up to ${MAX_TABLES}.`}
            step={1}
            title="Which tables">
            <div className="tw:grid tw:gap-4 tw:lg:grid-cols-2">
              <div className="tw:flex tw:min-w-0 tw:flex-col tw:gap-2">
                <div className="tw:relative">
                  <SearchLg
                    aria-hidden
                    className="tw:pointer-events-none tw:absolute tw:top-1/2 tw:left-3 tw:size-4 tw:-translate-y-1/2 tw:text-fg-quaternary"
                  />
                  <input
                    aria-label="Search tables"
                    className={`${FIELD} tw:w-full tw:pl-9`}
                    onChange={(event) => setSearch(event.target.value)}
                    placeholder="Search by name, description or column"
                    value={search}
                  />
                </div>
                <div className="tw:max-h-96 tw:overflow-auto tw:rounded-lg tw:border tw:border-secondary">
                  {found.isPending ? (
                    <p className="tw:px-3 tw:py-6 tw:text-center tw:text-sm tw:text-tertiary">Searching…</p>
                  ) : found.isError ? (
                    <p className="tw:px-3 tw:py-6 tw:text-center tw:text-sm tw:text-error-primary" role="alert">
                      {apiErrorMessage(found.error, 'The catalog could not be searched.')}
                    </p>
                  ) : results.length === 0 ? (
                    <p className="tw:px-3 tw:py-6 tw:text-center tw:text-sm tw:text-tertiary">
                      No table matches{typed ? ` “${typed}”` : ''}.
                    </p>
                  ) : (
                    <ul aria-label="Tables found" className="tw:divide-y tw:divide-secondary">
                      {results.map((asset) => {
                        const picked = chosen.some((a) => a.fqn === asset.fqn);
                        const full = !picked && chosen.length >= MAX_TABLES;
                        return (
                          <li key={asset.fqn}>
                            <label
                              className={`tw:flex tw:items-start tw:gap-3 tw:px-3 tw:py-2 ${
                                full ? 'tw:opacity-50' : 'tw:cursor-pointer tw:hover:bg-primary_hover'
                              }`}>
                              <input
                                aria-label={`Choose ${asset.fqn}`}
                                checked={picked}
                                className="tw:mt-1 tw:size-4 tw:accent-brand-600"
                                disabled={full}
                                onChange={() => toggle(asset)}
                                type="checkbox"
                              />
                              <span className="tw:min-w-0">
                                <span className="tw:block tw:truncate tw:text-sm tw:font-medium tw:text-primary">
                                  {asset.displayName || asset.name}
                                </span>
                                <span className="tw:block tw:truncate tw:font-mono tw:text-xs tw:text-tertiary">
                                  {asset.fqn}
                                </span>
                              </span>
                            </label>
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </div>
                {found.data && found.data.total > results.length && (
                  <p className="tw:text-xs tw:text-tertiary">
                    Showing {results.length} of {found.data.total}; search to narrow it.
                  </p>
                )}
              </div>

              <div className="tw:flex tw:min-w-0 tw:flex-col tw:gap-2">
                <p className="tw:text-sm tw:font-medium tw:text-secondary">
                  Chosen <span className="tw:text-tertiary">({chosen.length})</span>
                </p>
                {chosen.length === 0 ? (
                  <p className="tw:rounded-lg tw:border tw:border-dashed tw:border-secondary tw:px-3 tw:py-6 tw:text-center tw:text-sm tw:text-tertiary">
                    Nothing chosen yet.
                  </p>
                ) : (
                  <ul aria-label="Chosen tables" className="tw:flex tw:flex-col tw:gap-1.5">
                    {chosen.map((asset, i) => (
                      <li
                        className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:px-3 tw:py-2"
                        key={asset.fqn}>
                        <div className="tw:min-w-0 tw:flex-1">
                          <p className="tw:truncate tw:font-mono tw:text-xs tw:text-secondary">{asset.fqn}</p>
                          <StandingNote standing={standings[i]} template={templates[i]?.data} />
                        </div>
                        <button
                          aria-label={`Remove ${asset.fqn}`}
                          className="tw:cursor-pointer tw:rounded tw:p-0.5 tw:text-fg-quaternary tw:hover:text-primary"
                          onClick={() => toggle(asset)}
                          type="button">
                          <XClose className="tw:size-4" />
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          </Step>

          <Step
            description="Asked once for every table. Where the tables' templates differ, the stricter one applies."
            step={2}
            title="Why, and for how long">
            <div className="tw:flex tw:max-w-2xl tw:flex-col tw:gap-4">
              {going.length > 0 && form.guidance && (
                // Plain text on purpose, as on the single-table form.
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
                  Why you need them <span className="tw:text-error-primary">*</span>
                </span>
                <textarea
                  aria-label="Why you need them"
                  className={`${FIELD} tw:min-h-20 tw:resize-y`}
                  onChange={(event) => setReason(event.target.value)}
                  placeholder="What the data is for; the approvers decide on what you write here"
                  value={reason}
                />
                {form.minReasonLength > 1 && (
                  <span
                    className={`tw:text-xs ${
                      trimmed > 0 && trimmed < form.minReasonLength ? 'tw:text-error-primary' : 'tw:text-tertiary'
                    }`}>
                    At least {form.minReasonLength} characters · {trimmed} so far
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
                    ariaLabel="Reference"
                    className="tw:max-w-sm"
                    onChange={(value) => setReference(value.slice(0, MAX_REFERENCE))}
                    value={reference}
                  />
                </label>
              )}

              <div className="tw:flex tw:flex-col tw:gap-1.5">
                <span className="tw:text-sm tw:font-medium tw:text-secondary">For how long</span>
                {(form.durations.length > 0 || form.allowUntilRevoked) && (
                  <div aria-label="Durations" className="tw:flex tw:flex-wrap tw:gap-1.5" role="group">
                    {form.durations.map((option) => (
                      <button
                        aria-pressed={parsedDays === option}
                        className={chip(parsedDays === option)}
                        key={option}
                        onClick={() => setDays(String(option))}
                        type="button">
                        {option} days
                      </button>
                    ))}
                    {form.allowUntilRevoked && (
                      <button
                        aria-pressed={days.trim() === ''}
                        className={chip(days.trim() === '')}
                        onClick={() => setDays('')}
                        type="button">
                        Until revoked
                      </button>
                    )}
                  </div>
                )}
                <div className="tw:flex tw:items-center tw:gap-2">
                  <TextField
                    ariaLabel="Days"
                    className="tw:w-20"
                    onChange={(value) => setDays(value.replace(/[^0-9]/g, ''))}
                    placeholder="—"
                    value={days}
                  />
                  <span className="tw:text-sm tw:text-tertiary">
                    days{days.trim() === '' && form.allowUntilRevoked ? ' (until revoked)' : ''} · at most{' '}
                    {ceiling}
                  </span>
                </div>
              </div>
            </div>
          </Step>

          <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-end tw:gap-3 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-5 tw:py-3">
            {problem && chosen.length > 0 && (
              <p className="tw:mr-auto tw:flex tw:items-center tw:gap-1.5 tw:text-sm tw:text-tertiary" role="status">
                <InfoCircle aria-hidden className="tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
                {problem}
              </p>
            )}
            <Button color="primary" iconLeading={Send01} isDisabled={!ready} size="sm" type="submit">
              {send.isPending
                ? 'Sending…'
                : going.length > 1
                  ? `Send ${going.length} requests`
                  : 'Send request'}
            </Button>
          </div>
        </>
      )}
    </form>
  );
}

function StandingNote({ standing, template }: { standing: Standing; template?: RequestTemplate }) {
  const line = 'tw:mt-0.5 tw:flex tw:flex-wrap tw:items-center tw:gap-1.5 tw:text-xs';
  switch (standing.kind) {
    case 'checking':
      return <p className={`${line} tw:text-tertiary`}>Checking…</p>;
    case 'failed':
      return <p className={`${line} tw:text-error-primary`}>{standing.message}; it will be left out.</p>;
    case 'readable':
      return <p className={`${line} tw:text-tertiary`}>You can already read it; it will be left out.</p>;
    case 'requested':
      return (
        <p className={`${line} tw:text-tertiary`}>
          Already requested; it will be left out.{' '}
          <Link
            className="tw:font-semibold tw:text-brand-secondary tw:hover:underline"
            to={`/requests?tab=mine&status=&id=${encodeURIComponent(standing.requestId)}`}>
            See the request
          </Link>
        </p>
      );
    case 'blocked':
      return (
        <p className={`${line} tw:text-warning-primary`}>
          <AlertTriangle aria-hidden className="tw:size-3.5 tw:shrink-0" />
          {standing.by
            ? `Asking would not help: ${standing.by} still refuses. It will be left out.`
            : 'It cannot be asked for; it will be left out.'}
        </p>
      );
    case 'ready':
      return (
        <p className={`${line} tw:text-success-primary`}>
          <CheckCircle aria-hidden className="tw:size-3.5 tw:shrink-0" />
          Will be requested
          {template?.id && (
            <Badge color="gray" size="sm" type="pill-color">
              {template.name}
            </Badge>
          )}
        </p>
      );
  }
}

function Results({ outcomes, onAgain }: { outcomes: Outcome[]; onAgain: () => void }) {
  const sent = outcomes.filter((o) => 'sent' in o).length;
  return (
    <section
      aria-label="What was sent"
      className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
      <p className="tw:text-md tw:font-semibold tw:text-primary">
        {sent === outcomes.length
          ? sent === 1
            ? 'Request sent'
            : `${sent} requests sent`
          : `${sent} of ${outcomes.length} requests sent`}
      </p>
      <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
        You will be let in to each table once its request is approved and set up.
      </p>
      <ul className="tw:mt-4 tw:flex tw:flex-col tw:gap-1.5">
        {outcomes.map((o) => (
          <li
            className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:px-3 tw:py-2 tw:text-sm"
            key={o.fqn}>
            {'sent' in o ? (
              <CheckCircle aria-hidden className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-success-primary" />
            ) : (
              <AlertTriangle aria-hidden className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-error-primary" />
            )}
            <span className="tw:min-w-0 tw:flex-1">
              <span className="tw:block tw:truncate tw:font-mono tw:text-xs tw:text-secondary">{o.fqn}</span>
              {'sent' in o ? (
                <Link
                  className="tw:font-semibold tw:text-brand-secondary tw:hover:underline"
                  to={`/requests/${encodeURIComponent(o.sent.ticket)}`}>
                  {o.sent.ticket}
                </Link>
              ) : (
                <span className="tw:text-error-primary">{o.failed}</span>
              )}
            </span>
          </li>
        ))}
      </ul>
      <div className="tw:mt-4 tw:flex tw:gap-2">
        <Link
          className="tw:inline-flex tw:items-center tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:font-semibold tw:text-secondary tw:shadow-xs tw:hover:bg-primary_hover"
          to="/requests?tab=mine">
          My requests
        </Link>
        <Button color="secondary" onPress={onAgain} size="sm">
          Ask for more
        </Button>
      </div>
    </section>
  );
}

function daysText(days: number | null): string {
  return days === null ? '' : String(days);
}

function chip(active: boolean) {
  return `tw:cursor-pointer tw:rounded-md tw:border tw:px-2.5 tw:py-1 tw:text-sm ${
    active
      ? 'tw:border-brand tw:bg-brand-primary tw:text-brand-secondary'
      : 'tw:border-secondary tw:text-tertiary tw:hover:text-primary'
  }`;
}
