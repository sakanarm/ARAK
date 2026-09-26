import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { AlertTriangle, CheckCircle, InfoCircle, Key01, Send01 } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage } from '../../api/client';
import {
  describeApprovers,
  describeOnReject,
  describeRule,
  requestAccess,
  type AccessRequest,
  type Refusal,
  type Route,
} from '../../api/accessRequests';
import { joinOf, stepsOf } from '../../api/accessWorkflows';
import {
  checkAnswers,
  fetchEffectiveTemplate,
  MAX_DAYS,
  MAX_REFERENCE,
  TEMPLATES_KEY,
  type RequestForm,
  type RequestTemplate,
} from '../../api/requestTemplates';
import { FIELD, Select, TextField } from '../policies/controls';

/**
 * What to do about a refusal, under the refusal itself.
 *
 * <p>Only what the server said is offered. Whether a request could help is the
 * engine's answer — the same decision re-run with a grant from the owner in the
 * stack — so the button is drawn only when a "yes" from the owner would open
 * the table. When a DENY or a stricter layer would still refuse, the box says
 * which policy that is instead, because asking the owner would put a request in
 * front of somebody who cannot give the answer being asked for.
 *
 * <p>The catalog asks the same question before anybody has run anything
 * (`from="catalog"`): there is no statement and no refusal to send along, so
 * the request carries only the reason, and the box says "you cannot read this
 * yet" rather than pointing at a query that never ran.
 */
export default function RequestAccess({
  refusal,
  sourceId,
  sql,
  purpose,
  from = 'query',
}: {
  refusal: Refusal;
  sourceId: string | null;
  sql: string;
  purpose: string | null;
  from?: 'query' | 'catalog';
}) {
  const catalog = from === 'catalog';
  const [asking, setAsking] = useState(false);
  const [sent, setSent] = useState<AccessRequest | null>(null);

  if (!refusal.assetFqn) {
    return null;
  }

  if (sent || refusal.openRequestId) {
    return <RequestedNote refusal={refusal} sent={sent} />;
  }

  if (!refusal.requestable) {
    return <BlockedNote refusal={refusal} />;
  }

  if (!asking) {
    return (
      <Card>
        <div className="tw:min-w-0 tw:flex-1">
          <p className="tw:text-sm tw:font-semibold tw:text-primary">
            {catalog ? 'You cannot read this table yet' : 'The owner can let you in'}
          </p>
          <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">
            {catalog && 'The owner can let you in. '}
            {whoDecides(refusal)}
          </p>
        </div>
        <Button color="primary" iconLeading={Send01} onPress={() => setAsking(true)} size="sm">
          {catalog ? 'Request access' : 'Request access from the owner'}
        </Button>
      </Card>
    );
  }

  return (
    <RequestAccessForm
      className="tw:mt-3 tw:shadow-xs"
      from={from}
      onCancel={() => setAsking(false)}
      onSent={setSent}
      purpose={purpose}
      refusal={refusal}
      sourceId={sourceId}
      sql={sql}
    />
  );
}

/**
 * The request itself: why, for how long, and whatever the table's template asks.
 *
 * <p>Its own component so the catalog can open it in a dialog from the page
 * header, where OpenMetadata puts an asset's actions, while the Query page
 * keeps it inline under the refusal it answers. Both send the same request.
 *
 * <p>The fields come from the template the table resolves to: a list of
 * purposes, the durations on offer and the longest allowed, a reference such
 * as a DPIA number, the shortest reason, and guidance from whoever wrote it.
 * Until that arrives, and if it cannot be fetched, the built-in form stands in;
 * the server holds the request to the real template either way.
 */
