import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { apiErrorMessage } from '../../api/client';
import {
  fetchAttributeVocabulary,
  fetchPrincipals,
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
 */
export default function PrincipalsPage() {
  const [search, setSearch] = useState('');
  const [type, setType] = useState('');

  const { data, isLoading, error } = useQuery({
    queryKey: ['principals', type, search],
    queryFn: () => fetchPrincipals({ type, search, limit: 200 }),
    placeholderData: keepPreviousData,
  });

  const { data: vocabulary } = useQuery({
    queryKey: ['attribute-vocabulary'],
    queryFn: fetchAttributeVocabulary,
    staleTime: 5 * 60 * 1000,
  });

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
          values it takes.
        </p>
        <div className="tw:mt-3 tw:flex tw:flex-wrap tw:gap-2">
          {(vocabulary?.keys ?? []).map((key) => (
            <div
              className="tw:rounded-lg tw:border tw:border-secondary tw:px-3 tw:py-2"
              key={`${key.key}-${key.source}`}>
              <div className="tw:flex tw:items-center tw:gap-2">
                <span className="tw:text-sm tw:font-medium tw:text-primary">
                  {key.key}
                </span>
                <Badge color="gray" size="sm" type="pill-color">
                  {key.source}
                </Badge>
                <span className="tw:text-xs tw:text-tertiary">
                  {key.principals} people
                </span>
              </div>
              <p className="tw:mt-1 tw:text-xs tw:text-tertiary">
                {key.values.join(', ') || 'no values cached'}
              </p>
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

      <section className="tw:mt-6 tw:flex tw:flex-wrap tw:gap-3">
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
      </section>

      {error && (
        <p className="tw:mt-6 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not load the identity cache.')}
        </p>
      )}
      {isLoading && <p className="tw:mt-6 tw:text-sm tw:text-tertiary">Loading…</p>}

      <section className="tw:mt-4 tw:flex tw:flex-col tw:gap-2">
        {data?.map((principal) => (
          <PrincipalRow key={principal.id} principal={principal} />
        ))}
        {data?.length === 0 && (
          <p className="tw:text-sm tw:text-tertiary">Nobody matches that.</p>
        )}
      </section>
    </>
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
