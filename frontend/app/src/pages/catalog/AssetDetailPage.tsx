import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  ArrowLeft,
  AlertCircle,
  Check,
  ChevronRight,
  Copy01,
  Eye,
  LinkExternal01,
} from '@untitledui/icons';
import { lookFor } from './assetLook';
import { Chip as Badge } from '../../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import {
  apiErrorMessage,
  fetchAsset,
  type AssetOwner,
  type ColumnDetail,
  type FacetRow,
} from '../../api/client';
import {
  fetchPoliciesForAsset,
  type AppliedPolicy,
} from '../../api/policies';
import {
  FacetChip,
  FacetGroup,
  columnFacets,
  facetName,
  groupFacets,
} from './facets';
import { AccessTab } from './AccessTab';
import { AssetAccessAction } from './AssetRequestAccess';
import { AuditTab } from './AuditTab';
import { Field, Panel } from './panels';
import { ChildrenPanel, childLabel, childrenTitle, isContainer } from './hierarchy';
import { plainText } from '../../lib/text';
import { isAncestor, leaf, segments } from '../../lib/fqn';
import { ReachBadges, reachSentence } from './reach';

/**
 * One asset, with everything a policy can select it by.
 *
 * <p>This is the screen a data owner opens to answer "why is this column
 * restricted?", so every facet says whether it was applied here or inherited
 * and from what (FR-2A.1). The physical facets are kept rather than filtered
 * out as they are on the list: down here they are the answer to "what exactly
 * does a policy on `schema = dbo` reach?".
 *
 * <p>The Policies panel is the other half of the policy summary screen: there
 * you ask what one rule reaches, here you ask what reaches one table. Both read
 * the same bindings, so the two answers cannot drift apart (FR-3.1.5).
 *
 * <p>The five tabs are the five questions asked here, and they are separate
 * tabs because they are asked one at a time: what is this, who can read it,
 * what governs it, what is in it, what has been done to it. Stacked on one
 * page they made the two that matter most -- access and policies -- the two
 * furthest down.
 *
 * <p>The open tab is in the query string, so a link to this page can be a
 * link to an answer. "Look at the access on this table" is the message
 * somebody actually sends, and it should not arrive as a link to the top of a
 * page.
 *
 * <p>What is not here yet: the enforcement state, which arrives with M5.
 */