export function RequestAccessForm({
  refusal,
  sourceId,
  sql,
  purpose,
  from = 'query',
  onCancel,
  onSent,
  className = '',
}: {
  refusal: Refusal;
  sourceId: string | null;
  sql: string;
  purpose: string | null;
  from?: 'query' | 'catalog';
  onCancel: () => void;
  onSent: (request: AccessRequest) => void;
  /** Added to the frame: a margin inline, a heavier shadow in a dialog. */
  className?: string;
}) {
  const catalog = from === 'catalog';
  const queryClient = useQueryClient();
  const loaded = useQuery({
    queryKey: [...TEMPLATES_KEY, 'effective', refusal.assetFqn],
    queryFn: () => fetchEffectiveTemplate(refusal.assetFqn!),
    enabled: !!refusal.assetFqn,
    retry: false,
    staleTime: 60_000,
  });
  const template = loaded.data ?? BUILT_IN_TEMPLATE;
  const form = template.form;

  const [reason, setReason] = useState('');
  const [days, setDays] = useState(daysText(form.defaultDays));
  const [chosen, setChosen] = useState(pickPurpose(form, purpose));
  const [reference, setReference] = useState('');
  // A template that arrives after the form opened starts it where it says;
  // what was typed as the reason stays.
  const [shapedBy, setShapedBy] = useState(template.id);
  if (shapedBy !== template.id) {
    setShapedBy(template.id);
    setDays(daysText(form.defaultDays));
    setChosen(pickPurpose(form, purpose));
  }

  const listed = form.purposes.length > 0;
  const sentPurpose = listed ? chosen || null : chosen.trim() || purpose;
  const parsedDays = days.trim() === '' ? null : Number.parseInt(days, 10);
  const ceiling = form.maxDays ?? MAX_DAYS;
  const daysValid =
    parsedDays === null ? form.allowUntilRevoked : parsedDays >= 1 && parsedDays <= ceiling;
  const problem = checkAnswers(form, {
    reason,
    purpose: sentPurpose,
    days: parsedDays,
    reference: form.referenceLabel ? reference : '',
  });

  const send = useMutation({
    mutationFn: () =>
      requestAccess({
        assetFqn: refusal.assetFqn!,
        sourceId,
        reason: reason.trim(),
        purpose: sentPurpose,
        days: parsedDays,
        attemptedSql: catalog ? null : sql,
        deniedBy: catalog ? null : refusal.message,
        ...(form.referenceLabel ? { reference: reference.trim() || null } : {}),
      }),
    onSuccess: (request) => {
      void queryClient.invalidateQueries({ queryKey: ['access-requests'] });
      onSent(request);
    },
  });

  const ready = problem === null && !send.isPending;
  const trimmed = reason.trim().length;

  return (
    <form
      aria-label="Request access"
      className={`tw:rounded-xl tw:border tw:border-secondary tw:bg-primary ${className}`}
      onSubmit={(event) => {
        event.preventDefault();
        if (ready) send.mutate();
      }}>
      <div className="tw:flex tw:items-start tw:gap-3 tw:border-b tw:border-secondary tw:px-4 tw:py-3">
        <Tile />
        <div className="tw:min-w-0 tw:flex-1">
          <p className="tw:text-sm tw:font-semibold tw:text-primary">Request access</p>
          <p className="tw:truncate tw:font-mono tw:text-xs tw:text-tertiary">
            {refusal.assetFqn}
          </p>
        </div>
        {template.id && (
          <Badge color="gray" size="sm" type="pill-color">
            {template.name}
          </Badge>
        )}
      </div>

      <div className="tw:flex tw:flex-col tw:gap-4 tw:px-4 tw:py-4">
        {form.guidance && (
          // Plain text on purpose: whoever wrote the template does not get to
          // put markup in front of every requester.
          <div
            aria-label="Guidance"
            className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-secondary tw:px-3 tw:py-2.5 tw:text-sm tw:text-secondary"
            role="note">
            <InfoCircle aria-hidden className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
            <p className="tw:min-w-0 tw:whitespace-pre-line tw:break-words">{form.guidance}</p>
          </div>
        )}
        {refusal.route && !refusal.stranded && !ownersOnly(refusal.route) && (
          <RouteSteps route={refusal.route} />
        )}
        <p className="tw:text-sm tw:text-tertiary">
          {whoDecides(refusal)}{' '}
          {catalog
            ? 'Say what the data is for; that is what they decide on.'
            : 'The statement you ran and the refusal go with the request, so they can see what you were trying to do.'}
        </p>

        {(listed || form.purposeRequired) && (
          <div className="tw:flex tw:flex-col tw:gap-1.5">
            <span className="tw:text-sm tw:font-medium tw:text-secondary">
              Purpose {form.purposeRequired && <span className="tw:text-error-primary">*</span>}
            </span>
            {listed ? (
              <Select
                ariaLabel="Purpose"
                className="tw:max-w-sm"
                onChange={(value) => setChosen(value === NO_PURPOSE ? '' : value)}
                options={[
                  ...(form.purposeRequired ? [] : [{ value: NO_PURPOSE, label: 'No particular purpose' }]),
                  ...form.purposes.map((p) => ({ value: p, label: p })),
                ]}
                placeholder="Choose a purpose"
                value={chosen || (form.purposeRequired ? '' : NO_PURPOSE)}
              />
            ) : (
              <TextField
                ariaLabel="Purpose"
                className="tw:max-w-sm"
                onChange={setChosen}
                placeholder={purpose ?? 'What the data is for'}
                value={chosen}
              />
            )}
          </div>
        )}

        <label className="tw:flex tw:flex-col tw:gap-1.5">
          <span className="tw:text-sm tw:font-medium tw:text-secondary">
            Why you need it <span className="tw:text-error-primary">*</span>
          </span>
          <textarea
            aria-label="Why you need it"
            className={`${FIELD} tw:min-h-20 tw:resize-y`}
            onChange={(event) => setReason(event.target.value)}
            placeholder="What the data is for, and for how long"
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
              ariaLabel={form.referenceLabel}
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
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <TextField
              ariaLabel="Days"
              className="tw:w-20"
              onChange={(value) => setDays(value.replace(/[^0-9]/g, ''))}
              placeholder="—"
              value={days}
            />
            <span className="tw:text-sm tw:text-tertiary">
              days{days.trim() === '' && form.allowUntilRevoked ? ' (until revoked)' : ''}
              {form.maxDays ? ` · at most ${form.maxDays}` : ''}
            </span>
            {purpose && !listed && !form.purposeRequired && (
              <Badge color="gray" size="sm" type="pill-color">
                Purpose: {purpose}
              </Badge>
            )}
          </div>
          {!daysValid && (
            <span className="tw:text-xs tw:text-error-primary">
              Between 1 and {ceiling} days{form.allowUntilRevoked ? ', or blank' : ''}.
            </span>
          )}
        </div>
        {send.isError && (
          <p
            className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
            role="alert">
            <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
            <span>{apiErrorMessage(send.error, 'The request was not sent.')}</span>
          </p>
        )}
      </div>

      <div className="tw:flex tw:justify-end tw:gap-2 tw:border-t tw:border-secondary tw:px-4 tw:py-3">
        <Button color="secondary" onPress={onCancel} size="sm">
          Cancel
        </Button>
        <Button color="primary" iconLeading={Send01} isDisabled={!ready} size="sm" type="submit">
          {send.isPending ? 'Sending…' : 'Send request'}
        </Button>
      </div>
    </form>
  );
}

