import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { ChevronDown, ChevronRight } from '@untitledui/icons';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { apiErrorMessage } from '../../api/client';
import {
  fetchVocabulary,
  type CustomPropertyDef,
  type GovernanceValue,
} from '../../api/governance';
import { TextField } from '../policies/controls';

/**
 * The governance vocabulary: every tag, term, domain and data product a policy
 * can be written against (FR-2A).
 *
 * OpenMetadata already lists these, and listing them again would be a worse
 * copy of its own screens. What it cannot answer is the pair of numbers in every
 * row here — how many assets in this platform actually carry a value, and how
 * many policies name it. Those two together are what tells a steward whether a
 * tag is load-bearing or decorative, and whether deleting one would quietly
 * unprotect something.
 */

type TabKey = 'classifications' | 'glossaries' | 'domains' | 'properties';

const TABS: { key: TabKey; label: string; blurb: string }[] = [
  {
    key: 'classifications',
    label: 'Classifications & tags',
    blurb:
      'A policy written against a classification covers every tag under it, including ones a steward adds next month.',
  },
  {
    key: 'glossaries',
    label: 'Glossaries & terms',
    blurb:
      'The business vocabulary. Terms are hierarchical too, so a policy on a parent term reaches its children.',
  },
  {
    key: 'domains',
    label: 'Domains & data products',
    blurb:
      'Sub-domains nest arbitrarily deep. "contains Finance" reaches Finance.Risk.Credit; "eq Finance" does not, which is the distinction most policy mistakes turn on.',
  },
  {
    key: 'properties',
    label: 'Custom properties',
    blurb:
      'Typed attributes on the asset side, the counterpart to a person’s attributes. Their type decides which operators the builder offers.',
  },
];

export default function GovernancePage() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as TabKey) ?? 'classifications';
  const [search, setSearch] = useState('');

  const { data, isLoading, error } = useQuery({
    queryKey: ['governance-vocabulary'],
    queryFn: fetchVocabulary,
    staleTime: 5 * 60 * 1000,
  });

  const active = TABS.find((entry) => entry.key === tab) ?? TABS[0];

  return (
    <>
      <header>
        <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
          Governance
        </h1>
        <p className="tw:mt-2 tw:text-md tw:text-tertiary">
          The vocabulary policies are written against, with how much of the estate
          each value touches and how many policies depend on it.
        </p>
      </header>

      <nav className="tw:mt-8 tw:flex tw:flex-wrap tw:gap-2">
        {TABS.map((entry) => (
          <button
            className={`tw:rounded-lg tw:px-3 tw:py-2 tw:text-sm tw:font-medium ${
              entry.key === tab
                ? 'tw:bg-brand-solid tw:text-white'
                : 'tw:border tw:border-secondary tw:text-secondary'
            }`}
            key={entry.key}
            onClick={() => setParams({ tab: entry.key }, { replace: true })}
            type="button">
            {entry.label}
          </button>
        ))}
      </nav>

      <p className="tw:mt-3 tw:text-sm tw:text-tertiary">{active.blurb}</p>

      <div className="tw:mt-4 tw:max-w-sm">
        <TextField
          ariaLabel="Filter"
          onChange={setSearch}
          placeholder="Filter by name"
          value={search}
        />
      </div>

      {error && (
        <p className="tw:mt-6 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not load the governance vocabulary.')}
        </p>
      )}
      {isLoading && <p className="tw:mt-6 tw:text-sm tw:text-tertiary">Loading…</p>}

      <section className="tw:mt-6 tw:flex tw:flex-col tw:gap-2">
        {tab === 'properties'
          ? (data?.customProperties ?? [])
              .filter((property) => matchesProperty(property, search))
              .map((property) => (
                <PropertyRow
                  key={`${property.entityType}.${property.name}`}
                  property={property}
                />
              ))
          : treeFor(data, tab)
              .map((value) => prune(value, search.trim().toLowerCase()))
              .filter((value): value is GovernanceValue => value !== null)
              .map((value) => (
                <ValueRow
                  forceOpen={search.trim().length > 0}
                  key={value.fqn}
                  tab={tab}
                  value={value}
                />
              ))}

        {tab === 'domains' && (data?.dataProducts.length ?? 0) > 0 && (
          <>
            <h2 className="tw:mt-6 tw:text-sm tw:font-semibold tw:text-primary">
              Data products
            </h2>
            {(data?.dataProducts ?? [])
              .filter((value) => matches(value, search.trim().toLowerCase()))
              .map((value) => (
                <ValueRow key={value.fqn} tab="dataProducts" value={value} />
              ))}
          </>
        )}
      </section>
    </>
  );
}

function treeFor(
  data: { classifications: GovernanceValue[]; glossaries: GovernanceValue[]; domains: GovernanceValue[] } | undefined,
  tab: TabKey
): GovernanceValue[] {
  if (!data) return [];
  if (tab === 'glossaries') return data.glossaries;
  if (tab === 'domains') return data.domains;
  return data.classifications;
}

