import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Plus, SearchLg, ShieldTick } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Input } from '@openmetadata/ui-core-components/components/base/input/input';
import { apiErrorMessage } from '../../api/client';
import {
  countPolicies,
  fetchPolicies,
  type StoredPolicy,
} from '../../api/policies';
import { PAGE_SIZES, Pager } from '../../components/Pager';
import { Select } from './controls';
import { describeSelector, describeSubject } from './policyLanguage';

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

export default function PolicyListPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const state = params.get('state') ?? '';
  const type = params.get('type') ?? '';
  const scopeLevel = params.get('scopeLevel') ?? '';
  const search = params.get('q') ?? '';
  const [searchDraft, setSearchDraft] = useState(search);

  const offset = Math.max(0, Number(params.get('offset') ?? 0) || 0);
  const sized = Number(params.get('size') ?? PAGE_SIZE);
  const pageSize = PAGE_SIZES.includes(sized) ? sized : PAGE_SIZE;
  const filter = { state, type, scopeLevel, q: search };

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['policies', state, type, scopeLevel, search, offset, pageSize],
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
    queryKey: ['policies-count', state, type, scopeLevel, search],
    queryFn: () => countPolicies(filter),
  });

  const filtered = Boolean(state || type || scopeLevel || search);

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
        <Button
          iconLeading={Plus}
          onPress={() => navigate('/policies/new')}
          size="md">
          New policy
        </Button>
      </header>

      {/*
        Searched on the server, not here. The list arrives one page at a time,
        so filtering the rows already on screen would quietly answer "no such
        policy" for anything past the first hundred -- the worst possible
        answer to give somebody checking whether a rule already exists.
      */}
      <form
        className="tw:mt-8 tw:flex tw:min-w-72 tw:items-center tw:gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          update('q', searchDraft.trim());
        }}>
        <div className="tw:min-w-56 tw:max-w-md tw:flex-1">
          <Input
            aria-label="Search policies"
            icon={SearchLg}
            onChange={setSearchDraft}
            placeholder="Name, description or the table it scopes to"
            value={searchDraft}
          />
        </div>
        <Button size="md" type="submit">
          Search
        </Button>
        {search && (
          <Button
            color="tertiary"
            onPress={() => {
              setSearchDraft('');
              update('q', '');
            }}
            size="md">
            Clear
          </Button>
        )}
      </form>

      <section className="tw:mt-4 tw:flex tw:flex-wrap tw:gap-3">
        <Select
          ariaLabel="Lifecycle state"
          className="tw:w-48"
          onChange={(next) => update('state', next)}
          options={[
            { value: '', label: 'Any state' },
            { value: 'DRAFT', label: 'Draft' },
            { value: 'PENDING_APPROVAL', label: 'Pending approval' },
            { value: 'ACTIVE', label: 'Active' },
            { value: 'DISABLED', label: 'Disabled' },
            { value: 'ARCHIVED', label: 'Archived' },
          ]}
          value={state}
        />
        <Select
          ariaLabel="Policy type"
          className="tw:w-56"
          onChange={(next) => update('type', next)}
          options={[
            { value: '', label: 'Both kinds' },
            { value: 'SUBSCRIPTION', label: 'Subscription — who gets in' },
            { value: 'DATA', label: 'Data — what they see' },
          ]}
          value={type}
        />
        <Select
          ariaLabel="Scope level"
          className="tw:w-48"
          onChange={(next) => update('scopeLevel', next)}
          options={[
            { value: '', label: 'Any level' },
            { value: 'ORG', label: 'Organisation' },
            { value: 'DOMAIN', label: 'Domain' },
            { value: 'SERVICE', label: 'Service' },
            { value: 'DATABASE', label: 'Database' },
            { value: 'SCHEMA', label: 'Schema' },
            { value: 'TABLE', label: 'Table' },
            { value: 'COLUMN', label: 'Column' },
          ]}
          value={scopeLevel}
        />
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
            : `${total} ${total === 1 ? 'policy' : 'policies'}${filtered ? ' matching' : ''}`}
          {isFetching && !isLoading && ' · refreshing'}
        </p>
        {total > pageSize && (
          <p className="tw:text-xs tw:text-tertiary">
            Showing {offset + 1}–{Math.min(offset + pageSize, total)}
          </p>
        )}
      </div>

      <section className="tw:mt-3 tw:flex tw:flex-col tw:gap-3">

        {data?.length === 0 && (
          <div className="tw:rounded-xl tw:border tw:border-dashed tw:border-secondary tw:p-10 tw:text-center">
            <ShieldTick className="tw:mx-auto tw:size-8 tw:text-tertiary" />
            <p className="tw:mt-3 tw:text-md tw:font-medium tw:text-primary">
              {search
                ? `No policy matches “${search}”`
                : 'No policy matches these filters'}
            </p>
            <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
              With nothing active, the engine denies by default — assets are not
              exposed while this list is empty, they are simply unreachable
              through us.
            </p>
          </div>
        )}

        {data?.map((policy) => (
          <PolicyRow key={policy.id} policy={policy} />
        ))}
      </section>

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

function PolicyRow({ policy }: { policy: StoredPolicy }) {
  const document = policy.document;
  const readback =
    document.policyType === 'SUBSCRIPTION'
      ? `${document.effect === 'DENY' ? 'Denies' : 'Allows'} ${describeSubject(document.subject)}`
      : summariseData(policy);

  return (
    <Link
      className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4 tw:transition tw:hover:border-brand"
      to={`/policies/${policy.id}`}>
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <span className="tw:text-md tw:font-semibold tw:text-primary">
          {document.displayName || document.name}
        </span>
        <Badge color={STATE_TONE[policy.lifecycleState] ?? 'gray'} size="sm" type="pill-color">
          {policy.lifecycleState.replace('_', ' ').toLowerCase()}
        </Badge>
        <Badge color="gray" size="sm" type="pill-color">
          {document.policyType === 'DATA' ? 'data' : 'subscription'}
        </Badge>
        <Badge color="gray" size="sm" type="pill-color">
          {document.scopeLevel.toLowerCase()}
          {document.scopeFqn ? ` · ${document.scopeFqn}` : ''}
        </Badge>
        <Badge color="gray" size="sm" type="pill-color">
          {policy.environment}
        </Badge>
        <span className="tw:ml-auto tw:text-xs tw:text-tertiary">
          v{policy.version} · {policy.updatedBy}
        </span>
      </div>

      <p className="tw:mt-2 tw:text-sm tw:text-secondary">
        On assets where {describeSelector(document.selector)} — {readback}.
      </p>
    </Link>
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
