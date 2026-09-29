import { useDeferredValue, useMemo, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle,
  Columns03,
  Eye,
  EyeOff,
  Play,
  Rows03,
  ShieldTick,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import {
  apiErrorMessage,
  fetchAsset,
  fetchAssets,
  type ColumnDetail,
} from '../../api/client';
import { fetchPrincipals } from '../../api/governance';
import { ENFORCED_ENVIRONMENT } from '../../api/policies';
import {
  simulate,
  type DecisionAsk,
  type PolicyDecision,
  type ResolvedColumnMask,
  type ResolvedRowPredicate,
} from '../../api/decisions';
import { Field, Select, TextField } from '../policies/controls';
import { PurposeSelect } from '../policies/purposePickers';
import { describeMasking } from '../policies/policyLanguage';

/**
 * "View as user" — the screen a policy is checked on before it is published
 * (FR-5.2).
 *
 * The plan calls this the feature that decides whether anyone dares turn
 * enforcement on, and that is not rhetoric: an author who cannot see the
 * consequences of a rule will not publish it, and an estate nobody dares
 * govern is not governed.
 *
 * Nothing here reimplements the engine. The answer comes from
 * `POST /v1/decisions`, the same call the query proxy makes, so what this page
 * shows is the decision rather than a prediction of it. The whole value of the
 * screen rests on that: a simulator that approximates enforcement is worse
 * than no simulator, because people trust it.
 *
 * ## Why the moment and the address are fields
 *
 * A policy with an 08:00–18:00 window and a policy gated on an address range
 * are both untestable if the only moment you can ask about is now and the only
 * address is your own. Being able to pass them is what turns "we think this
 * denies out-of-hours access" into something anyone can check in a second.
 */
export default function SimulatorPage() {
  const [params, setParams] = useSearchParams();

  const [principal, setPrincipal] = useState(params.get('principal') ?? '');
  const [assetFqn, setAssetFqn] = useState(params.get('asset') ?? '');
  const [at, setAt] = useState('');
  const [ip, setIp] = useState('');
  const [purpose, setPurpose] = useState('');
  const [environment, setEnvironment] = useState(
    params.get('environment') ?? ENFORCED_ENVIRONMENT
  );

  /*
   * The question that was asked, not the one being typed. A simulation is a
   * statement about one person at one moment; re-running it under the cursor
   * would mean the answer on screen and the fields above it were describing
   * different questions.
   */
  const [asked, setAsked] = useState<DecisionAsk | null>(null);

  const decision = useQuery({
    queryKey: ['decision', asked],
    queryFn: () => simulate(asked!),
    enabled: asked !== null,
    retry: false,
  });

  // The same cache key the asset page uses, so opening one after the other
  // costs one request rather than two.
  const asset = useQuery({
    queryKey: ['catalog-asset', asked?.assetFqn],
    queryFn: () => fetchAsset(asked!.assetFqn),
    enabled: asked !== null,
    retry: false,
  });

  const ready = principal.trim() !== '' && assetFqn.trim() !== '';

  function ask() {
    if (!ready) return;
    const next: DecisionAsk = {
      principal: principal.trim(),
      assetFqn: assetFqn.trim(),
      at: at ? new Date(at).toISOString() : null,
      ip: ip.trim() || null,
      purpose: purpose.trim() || null,
      environment,
    };
    setAsked(next);
    // Shareable: a denial somebody disputes is worth sending as a link rather
    // than as a description of which boxes to fill in.
    const query = new URLSearchParams({
      principal: next.principal,
      asset: next.assetFqn,
    });
    if (environment !== ENFORCED_ENVIRONMENT) {
      query.set('environment', environment);
    }
    setParams(query, { replace: true });
  }

  return (
    <>
      <header>
        <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">
          Simulator
        </h1>
        <p className="tw:mt-2 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
          Ask the engine what one person would see in one table, at a moment
          you choose. The answer is the decision itself — the same call the
          query proxy makes — not a preview of it.
        </p>
      </header>

      <div className="tw:mt-6 tw:grid tw:gap-6 tw:lg:grid-cols-3">
        <form
          className="tw:space-y-4 tw:self-start tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5"
          onSubmit={(event) => {
            event.preventDefault();
            ask();
          }}>
          <PersonField onChange={setPrincipal} value={principal} />
          <AssetField onChange={setAssetFqn} value={assetFqn} />

          <Field
            hint="Their local time. Leave it empty to ask about now."
            label="At">
            <TextField onChange={setAt} type="datetime-local" value={at} />
          </Field>

          <Field
            hint="Only matters where a policy names an address range."
            label="From address">
            <TextField onChange={setIp} placeholder="203.0.113.10" value={ip} />
          </Field>

          <Field
            hint="From the register. Some policies grant access only for a stated purpose."
            label="Purpose">
<PurposeSelect onChange={setPurpose} value={purpose} />
          </Field>

          <Field
            hint={
              environment === ENFORCED_ENVIRONMENT
                ? undefined
                : `The engine enforces ${ENFORCED_ENVIRONMENT}. This answer is about a policy set that never runs.`
            }
            label="Environment">
            <Select
              onChange={setEnvironment}
              options={[
                {
                  value: 'dev',
                  label: 'dev',
                  hint: 'Authoring only — not enforced',
                },
                {
                  value: 'uat',
                  label: 'uat',
                  hint: 'Authoring only — not enforced',
                },
                {
                  value: 'prod',
                  label: 'prod',
                  hint: 'The environment the engine enforces',
                },
              ]}
              value={environment}
            />
          </Field>

          <Button
            iconLeading={Play}
            isDisabled={!ready || decision.isFetching}
            size="md"
            type="submit">
            {decision.isFetching ? 'Asking…' : 'See what they see'}
          </Button>
        </form>

        <div className="tw:space-y-6 tw:lg:col-span-2">
          {asked === null && <Blank />}

          {decision.error != null && (
            <p className="tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
              {apiErrorMessage(decision.error, 'The engine could not answer.')}
            </p>
          )}

          {decision.data && (
            <Answer
              columns={asset.data?.columns ?? []}
              columnsError={asset.error}
              decision={decision.data}
            />
          )}
        </div>
      </div>
    </>
  );
}

// -------------------------------------------------------------------- fields

/**
 * A native datalist rather than a dropdown.
 *
 * The list of people and the list of tables are each as long as the
 * organisation is, and a select that cannot be typed into is unusable at that
 * size. A datalist filters as you type, opens on the down arrow, and still
 * accepts a name that is not in the suggestions — which matters, because a
 * principal the platform has never heard of is a legitimate thing to ask
 * about and the answer is a denial that says exactly that.
 */
function PersonField({
  value,
  onChange,
}: {
  value: string;
  onChange: (next: string) => void;
}) {
  const typed = useDeferredValue(value);
  const { data } = useQuery({
    queryKey: ['principals', 'simulate', typed],
    queryFn: () => fetchPrincipals({ type: 'USER', search: typed, limit: 20 }),
    staleTime: 60_000,
  });

  return (
    <Field hint="Anyone in the identity cache." label="Person">
      <TextField
        list="simulate-people"
        onChange={onChange}
        placeholder="analyst_a"
        value={value}
      />
      <datalist id="simulate-people">
        {(data ?? []).map((person) => (
          <option
            key={person.id}
            label={person.displayName ?? undefined}
            value={person.username}
          />
        ))}
      </datalist>
    </Field>
  );
}

function AssetField({
  value,
  onChange,
}: {
  value: string;
  onChange: (next: string) => void;
}) {
  const typed = useDeferredValue(value);
  const { data } = useQuery({
    queryKey: ['catalog-assets', 'simulate', typed],
    queryFn: () => fetchAssets({ search: typed, limit: 20 }),
    staleTime: 60_000,
  });

  return (
    <Field
      hint="The fully qualified name, as the catalogue spells it."
      label="Table">
      <TextField
        list="simulate-assets"
        onChange={onChange}
        placeholder="prod-pg.sales.public.customer"
        value={value}
      />
      <datalist id="simulate-assets">
        {(data?.items ?? []).map((item) => (
          <option key={item.id} label={item.assetType} value={item.fqn} />
        ))}
      </datalist>
    </Field>
  );
}

// -------------------------------------------------------------------- answer

function Answer({
  decision,
  columns,
  columnsError,
}: {
  decision: PolicyDecision;
  columns: ColumnDetail[];
  columnsError: unknown;
}) {
  const allowed = decision.allowed;
  const reasons = decision.reasons ?? [];
  /*
   * A reason is decisive when it argues for the verdict, and which reasons do
   * that flips with the verdict itself.
   *
   * Under an allow, the policies that matched are the ones that granted it.
   * Under a denial they are the opposite: a policy that failed to match is
   * precisely what withheld access ("clearance lt L2" is the answer somebody
   * came here for), while a policy that matched anyway did not cause it. So
   * `matched` alone is the wrong test -- it puts the explanation of a denial
   * behind a disclosure triangle, under a green ALLOW that reads as though it
   * were the reason.
   *
   * The engine's own composition note carries no policy id. It is not one of
   * the policies; it is the sentence about how they add up, and it stays at
   * the top of a denial where it belongs.
   */
  const summary = reasons.filter((reason) => !reason.policyId);
  const fromPolicies = reasons.filter((reason) => Boolean(reason.policyId));
  const decisive = fromPolicies.filter((reason) =>
    allowed ? reason.matched : !reason.matched || reason.effect === 'DENY'
  );
  const considered = fromPolicies.filter(
    (reason) => !decisive.includes(reason)
  );
  const unenforceable = decision.unenforceable ?? [];

  return (
    <>
      <section
        className={`tw:rounded-xl tw:border tw:p-5 ${
          allowed
            ? 'tw:border-success_subtle tw:bg-success-primary'
            : 'tw:border-error_subtle tw:bg-error-primary'
        }`}>
        <div className="tw:flex tw:items-start tw:gap-3">
          {allowed ? (
            <Eye className="tw:mt-0.5 tw:size-5 tw:shrink-0 tw:text-success-primary" />
          ) : (
            <EyeOff className="tw:mt-0.5 tw:size-5 tw:shrink-0 tw:text-error-primary" />
          )}
          <div className="tw:min-w-0">
            <p
              className={`tw:text-md tw:font-semibold ${
                allowed ? 'tw:text-success-primary' : 'tw:text-error-primary'
              }`}>
              {allowed
                ? `${decision.principal} can read this table`
                : `${decision.principal} cannot read this table`}
            </p>
            <p className="tw:mt-1 tw:font-mono tw:text-xs tw:break-all tw:text-quaternary">
              {decision.assetFqn}
            </p>
            {!allowed && (
              // The denial is the whole answer. What would have been masked
              // underneath it is not a smaller version of the same thing; it
              // is irrelevant, and showing it invites the wrong conclusion.
              <p className="tw:mt-2 tw:text-pretty tw:text-sm tw:text-error-primary">
                Nothing in the table is reachable, so there is no row filter or
                masking to show. The reasons below name the policy that decided
                it.
              </p>
            )}
          </div>
        </div>
      </section>

      {unenforceable.length > 0 && (
        <section className="tw:rounded-xl tw:border tw:border-warning_subtle tw:bg-warning-primary tw:p-4">
          <div className="tw:flex tw:items-start tw:gap-2">
            <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-warning-primary" />
            <div>
              <h2 className="tw:text-sm tw:font-semibold tw:text-warning-primary">
                Restrictions the chosen mode cannot express
              </h2>
              <ul className="tw:mt-2 tw:space-y-1">
                {unenforceable.map((item, index) => (
                  <li
                    className="tw:text-pretty tw:text-sm tw:text-warning-primary"
                    key={index}>
                    {item.detail}
                    {item.suggestedMode && (
                      <span className="tw:text-quaternary">
                        {' '}
                        — {MODE_WORDS[item.suggestedMode]} can.
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </section>
      )}

      {allowed && (
        <>
          <Projection
            columns={columns}
            decision={decision}
            error={columnsError}
          />
          <Rows predicates={decision.rowPredicates ?? []} />
        </>
      )}

      <Panel icon={ShieldTick} title="Why">
        {reasons.length === 0 ? (
          <p className="tw:text-pretty tw:text-sm tw:text-tertiary">
            The engine returned no reasons, which should not happen: a decision
            with nothing behind it is one nobody can defend.
          </p>
        ) : (
          <>
            {summary.map((reason, index) => (
              <p
                className="tw:mb-3 tw:text-pretty tw:text-sm tw:font-medium tw:text-primary"
                key={`summary-${index}`}>
                {reason.explanation}
              </p>
            ))}

            <ul className="tw:space-y-2">
              {decisive.map((reason, index) => (
                <li
                  className="tw:rounded-lg tw:border tw:border-secondary tw:p-3"
                  key={index}>
                  <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
                    {reason.policyId ? (
                      <Link
                        className="tw:text-sm tw:font-medium tw:text-brand-secondary tw:hover:underline"
                        to={`/policies/${reason.policyId}`}>
                        {reason.policyName}
                      </Link>
                    ) : (
                      <span className="tw:text-sm tw:font-medium tw:text-primary">
                        {reason.policyName}
                      </span>
                    )}
                    {reason.effect && (
                      /*
                        Coloured by what happened, not by what the policy
                        declares. A green "allow" beside "expression is false
                        for this principal" is the policy's effect stated
                        truthfully and read as its opposite.
                      */
                      <Badge
                        color={
                          !reason.matched
                            ? 'gray'
                            : reason.effect === 'DENY'
                              ? 'error'
                              : 'success'
                        }
                        size="sm"
                        type="pill-color">
                        {reason.matched
                          ? reason.effect.toLowerCase()
                          : `would ${reason.effect.toLowerCase()}, did not match`}
                      </Badge>
                    )}
                    {reason.scopeLevel && (
                      <Badge color="gray" size="sm" type="modern">
                        {reason.scopeLevel === 'ORG'
                          ? 'organisation'
                          : reason.scopeLevel.toLowerCase()}
                      </Badge>
                    )}
                  </div>
                  {reason.explanation && (
                    <p className="tw:mt-1 tw:text-pretty tw:text-sm tw:text-secondary">
                      {reason.explanation}
                    </p>
                  )}
                </li>
              ))}
            </ul>

            {considered.length > 0 && (
              /*
                Kept, but below and quieter. A policy that was looked at and
                did not apply is the answer to "why didn't my rule work",
                which gets asked as often as "why am I blocked" — and mixing
                the two lists buries the handful that decided the outcome.
              */
              <details className="tw:mt-3">
                <summary className="tw:cursor-pointer tw:text-sm tw:text-tertiary">
                  {considered.length}{' '}
                  {allowed
                    ? 'more were considered and did not apply'
                    : 'more did apply and did not change the outcome'}
                </summary>
                <ul className="tw:mt-2 tw:space-y-1.5">
                  {considered.map((reason, index) => (
                    <li className="tw:text-sm tw:text-tertiary" key={index}>
                      <span className="tw:text-secondary">
                        {reason.policyName}
                      </span>
                      {reason.explanation ? ` — ${reason.explanation}` : ''}
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </>
        )}
      </Panel>
    </>
  );
}

/**
 * The table as this person would receive it, column by column.
 *
 * Listed against the catalogue's columns rather than against the decision's
 * mask list, because the interesting row is often the one with no mask on it:
 * "citizen_id is readable" is the finding that sends somebody to write a
 * policy, and a list of masks alone can never show it.
 */
function Projection({
  decision,
  columns,
  error,
}: {
  decision: PolicyDecision;
  columns: ColumnDetail[];
  error: unknown;
}) {
  const masks = useMemo(() => {
    const byName = new Map<string, ResolvedColumnMask>();
    for (const mask of decision.columnMasks ?? []) {
      byName.set(mask.column.toLowerCase(), mask);
    }
    return byName;
  }, [decision]);

  const hidden = useMemo(
    () =>
      new Set((decision.hiddenColumns ?? []).map((name) => name.toLowerCase())),
    [decision]
  );

  const rows = columns.map((column) => {
    const key = column.name.toLowerCase();
    return { column, hidden: hidden.has(key), mask: masks.get(key) };
  });

  const hiddenCount = rows.filter((row) => row.hidden).length;
  const maskedCount = rows.filter((row) => row.mask && !row.hidden).length;
  const readable = rows.length - hiddenCount - maskedCount;

  return (
    <Panel
      icon={Columns03}
      subtitle={
        rows.length === 0
          ? undefined
          : `${readable} as stored · ${maskedCount} masked · ${hiddenCount} hidden`
      }
      title="What they would see">
      {error != null ? (
        <p className="tw:text-pretty tw:text-sm tw:text-tertiary">
          {apiErrorMessage(
            error,
            'The column list could not be read, so only the row filter and the reasons below are shown.'
          )}
        </p>
      ) : rows.length === 0 ? (
        <p className="tw:text-pretty tw:text-sm tw:text-tertiary">
          The cache holds no columns for this asset, so there is nothing to
          show per column. Crawl it first.
        </p>
      ) : (
        <ul className="tw:divide-y tw:divide-secondary">
          {rows.map((row) => (
            <li
              className="tw:flex tw:flex-wrap tw:items-center tw:gap-2 tw:py-2"
              key={row.column.fqn}>
              <span
                className={`tw:font-mono tw:text-xs ${
                  row.hidden
                    ? 'tw:text-quaternary tw:line-through'
                    : 'tw:text-primary'
                }`}>
                {row.column.name}
              </span>
              {row.column.dataType && (
                <span className="tw:text-xs tw:text-quaternary">
                  {row.column.dataType}
                </span>
              )}
              <span className="tw:ml-auto tw:shrink-0">
                {row.hidden ? (
                  <Badge color="gray" size="sm" type="pill-color">
                    not in the results
                  </Badge>
                ) : row.mask ? (
                  <Badge color="warning" size="sm" type="pill-color">
                    {describeMasking(row.mask.masking)}
                    {row.mask.condition ? ' — on some rows' : ''}
                  </Badge>
                ) : (
                  <Badge color="success" size="sm" type="pill-color">
                    as stored
                  </Badge>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function Rows({ predicates }: { predicates: ResolvedRowPredicate[] }) {
  return (
    <Panel icon={Rows03} title="Which rows">
      {predicates.length === 0 ? (
        <p className="tw:text-sm tw:text-secondary">
          Every row. No policy restricts which records they reach.
        </p>
      ) : (
        <ul className="tw:space-y-1.5">
          {predicates.map((predicate, index) => (
            <li
              className="tw:text-pretty tw:text-sm tw:text-secondary"
              key={index}>
              {describePredicate(predicate)}
            </li>
          ))}
          {predicates.length > 1 && (
            <li className="tw:pt-1 tw:text-xs tw:text-tertiary">
              All of these hold at once — layers narrow each other, they never
              widen.
            </li>
          )}
        </ul>
      )}
    </Panel>
  );
}

// ------------------------------------------------------------------- wording

const MODE_WORDS: Record<string, string> = {
  NATIVE_CONFIG: 'source configuration',
  SECURE_VIEW: 'a secure view',
  PROXY: 'the query proxy',
};

const OPERATORS: Record<string, string> = {
  eq: 'is',
  ne: 'is not',
  in: 'is one of',
  notIn: 'is none of',
  gt: 'is above',
  gte: 'is at least',
  lt: 'is below',
  lte: 'is at most',
  contains: 'is under',
  startsWith: 'starts with',
  matches: 'matches',
  exists: 'is set',
  notExists: 'is not set',
};

/**
 * A row restriction in words, with this person's values already in it.
 *
 * The decision carries the values the attribute resolved to, not the name of
 * the attribute the policy was written against, so the sentence can say
 * "branch_code is one of BKK, CNX" rather than "branch_code matches their
 * branch". The second is a restatement of the policy; only the first is an
 * answer about this person.
 */
export function describePredicate(predicate: ResolvedRowPredicate): string {
  const values = predicate.values ?? [];
  const list = values.join(', ');
  // One value is the ordinary case -- most people carry one branch, one
  // department, one country -- and "is one of BKK-01" reads like a machine
  // talking.
  const oneOf = values.length === 1 ? `is ${list}` : `is one of ${list}`;

  switch (predicate.kind) {
    case 'ALWAYS_FALSE':
      return 'No rows at all. The shape of the table stays visible; its contents do not.';
    case 'ATTRIBUTE_COMPARE':
      if (values.length === 0) {
        return `No rows: the filter compares ${predicate.column} with an attribute this person does not carry.`;
      }
      return `Only rows where ${predicate.column} ${
        OPERATORS[predicate.operator ?? 'eq'] ?? predicate.operator
      } ${list}.`;
    case 'IN_LIST':
      if (values.length === 0) {
        return `No rows: the filter on ${predicate.column} has no values for this person.`;
      }
      return `Only rows where ${predicate.column} ${oneOf}.`;
    case 'ENTITLEMENT_JOIN':
      return `Only rows they are entitled to, matched on ${predicate.entitlementKey}.`;
    case 'LOOKUP': {
      // The decision carries this person's own values for each key. What the
      // mapping gives them is read only when a query runs, so it is never
      // shown here.
      const lookup = predicate.lookup;
      if (!lookup) {
        return `No rows: the filter on ${predicate.column} names no mapping table.`;
      }
      const keys = (lookup.keys ?? [])
        .map((key) => `${key.column} ${key.values.join(', ')}`)
        .join(' and ');
      const when =
        lookup.mode === 'READ_VALUES'
          ? 'ARAK reads them when the query runs'
          : 'the source reads them as part of the query';
      return `Only rows where ${predicate.column} is one of the ${lookup.valueColumn} values in ${lookup.table} for ${keys}; ${when}.`;
    }
    case 'RAW_PREDICATE':
      return `Only rows where ${predicate.rawPredicate}.`;
    default:
      return 'A row filter this screen has no wording for yet.';
  }
}

// --------------------------------------------------------------------- shell

function Blank() {
  return (
    <section className="tw:rounded-xl tw:border tw:border-dashed tw:border-secondary tw:p-8">
      <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
        Nothing asked yet
      </h2>
      <p className="tw:mt-1 tw:max-w-xl tw:text-pretty tw:text-sm tw:text-tertiary">
        Name a person and a table. The answer says whether they reach it at
        all, which rows they get, what each column looks like in their hands,
        and which policy decided each of those.
      </p>
    </section>
  );
}

function Panel({
  title,
  subtitle,
  icon: Icon,
  children,
}: {
  title: string;
  subtitle?: string;
  icon: typeof ShieldTick;
  children: ReactNode;
}) {
  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
      <div className="tw:flex tw:items-center tw:gap-2">
        <Icon className="tw:size-4 tw:shrink-0 tw:text-tertiary" />
        <h2 className="tw:text-sm tw:font-semibold tw:text-primary">{title}</h2>
        {subtitle && (
          <span className="tw:ml-auto tw:text-xs tw:text-tertiary">
            {subtitle}
          </span>
        )}
      </div>
      <div className="tw:mt-3">{children}</div>
    </section>
  );
}
