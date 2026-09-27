import { useEffect, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { ChevronDown, ChevronRight, Minimize01, Maximize01, Plus } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage } from '../../api/client';
import {
  fetchVocabulary,
  fetchVocabularyPermission,
  type CustomPropertyDef,
  type GovernanceValue,
} from '../../api/governance';
import { TextField } from '../policies/controls';
import { plainText } from '../../lib/text';
import {
  EditingContext,
  VocabularyFormPanel,
  formUnder,
  useEditing,
  type Editing,
  type VocabularyForm,
} from './VocabularyEditor';

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
 *
 * A platform admin or a policy author can also make classifications and tags
 * here, for vocabulary OpenMetadata does not have yet (see VocabularyEditor).
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
  // Expand/collapse all is a one-shot instruction, not a mode: a new object
  // identity tells every row to jump to that state once, after which each row
  // is free to disagree again. Holding it as a boolean mode instead would mean
  // "expand all" keeps forcing a row open that the reader just closed.
  const [bulk, setBulk] = useState<Bulk | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ['governance-vocabulary'],
    queryFn: fetchVocabulary,
    staleTime: 5 * 60 * 1000,
  });
  const permission = useQuery({
    queryKey: ['local-vocabulary'],
    queryFn: fetchVocabularyPermission,
    staleTime: 5 * 60 * 1000,
  });
  const [form, setForm] = useState<VocabularyForm | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const editing: Editing = {
    // Classifications and tags only: they are what "Edit tags" attaches.
    canEdit: permission.data?.canEdit === true && tab === 'classifications',
    form,
    open: (next) => {
      setNotice(null);
      setForm(next);
    },
    close: () => setForm(null),
    done: (value, verb) => {
      setForm(null);
      setNotice(
        verb === 'changed'
          ? `Saved ${value.fqn}.`
          : value.kind === 'TAG'
            ? `Made ${value.fqn}. Attach it to a column from the table's Columns tab, with Edit tags.`
            : `Made ${value.fqn}. Add its first tag with Add tag on its row.`
      );
    },
  };

  const active = TABS.find((entry) => entry.key === tab) ?? TABS[0];
  const roots = treeFor(data, tab)
    .map((value) => prune(value, search.trim().toLowerCase()))
    .filter((value): value is GovernanceValue => value !== null);

  return (
    <EditingContext.Provider value={editing}>
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
            className={`tw:cursor-pointer tw:rounded-lg tw:px-3 tw:py-2 tw:text-sm tw:font-medium ${
              entry.key === tab
                ? 'tw:bg-brand-solid tw:text-white'
                : 'tw:border tw:border-secondary tw:text-secondary'
            }`}
            key={entry.key}
            onClick={() => {
              setForm(null);
              setNotice(null);
              setParams({ tab: entry.key }, { replace: true });
            }}
            type="button">
            {entry.label}
          </button>
        ))}
      </nav>

      <p className="tw:mt-3 tw:text-sm tw:text-tertiary">{active.blurb}</p>

      <div className="tw:mt-4 tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <div className="tw:w-full tw:max-w-sm">
          <TextField
            ariaLabel="Filter"
            onChange={setSearch}
            placeholder="Filter by name"
            value={search}
          />
        </div>
        {tab !== 'properties' && (
          <div className="tw:ml-auto tw:flex tw:gap-2">
            {editing.canEdit && (
              <Button
                color="primary"
                iconLeading={Plus}
                isDisabled={form?.mode === 'classification'}
                onPress={() => editing.open({ mode: 'classification' })}
                size="sm">
                New classification
              </Button>
            )}
            <BulkButton
              icon={<Maximize01 className="tw:size-4" />}
              label="Expand all"
              onPress={() => setBulk({ nonce: Date.now(), open: true })}
            />
            <BulkButton
              icon={<Minimize01 className="tw:size-4" />}
              label="Collapse all"
              onPress={() => setBulk({ nonce: Date.now(), open: false })}
            />
          </div>
        )}
      </div>

      {error && (
        <p className="tw:mt-6 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not load the governance vocabulary.')}
        </p>
      )}
      {isLoading && <p className="tw:mt-6 tw:text-sm tw:text-tertiary">Loading…</p>}

      {notice && (
        <p
          className="tw:mt-4 tw:rounded-lg tw:border tw:border-success tw:bg-success-primary tw:p-3 tw:text-sm tw:text-success-primary"
          role="status">
          {notice}
        </p>
      )}
      {editing.canEdit && form?.mode === 'classification' && (
        <div className="tw:mt-4">
          <VocabularyFormPanel form={form} />
        </div>
      )}

      {data && tab === 'properties' && (
        <section className="tw:mt-5 tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary">
          <div className="tw:flex tw:items-center tw:gap-3 tw:border-b tw:border-secondary tw:bg-secondary_subtle tw:px-4 tw:py-2 tw:text-xs tw:font-medium tw:text-tertiary">
            <span className="tw:w-56 tw:shrink-0">Property</span>
            <span className="tw:w-44 tw:shrink-0">Applies to · type</span>
            <span className="tw:flex-1">Values and description</span>
          </div>
          {(data.customProperties ?? [])
            .filter((property) => matchesProperty(property, search))
            .map((property) => (
              <PropertyRow
                key={`${property.entityType}.${property.name}`}
                property={property}
              />
            ))}
          {(data.customProperties ?? []).filter((property) => matchesProperty(property, search))
            .length === 0 && (
            <Empty>
              {search.trim()
                ? `No custom property matches “${search.trim()}”.`
                : 'OpenMetadata defines no custom properties yet. Once a steward adds one to a table or column type, it appears here after the next sync.'}
            </Empty>
          )}
        </section>
      )}

      {data && tab !== 'properties' && (
        <VocabularyTable>
          {roots.length === 0 && (
            <Empty>
              {search.trim()
                ? `Nothing here matches “${search.trim()}”.`
                : editing.canEdit
                  ? 'Nothing synced from OpenMetadata under this heading yet. Make the first one here with New classification.'
                  : 'Nothing synced from OpenMetadata under this heading yet.'}
            </Empty>
          )}
          {roots.map((value) => (
              <ValueRow
                bulk={bulk}
                forceOpen={search.trim().length > 0}
                key={value.fqn}
                tab={tab}
                value={value}
              />
            ))}
        </VocabularyTable>
      )}

      {data && tab === 'domains' && data.dataProducts.length > 0 && (
        <>
          <h2 className="tw:mt-8 tw:text-md tw:font-semibold tw:text-primary">
            Data products
          </h2>
          <VocabularyTable>
            {data.dataProducts
              .filter((value) => matches(value, search.trim().toLowerCase()))
              .map((value) => (
                <ValueRow
                  bulk={bulk}
                  key={value.fqn}
                  tab="dataProducts"
                  value={value}
                />
              ))}
          </VocabularyTable>
        </>
      )}
    </EditingContext.Provider>
  );
}

