import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, AlertCircle, Eye } from '@untitledui/icons';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import {
  apiErrorMessage,
  fetchAsset,
  type ColumnDetail,
  type FacetRow,
} from '../../api/client';
import {
  fetchPoliciesForAsset,
  type AppliedPolicy,
} from '../../api/policies';
import { FacetChip, OwnerChip, facetLabel, groupFacets } from './facets';
import { AccessTab } from './AccessTab';
import { AuditTab } from './AuditTab';
import { Field, Panel } from './panels';
import { plainText } from '../../lib/text';

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
  const grouped = groupFacets(data.facets);
  const properties = Object.entries(customProperties ?? {});

  return (
    <>
      <BackLink />

      <header className="tw:mt-4">
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">
            {asset.displayName || asset.name}
          </h1>
          {/*
            The policies below say what governs this table. This says what
            that adds up to for one person -- the question an owner asks
            straight after reading the list, and the one the list cannot
            answer, because composing seven layers in your head is exactly
            what nobody can do reliably (FR-5.2).
          */}
          <Button
            className="tw:ml-auto"
            color="secondary"
            iconLeading={Eye}
            onPress={() =>
              navigate(`/simulator?asset=${encodeURIComponent(asset.fqn)}`)
            }
            size="sm">
            View as someone
          </Button>
          <Badge color="gray" size="sm" type="modern">
            {asset.assetType}
          </Badge>
          {asset.tier && (
            <Badge color="warning" size="sm" type="pill-color">
              {asset.tier}
            </Badge>
          )}
          {asset.certification && (
            <Badge color="success" size="sm" type="pill-color">
              {asset.certification}
            </Badge>
          )}
        </div>
        <p className="tw:mt-2 tw:font-mono tw:text-xs tw:break-all tw:text-quaternary">
          {asset.fqn}
        </p>
        {plainText(asset.description) && (
          <p className="tw:mt-3 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
            {plainText(asset.description)}
          </p>
        )}
      </header>

      <AssetTabs columnCount={columns.length} onChange={openTab} value={tab} />

      <div className="tw:mt-6">
        {tab === 'overview' && (
          <div className="tw:grid tw:gap-6 tw:lg:grid-cols-3">
            <section className="tw:lg:col-span-2 tw:space-y-6">
              <Panel title="Governance">
                {grouped.length === 0 ? (
                  <p className="tw:text-sm tw:text-tertiary">
                    No tags, terms, domains or data products reach this asset. A
                    policy written against facets will not select it.
                  </p>
                ) : (
                  <dl className="tw:space-y-3">
                    {grouped.map(([type, facets]) => (
                      <div className="tw:flex tw:flex-wrap tw:gap-2" key={type}>
                        <dt className="tw:w-40 tw:shrink-0 tw:text-xs tw:text-tertiary">
                          {facetLabel(type)}
                        </dt>
                        <dd className="tw:flex tw:flex-wrap tw:gap-1.5">
                          {facets.map((facet) => (
                            <FacetChip facet={facet} key={facetKey(facet)} />
                          ))}
                        </dd>
                      </div>
                    ))}
                  </dl>
                )}
              </Panel>
            </section>

            <aside className="tw:space-y-6">
              <Panel title="Owners">
                {data.owners.length === 0 ? (
                  // Worth saying rather than leaving blank: no owner means no one
                  // can author a local policy here (FR-3.1.2) and, in Phase 2, no
                  // one to route a request to.
                  <p className="tw:text-sm tw:text-warning-primary">
                    Nobody owns this asset in OpenMetadata, so no local policy
                    can be authored for it.
                  </p>
                ) : (
                  <div className="tw:flex tw:flex-wrap tw:gap-1.5">
                    {data.owners.map((owner) => (
                      <OwnerChip
                        key={`${owner.type}:${owner.name}`}
                        owner={owner}
                      />
                    ))}
                  </div>
                )}
              </Panel>

              <Panel title="Location">
                <dl className="tw:space-y-2 tw:text-sm">
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

        {tab === 'access' && <AccessTab fqn={asset.fqn} />}

        {tab === 'policies' && <Policies fqn={asset.fqn} />}

        {tab === 'columns' && (
          <Panel
            subtitle={`${columns.length} columns · ${
              columns.filter((column) => column.facets.length > 0).length
            } carrying a facet`}
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

        {tab === 'audit' && <AuditTab fqn={asset.fqn} />}
      </div>

    </>
  );
}

const TABS = [
  { value: 'overview', label: 'Overview' },
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
 * The five tabs, underlined in the style of the catalog they mirror.
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
}: {
  value: TabId;
  onChange: (next: TabId) => void;
  columnCount: number;
}) {
  return (
    <div
      aria-label="Asset"
      className="tw:mt-6 tw:flex tw:gap-1 tw:overflow-x-auto tw:border-b tw:border-secondary"
      role="tablist">
      {TABS.map((tab) => {
        const active = tab.value === value;
        return (
          <button
            aria-selected={active}
            className={`tw:-mb-px tw:shrink-0 tw:border-b-2 tw:px-3 tw:py-2.5 tw:text-sm tw:font-medium ${
              active
                ? 'tw:border-brand tw:text-brand-secondary'
                : 'tw:border-transparent tw:text-tertiary tw:hover:text-primary'
            }`}
            key={tab.value}
            onClick={() => onChange(tab.value)}
            role="tab"
            type="button">
            {tab.label}
            {tab.value === 'columns' && columnCount > 0 && (
              <span className="tw:ml-1.5 tw:rounded tw:bg-secondary tw:px-1.5 tw:py-0.5 tw:text-xs tw:text-tertiary">
                {columnCount}
              </span>
            )}
          </button>
        );
      })}
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
          <span className="tw:text-xs tw:text-quaternary">cannot be relaxed below</span>
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
        {column.facets.length === 0 ? (
          <span className="tw:text-xs tw:text-quaternary">—</span>
        ) : (
          <div className="tw:flex tw:flex-wrap tw:gap-1.5">
            {column.facets.map((facet) => (
              <FacetChip facet={facet} key={facetKey(facet)} />
            ))}
          </div>
        )}
      </td>
    </tr>
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
