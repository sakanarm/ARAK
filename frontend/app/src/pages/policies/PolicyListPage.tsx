import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { EyeOff, Plus, SearchLg, ShieldTick } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Dropdown } from '@openmetadata/ui-core-components/components/base/dropdown/dropdown';
import { Input } from '@openmetadata/ui-core-components/components/base/input/input';
import { apiErrorMessage } from '../../api/client';
import {
  countPolicies,
  EVERY_CONNECTION,
  fetchPolicies,
  type ListedPolicy,
  type PolicyReach,
  type StoredPolicy,
} from '../../api/policies';
import {
  ENFORCEMENT_MODES,
  type EnforcementMode,
  fetchSources,
} from '../../api/sources';
import { engineLabel, useSourceEngines } from '../../engines';
import { PAGE_SIZES, Pager } from '../../components/Pager';
import { relativeTime } from '../../components/widgets';
import { shortFqn } from '../../lib/fqn';
import { Select } from './controls';
import { describeSelector, describeSubject } from './policyLanguage';
import { NEW_POLICY_PATH } from './policyKind';

/**
 * Every policy in the platform, with what it says rather than what it is called.
 *
 * A list of policy names is close to useless for the question people actually
 * arrive with — "is there already something covering PII in Finance?" — so each
 * row carries its own one-line readback, generated from the document. Names
 * drift from intent; the document cannot.
 */

/**
 * How many policies fit before the page is longer than it is useful.
 *
 * Fifteen rather than the catalogue's twenty-five. A policy row is a title
 * and a sentence of readback where an asset card is a name and two chips, so
 * the same count of rows is roughly twice the page.
 */
const PAGE_SIZE = 15;

const STATE_TONE: Record<string, 'success' | 'gray' | 'warning' | 'error'> = {
  ACTIVE: 'success',
  DRAFT: 'gray',
  PENDING_APPROVAL: 'warning',
  DISABLED: 'warning',
  ARCHIVED: 'gray',
};

const STATE_LABEL: Record<string, string> = {
  ACTIVE: 'Active',
  DRAFT: 'Draft',
  PENDING_APPROVAL: 'Pending approval',
  DISABLED: 'Disabled',
  ARCHIVED: 'Archived (Not active)',
};

const LEVEL_LABEL: Record<string, string> = {
  ORG: 'Organisation',
  DOMAIN: 'Domain',
  SERVICE: 'Service',
  DATABASE: 'Database',
  SCHEMA: 'Schema',
  TABLE: 'Table',
  COLUMN: 'Column',
};

/** The two kinds as tabs: they answer different questions, so they are read apart. */
const KINDS = [
  { value: '', label: 'All policies' },
  { value: 'SUBSCRIPTION', label: 'Subscription' },
  { value: 'DATA', label: 'Data' },
] as const;

/** The columns of the list, shared by its heading and its rows. */
const ROW_GRID =
  'tw:md:grid tw:md:grid-cols-[minmax(0,1fr)_12rem_10rem_8rem_8rem] tw:md:items-center tw:md:gap-4';

function modeLabel(mode: EnforcementMode): string {
  return ENFORCEMENT_MODES.find((entry) => entry.value === mode)?.label ?? mode;
}

function modeColor(mode: EnforcementMode) {
  switch (mode) {
    case 'NATIVE_CONFIG':
      return 'warning' as const;
    case 'SECURE_VIEW':
      return 'blue' as const;
    case 'PROXY':
      return 'purple' as const;
    default:
      return 'gray' as const;
  }
}

