import { Plus, Trash01 } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import type {
  AssetSelector,
  FacetCondition,
} from '../../generated/entity/policy/policy';
import { flatten, type Vocabulary } from '../../api/governance';
import { Select, TextField } from './controls';

/**
 * The facet condition builder, used for both halves of a policy.
 *
 * The same control picks the assets a policy binds to and the columns a masking
 * rule covers, because in this model they are the same question asked at two
 * levels. Two similar-but-different builders is how the two drift, until an
 * author can select columns by glossary term in one place and not the other.
 *
 * The schema allows arbitrary nesting; this edits the flat shape — a list of
 * conditions joined by one combinator — which is what nearly every real policy
 * is. A more deeply nested document is shown as it was written rather than
 * flattened, so opening a policy here can never quietly simplify it.
 */

const FACETS: { value: FacetCondition['facet']; label: string }[] = [
  { value: 'tags', label: 'Tag' },
  { value: 'classifications', label: 'Classification' },
  { value: 'terms', label: 'Glossary term' },
  { value: 'glossaries', label: 'Glossary' },
  { value: 'domains', label: 'Domain / sub-domain' },
  { value: 'dataProducts', label: 'Data product' },
  { value: 'tier', label: 'Tier' },
  { value: 'certification', label: 'Certification' },
  { value: 'owners', label: 'Owner' },
  { value: 'customProperty', label: 'Custom property' },
  { value: 'service', label: 'Service' },
  { value: 'database', label: 'Database' },
  { value: 'schema', label: 'Schema' },
  { value: 'table', label: 'Table' },
  { value: 'columnName', label: 'Column name' },
  { value: 'dataType', label: 'Column data type' },
];

/** Facets with a below: only these can be matched with "is or is under". */
const HIERARCHICAL = new Set([
  'tags',
  'classifications',
  'terms',
  'glossaries',
  'domains',
]);

const OPERATORS: { value: FacetCondition['operator']; label: string }[] = [
  { value: 'contains', label: 'is or is under' },
  { value: 'eq', label: 'is exactly' },
  { value: 'ne', label: 'is not' },
  { value: 'startsWith', label: 'starts with' },
  { value: 'matches', label: 'matches pattern' },
  { value: 'in', label: 'is one of' },
  { value: 'notIn', label: 'is none of' },
  { value: 'gte', label: 'is at least' },
  { value: 'lte', label: 'is at most' },
  { value: 'exists', label: 'is set' },
  { value: 'notExists', label: 'is not set' },
];

export interface SelectorBuilderProps {
  value: AssetSelector | undefined;
  onChange: (next: AssetSelector) => void;
  vocabulary?: Vocabulary;
  /** Column rules select columns, not tables; the leading word follows. */
  subject?: 'asset' | 'column';
}

interface FlatSelector {
  combinator: 'and' | 'or';
  rows: FacetCondition[];
  /** True when the document says more than this form can edit. */
  nested: boolean;
}

function toRows(selector?: AssetSelector): FlatSelector {
  if (!selector) return { combinator: 'and', rows: [], nested: false };
  if (selector.condition && !selector.and?.length && !selector.or?.length) {
    return { combinator: 'and', rows: [selector.condition], nested: false };
  }
  const branch = selector.or?.length ? selector.or : (selector.and ?? []);
  const combinator: 'and' | 'or' = selector.or?.length ? 'or' : 'and';
  const rows: FacetCondition[] = [];
  let nested = Boolean(selector.not) || Boolean(selector.condition && branch.length);
  for (const child of branch) {
    if (child.condition && !child.and?.length && !child.or?.length && !child.not) {
      rows.push(child.condition);
    } else {
      nested = true;
    }
  }
  return { combinator, rows, nested };
}

function fromRows(
  combinator: 'and' | 'or',
  rows: FacetCondition[]
): AssetSelector {
  if (rows.length === 1) return { condition: rows[0] };
  const children = rows.map((condition) => ({ condition }));
  return combinator === 'or' ? { or: children } : { and: children };
}