export default function AssetDetailPage() {
  // The FQN is one path param containing dots and, rarely, slashes; the route
  // uses a splat so React Router hands over the whole tail rather than the
  // first segment.
  const params = useParams();
  const navigate = useNavigate();
  const [search, setSearch] = useSearchParams();
  const fqn = params['*'] ?? params.fqn ?? '';
  const requested = search.get('tab');
  const tab: TabId = isTab(requested) ? requested : 'overview';

  const openTab = (next: TabId) => {
    const updated = new URLSearchParams(search);
    if (next === 'overview') {
      updated.delete('tab');
    } else {
      updated.set('tab', next);
    }
    // Replaced rather than pushed: reading five tabs on one asset should not
    // put five entries in the back button between here and the catalog.
    setSearch(updated, { replace: true });
  };

  const { data, isLoading, error } = useQuery({
    queryKey: ['catalog-asset', fqn],
    queryFn: () => fetchAsset(fqn),
    enabled: Boolean(fqn),
    retry: false,
  });

  if (isLoading) {
    return <p className="tw:text-sm tw:text-tertiary">Loading…</p>;
  }

  if (error || !data) {
    return (
      <>
        <BackLink />
        <div className="tw:mt-6 tw:flex tw:items-start tw:gap-2 tw:rounded-xl tw:border tw:border-error_subtle tw:bg-error-primary tw:p-4">
          <AlertCircle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-error-primary" />
          <div>
            <p className="tw:text-sm tw:text-error-primary">
              {apiErrorMessage(error, 'That asset could not be read.')}
            </p>
            {/* The distinction the 404 itself cannot draw, and the one that
                decides whether the fix is a crawl or a conversation with the
                catalog team. */}
            <p className="tw:mt-1 tw:text-xs tw:text-error-primary">
              Not being in the cache is not the same as not being in
              OpenMetadata — check the last sync on the System page.
            </p>
          </div>
        </div>
      </>
    );
  }

  const { asset, columns, customProperties } = data;
  const container = isContainer(asset.assetType);
  // A link to a table's "contents" or a schema's "columns" -- a tab this kind
  // of asset does not have -- opens on the overview instead of on nothing.
  const shown: TabId =
    (tab === 'contents' && !container) || (tab === 'columns' && container)
      ? 'overview'
      : tab;
  const look = lookFor(asset.assetType);
  const grouped = groupFacets(data.facets);
  const properties = Object.entries(customProperties ?? {});

  return (
    <>
      {/* One card, laid out as OpenMetadata lays out its own asset page:
          where it sits, what it is called, what you can do to it, and the
          handful of facts people look for first. Somebody who lives in
          OpenMetadata should find each of them where their eye already goes. */}
      <header className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-5 tw:pt-4 tw:pb-5 tw:shadow-xs">
        <Breadcrumb fqn={asset.fqn} />

        <div className="tw:mt-3 tw:flex tw:flex-wrap tw:items-start tw:gap-x-4 tw:gap-y-3">
          <div className="tw:flex tw:min-w-0 tw:flex-1 tw:items-start tw:gap-3">
            {/* The same tile the catalog list draws, so arriving here confirms
              * you opened what you clicked rather than asking you to re-read
              * the FQN to be sure. */}
            <span
              className={`tw:flex tw:size-10 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg ${look.tile}`}>
              <look.Icon className="tw:size-5" />
            </span>
            <div className="tw:min-w-0">
              <div className="tw:flex tw:items-center tw:gap-1.5">
                <h1 className="tw:truncate tw:text-display-xs tw:font-semibold tw:text-primary">
                  {asset.displayName || asset.name}
                </h1>
                <CopyFqn fqn={asset.fqn} />
              </div>
              {plainText(asset.description) && (
                <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
                  {plainText(asset.description)}
                </p>
              )}
            </div>
          </div>

          {/* The asset's actions, top right. Request access leads because it
            * is the one a reader who cannot get in came for; the other two
            * are for somebody who already can. */}
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <AssetAccessAction asset={asset} />
            {/* ARAK caches what OpenMetadata knows; it does not replace it.
              * Everything this page cannot answer -- lineage, profiles, the
              * conversation hanging off the description -- is one click away
              * rather than a copied FQN in another tab. Absent for an asset
              * that has no page over there. */}
            {data.openMetadataUrl && (
              <Button
                color="secondary"
                href={data.openMetadataUrl}
                iconLeading={LinkExternal01}
                rel="noreferrer"
                size="sm"
                target="_blank">
                Open in OpenMetadata
              </Button>
            )}
            {/* What the policies add up to for one person -- the question an
              * owner asks straight after reading them, and the one a list
              * cannot answer (FR-5.2). */}
            <Button
              color="secondary"
              iconLeading={Eye}
              onPress={() =>
                navigate(`/simulator?asset=${encodeURIComponent(asset.fqn)}`)
              }
              size="sm">
              View as someone
            </Button>
          </div>
        </div>

        <dl className="tw:mt-5 tw:flex tw:flex-wrap tw:items-start tw:gap-y-4">
          <Stat first label="Type">
            {asset.assetType}
          </Stat>
          <Stat label="Domains">
            <Domains facets={data.facets} />
          </Stat>
          <Stat label="Owners">
            <Owners owners={data.owners} />
          </Stat>
          <Stat label="Tier">
            {asset.tier ? (
              <Badge color="warning" size="sm" type="pill-color">
                {leaf(asset.tier)}
              </Badge>
            ) : (
              <None />
            )}
          </Stat>
          <Stat label="Certification">
            {asset.certification ? (
              <Badge color="success" size="sm" type="pill-color">
                {leaf(asset.certification)}
              </Badge>
            ) : (
              <None />
            )}
          </Stat>
          <Stat label="Connection">
            <span className="tw:flex tw:flex-col tw:items-start tw:gap-1">
              <ReachBadges asset={asset} />
              <span className="tw:max-w-72 tw:text-xs tw:font-normal tw:text-tertiary">
                {reachSentence(asset)}
              </span>
            </span>
          </Stat>
          {container ? (
            <Stat label="Contains">{childLabel(asset.assetType, asset.childCount ?? 0)}</Stat>
          ) : (
            <Stat label="Columns">
              {asset.columnCount > 0 ? asset.columnCount : <None />}
            </Stat>
          )}
        </dl>
      </header>

      <AssetTabs
        assetType={asset.assetType}
        childCount={asset.childCount ?? 0}
        columnCount={columns.length}
        onChange={openTab}
        value={shown}
      />

      <div className="tw:mt-6">
        {shown === 'contents' && <ChildrenPanel asset={asset} />}

        {shown === 'overview' && (
          <div className="tw:grid tw:gap-6 tw:lg:grid-cols-3">
            <section className="tw:lg:col-span-2 tw:space-y-6">
              <Panel title="Governance">
                {grouped.length === 0 ? (
                  <p className="tw:text-sm tw:text-tertiary">
                    No tags, terms, domains or data products reach this asset. A
                    policy written against facets will not select it.
                  </p>
                ) : (
                  // A heading per kind, divided. The headings went away once
                  // because they sat in a left-hand column that cost a sixth
                  // of the panel; above the chips they cost one short line and
                  // let the chips drop the prefix that was repeating them.
                  <div className="tw:flex tw:flex-col tw:divide-y tw:divide-secondary">
                    {grouped.map(([type, facets]) => (
                      <div className="tw:py-3 tw:first:pt-0 tw:last:pb-0" key={type}>
                        <FacetGroup facets={facets} type={type} />
                      </div>
                    ))}
                  </div>
                )}
              </Panel>
            </section>

            <aside className="tw:space-y-6">
              <Panel title="Location">
                <dl className="tw:space-y-2 tw:text-sm">
                  <Field label="FQN" value={asset.fqn} />
                  <Field label="Source" value={asset.dataSource} />
                  <Field label="Parent" value={asset.parentFqn} />
                  <Field
                    label="Columns"
                    value={asset.columnCount > 0 ? String(asset.columnCount) : null}
                  />
                </dl>
              </Panel>

              {properties.length > 0 && (
                <Panel
                  subtitle="Asset-side attributes an ABAC rule can compare against"
                  title="Custom properties">
                  <dl className="tw:space-y-2 tw:text-sm">
                    {properties.map(([name, value]) => (
                      <Field
                        key={name}
                        label={name}
                        value={
                          typeof value === 'object' && value !== null
                            ? JSON.stringify(value)
                            : String(value)
                        }
                      />
                    ))}
                  </dl>
                </Panel>
              )}
            </aside>
          </div>
        )}

        {shown === 'access' && <AccessTab fqn={asset.fqn} />}

        {shown === 'policies' && <Policies fqn={asset.fqn} />}

        {shown === 'columns' && (
          <Panel
            subtitle={`${columns.length} columns · ${
              columns.filter((column) => columnFacets(column.facets).length > 0)
                .length
            } carrying governance of their own`}
            title="Columns">
            {columns.length === 0 ? (
              <p className="tw:text-sm tw:text-tertiary">
                The crawl found no columns on this asset.
              </p>
            ) : (
              <div className="tw:overflow-x-auto">
                <table className="tw:w-full tw:text-sm">
                  <thead>
                    <tr className="tw:border-b tw:border-secondary tw:text-left tw:text-xs tw:text-tertiary">
                      <th className="tw:py-2 tw:pr-3 tw:font-medium">Column</th>
                      <th className="tw:py-2 tw:pr-3 tw:font-medium">Type</th>
                      <th className="tw:py-2 tw:font-medium">Governance</th>
                    </tr>
                  </thead>
                  <tbody>
                    {columns.map((column) => (
                      <ColumnRow column={column} key={column.id} />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        )}

        {shown === 'audit' && <AuditTab fqn={asset.fqn} />}
      </div>

    </>
  );
}

const TABS = [
  { value: 'overview', label: 'Overview' },
  // Named for what it holds -- Databases, Schemas, Tables -- and only on a
  // service, database or schema, the way OpenMetadata's container pages open.
  { value: 'contents', label: 'Contents' },
  { value: 'access', label: 'Access' },
  { value: 'policies', label: 'Policies' },
  { value: 'columns', label: 'Columns' },
  { value: 'audit', label: 'Audit' },
] as const;

type TabId = (typeof TABS)[number]['value'];

function isTab(value: string | null): value is TabId {
  return TABS.some((tab) => tab.value === value);
}

/**
 * The five tabs, as OpenMetadata draws its own: a full-width strip in a card
 * of its own, the open tab underlined in brand blue, its count filled.
 *
 * <p>A segmented control stood here for a while, because an underline alone
 * was too light between a heading and a card. The card is what fixes that --
 * the strip reads as the page's navigation because it is a surface of its own
 * -- and it keeps the page looking like the one people have open in the other
 * tab.
 *
 * <p>Only the column count is shown, because it is the only one already in
 * hand. The others would each cost a request made solely to put a number on a
 * tab nobody has opened, and a count that is sometimes a number and sometimes a
 * spinner reads as broken. One count, always right, does not.
 */
function AssetTabs({
  value,
  onChange,
  columnCount,
  assetType,
  childCount,
}: {
  value: TabId;
  onChange: (next: TabId) => void;
  columnCount: number;
  assetType: string;
  childCount: number;
}) {
  const container = isContainer(assetType);
  // A container holds assets, not columns; a table holds columns, not assets.
  const tabs = TABS.filter((tab) =>
    tab.value === 'contents' ? container : tab.value === 'columns' ? !container : true
  );
  const count = (tab: TabId) =>
    tab === 'columns' ? columnCount : tab === 'contents' ? childCount : 0;
  return (
    <div className="tw:mt-4 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:shadow-xs">
      {/* `overflow-y-hidden` is not decoration. Setting only `overflow-x`
          leaves the other axis computing to `auto`, and the half-pixel the
          underline overhangs by was enough for Windows to park a full
          vertical scrollbar -- arrows and all -- on the right of the strip. */}
      <div
        aria-label="Asset"
        className="tw:flex tw:gap-2 tw:overflow-x-auto tw:overflow-y-hidden"
        role="tablist">
        {tabs.map((tab) => {
          const active = tab.value === value;
          const counted = count(tab.value);
          return (
            <button
              aria-selected={active}
              // Tailwind's reset gives a button `cursor: default`, which reads
              // as "not clickable" on everything that is not obviously a form
              // control. These are the page's main navigation.
              className={`tw:relative tw:flex tw:shrink-0 tw:cursor-pointer tw:items-center tw:px-3 tw:py-3.5 tw:text-sm tw:font-semibold tw:transition-colors tw:focus-visible:outline-2 tw:focus-visible:-outline-offset-2 tw:focus-visible:outline-brand ${
                active ? 'tw:text-brand-secondary' : 'tw:text-tertiary tw:hover:text-primary'
              }`}
              key={tab.value}
              onClick={() => onChange(tab.value)}
              role="tab"
              type="button">
              {tab.value === 'contents' ? childrenTitle(assetType) : tab.label}
              {counted > 0 && (
                <span
                  className={`tw:ml-2 tw:rounded tw:px-1.5 tw:py-0.5 tw:text-xs tw:tabular-nums ${
                    active
                      ? 'tw:bg-brand-solid tw:text-white'
                      : 'tw:bg-secondary tw:text-tertiary'
                  }`}>
                  {counted}
                </span>
              )}
              {active && (
                <span
                  aria-hidden="true"
                  className="tw:absolute tw:inset-x-3 tw:bottom-0 tw:h-0.5 tw:rounded-full tw:bg-brand-solid"
                />
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * Every policy in force on this table, split the way the model splits them.
 *
 * <p>The two halves answer different questions and a data owner asks them
 * separately: subscription decides whether the table opens at all, data policy
 * decides what is left once it does. Merging them into one list would make the
 * screen shorter and the answer worse.
 */
function Policies({ fqn }: { fqn: string }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['asset-policies', fqn],
    queryFn: () => fetchPoliciesForAsset(fqn),
    enabled: Boolean(fqn),
    retry: false,
  });

  const rows = data ?? [];
  const subscription = rows.filter(
    (row) => row.policy.document.policyType === 'SUBSCRIPTION'
  );
  const dataRows = rows.filter(
    (row) => row.policy.document.policyType === 'DATA'
  );

  return (
    <Panel
      subtitle="Active policies bound to this asset, outermost layer first"
      title="Policies">
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading…</p>}

      {error != null && (
        <p className="tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'The policies for this asset could not be read.')}
        </p>
      )}

      {data && rows.length === 0 && (
        // Not a neutral emptiness: default is deny, so no policy here means
        // nobody reaches this table at all, which is worth saying outright.
        <p className="tw:text-sm tw:text-warning-primary">
          No active policy reaches this asset, so nothing grants access to it.
          Access is denied by default until a subscription policy selects it.
        </p>
      )}

      {rows.length > 0 && (
        <div className="tw:space-y-5">
          <PolicyGroup
            empty="No subscription policy selects this asset, so nobody is granted access to it."
            rows={subscription}
            title="Subscription — who may read it"
          />
          <PolicyGroup
            empty="No data policy applies, so rows and columns are returned whole to anyone the subscription lets in."
            rows={dataRows}
            title="Data — what is visible once they are in"
          />
        </div>
      )}
    </Panel>
  );
}

function PolicyGroup({
  title,
  rows,
  empty,
}: {
  title: string;
  rows: AppliedPolicy[];
  empty: string;
}) {
  return (
    <div>
      <h3 className="tw:text-xs tw:font-semibold tw:tracking-wide tw:text-tertiary tw:uppercase">
        {title}
      </h3>
      {rows.length === 0 ? (
        <p className="tw:mt-2 tw:text-pretty tw:text-sm tw:text-tertiary">{empty}</p>
      ) : (
        <ul className="tw:mt-2 tw:space-y-2">
          {rows.map((row) => (
            <AppliedRow key={row.policy.id} row={row} />
          ))}
        </ul>
      )}
    </div>
  );
}

function AppliedRow({ row }: { row: AppliedPolicy }) {
  const policy = row.policy.document;
  const deny = policy.effect === 'DENY';
  return (
    <li className="tw:rounded-lg tw:border tw:border-secondary tw:p-3">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <Link
          className="tw:text-sm tw:font-medium tw:text-primary tw:hover:underline"
          to={`/policies/${row.policy.id}`}>
          {policy.displayName || policy.name}
        </Link>
        <Badge color="gray" size="sm" type="modern">
          {policy.scopeLevel === 'ORG' ? 'Global' : policy.scopeLevel}
        </Badge>
        {policy.policyType === 'SUBSCRIPTION' && (
          <Badge color={deny ? 'error' : 'success'} size="sm" type="pill-color">
            {deny ? 'Deny' : 'Allow'}
          </Badge>
        )}
        {!policy.allowLocalOverride && (
          <span className="tw:text-xs tw:text-quaternary">a grant cannot get past this</span>
        )}
      </div>

      {plainText(policy.description) && (
        <p className="tw:mt-1 tw:text-pretty tw:text-xs tw:text-tertiary">
          {plainText(policy.description)}
        </p>
      )}

      {row.columns.length > 0 && (
        <div className="tw:mt-2 tw:flex tw:flex-wrap tw:items-center tw:gap-1.5">
          <span className="tw:text-xs tw:text-tertiary">Columns:</span>
          {row.columns.map((column) => (
            <span
              className="tw:rounded tw:bg-secondary tw:px-1.5 tw:py-0.5 tw:font-mono tw:text-xs tw:text-secondary"
              key={column.fqn}>
              {column.name ?? column.fqn}
              {typeof column.matchReason.action === 'string' && (
                <span className="tw:ml-1 tw:text-quaternary">
                  {String(column.matchReason.action).toLowerCase()}
                </span>
              )}
            </span>
          ))}
        </div>
      )}
    </li>
  );
}

function ColumnRow({ column }: { column: ColumnDetail }) {
  const own = columnFacets(column.facets);

  return (
    <tr className="tw:border-b tw:border-secondary tw:last:border-0">
      <td className="tw:py-2 tw:pr-3 tw:align-top">
        <span className="tw:font-medium tw:text-primary">{column.name}</span>
        {plainText(column.description) && (
          <p className="tw:mt-0.5 tw:max-w-md tw:text-xs tw:text-tertiary">
            {plainText(column.description)}
          </p>
        )}
      </td>
      <td className="tw:py-2 tw:pr-3 tw:align-top tw:font-mono tw:text-xs tw:text-tertiary">
        {column.dataType ?? '—'}
        {column.dataLength ? `(${column.dataLength})` : ''}
        {column.nullable === false && (
          <span className="tw:ml-1 tw:text-quaternary">NOT NULL</span>
        )}
      </td>
      <td className="tw:py-2 tw:align-top">
        {own.length === 0 ? (
          <span className="tw:text-xs tw:text-quaternary">—</span>
        ) : (
          <div className="tw:flex tw:flex-wrap tw:gap-1.5">
            {own.map((facet) => (
              <FacetChip facet={facet} key={facetKey(facet)} />
            ))}
          </div>
        )}
      </td>
    </tr>
  );
}

/**
 * Where the asset sits: Catalog, then each level of its FQN.
 *
 * <p>Each parent is a link, because "what else is in this schema?" is the next
 * question often enough, and the service, database and schema all have pages
 * of their own here. The last segment is this page and is not a link.
 */
function Breadcrumb({ fqn }: { fqn: string }) {
  const parts = segments(fqn);
  // Re-quoted where OpenMetadata quotes: a segment holding a dot is one
  // level, and splitting it on the way back would link to an asset that is
  // not there.
  const raw = parts.map((part) => (part.includes('.') ? `"${part}"` : part));
  return (
    <nav aria-label="Breadcrumb">
      <ol className="tw:flex tw:flex-wrap tw:items-center tw:gap-1 tw:text-sm">
        <li>
          <Link className="tw:text-tertiary tw:hover:text-primary" to="/catalog">
            Catalog
          </Link>
        </li>
        {parts.map((part, index) => {
          const last = index === parts.length - 1;
          return (
            <li className="tw:flex tw:min-w-0 tw:items-center tw:gap-1" key={index}>
              <ChevronRight aria-hidden className="tw:size-3.5 tw:shrink-0 tw:text-quaternary" />
              {last ? (
                <span aria-current="page" className="tw:truncate tw:font-semibold tw:text-primary">
                  {part}
                </span>
              ) : (
                <Link
                  className="tw:truncate tw:text-tertiary tw:hover:text-primary"
                  to={`/catalog/${raw.slice(0, index + 1).join('.')}`}>
                  {part}
                </Link>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/** The FQN to the clipboard -- the thing people paste into a policy or a ticket. */
function CopyFqn({ fqn }: { fqn: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      aria-label={copied ? 'Copied' : 'Copy FQN'}
      className="tw:flex tw:size-7 tw:shrink-0 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-md tw:text-quaternary tw:hover:bg-primary_hover tw:hover:text-secondary"
      onClick={() => {
        void navigator.clipboard?.writeText(fqn).then(() => {
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1500);
        });
      }}
      title={fqn}
      type="button">
      {copied ? (
        <Check className="tw:size-4 tw:text-fg-success-primary" />
      ) : (
        <Copy01 className="tw:size-4" />
      )}
    </button>
  );
}

/**
 * One fact in the strip under the name: its label above, its value below,
 * a thin rule between it and the one before, the height of both lines. A dot
 * was tried first and read as a speck on the page rather than a separator.
 */
function Stat({
  label,
  first = false,
  children,
}: {
  label: string;
  first?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="tw:flex tw:min-w-0 tw:items-stretch">
      {!first && (
        <span aria-hidden="true" className="tw:mx-6 tw:w-px tw:shrink-0 tw:self-stretch tw:bg-border-secondary" />
      )}
      <div className="tw:min-w-0">
        <dt className="tw:text-sm tw:text-tertiary">{label}</dt>
        <dd className="tw:mt-1.5 tw:flex tw:min-h-6 tw:items-center tw:text-sm tw:font-medium tw:text-primary">
          {children}
        </dd>
      </div>
    </div>
  );
}

function None() {
  return <span className="tw:text-quaternary">--</span>;
}

/**
 * The domains this asset is in, deepest only -- one shown, the rest counted.
 *
 * <p>Only the leaves: the crawl stores every ancestor of a sub-domain so a
 * selector stays an index lookup (FR-2A.2), and printing `Finance`,
 * `Finance.Risk` and `Finance.Risk.Credit` side by side says one thing three
 * times. The Governance panel below has them all.
 */
function Domains({ facets }: { facets: FacetRow[] }) {
  const fqns = [
    ...new Set(
      facets.filter((facet) => facet.facetType === 'domains').map((facet) => facet.facetFqn)
    ),
  ];
  const leaves = fqns.filter((fqn) => !fqns.some((other) => isAncestor(fqn, other)));
  if (leaves.length === 0) {
    return <None />;
  }
  return (
    <span className="tw:flex tw:min-w-0 tw:items-center tw:gap-1.5">
      <span
        className="tw:max-w-56 tw:truncate tw:rounded-md tw:border tw:border-secondary tw:bg-secondary tw:px-2 tw:py-0.5 tw:text-sm tw:font-medium tw:text-secondary"
        title={`Domain · ${leaves[0]}`}>
        {facetName(leaves[0])}
      </span>
      {leaves.length > 1 && (
        <span className="tw:text-xs tw:text-tertiary" title={leaves.slice(1).join('\n')}>
          +{leaves.length - 1}
        </span>
      )}
    </span>
  );
}

/**
 * Who owns it: the first owner with an initial, as OpenMetadata draws a person,
 * and how many more.
 *
 * <p>No owner is said outright rather than left as a dash: it means no one can
 * author a local policy here (FR-3.1.2) and nobody to send a request to, so the
 * platform admin decides it.
 */
function Owners({ owners }: { owners: AssetOwner[] }) {
  if (owners.length === 0) {
    return (
      <span
        className="tw:text-warning-primary"
        title="Nobody owns this asset in OpenMetadata, so no local policy can be authored for it and access requests go to the platform admin.">
        No owner
      </span>
    );
  }
  const [first, ...rest] = owners;
  const how = first.direct
    ? 'named on this asset'
    : `inherited${first.inheritedFrom ? ` from ${first.inheritedFrom}` : ''}`;
  return (
    <span className="tw:flex tw:min-w-0 tw:items-center tw:gap-1.5">
      <span
        aria-hidden="true"
        className="tw:flex tw:size-6 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-full tw:bg-utility-brand-50 tw:text-xs tw:font-semibold tw:text-brand-secondary tw:uppercase">
        {first.name.charAt(0)}
      </span>
      <span className="tw:max-w-44 tw:truncate" title={`${first.type} · ${how}`}>
        {first.name}
      </span>
      {rest.length > 0 && (
        <span
          className="tw:text-xs tw:text-tertiary"
          title={rest.map((owner) => owner.name).join('\n')}>
          +{rest.length}
        </span>
      )}
    </span>
  );
}

function BackLink() {
  return (
    <Link
      className="tw:inline-flex tw:items-center tw:gap-1 tw:text-sm tw:text-tertiary tw:hover:text-primary"
      to="/catalog">
      <ArrowLeft className="tw:size-4" />
      Catalog
    </Link>
  );
}

function facetKey(facet: FacetRow): string {
  return `${facet.facetType}:${facet.facetFqn}:${facet.property ?? ''}:${facet.depth}`;
}