export default function PolicyListPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const state = params.get('state') ?? '';
  const type = params.get('type') ?? '';
  const scopeLevel = params.get('scopeLevel') ?? '';
  const search = params.get('q') ?? '';
  const source = params.get('source') ?? '';
  const mode = params.get('mode') ?? '';
  const [searchDraft, setSearchDraft] = useState(search);
  const sources = useQuery({ queryKey: ['sources'], queryFn: fetchSources, retry: false });

  const offset = Math.max(0, Number(params.get('offset') ?? 0) || 0);
  const sized = Number(params.get('size') ?? PAGE_SIZE);
  const pageSize = PAGE_SIZES.includes(sized) ? sized : PAGE_SIZE;
  const filter = { state, type, scopeLevel, q: search, source, mode };

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['policies', state, type, scopeLevel, search, source, mode, offset, pageSize],
    queryFn: () => fetchPolicies({ ...filter, limit: pageSize, offset }),
    // The page being left stays on screen while the next one is fetched.
    // Without this the list empties for the length of a request and the page
    // springs back to the top, which reads as something having gone wrong
    // rather than as a page turn.
    placeholderData: keepPreviousData,
  });

  // Counted separately, and deliberately not keyed on the offset: the total
  // belongs to the filter, not to the page, so clicking through pages must not
  // make the server count the same rows again.
  const { data: total = 0 } = useQuery({
    queryKey: ['policies-count', state, type, scopeLevel, search, source, mode],
    queryFn: () => countPolicies(filter),
  });

  const filtered = Boolean(state || scopeLevel || search || source || mode);

  // A source the list was linked to keeps its option even when the sources
  // cannot be read, so the select never shows a value it has no label for.
  const sourceOptions = [
    { value: '', label: 'Any connection' },
    { value: EVERY_CONNECTION, label: 'Every connection' },
    ...(sources.data ?? []).map((entry) => ({ value: entry.id, label: entry.name })),
    ...(source && source !== EVERY_CONNECTION && !sources.data?.some((entry) => entry.id === source)
      ? [{ value: source, label: 'This connection' }]
      : []),
  ];

  function update(key: string, value: string) {
    const draft = new URLSearchParams(params);
    if (value) draft.set(key, value);
    else draft.delete(key);
    // Page four of the old filter is not page four of the new one, and more
    // often than not it is past the end of it -- an empty list that reads as
    // "nothing matches" when the answer was sitting on page one.
    draft.delete('offset');
    setParams(draft, { replace: true });
  }

  function page(next: URLSearchParams) {
    setParams(next, { replace: true });
  }

  return (
    <>
      <header className="tw:flex tw:flex-wrap tw:items-end tw:justify-between tw:gap-4">
        <div>
          <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
            Policies
          </h1>
          {/* Broken where the sentence breaks, not where the box runs out.
              Left to wrap on its own the second line came out as a three-word
              orphan, and `text-pretty` only moved which three words they were.
              The clause boundary is the one place a break reads as intended. */}
          <p className="tw:mt-2 tw:max-w-3xl tw:text-md tw:text-tertiary">
            Who may reach an asset, and what they see inside it. Layers compose
            from the organisation down to
            <br />a single column — a lower layer adds restrictions and never
            removes them.
          </p>
        </div>
        {/* The kind is asked here, as in the header's Create menu: the two
            kinds are different jobs, and the builder opens on the one chosen. */}
        <Dropdown.Root>
          <Button iconLeading={Plus} size="md">
            New policy
          </Button>
          <Dropdown.Popover>
            <Dropdown.Menu selectionMode="none">
              <Dropdown.Item
                icon={ShieldTick}
                label="Subscription policy"
                onAction={() => navigate(NEW_POLICY_PATH.SUBSCRIPTION)}
              />
              <Dropdown.Item
                icon={EyeOff}
                label="Data policy"
                onAction={() => navigate(NEW_POLICY_PATH.DATA)}
              />
            </Dropdown.Menu>
          </Dropdown.Popover>
        </Dropdown.Root>
      </header>

      <nav
        aria-label="Policy kind"
        className="tw:mt-6 tw:flex tw:gap-6 tw:border-b tw:border-secondary">
        {KINDS.map((kind) => (
          <button
            aria-current={type === kind.value ? 'page' : undefined}
            className={`tw:-mb-px tw:cursor-pointer tw:border-b-2 tw:px-1 tw:pb-2.5 tw:text-sm tw:font-semibold ${
              type === kind.value
                ? 'tw:border-brand tw:text-brand-secondary'
                : 'tw:border-transparent tw:text-tertiary tw:hover:text-primary'
            }`}
            key={kind.value}
            onClick={() => update('type', kind.value)}
            type="button">
            {kind.label}
          </button>
        ))}
      </nav>

      {/*
        Searched on the server, not here. The list arrives one page at a time,
        so filtering the rows already on screen would quietly answer "no such
        policy" for anything past the first hundred -- the worst possible
        answer to give somebody checking whether a rule already exists.
      */}
      <section className="tw:mt-4 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
        <form
          className="tw:flex tw:flex-wrap tw:items-center tw:gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            update('q', searchDraft.trim());
          }}>
          <div className="tw:min-w-64 tw:flex-1">
            <Input
              aria-label="Search policies"
              icon={SearchLg}
              onChange={setSearchDraft}
              placeholder="Name, description or the table it scopes to"
              value={searchDraft}
            />
          </div>
          <Select
            ariaLabel="Lifecycle state"
            className="tw:w-48"
            onChange={(next) => update('state', next)}
            options={[
              { value: '', label: 'Any state' },
              ...Object.entries(STATE_LABEL).map(([value, label]) => ({ value, label })),
            ]}
            value={state}
          />
          <Select
            ariaLabel="Scope level"
            className="tw:w-44"
            onChange={(next) => update('scopeLevel', next)}
            options={[
              { value: '', label: 'Any level' },
              ...Object.entries(LEVEL_LABEL).map(([value, label]) => ({ value, label })),
            ]}
            value={scopeLevel}
          />
          {/* Where it runs. A policy stores no connection and no mode: the
              connection is read from what its anchor and selector confine it
              to, and the mode is that connection's own, as it is set today. */}
          <Select
            ariaLabel="Connection"
            className="tw:w-48"
            onChange={(next) => update('source', next)}
            options={sourceOptions}
            value={source}
          />
          <Select
            ariaLabel="Enforcement mode"
            className="tw:w-48"
            onChange={(next) => update('mode', next)}
            options={[
              { value: '', label: 'Any mode' },
              ...ENFORCEMENT_MODES.map((entry) => ({ value: entry.value, label: entry.label })),
            ]}
            value={mode}
          />
          <Button size="md" type="submit">
            Search
          </Button>
          {filtered && (
            <Button
              color="tertiary"
              onPress={() => {
                setSearchDraft('');
                const draft = new URLSearchParams();
                if (type) draft.set('type', type);
                setParams(draft, { replace: true });
              }}
              size="md">
              Clear
            </Button>
          )}
        </form>
      </section>

      {error && (
        <p className="tw:mt-6 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not load the policy list.')}
        </p>
      )}

      {/* The size of the answer, above the answer. At the foot of the list it
          would arrive after the reader had already decided whether to scroll,
          which is the one moment it was useful. */}
      <div className="tw:mt-6 tw:flex tw:flex-wrap tw:items-baseline tw:justify-between tw:gap-2">
        <p className="tw:text-sm tw:text-tertiary">
          {isLoading
            ? 'Loading…'
            : `${total} ${total === 1 ? 'policy' : 'policies'}${filtered || type ? ' matching' : ''}`}
          {isFetching && !isLoading && ' · refreshing'}
        </p>
        {total > pageSize && (
          <p className="tw:text-xs tw:text-tertiary">
            Showing {offset + 1}–{Math.min(offset + pageSize, total)}
          </p>
        )}
      </div>

      {data?.length === 0 ? (
        <div className="tw:mt-3 tw:rounded-xl tw:border tw:border-dashed tw:border-secondary tw:p-10 tw:text-center">
          <ShieldTick className="tw:mx-auto tw:size-8 tw:text-tertiary" />
          <p className="tw:mt-3 tw:text-md tw:font-medium tw:text-primary">
            {search
              ? `No policy matches “${search}”`
              : filtered || type
                ? 'No policy matches these filters'
                : 'No policies yet'}
          </p>
          {/* An empty filtered list says nothing about what the engine does,
              so it must not borrow the words for an empty platform: a reader
              would take "denies by default" as the effect of their filter. */}
          <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
            {filtered
              ? 'This only narrows the list — policies outside these filters still apply. Clear the filters to see every policy.'
              : type
                ? 'Policies of the other kind still apply. All policies shows every one.'
                : 'With nothing active, the engine denies by default — assets are not exposed while this list is empty, they are simply unreachable through us.'}
          </p>
        </div>
      ) : (
        <section className="tw:mt-3 tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary">
          <div
            aria-hidden
            className={`tw:hidden tw:border-b tw:border-secondary tw:bg-secondary tw:px-4 tw:py-2.5 tw:text-xs tw:font-semibold tw:text-tertiary ${ROW_GRID}`}>
            <span>Policy</span>
            <span>Connection</span>
            <span>Scope</span>
            <span>State</span>
            <span>Updated</span>
          </div>
          <ul className="tw:divide-y tw:divide-secondary">
            {data?.map((policy) => (
              <li key={policy.id}>
                <PolicyRow policy={policy} />
              </li>
            ))}
          </ul>
        </section>
      )}

      <Pager
        label="Policy pages"
        noun="Policies"
        offset={offset}
        onOffset={(next) => {
          const draft = new URLSearchParams(params);
          draft.set('offset', String(next));
          page(draft);
        }}
        onPageSize={(next) => {
          const draft = new URLSearchParams(params);
          draft.set('size', String(next));
          // A different page size means different page boundaries, so the
          // offset it was on no longer points at anything anybody chose.
          draft.delete('offset');
          page(draft);
        }}
        pageSize={pageSize}
        total={total}
      />
    </>
  );
}