export default function SelectorBuilder({
  value,
  onChange,
  vocabulary,
  subject = 'asset',
}: SelectorBuilderProps) {
  const { combinator, rows, nested } = toRows(value);

  function update(nextRows: FacetCondition[], nextCombinator = combinator) {
    onChange(fromRows(nextCombinator, nextRows));
  }

  if (nested) {
    return (
      <div className="tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-4 tw:text-sm tw:text-tertiary">
        This selector nests conditions more deeply than this form edits, so it is
        shown as it was written. Change it through the API or policy-as-code
        rather than here, where saving would flatten it.
        <pre className="tw:mt-3 tw:overflow-x-auto tw:text-xs tw:text-secondary">
          {JSON.stringify(value, null, 2)}
        </pre>
      </div>
    );
  }

  return (
    <div className="tw:flex tw:flex-col tw:gap-3">
      {rows.map((row, index) => (
        <div
          className="tw:flex tw:flex-wrap tw:items-center tw:gap-2"
          key={index}>
          <div className="tw:w-20 tw:flex-none tw:text-sm tw:font-medium tw:text-tertiary">
            {index === 0 ? (
              subject === 'column' ? (
                'Column'
              ) : (
                'Asset'
              )
            ) : (
              <Select
                ariaLabel="Combine with"
                // Only the first join is editable. A flat list mixing and with
                // or has no unambiguous reading, and pretending otherwise is
                // how a policy comes to mean something nobody intended.
                className="tw:w-full tw:py-1"
                onChange={(next) => update(rows, next as 'and' | 'or')}
                options={[
                  { label: 'and', value: 'and' },
                  { label: 'or', value: 'or' },
                ]}
                value={combinator}
              />
            )}
          </div>

          <Select
            ariaLabel="Facet"
            className="tw:w-52"
            onChange={(next) => {
              const copy = [...rows];
              copy[index] = {
                ...row,
                facet: next as FacetCondition['facet'],
                value: '',
                property: undefined,
                operator: HIERARCHICAL.has(next) ? row.operator : coerce(row.operator),
              };
              update(copy);
            }}
            options={FACETS.map((facet) => ({
              label: facet.label,
              value: facet.value,
            }))}
            value={row.facet}
          />

          {row.facet === 'customProperty' && (
            <Select
              ariaLabel="Custom property"
              className="tw:w-48"
              onChange={(next) => {
                const copy = [...rows];
                copy[index] = { ...row, property: next };
                update(copy);
              }}
              options={[
                { label: 'choose a property…', value: '' },
                ...(vocabulary?.customProperties ?? []).map((property) => ({
                  label: `${property.name} (${property.dataType})`,
                  value: property.name,
                })),
              ]}
              value={row.property ?? ''}
            />
          )}

          <Select
            ariaLabel="Operator"
            className="tw:w-44"
            onChange={(next) => {
              const copy = [...rows];
              copy[index] = {
                ...row,
                operator: next as FacetCondition['operator'],
              };
              update(copy);
            }}
            options={OPERATORS.filter(
              // "is or is under" only means something where there is a below.
              // Offering it on a data type invites a policy whose author
              // expects a hierarchy that does not exist.
              (operator) =>
                operator.value !== 'contains' || HIERARCHICAL.has(row.facet)
            ).map((operator) => ({
              label: operator.label,
              value: operator.value,
            }))}
            value={row.operator}
          />

          {row.operator !== 'exists' && row.operator !== 'notExists' && (
            <ValueField
              onChange={(next) => {
                const copy = [...rows];
                copy[index] = { ...row, value: next };
                update(copy);
              }}
              row={row}
              vocabulary={vocabulary}
            />
          )}

          <Button
            aria-label="Remove this condition"
            color="tertiary"
            iconLeading={Trash01}
            onPress={() => update(rows.filter((_, i) => i !== index))}
            size="sm"
          />
        </div>
      ))}

      <div>
        <Button
          color="secondary"
          iconLeading={Plus}
          onPress={() =>
            update([...rows, { facet: 'tags', operator: 'contains', value: '' }])
          }
          size="sm">
          Add condition
        </Button>
      </div>

      {rows.length === 0 && (
        <p className="tw:text-sm tw:text-tertiary">
          No conditions yet. A policy with an empty selector binds to nothing and
          protects nobody, so this has to say something before it can be saved.
        </p>
      )}
    </div>
  );
}

/** Falls back to an operator that means something for a flat facet. */
function coerce(operator: FacetCondition['operator']): FacetCondition['operator'] {
  return operator === 'contains' ? 'eq' : operator;
}

/**
 * The value editor, prompted from the live vocabulary.
 *
 * A datalist rather than a closed dropdown: the crawl is a snapshot, and a
 * steward who created a tag five minutes ago should be able to write a policy
 * about it without waiting for the next sync. The list is a shortcut, not a
 * gate — and each entry carries how many assets carry that value, which is the
 * cheapest impact analysis there is.
 */
function ValueField({
  row,
  vocabulary,
  onChange,
}: {
  row: FacetCondition;
  vocabulary?: Vocabulary;
  onChange: (value: string) => void;
}) {
  const listId = `values-${row.facet}-${row.property ?? 'x'}`;
  const options = valueOptions(row, vocabulary);

  return (
    <>
      <TextField
        ariaLabel="Value"
        className="tw:w-72"
        list={options.length ? listId : undefined}
        onChange={onChange}
        placeholder={placeholderFor(row.facet)}
        value={String(row.value ?? '')}
      />
      {options.length > 0 && (
        <datalist id={listId}>
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.hint}
            </option>
          ))}
        </datalist>
      )}
    </>
  );
}

function valueOptions(
  row: FacetCondition,
  vocabulary?: Vocabulary
): { value: string; hint: string }[] {
  if (!vocabulary) return [];
  const label = (value: { fqn: string; assets: number }) => ({
    value: value.fqn,
    hint: `${value.assets} asset${value.assets === 1 ? '' : 's'}`,
  });

  switch (row.facet) {
    case 'tags':
      return flatten(vocabulary.classifications)
        .filter((value) => value.parentFqn)
        .map(label);
    case 'classifications':
      return vocabulary.classifications.map(label);
    case 'terms':
      return flatten(vocabulary.glossaries)
        .filter((value) => value.parentFqn)
        .map(label);
    case 'glossaries':
      return vocabulary.glossaries.map(label);
    case 'domains':
      return flatten(vocabulary.domains).map(label);
    case 'dataProducts':
      return vocabulary.dataProducts.map(label);
    case 'tier':
      return flatten(vocabulary.classifications)
        .filter((value) => value.fqn.startsWith('Tier.'))
        .map(label);
    default:
      return [];
  }
}

function placeholderFor(facet: FacetCondition['facet']): string {
  switch (facet) {
    case 'tags':
      return 'PII.Sensitive';
    case 'classifications':
      return 'PII';
    case 'terms':
      return 'Finance.CustomerIdentity';
    case 'domains':
      return 'Finance.Risk.Credit';
    case 'columnName':
      return 'citizen_id, or *_email';
    case 'dataType':
      return 'VARCHAR';
    default:
      return 'value';
  }
}
