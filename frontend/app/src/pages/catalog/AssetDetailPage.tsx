import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, AlertCircle } from '@untitledui/icons';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import {
  apiErrorMessage,
  fetchAsset,
  type ColumnDetail,
  type FacetRow,
} from '../../api/client';
import { FacetChip, OwnerChip, facetLabel, groupFacets } from './facets';

/**
 * One asset, with everything a policy can select it by.
 *
 * <p>This is the screen a data owner opens to answer "why is this column
 * restricted?", so every facet says whether it was applied here or inherited
 * and from what (FR-2A.1). The physical facets are kept rather than filtered
 * out as they are on the list: down here they are the answer to "what exactly
 * does a policy on `schema = dbo` reach?".
 *
 * <p>What is not here yet: the policies that apply to this asset (FR-3.1.5) and
 * the enforcement state. Both arrive with M3/M5 and belong on this page.
 */
export default function AssetDetailPage() {
  // The FQN is one path param containing dots and, rarely, slashes; the route
  // uses a splat so React Router hands over the whole tail rather than the
  // first segment.
  const params = useParams();
  const fqn = params['*'] ?? params.fqn ?? '';

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
        {asset.description && (
          <p className="tw:mt-3 tw:max-w-3xl tw:text-sm tw:text-tertiary">
            {asset.description}
          </p>
        )}
      </header>

      <div className="tw:mt-8 tw:grid tw:gap-6 tw:lg:grid-cols-3">
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

          {columns.length > 0 && (
            <Panel
              subtitle={`${columns.length} columns · ${
                columns.filter((column) => column.facets.length > 0).length
              } carrying a facet`}
              title="Columns">
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
            </Panel>
          )}
        </section>

        <aside className="tw:space-y-6">
          <Panel title="Owners">
            {data.owners.length === 0 ? (
              // Worth saying rather than leaving blank: no owner means no one
              // can author a local policy here (FR-3.1.2) and, in Phase 2, no
              // one to route a request to.
              <p className="tw:text-sm tw:text-warning-primary">
                Nobody owns this asset in OpenMetadata, so no local policy can
                be authored for it.
              </p>
            ) : (
              <div className="tw:flex tw:flex-wrap tw:gap-1.5">
                {data.owners.map((owner) => (
                  <OwnerChip key={`${owner.type}:${owner.name}`} owner={owner} />
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
    </>
  );
}

function ColumnRow({ column }: { column: ColumnDetail }) {
  return (
    <tr className="tw:border-b tw:border-secondary tw:last:border-0">
      <td className="tw:py-2 tw:pr-3 tw:align-top">
        <span className="tw:font-medium tw:text-primary">{column.name}</span>
        {column.description && (
          <p className="tw:mt-0.5 tw:max-w-md tw:text-xs tw:text-tertiary">
            {column.description}
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

function Panel({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
      <h2 className="tw:text-sm tw:font-semibold tw:text-primary">{title}</h2>
      {subtitle && <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">{subtitle}</p>}
      <div className="tw:mt-3">{children}</div>
    </section>
  );
}

function Field({ label, value }: { label: string; value: string | null }) {
  if (!value) {
    return null;
  }
  return (
    <div className="tw:flex tw:gap-2">
      <dt className="tw:w-28 tw:shrink-0 tw:text-xs tw:text-tertiary">{label}</dt>
      <dd className="tw:min-w-0 tw:break-all tw:text-sm tw:text-primary">{value}</dd>
    </div>
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