/**
 * One table for the whole tree: a header naming the two numbers once, and a
 * row per value with its counts in fixed columns, so a column of zeros reads
 * as a column instead of a scatter of cards.
 */
function VocabularyTable({ children }: { children: ReactNode }) {
  return (
    <section className="tw:mt-5 tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary">
      <div className="tw:flex tw:items-center tw:gap-3 tw:border-b tw:border-secondary tw:bg-secondary_subtle tw:px-4 tw:py-2 tw:text-xs tw:font-medium tw:text-tertiary">
        <span className="tw:flex-1">Name</span>
        <span className="tw:w-36 tw:shrink-0 tw:text-right">Assets</span>
        <span className="tw:w-24 tw:shrink-0 tw:text-right">Policies</span>
      </div>
      <div className="tw:divide-y tw:divide-secondary">{children}</div>
    </section>
  );
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="tw:px-4 tw:py-8 tw:text-center tw:text-sm tw:text-tertiary">{children}</p>;
}

/** A single "open/close everything" instruction, identified by when it was given. */
interface Bulk {
  nonce: number;
  open: boolean;
}

function BulkButton({
  label,
  icon,
  onPress,
}: {
  label: string;
  icon: ReactNode;
  onPress: () => void;
}) {
  return (
    <button
      className="tw:cursor-pointer tw:flex tw:items-center tw:gap-1.5 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:font-medium tw:text-secondary tw:hover:bg-secondary"
      onClick={onPress}
      type="button">
      {icon}
      {label}
    </button>
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

/** "3 tags", "1 sub-domain": what the children of a row are called on this tab. */
function childLabel(tab: string, count: number): string {
  const noun =
    tab === 'classifications'
      ? 'tag'
      : tab === 'glossaries'
        ? 'term'
        : tab === 'domains'
          ? 'sub-domain'
          : 'item';
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}

function ValueRow({
  value,
  tab,
  depth = 0,
  forceOpen = false,
  bulk = null,
}: {
  value: GovernanceValue;
  tab: string;
  depth?: number;
  forceOpen?: boolean;
  bulk?: Bulk | null;
}) {
  const facet = facetOf(tab, value);
  const editing = useEditing();
  const [open, setOpen] = useState(depth === 0);
  // Only on a new instruction, hence the nonce in the dependency list rather
  // than the flag: clicking "expand all" twice should still reopen a row the
  // reader closed in between.
  useEffect(() => {
    if (bulk) setOpen(bulk.open);
  }, [bulk?.nonce]); // eslint-disable-line react-hooks/exhaustive-deps
  const expanded = forceOpen || open;
  const hasChildren = value.children.length > 0;
  const label = value.displayName || value.name;
  const description = plainText(value.description);
  // A description that only repeats the name says nothing and doubles the row.
  const describes =
    description && description.trim().toLowerCase() !== label.trim().toLowerCase()
      ? description
      : '';
  // Tags go directly under a classification, OpenMetadata's or one made here;
  // only what was made here can be changed here.
  const isClassification = tab === 'classifications' && !value.parentFqn;
  const mayAddTag = editing.canEdit && isClassification && !value.disabled;
  const mayChange = editing.canEdit && value.provenance === 'local';
  const formHere = editing.canEdit && formUnder(editing.form, value) ? editing.form : null;

  return (
    <>
      <div
        className={`tw:flex tw:items-center tw:gap-3 tw:py-2.5 tw:pr-4 tw:hover:bg-secondary ${
          depth === 0 && hasChildren ? 'tw:bg-secondary_subtle' : ''
        }`}
        style={{ paddingLeft: 16 + depth * 24 }}>
        {hasChildren ? (
          <button
            aria-label={expanded ? 'Collapse' : 'Expand'}
            className="tw:shrink-0 tw:cursor-pointer tw:rounded tw:text-tertiary tw:hover:text-primary"
            onClick={() => setOpen(!open)}
            type="button">
            {expanded ? (
              <ChevronDown className="tw:size-4" />
            ) : (
              <ChevronRight className="tw:size-4" />
            )}
          </button>
        ) : (
          <span className="tw:w-4 tw:shrink-0" />
        )}

        <div className="tw:min-w-0 tw:flex-1">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-x-2 tw:gap-y-0.5">
            <span
              className={`tw:text-sm tw:text-primary ${
                depth === 0 ? 'tw:font-semibold' : 'tw:font-medium'
              }`}
              title={value.fqn}>
              {label}
            </span>
            {/* Below a root the tree already spells the path out; repeating
                it in full on every row is what made a sub-domain unreadable. */}
            {depth === 0 && value.fqn !== label && (
              <span className="tw:text-xs tw:text-quaternary">{value.fqn}</span>
            )}
            {hasChildren && (
              <span className="tw:rounded-full tw:bg-secondary tw:px-2 tw:py-0.5 tw:text-xs tw:text-tertiary">
                {childLabel(tab, value.children.length)}
              </span>
            )}
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
                {value.provenance === 'local' ? 'made in ARAK' : value.provenance}
              </Badge>
            )}
          </div>
          {describes && (
            <p className="tw:mt-0.5 tw:truncate tw:text-xs tw:text-tertiary">{describes}</p>
          )}
        </div>

        {(mayAddTag || mayChange) && (
          <span className="tw:flex tw:shrink-0 tw:gap-3">
            {mayAddTag && (
              <button
                aria-label={`Add a tag under ${label}`}
                className="tw:cursor-pointer tw:text-xs tw:font-medium tw:text-brand-secondary tw:hover:underline"
                onClick={() => {
                  setOpen(true);
                  editing.open({ mode: 'tag', classification: value });
                }}
                type="button">
                Add tag
              </button>
            )}
            {mayChange && (
              <button
                aria-label={`Edit ${label}`}
                className="tw:cursor-pointer tw:text-xs tw:font-medium tw:text-secondary tw:hover:underline"
                onClick={() =>
                  editing.open({
                    mode: 'edit',
                    kind: isClassification ? 'CLASSIFICATION' : 'TAG',
                    value,
                  })
                }
                type="button">
                Edit
              </button>
            )}
          </span>
        )}

        <Link
          className={`tw:w-36 tw:shrink-0 tw:text-right tw:text-sm tw:tabular-nums tw:hover:underline ${
            value.assets > 0 ? 'tw:text-brand-secondary' : 'tw:text-tertiary'
          }`}
          to={`/catalog?facet=${encodeURIComponent(`${facet}:${value.fqn}`)}`}>
          {value.assets} asset{value.assets === 1 ? '' : 's'}
          {value.directAssets !== value.assets && (
            <span className="tw:text-tertiary"> · {value.directAssets} direct</span>
          )}
        </Link>

        <span
          className={`tw:w-24 tw:shrink-0 tw:text-right tw:text-sm tw:tabular-nums ${
            value.policies > 0 ? 'tw:font-medium tw:text-primary' : 'tw:text-tertiary'
          }`}>
          {value.policies} polic{value.policies === 1 ? 'y' : 'ies'}
        </span>
      </div>

      {formHere && (
        <div className="tw:py-3 tw:pr-4" style={{ paddingLeft: 40 + depth * 24 }}>
          <VocabularyFormPanel form={formHere} key={`${formHere.mode}:${value.fqn}`} />
        </div>
      )}

      {expanded &&
        value.children.map((child) => (
          <ValueRow
            bulk={bulk}
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
    <div className="tw:flex tw:items-start tw:gap-3 tw:border-t tw:border-secondary tw:px-4 tw:py-2.5 tw:first:border-t-0 tw:hover:bg-secondary">
      <span className="tw:w-56 tw:shrink-0 tw:truncate tw:text-sm tw:font-medium tw:text-primary">
        {property.name}
      </span>
      <span className="tw:flex tw:w-44 tw:shrink-0 tw:flex-wrap tw:gap-1">
        <Badge color="gray" size="sm" type="pill-color">
          {property.entityType}
        </Badge>
        <Badge color="blue-light" size="sm" type="pill-color">
          {property.dataType}
          {property.multiSelect ? ' · multi' : ''}
        </Badge>
      </span>
      <span className="tw:min-w-0 tw:flex-1 tw:text-xs tw:text-tertiary">
        {property.enumValues && <span className="tw:block">{property.enumValues}</span>}
        {plainText(property.description) && (
          <span className="tw:block tw:truncate">{plainText(property.description)}</span>
        )}
      </span>
    </div>
  );
}