function PolicyRow({ policy }: { policy: ListedPolicy }) {
  const document = policy.document;
  const data = document.policyType === 'DATA';
  const readback = data
    ? summariseData(policy)
    : `${document.effect === 'DENY' ? 'denies' : 'allows'} ${describeSubject(document.subject)}`;
  const Icon = data ? EyeOff : ShieldTick;

  return (
    <Link
      className={`tw:block tw:px-4 tw:py-3.5 tw:transition tw:hover:bg-secondary ${ROW_GRID}`}
      to={`/policies/${policy.id}`}>
      <div className="tw:flex tw:min-w-0 tw:items-start tw:gap-3">
        <span
          className={`tw:mt-0.5 tw:flex tw:size-9 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg ${
            data
              ? 'tw:bg-utility-indigo-50 tw:text-utility-indigo-600'
              : 'tw:bg-utility-brand-50 tw:text-utility-brand-600'
          }`}
          title={data ? 'Data policy' : 'Subscription policy'}>
          <Icon aria-hidden className="tw:size-4.5" />
        </span>
        <div className="tw:min-w-0">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-x-2">
            <span className="tw:truncate tw:text-sm tw:font-semibold tw:text-primary">
              {document.displayName || document.name}
            </span>
            <Badge color={data ? 'indigo' : 'brand'} size="sm" type="pill-color">
              {data ? 'Data' : 'Subscription'}
            </Badge>
            {document.effect === 'DENY' && (
              <Badge color="error" size="sm" type="pill-color">
                Deny
              </Badge>
            )}
          </div>
          <p className="tw:mt-0.5 tw:line-clamp-2 tw:text-pretty tw:text-xs tw:text-tertiary">
            {data ? 'Data' : 'Subscription'} · on assets where{' '}
            {describeSelector(document.selector)} — {readback}.
          </p>
        </div>
      </div>

      <Reach reach={policy.reach} />

      <div className="tw:mt-2 tw:min-w-0 tw:pl-12 tw:md:mt-0 tw:md:pl-0">
        <p className="tw:text-sm tw:text-secondary">
          {LEVEL_LABEL[document.scopeLevel] ?? document.scopeLevel}
        </p>
        {document.scopeFqn && (
          <p
            className="tw:truncate tw:font-mono tw:text-xs tw:text-quaternary"
            title={document.scopeFqn}>
            {shortFqn(document.scopeFqn)}
          </p>
        )}
      </div>

      <div className="tw:mt-2 tw:flex tw:flex-wrap tw:items-center tw:gap-1.5 tw:pl-12 tw:md:mt-0 tw:md:pl-0">
        <Badge color={STATE_TONE[policy.lifecycleState] ?? 'gray'} size="sm" type="pill-color">
          {STATE_LABEL[policy.lifecycleState] ?? policy.lifecycleState}
        </Badge>
        <Badge color="gray" size="sm" type="modern">
          {policy.environment}
        </Badge>
      </div>

      <div className="tw:mt-1 tw:pl-12 tw:text-xs tw:text-tertiary tw:md:mt-0 tw:md:pl-0">
        <p>
          v{policy.version} · {relativeTime(policy.updatedAt)}
        </p>
        <p className="tw:truncate tw:text-quaternary">by {policy.updatedBy}</p>
      </div>
    </Link>
  );
}