/** What the form asked before templates, and asks while the table's template loads. */
export const BUILT_IN_TEMPLATE: RequestTemplate = {
  id: null,
  name: 'Built-in',
  description: null,
  scopeFqn: null,
  matchFacets: [],
  enabled: true,
  form: {
    purposes: [],
    purposeRequired: false,
    durations: [7, 30, 90],
    defaultDays: 30,
    maxDays: null,
    allowUntilRevoked: true,
    referenceLabel: null,
    referenceRequired: false,
    minReasonLength: 1,
    guidance: null,
  },
};

const NO_PURPOSE = '__none__';

function daysText(days: number | null): string {
  return days === null ? '' : String(days);
}

/**
 * The purpose the form starts on.
 *
 * <p>The Query page's purpose is kept when the template offers it, spelled as
 * the template spells it; a purpose the list does not have is dropped rather
 * than sent to be refused. With no list, the field starts empty and the
 * Query page's purpose is what goes unless something else is typed.
 */
function pickPurpose(form: RequestForm, purpose: string | null): string {
  if (!purpose || form.purposes.length === 0) {
    return '';
  }
  return form.purposes.find((p) => p.toLowerCase() === purpose.toLowerCase()) ?? '';
}

function chip(active: boolean) {
  return `tw:cursor-pointer tw:rounded-md tw:border tw:px-2.5 tw:py-1 tw:text-sm ${
    active
      ? 'tw:border-brand tw:bg-brand-primary tw:text-brand-secondary'
      : 'tw:border-secondary tw:text-tertiary tw:hover:text-primary'
  }`;
}

/**
 * The route is the built-in one: any one owner of the table approves.
 *
 * <p>Then the owners' names say it better than the route does, so the page
 * keeps saying "Decided by ann" rather than "Owners of the table".
 */
function ownersOnly(route: Route): boolean {
  const [only, ...rest] = route.stages;
  return (
    rest.length === 0 &&
    only !== undefined &&
    only.rule === 'ANY' &&
    only.approvers.length === 1 &&
    only.approvers[0] === 'Owners of the table'
  );
}

/**
 * Who a request will wait for, in a sentence.
 *
 * <p>The owners by name while the route is the built-in one, the workflow's
 * steps once somebody configured one -- the owners might not be asked at all
 * then. A request that would wait for nobody says so whatever the route.
 */
export function whoDecides(refusal: Refusal): string {
  const route = refusal.route;
  if (refusal.stranded || !route || route.stages.length === 0 || ownersOnly(route)) {
    return describeApprovers(refusal.approvers, refusal.stranded);
  }
  const steps = stepsOf(route.stages).map((group) =>
    group.length === 1
      ? group[0].name
      : joinOf(group) === 'ANY'
        ? `${group.map((stage) => stage.name).join(' or ')}, whichever passes first`
        : `${group.map((stage) => stage.name).join(' and ')} together`
  );
  return `It goes through the “${route.workflowName}” workflow: ${steps.join(', then ')}.`;
}