function matches(value: GovernanceValue, needle: string): boolean {
  if (!needle) return true;
  return (
    value.fqn.toLowerCase().includes(needle) ||
    (value.displayName ?? '').toLowerCase().includes(needle)
  );
}

/**
 * Keeps a branch when it matches or when anything below it does.
 *
 * Filtering a tree by dropping non-matching nodes outright hides the parent of
 * every match, which for a hierarchy is the one thing you needed to see.
 */
function prune(value: GovernanceValue, needle: string): GovernanceValue | null {
  if (!needle) return value;
  const children = value.children
    .map((child) => prune(child, needle))
    .filter((child): child is GovernanceValue => child !== null);
  if (children.length === 0 && !matches(value, needle)) return null;
  return { ...value, children };
}

/**
 * The facet name the catalog filters by, which is not the tab name.
 *
 * A root under Classifications is a classification and its children are tags;
 * the catalog stores those as two different facet types. Linking both as
 * "classifications" would send a tag row to a filter that matches nothing.
 */
function facetOf(tab: string, value: GovernanceValue): string {
  if (tab === 'classifications') return value.parentFqn ? 'tags' : 'classifications';
  if (tab === 'glossaries') return value.parentFqn ? 'terms' : 'glossaries';
  return tab;
}

function ValueRow({
  value,
  tab,
  depth = 0,
  forceOpen = false,
}: {
  value: GovernanceValue;
  tab: string;
  depth?: number;
  forceOpen?: boolean;
}) {
  const facet = facetOf(tab, value);
  const [open, setOpen] = useState(depth === 0);
  const expanded = forceOpen || open;
  const hasChildren = value.children.length > 0;

  return (
    <>
      <div
        className="tw:flex tw:flex-wrap tw:items-center tw:gap-3 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:p-3"
        style={{ marginLeft: depth * 20 }}>
        {hasChildren ? (
          <button
            aria-label={expanded ? 'Collapse' : 'Expand'}
            className="tw:text-tertiary"
            onClick={() => setOpen(!open)}
            type="button">
            {expanded ? (
              <ChevronDown className="tw:size-4" />
            ) : (
              <ChevronRight className="tw:size-4" />
            )}
          </button>
        ) : (
          <span className="tw:w-4" />
        )}

        <div className="tw:min-w-0 tw:flex-1">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <span className="tw:font-medium tw:text-primary">
              {value.displayName || value.name}
            </span>
            <span className="tw:text-xs tw:text-tertiary">{value.fqn}</span>
            {value.disabled && (
              <Badge color="warning" size="sm" type="pill-color">
                disabled
              </Badge>
            )}
            {value.mutuallyExclusive && (
              <Badge color="gray" size="sm" type="pill-color">
                one value only
              </Badge>
            )}
            {value.provenance !== 'openmetadata' && (
              <Badge color="blue" size="sm" type="pill-color">
                {value.provenance}
              </Badge>
            )}
          </div>
          {value.description && (
            <p className="tw:mt-0.5 tw:truncate tw:text-xs tw:text-tertiary">
              {value.description}
            </p>
          )}
        </div>

        <Link
          className="tw:text-sm tw:text-brand-secondary tw:hover:underline"
          to={`/catalog?facet=${encodeURIComponent(`${facet}:${value.fqn}`)}`}>
          {value.assets} asset{value.assets === 1 ? '' : 's'}
          {value.directAssets !== value.assets && (
            <span className="tw:text-tertiary"> · {value.directAssets} direct</span>
          )}
        </Link>

        <span
          className={`tw:w-28 tw:text-right tw:text-sm ${
            value.policies > 0 ? 'tw:text-primary' : 'tw:text-tertiary'
          }`}>
          {value.policies} polic{value.policies === 1 ? 'y' : 'ies'}
        </span>
      </div>

      {expanded &&
        value.children.map((child) => (
          <ValueRow
            depth={depth + 1}
            forceOpen={forceOpen}
            key={child.fqn}
            tab={tab}
            value={child}
          />
        ))}
    </>
  );
}

function matchesProperty(property: CustomPropertyDef, search: string): boolean {
  const needle = search.trim().toLowerCase();
  if (!needle) return true;
  return (
    property.name.toLowerCase().includes(needle) ||
    property.entityType.toLowerCase().includes(needle)
  );
}

function PropertyRow({ property }: { property: CustomPropertyDef }) {
  return (
    <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-3 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:p-3">
      <span className="tw:font-medium tw:text-primary">{property.name}</span>
      <Badge color="gray" size="sm" type="pill-color">
        {property.entityType}
      </Badge>
      <Badge color="blue-light" size="sm" type="pill-color">
        {property.dataType}
        {property.multiSelect ? ' · multi' : ''}
      </Badge>
      {property.enumValues && (
        <span className="tw:text-xs tw:text-tertiary">{property.enumValues}</span>
      )}
      {property.description && (
        <span className="tw:text-xs tw:text-tertiary">{property.description}</span>
      )}
    </div>
  );
}
