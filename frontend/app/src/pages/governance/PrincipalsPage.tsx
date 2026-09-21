import { useCallback, useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { apiErrorMessage } from '../../api/client';
import {
  fetchAttributeVocabulary,
  fetchPrincipals,
  type AttributeCondition,
  type Principal,
} from '../../api/governance';
import { Select, TextField } from '../policies/controls';

/**
 * People, groups and their attributes — the subject half of every policy.
 *
 * Read-only on purpose. Entra and OpenMetadata own this content and the next
 * sync would revert anything typed here, so an edit form would be a promise the
 * platform cannot keep. What this screen is for is the question that comes up
 * while writing a rule: does anybody actually carry the attribute I am about to
 * depend on, and what values does it take? A condition on an attribute nobody
 * has denies everyone, quietly and correctly, which is the hardest kind of
 * mistake to see afterwards.
 *
 * The attribute filter is that question asked directly. Several conditions are
 * ANDed, matching how a subject rule reads its own attribute list, so the count
 * at the top of the results is the number of people that rule would match.
 * Nothing here follows group membership, because the engine does not either:
 * PrincipalLoader reads attributes off the principal's own rows and takes
 * groups from a separate walk. A directory that credited people with their
 * group's attributes would promise matches the engine will not make.
 */

/** Stable identity for a condition, so React keys and removal agree. */
function conditionId(condition: AttributeCondition): string {
  return `${condition.key}=${condition.value ?? ''}`;
}

export default function PrincipalsPage() {
  const [search, setSearch] = useState('');
  const [type, setType] = useState('');
  const [conditions, setConditions] = useState<AttributeCondition[]>([]);

  const { data: vocabulary } = useQuery({
    queryKey: ['attribute-vocabulary'],
    queryFn: fetchAttributeVocabulary,
    staleTime: 5 * 60 * 1000,
  });

  const { data, isLoading, error } = useQuery({
    queryKey: ['principals', type, search, conditions.map(conditionId)],
    queryFn: () =>
      fetchPrincipals({ type, search, attributes: conditions, limit: 200 }),
    placeholderData: keepPreviousData,
  });

  const add = useCallback((condition: AttributeCondition) => {
    setConditions((current) =>
      current.some((existing) => conditionId(existing) === conditionId(condition))
        ? current
        : [...current, condition]
    );
  }, []);

  const remove = useCallback((id: string) => {
    setConditions((current) =>
      current.filter((condition) => conditionId(condition) !== id)
    );
  }, []);

  return (
    <>
      <header>
        <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
          People & attributes
        </h1>
        <p className="tw:mt-2 tw:text-md tw:text-tertiary">
          The identity cache a subject rule is written against. Synced from Entra
          and OpenMetadata, and read-only here — an edit would be reverted by the
          next sync.
        </p>
      </header>

      <section className="tw:mt-8 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
        <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
          Attributes in use
        </h2>
        <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
          Every key the builder will offer, with how many people carry it and the
          values it takes. Click a key to filter by it, or a value to pin it.
        </p>
        <div className="tw:mt-3 tw:flex tw:flex-wrap tw:gap-2">
          {(vocabulary?.keys ?? []).map((key) => (
            <div
              className="tw:rounded-lg tw:border tw:border-secondary tw:px-3 tw:py-2"
              key={`${key.key}-${key.source}`}>
              <div className="tw:flex tw:items-center tw:gap-2">
                <button
                  className="tw:rounded tw:text-sm tw:font-medium tw:text-primary tw:underline-offset-2 tw:outline-focus-ring tw:hover:underline tw:focus-visible:outline-2"
                  onClick={() => add({ key: key.key })}
                  type="button">
                  {key.key}
                </button>
                <Badge color="gray" size="sm" type="pill-color">
                  {key.source}
                </Badge>
                <span className="tw:text-xs tw:text-tertiary">
                  {key.principals} people
                </span>
              </div>
              <div className="tw:mt-1 tw:flex tw:flex-wrap tw:gap-1">
                {key.values.map((value) => (
                  <button
                    className="tw:rounded tw:px-1 tw:text-xs tw:text-tertiary tw:outline-focus-ring tw:hover:bg-secondary tw:hover:text-primary tw:focus-visible:outline-2"
                    key={value}
                    onClick={() => add({ key: key.key, value })}
                    type="button">
                    {value}
                  </button>
                ))}
                {key.values.length === 0 && (
                  <span className="tw:text-xs tw:text-tertiary">
                    no values cached
                  </span>
                )}
              </div>
            </div>
          ))}
          {vocabulary?.keys.length === 0 && (
            <p className="tw:text-sm tw:text-tertiary">
              No attributes cached yet. Until the Entra sync runs, an ABAC
              condition here would match nobody.
            </p>
          )}
        </div>
      </section>

      <section className="tw:mt-6 tw:flex tw:flex-wrap tw:items-center tw:gap-3">
        <TextField
          ariaLabel="Search"
          className="tw:w-72"
          onChange={setSearch}
          placeholder="Name, username or email"
          value={search}
        />
        <Select
          ariaLabel="Kind"
          className="tw:w-44"
          onChange={setType}
          options={[
            { value: '', label: 'Everyone' },
            { value: 'USER', label: 'People' },
            { value: 'GROUP', label: 'Groups' },
            { value: 'SERVICE', label: 'Service accounts' },
          ]}
          value={type}
        />
        <AttributeFilterPicker
          keys={vocabulary?.keys ?? []}
          onAdd={add}
        />
      </section>

      {conditions.length > 0 && (
        <section className="tw:mt-3 tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <span className="tw:text-xs tw:text-tertiary">Carrying all of:</span>
          {conditions.map((condition) => (
            <span
              className="tw:flex tw:items-center tw:gap-1.5 tw:rounded-lg tw:border tw:border-brand tw:bg-brand-primary tw:py-1 tw:pr-1 tw:pl-2.5 tw:text-sm tw:text-primary"
              key={conditionId(condition)}>
              {condition.key}
              {condition.value ? (
                <span className="tw:font-medium">= {condition.value}</span>
              ) : (
                <span className="tw:text-tertiary">any value</span>
              )}
              <button
                aria-label={`Remove filter ${condition.key}`}
                className="tw:rounded tw:px-1 tw:text-tertiary tw:outline-focus-ring tw:hover:text-primary tw:focus-visible:outline-2"
                onClick={() => remove(conditionId(condition))}
                type="button">
                ×
              </button>
            </span>
          ))}
          <button
            className="tw:rounded tw:px-1 tw:text-xs tw:font-medium tw:text-brand-secondary tw:outline-focus-ring tw:hover:underline tw:focus-visible:outline-2"
            onClick={() => setConditions([])}
            type="button">
            Clear
          </button>
        </section>
      )}

      {error && (
        <p className="tw:mt-6 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not load the identity cache.')}
        </p>
      )}
      {isLoading && <p className="tw:mt-6 tw:text-sm tw:text-tertiary">Loading…</p>}

      {data && data.length > 0 && conditions.length > 0 && (
        <p className="tw:mt-4 tw:text-sm tw:text-tertiary">
          {data.length === 1
            ? 'One principal carries every condition'
            : `${data.length} principals carry every condition`}{' '}
          — that is who a subject rule with these attributes would match.
        </p>
      )}

      <section className="tw:mt-4 tw:flex tw:flex-col tw:gap-2">
        {data?.map((principal) => (
          <PrincipalRow key={principal.id} principal={principal} />
        ))}
        {data?.length === 0 &&
          (conditions.length > 0 ? (
            <p className="tw:text-sm tw:text-tertiary">
              Nobody carries all of those. A subject rule written this way would
              match no one — which is a denial, not an error, and one worth
              noticing before the policy is active.
            </p>
          ) : (
            <p className="tw:text-sm tw:text-tertiary">Nobody matches that.</p>
          ))}
      </section>
    </>
  );
}

/**
 * Pick a key, then optionally a value, and add it as a condition.
 *
 * Two selects rather than one because the two choices mean different things: a
 * key alone asks who carries the attribute at all — the question behind "is
 * this populated yet" — while a value narrows it to the people a rule pinned to
 * that value would match.
 */
function AttributeFilterPicker({
  keys,
  onAdd,
}: {
  keys: { key: string; values: string[] }[];
  onAdd: (condition: AttributeCondition) => void;
}) {
  const [key, setKey] = useState('');
  const [value, setValue] = useState('');

  // The vocabulary lists a key once per source, so the same key can arrive
  // twice; the values are merged rather than shown as two entries, because the
  // filter does not care which sync produced the row.
  const byKey = useMemo(() => {
    const merged = new Map<string, Set<string>>();
    for (const entry of keys) {
      const values = merged.get(entry.key) ?? new Set<string>();
      for (const one of entry.values) {
        values.add(one);
      }
      merged.set(entry.key, values);
    }
    return merged;
  }, [keys]);

  const values = key ? [...(byKey.get(key) ?? [])].sort() : [];

  if (byKey.size === 0) {
    return null;
  }

  return (
    <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
      <Select
        ariaLabel="Attribute"
        className="tw:w-48"
        onChange={(next) => {
          setKey(next);
          setValue('');
        }}
        options={[...byKey.keys()].sort().map((one) => ({
          value: one,
          label: one,
        }))}
        placeholder="Attribute"
        value={key}
      />
      <Select
        ariaLabel="Value"
        className="tw:w-48"
        onChange={setValue}
        options={[
          { value: '', label: 'Any value' },
          ...values.map((one) => ({ value: one, label: one })),
        ]}
        placeholder="Any value"
        value={value}
      />
      <button
        className="tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:font-medium tw:text-primary tw:outline-focus-ring tw:hover:border-brand tw:focus-visible:outline-2 tw:disabled:opacity-50"
        disabled={!key}
        onClick={() => onAdd({ key, value: value || undefined })}
        type="button">
        Add filter
      </button>
    </div>
  );
}

function PrincipalRow({ principal }: { principal: Principal }) {
  return (
    <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-3 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:p-3">
      <div className="tw:min-w-0 tw:flex-1">
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <span className="tw:font-medium tw:text-primary">
            {principal.displayName || principal.username}
          </span>
          <span className="tw:text-xs tw:text-tertiary">{principal.username}</span>
          <Badge color="gray" size="sm" type="pill-color">
            {principal.principalType.toLowerCase()}
          </Badge>
          <Badge color="blue-light" size="sm" type="pill-color">
            {principal.source}
          </Badge>
          {!principal.enabled && (
            <Badge color="warning" size="sm" type="pill-color">
              disabled
            </Badge>
          )}
          {principal.appRoles.map((role) => (
            <Badge color="brand" key={role} size="sm" type="pill-color">
              {role.replace('_', ' ').toLowerCase()}
            </Badge>
          ))}
        </div>
        {principal.email && (
          <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">{principal.email}</p>
        )}
      </div>

      <span className="tw:text-sm tw:text-tertiary">
        {principal.attributeCount} attribute
        {principal.attributeCount === 1 ? '' : 's'}
      </span>
      <span className="tw:w-32 tw:text-right tw:text-sm tw:text-tertiary">
        {principal.principalType === 'GROUP'
          ? `${principal.memberCount} member${principal.memberCount === 1 ? '' : 's'}`
          : `${principal.memberCount} group${principal.memberCount === 1 ? '' : 's'}`}
      </span>
    </div>
  );
}
