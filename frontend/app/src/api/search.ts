import { apiClient } from './client';

/**
 * One hit from the header's search box.
 *
 * `kind` is what the thing is; `facetType` is how the catalog filters by it,
 * and is set only for the governance vocabularies. Keeping both means the UI
 * can name the hit in the user's language ("Glossary term") while linking with
 * the string the catalog's facet filter actually expects.
 */
export interface SearchHit {
  kind: SearchKind;
  facetType: string | null;
  subtype: string | null;
  id: string | null;
  fqn: string | null;
  name: string;
  displayName: string | null;
  description: string | null;
  parentFqn: string | null;
  /** How many assets a vocabulary value reaches; -1 where the count is meaningless. */
  assets: number;
}

export type SearchKind =
  | 'asset'
  | 'column'
  | 'tag'
  | 'classification'
  | 'term'
  | 'glossary'
  | 'domain'
  | 'dataProduct'
  | 'policy';

export interface SearchResults {
  query: string;
  limit: number;
  items: SearchHit[];
}

export async function search(
  query: string,
  limit = 20
): Promise<SearchResults> {
  const { data } = await apiClient.get<SearchResults>('/v1/search', {
    params: { q: query, limit },
  });
  return data;
}

/**
 * Where clicking a hit should land.
 *
 * Vocabulary hits go to the catalog filtered by that value rather than to a
 * page about the value itself: somebody who searched for a tag is looking for
 * the data carrying it, not for the tag's description.
 */
export function hitHref(hit: SearchHit): string {
  switch (hit.kind) {
    case 'asset':
      return `/catalog/${encodeURIComponent(hit.fqn ?? '')}`;
    case 'column':
      // A column has no page of its own, so open the table it belongs to;
      // parentFqn is that table's fqn.
      return `/catalog/${encodeURIComponent(hit.parentFqn ?? '')}`;
    case 'policy':
      return `/policies/${hit.id ?? ''}`;
    default:
      return `/catalog?facet=${encodeURIComponent(
        `${hit.facetType}:${hit.fqn}`
      )}`;
  }
}

/** The heading a hit files under, in the order the groups should be shown. */
export const KIND_GROUPS: { kind: SearchKind; label: string }[] = [
  { kind: 'asset', label: 'Data assets' },
  { kind: 'column', label: 'Columns' },
  { kind: 'tag', label: 'Tags' },
  { kind: 'classification', label: 'Classifications' },
  { kind: 'term', label: 'Glossary terms' },
  { kind: 'glossary', label: 'Glossaries' },
  { kind: 'domain', label: 'Domains' },
  { kind: 'dataProduct', label: 'Data products' },
  { kind: 'policy', label: 'Policies' },
];