/**
 * Where a policy runs: the connections it is confined to and the mode each
 * enforces it with, or every connection, where each runs it its own way.
 */
function Reach({ reach }: { reach: PolicyReach | undefined }) {
  const { data: engines } = useSourceEngines();
  const cell = 'tw:mt-2 tw:min-w-0 tw:pl-12 tw:md:mt-0 tw:md:pl-0';
  if (!reach) return <div className={cell} />;
  if (reach.everyConnection) {
    return (
      <div className={cell}>
        <p className="tw:text-sm tw:text-secondary">Every connection</p>
        <p className="tw:text-xs tw:text-quaternary">Each in its own mode</p>
      </div>
    );
  }
  if (reach.connections.length === 0) {
    return (
      <div className={cell}>
        <p className="tw:text-sm tw:text-warning-primary">No connection</p>
        <p className="tw:text-xs tw:text-quaternary">
          Anchored on one service, selecting another
        </p>
      </div>
    );
  }
  return (
    <ul className={`${cell} tw:space-y-1`}>
      {reach.connections.map((connection) => (
        <li className="tw:min-w-0" key={connection.service}>
          <p
            className="tw:truncate tw:text-sm tw:text-secondary"
            title={connection.engine ? engineLabel(engines, connection.engine) : undefined}>
            {connection.name}
          </p>
          {connection.mode ? (
            <Badge color={modeColor(connection.mode)} size="sm" type="pill-color">
              {modeLabel(connection.mode)}
            </Badge>
          ) : (
            <Badge color="gray" size="sm" type="modern">
              Not a registered connection
            </Badge>
          )}
        </li>
      ))}
    </ul>
  );
}

function summariseData(policy: StoredPolicy): string {
  const rows = policy.document.data?.rowFilters?.length ?? 0;
  const columns = policy.document.data?.columnRules?.length ?? 0;
  const parts: string[] = [];
  if (rows) parts.push(`${rows} row filter${rows === 1 ? '' : 's'}`);
  if (columns) parts.push(`${columns} column rule${columns === 1 ? '' : 's'}`);
  // A data policy carrying neither restricts nothing, which is worth saying out
  // loud rather than showing as an empty tail to the sentence.
  return parts.length ? parts.join(' and ') : 'nothing restricted yet';
}
