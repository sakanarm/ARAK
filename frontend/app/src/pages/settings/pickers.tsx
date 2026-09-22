import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Check, SearchLg } from '@untitledui/icons';
import { fetchAssets } from '../../api/client';
import { fetchPrincipals, type Principal } from '../../api/governance';
import { FIELD } from '../policies/controls';

/**
 * The two "choose a real thing" controls the identity screens need.
 *
 * Both are search-and-pick rather than free text, and for the same reason:
 * what goes in is an identifier the server matches exactly. A username typed
 * one character wrong is a 404 the operator can see; an asset FQN typed one
 * character wrong is a DATA_OWNER grant that matches nothing at all, silently,
 * and looks correct on every screen that lists it. Neither is worth the saved
 * keystrokes.
 */

/** Picks a person, group or service account out of the directory. */
export function PrincipalPicker({
  value,
  onChange,
  disabled,
}: {
  value: Principal | null;
  onChange: (principal: Principal | null) => void;
  disabled?: boolean;
}) {
  const [search, setSearch] = useState('');

  const { data, isFetching } = useQuery({
    queryKey: ['principal-picker', search],
    queryFn: () => fetchPrincipals({ search, limit: 20 }),
    placeholderData: keepPreviousData,
    staleTime: 30 * 1000,
    enabled: !value,
  });

  if (value) {
    return (
      <Chosen
        detail={`${value.principalType.toLowerCase()} · ${value.source}`}
        disabled={disabled}
        label={value.displayName || value.username}
        onClear={() => {
          onChange(null);
          setSearch('');
        }}
      />
    );
  }

  return (
    <div>
      <SearchBox
        disabled={disabled}
        onChange={setSearch}
        placeholder="Search people, groups and service accounts"
        value={search}
      />
      <Results busy={isFetching} empty={(data ?? []).length === 0}>
        {(data ?? []).map((principal) => (
          <Option
            detail={`${principal.principalType.toLowerCase()} · ${principal.source}${
              principal.enabled ? '' : ' · disabled'
            }`}
            key={principal.id}
            label={principal.displayName || principal.username}
            onPick={() => onChange(principal)}
            sub={principal.username}
          />
        ))}
      </Results>
    </div>
  );
}

/**
 * Picks the asset a data owner owns, out of what the crawl has cached.
 *
 * Only the catalog can say which FQNs exist, so only the catalog is offered.
 * An asset the crawl has not reached yet cannot be granted here — which is the
 * honest answer, because a grant on an FQN the engine has never seen protects
 * nothing.
 */
export function ScopePicker({
  value,
  onChange,
  disabled,
}: {
  value: string | null;
  onChange: (fqn: string | null) => void;
  disabled?: boolean;
}) {
  const [search, setSearch] = useState('');

  const { data, isFetching } = useQuery({
    queryKey: ['scope-picker', search],
    queryFn: () => fetchAssets({ search, limit: 20 }),
    placeholderData: keepPreviousData,
    staleTime: 30 * 1000,
    enabled: !value,
  });

  if (value) {
    return (
      <Chosen
        detail="catalog asset"
        disabled={disabled}
        label={value}
        onClear={() => {
          onChange(null);
          setSearch('');
        }}
      />
    );
  }

  return (
    <div>
      <SearchBox
        disabled={disabled}
        onChange={setSearch}
        placeholder="Search the catalog for a service, database, schema or table"
        value={search}
      />
      <Results busy={isFetching} empty={(data?.items ?? []).length === 0}>
        {(data?.items ?? []).map((asset) => (
          <Option
            detail={asset.assetType.toLowerCase()}
            key={asset.id}
            label={asset.displayName || asset.name}
            onPick={() => onChange(asset.fqn)}
            sub={asset.fqn}
          />
        ))}
      </Results>
    </div>
  );
}

function SearchBox({
  value,
  onChange,
  placeholder,
  disabled,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  disabled?: boolean;
}) {
  return (
    <div className="tw:relative">
      <SearchLg className="tw:pointer-events-none tw:absolute tw:top-1/2 tw:left-3 tw:size-4 tw:-translate-y-1/2 tw:text-quaternary" />
      <input
        aria-label={placeholder}
        className={`${FIELD} tw:w-full tw:pl-9`}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        type="search"
        value={value}
      />
    </div>
  );
}

function Results({
  busy,
  empty,
  children,
}: {
  busy: boolean;
  empty: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="tw:mt-2 tw:max-h-56 tw:overflow-y-auto tw:rounded-lg tw:border tw:border-secondary">
      {empty ? (
        <p className="tw:px-3 tw:py-3 tw:text-sm tw:text-tertiary">
          {busy ? 'Searching…' : 'Nothing matches that.'}
        </p>
      ) : (
        <ul>{children}</ul>
      )}
    </div>
  );
}

function Option({
  label,
  sub,
  detail,
  onPick,
}: {
  label: string;
  sub: string;
  detail: string;
  onPick: () => void;
}) {
  return (
    <li>
      <button
        className="tw:flex tw:w-full tw:items-center tw:justify-between tw:gap-3 tw:px-3 tw:py-2 tw:text-left tw:hover:bg-secondary"
        onClick={onPick}
        type="button">
        <span className="tw:min-w-0">
          <span className="tw:block tw:truncate tw:text-sm tw:text-primary">{label}</span>
          <span className="tw:block tw:truncate tw:text-xs tw:text-tertiary">{sub}</span>
        </span>
        <span className="tw:flex-none tw:text-xs tw:text-quaternary">{detail}</span>
      </button>
    </li>
  );
}

function Chosen({
  label,
  detail,
  onClear,
  disabled,
}: {
  label: string;
  detail: string;
  onClear: () => void;
  disabled?: boolean;
}) {
  return (
    <div className="tw:flex tw:items-center tw:justify-between tw:gap-3 tw:rounded-lg tw:border tw:border-brand tw:bg-secondary tw:px-3 tw:py-2">
      <span className="tw:flex tw:min-w-0 tw:items-center tw:gap-2">
        <Check className="tw:size-4 tw:flex-none tw:text-brand-secondary" />
        <span className="tw:min-w-0">
          <span className="tw:block tw:truncate tw:text-sm tw:text-primary">{label}</span>
          <span className="tw:block tw:text-xs tw:text-tertiary">{detail}</span>
        </span>
      </span>
      <button
        className="tw:flex-none tw:text-sm tw:font-medium tw:text-brand-secondary tw:disabled:text-quaternary"
        disabled={disabled}
        onClick={onClear}
        type="button">
        Change
      </button>
    </div>
  );
}