/**
 * The steps of the route, one under the other, with who each stage asks.
 *
 * <p>Shown in the form, before the request is sent, so the requester knows
 * how many answers it takes and from whom; stages that share a step are
 * asked at the same time.
 */
export function RouteSteps({ route }: { route: Route }) {
  const steps = stepsOf(route.stages);
  return (
    <div
      aria-label="Approval route"
      className="tw:rounded-lg tw:border tw:border-secondary tw:px-3 tw:py-2.5"
      role="group">
      <p className="tw:text-xs tw:text-tertiary">
        Workflow <span className="tw:font-semibold tw:text-secondary">{route.workflowName}</span>
      </p>
      <ol className="tw:mt-2 tw:flex tw:flex-col tw:gap-2">
        {steps.map((group, index) => (
          <li className="tw:flex tw:items-start tw:gap-2.5" key={group[0].step}>
            <span
              aria-hidden
              className="tw:flex tw:size-5 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-full tw:bg-utility-brand-50 tw:text-xs tw:font-semibold tw:text-brand-secondary">
              {index + 1}
            </span>
            <span className="tw:flex tw:min-w-0 tw:flex-col tw:gap-1">
              {group.length > 1 && (
                <span className="tw:text-xs tw:text-tertiary">
                  Step {index + 1} · in parallel{joinOf(group) === 'ANY' ? ', any one is enough' : ''}
                </span>
              )}
              {group.map((stage) => (
                <span className="tw:block" key={`${stage.step}-${stage.name}`}>
                  <span className="tw:text-sm tw:font-medium tw:text-primary">{stage.name}</span>
                  <span className="tw:block tw:text-xs tw:text-tertiary">
                    {describeRule(stage.rule, stage.minApprovals)} ·{' '}
                    {describeOnReject(stage.onReject, stage.rule, stage.minApprovals)}
                  </span>
                  <span className="tw:block tw:text-xs tw:text-secondary">
                    Asks {stage.approvers.join(', ') || 'nobody'}
                  </span>
                </span>
              ))}
            </span>
          </li>
        ))}
      </ol>
    </div>
  );
}

/** The key in a brand tile, as the cards elsewhere carry their icon. */
function Tile() {
  return (
    <span
      aria-hidden
      className="tw:flex tw:size-9 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-brand-50">
      <Key01 className="tw:size-4 tw:text-fg-brand-primary" />
    </span>
  );
}

function Card({ children }: { children: ReactNode }) {
  return (
    <div className="tw:mt-3 tw:flex tw:flex-wrap tw:items-center tw:gap-3 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-4 tw:py-3 tw:shadow-xs">
      <Tile />
      {children}
    </div>
  );
}

/** A request is already on its way to the people who decide it. */
export function RequestedNote({
  refusal,
  sent,
  className,
}: {
  refusal: Refusal;
  sent: AccessRequest | null;
  className?: string;
}) {
  return (
    <Note className={className} icon={CheckCircle} tone="success">
      <span className="tw:font-semibold">{sent ? 'Request sent' : 'Already requested'}</span>{' '}
      for <span className="tw:font-mono">{refusal.assetFqn}</span>.{' '}
      {whoDecides(sent ? { ...refusal, approvers: sent.approvers, stranded: sent.stranded } : refusal)}{' '}
      You will be let in once it is approved and set up — follow it under{' '}
      <Link className="tw:font-semibold tw:text-brand-secondary tw:hover:underline" to="/requests">
        Access requests
      </Link>
      .
    </Note>
  );
}

/**
 * A grant would not help, and which policy is in the way.
 *
 * <p>Nothing to say when the server gave no reason: that is a run on
 * somebody else's behalf, and a request is theirs to make, not ours.
 */
export function BlockedNote({ refusal, className }: { refusal: Refusal; className?: string }) {
  return refusal.blockedBy ? (
    <Note className={className} icon={AlertTriangle} tone="warning">
      Asking the owner would not help: even with their grant,{' '}
      <strong>{refusal.blockedBy}</strong> still refuses. Access to{' '}
      <span className="tw:font-mono">{refusal.assetFqn}</span> has to change in that policy.
    </Note>
  ) : null;
}

function Note({
  children,
  icon: Icon,
  tone,
  className = 'tw:mt-3 tw:shadow-xs',
}: {
  children: ReactNode;
  icon: typeof CheckCircle;
  tone: 'success' | 'warning';
  className?: string;
}) {
  return (
    <p
      className={`${className} tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2.5 tw:text-sm tw:text-secondary`}>
      <Icon
        className={`tw:mt-0.5 tw:size-4 tw:shrink-0 ${
          tone === 'success' ? 'tw:text-fg-success-primary' : 'tw:text-fg-warning-primary'
        }`}
      />
      <span>{children}</span>
    </p>
  );
}
