import { Plus, Trash01 } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import type {
  ColumnRule,
  DataPolicy,
  LookupKey,
  MaskingSpec,
  RowFilter,
  RowLookup,
} from '../../generated/entity/policy/policy';
import type { AttributeVocabulary, Vocabulary } from '../../api/governance';
import { Field, Select, TextField } from './controls';
import SelectorBuilder from './SelectorBuilder';

/**
 * What a reader sees inside a table they are allowed to reach: row filters
 * (FR-4.1) and column rules (FR-4.2 to FR-4.5).
 *
 * Row filters and column rules are edited side by side because they are one
 * decision. "Analysts see their own branch, with the ID number masked" is a
 * single sentence in the head of the person writing it, and splitting it across
 * two screens is how half of it gets forgotten.
 */

const ROW_FILTER_KINDS: { value: RowFilter['kind']; label: string; help: string }[] = [
  {
    value: 'ATTRIBUTE_COMPARE',
    label: 'Column matches their own attribute',
    help: 'The commonest filter there is: branch_code equals their branch, cost_centre equals their cost centre.',
  },
  {
    value: 'IN_LIST',
    label: 'Column is one of their values',
    help: 'For multi-valued attributes — somebody covering three regions sees all three.',
  },
  {
    value: 'ENTITLEMENT_JOIN',
    label: 'Joined against the entitlement table',
    help: 'For mappings too irregular to express as a comparison. The platform maintains the entitlement rows from the same decision, in every mode.',
  },
  {
    value: 'LOOKUP',
    label: 'Column is one of the values a mapping table gives them',
    help: 'For access kept in a table of its own: department AA sees division A because a mapping row says so. Whoever can change that mapping table decides who sees what, so govern it like this policy. Enforced through the query API only; a secure view over the table shows no rows.',
  },
  {
    value: 'ALWAYS_FALSE',
    label: 'No rows at all',
    help: 'The table keeps its shape and loses its contents. Useful when a schema has to stay discoverable.',
  },
  {
    value: 'RAW_PREDICATE',
    label: 'Raw SQL predicate',
    help: 'The escape hatch. Dialect-checked, never interpolated with anything a caller sent, and it bypasses the validation the other kinds get.',
  },
];

const LOOKUP_MODES: {
  value: NonNullable<RowLookup['mode']>;
  label: string;
  help: string;
}[] = [
  {
    value: 'SUBQUERY',
    label: 'Join it into the query',
    help: 'The source reads the mapping as part of each query, so a change to it counts at once and a person may map to any number of values. The mapping table has to be on the same data source as the table it filters.',
  },
  {
    value: 'READ_VALUES',
    label: 'Read the values first',
    help: 'ARAK reads the allowed values when the query runs and filters on that list, so the mapping table may be on another data source. Refused when a person maps to more than 1,000 values, and only for text, whole-number, decimal, UUID and date columns.',
  },
];

const MASK_FUNCTIONS: { value: MaskingSpec['function']; label: string }[] = [
  { value: 'NULLIFY', label: 'Blank it out' },
  { value: 'CONSTANT', label: 'Replace with a fixed value' },
  { value: 'HASH', label: 'Hash it (still joinable)' },
  { value: 'PARTIAL', label: 'Keep the last few characters' },
  { value: 'REGEX_REPLACE', label: 'Rewrite by pattern' },
  { value: 'ROUNDING', label: 'Round it' },
];

export interface DataPolicyBuilderProps {
  value?: DataPolicy;
  onChange: (next: DataPolicy) => void;
  vocabulary?: Vocabulary;
  attributes?: AttributeVocabulary;
}

export default function DataPolicyBuilder({
  value,
  onChange,
  vocabulary,
  attributes,
}: DataPolicyBuilderProps) {
  const data: DataPolicy = value ?? {};
  const rowFilters = data.rowFilters ?? [];
  const columnRules = data.columnRules ?? [];

  const attributeNames = (attributes?.keys ?? []).map((key) => key.key);

  return (
    <div className="tw:flex tw:flex-col tw:gap-8">
      {/* ------------------------------------------------------ row level */}
      <section>
        <header className="tw:mb-3">
          <h3 className="tw:text-sm tw:font-semibold tw:text-primary">
            Which rows they see
          </h3>
          <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
            Every filter here is ANDed with the filters of every other policy at
            every level. Nothing written here can widen what another layer
            narrowed.
          </p>
        </header>

        <div className="tw:flex tw:flex-col tw:gap-3">
          {rowFilters.map((filter, index) => (
            <RowFilterRow
              attributeNames={attributeNames}
              filter={filter}
              key={index}
              vocabulary={vocabulary}
              onChange={(next) => {
                const copy = [...rowFilters];
                copy[index] = next;
                onChange({ ...data, rowFilters: copy });
              }}
              onRemove={() =>
                onChange({
                  ...data,
                  rowFilters: rowFilters.filter((_, i) => i !== index),
                })
              }
            />
          ))}
          <div>
            <Button
              color="secondary"
              iconLeading={Plus}
              onPress={() =>
                onChange({
                  ...data,
                  rowFilters: [
                    ...rowFilters,
                    {
                      kind: 'ATTRIBUTE_COMPARE',
                      column: '',
                      operator: 'eq',
                      userAttribute: attributeNames[0] ?? '',
                    },
                  ],
                })
              }
              size="sm">
              Add row filter
            </Button>
          </div>
        </div>
      </section>

      {/* --------------------------------------------------- column level */}
      <section>
        <header className="tw:mb-3">
          <h3 className="tw:text-sm tw:font-semibold tw:text-primary">
            What they see in each column
          </h3>
          <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
            Columns are chosen the same way assets are — by tag, glossary term,
            data type or name. A rule written against a tag keeps working when a
            steward tags a new column tomorrow, which is the whole point.
          </p>
        </header>

        <div className="tw:flex tw:flex-col tw:gap-3">
          {columnRules.map((rule, index) => (
            <ColumnRuleCard
              key={index}
              onChange={(next) => {
                const copy = [...columnRules];
                copy[index] = next;
                onChange({ ...data, columnRules: copy });
              }}
              onRemove={() =>
                onChange({
                  ...data,
                  columnRules: columnRules.filter((_, i) => i !== index),
                })
              }
              rule={rule}
              vocabulary={vocabulary}
            />
          ))}
          <div>
            <Button
              color="secondary"
              iconLeading={Plus}
              onPress={() =>
                onChange({
                  ...data,
                  columnRules: [
                    ...columnRules,
                    {
                      columns: {
                        condition: {
                          facet: 'tags',
                          operator: 'contains',
                          value: '',
                        },
                      },
                      action: 'MASK',
                      masking: { function: 'PARTIAL', showLast: 4 },
                    },
                  ],
                })
              }
              size="sm">
              Add column rule
            </Button>
          </div>
        </div>
      </section>
    </div>
  );
}

/** The kinds that compare a column with the person's own attribute. */
function comparesColumn(kind: RowFilter['kind']): boolean {
  return kind === 'ATTRIBUTE_COMPARE' || kind === 'IN_LIST';
}

/**
 * The kinds that filter on a column, and so can pick it by tag instead of
 * name. A lookup compares its column with what the mapping gives, not with an
 * attribute, but finds that column the same way.
 */
function picksColumn(kind: RowFilter['kind']): boolean {
  return comparesColumn(kind) || kind === 'LOOKUP';
}

/** A mapping to fill in: one key, joined into the query. */
function blankLookup(attributeNames: string[]): RowLookup {
  return {
    table: '',
    keys: [{ column: '', userAttribute: attributeNames[0] ?? '' }],
    valueColumn: '',
    mode: 'SUBQUERY',
  };
}

function RowFilterRow({
  filter,
  onChange,
  onRemove,
  attributeNames,
  vocabulary,
}: {
  filter: RowFilter;
  onChange: (next: RowFilter) => void;
  onRemove: () => void;
  attributeNames: string[];
  vocabulary?: Vocabulary;
}) {
  const kind = ROW_FILTER_KINDS.find((entry) => entry.value === filter.kind);
  const byTag = filter.columns !== undefined;
  const lookup = filter.lookup ?? blankLookup(attributeNames);
  const mode = LOOKUP_MODES.find((entry) => entry.value === (lookup.mode ?? 'SUBQUERY'));

  function patchLookup(next: Partial<RowLookup>) {
    onChange({ ...filter, lookup: { ...lookup, ...next } });
  }

  function patchKey(index: number, next: Partial<LookupKey>) {
    const keys = [...lookup.keys] as RowLookup['keys'];
    keys[index] = { ...keys[index], ...next };
    patchLookup({ keys });
  }

  /** Named or tagged, and the name when it is named. */
  function columnPicker(placeholder: string, width: string) {
    return (
      <>
        <Select
          ariaLabel="Pick the column by"
          className="tw:w-40"
          onChange={(next) =>
            onChange(
              next === 'tag'
                ? {
                    ...filter,
                    column: undefined,
                    columns: {
                      condition: { facet: 'tags', operator: 'contains', value: '' },
                    },
                  }
                : { ...filter, column: '', columns: undefined }
            )
          }
          options={[
            { value: 'name', label: 'Column named' },
            { value: 'tag', label: 'Column tagged' },
          ]}
          value={byTag ? 'tag' : 'name'}
        />
        {!byTag && (
          <TextField
            ariaLabel="Column"
            className={width}
            onChange={(next) => onChange({ ...filter, column: next })}
            placeholder={placeholder}
            value={filter.column ?? ''}
          />
        )}
      </>
    );
  }

  return (
    <div className="tw:rounded-lg tw:border tw:border-secondary tw:p-4">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <Select
          ariaLabel="Row filter kind"
          className="tw:w-72"
          onChange={(next) => {
            const nextKind = next as RowFilter['kind'];
            // A selector on a kind that filters on no column would be ignored,
            // and the server refuses it rather than let it look like it works.
            const moved: RowFilter = picksColumn(nextKind)
              ? { ...filter, kind: nextKind }
              : { ...filter, kind: nextKind, columns: undefined };
            onChange(
              nextKind === 'LOOKUP'
                ? byTag
                  ? { ...moved, lookup }
                  : { ...moved, column: filter.column ?? '', lookup }
                : // A mapping left on another kind would be saved and never read.
                  { ...moved, lookup: undefined }
            );
          }}
          options={ROW_FILTER_KINDS.map((entry) => ({
            value: entry.value,
            label: entry.label,
          }))}
          value={filter.kind}
        />

        {comparesColumn(filter.kind) && (
          <>
            {columnPicker('branch_code', 'tw:w-48')}
            {filter.kind === 'ATTRIBUTE_COMPARE' ? (
              <Select
                ariaLabel="Comparison"
                className="tw:w-32"
                onChange={(next) =>
                  onChange({ ...filter, operator: next as RowFilter['operator'] })
                }
                options={[
                  { value: 'eq', label: 'equals' },
                  { value: 'ne', label: 'is not' },
                  { value: 'gte', label: 'at least' },
                  { value: 'lte', label: 'at most' },
                ]}
                value={filter.operator ?? 'eq'}
              />
            ) : (
              <span className="tw:text-sm tw:text-tertiary">is one of their</span>
            )}
            <TextField
              ariaLabel="User attribute"
              className="tw:w-52"
              list="row-filter-attributes"
              onChange={(next) => onChange({ ...filter, userAttribute: next })}
              placeholder="branch"
              value={filter.userAttribute ?? ''}
            />
            <datalist id="row-filter-attributes">
              {attributeNames.map((name) => (
                <option key={name} value={name} />
              ))}
            </datalist>
          </>
        )}

        {filter.kind === 'ENTITLEMENT_JOIN' && (
          <TextField
            ariaLabel="Entitlement key"
            className="tw:w-64"
            onChange={(next) => onChange({ ...filter, entitlementKey: next })}
            placeholder="branch_code"
            value={filter.entitlementKey ?? ''}
          />
        )}

        {filter.kind === 'LOOKUP' && (
          <>
            {columnPicker('division', 'tw:w-44')}
            <span className="tw:text-sm tw:text-tertiary">is one of the</span>
            <TextField
              ariaLabel="Value column"
              className="tw:w-40"
              onChange={(next) => patchLookup({ valueColumn: next })}
              placeholder="division"
              value={lookup.valueColumn}
            />
            <span className="tw:text-sm tw:text-tertiary">values in</span>
            <TextField
              ariaLabel="Mapping table"
              className="tw:w-96 tw:font-mono"
              onChange={(next) => patchLookup({ table: next })}
              placeholder="service.database.schema.table"
              value={lookup.table}
            />
          </>
        )}

        {filter.kind === 'RAW_PREDICATE' && (
          <TextField
            ariaLabel="Predicate"
            className="tw:w-full tw:font-mono"
            onChange={(next) => onChange({ ...filter, rawPredicate: next })}
            placeholder="c.region_id IN (SELECT region_id FROM acl.region_of(:principal))"
            value={filter.rawPredicate ?? ''}
          />
        )}

        <Button
          aria-label="Remove this filter"
          color="tertiary"
          iconLeading={Trash01}
          onPress={onRemove}
          size="sm"
        />
      </div>
      {picksColumn(filter.kind) && byTag && (
        <div className="tw:mt-3">
          <SelectorBuilder
            onChange={(next) => onChange({ ...filter, columns: next })}
            subject="column"
            value={filter.columns}
            vocabulary={vocabulary}
          />
          <p className="tw:mt-2 tw:text-xs tw:text-tertiary">
            Found in each table by its tag, so one policy covers tables that name
            the column differently. A table without such a column shows no rows;
            a table with two is filtered on both.
          </p>
        </div>
      )}
      {filter.kind === 'LOOKUP' && (
        <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
          {lookup.keys.map((key, index) => (
            <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2" key={index}>
              <span className="tw:w-12 tw:text-sm tw:text-tertiary">
                {index === 0 ? 'where' : 'and'}
              </span>
              <TextField
                ariaLabel={`Mapping column ${index + 1}`}
                className="tw:w-44"
                onChange={(next) => patchKey(index, { column: next })}
                placeholder="department"
                value={key.column}
              />
              <span className="tw:text-sm tw:text-tertiary">is one of their</span>
              <TextField
                ariaLabel={`Their attribute ${index + 1}`}
                className="tw:w-52"
                list="row-filter-attributes"
                onChange={(next) => patchKey(index, { userAttribute: next })}
                placeholder="department"
                value={key.userAttribute}
              />
              {lookup.keys.length > 1 && (
                <Button
                  aria-label={`Remove key ${index + 1}`}
                  color="tertiary"
                  iconLeading={Trash01}
                  onPress={() =>
                    patchLookup({
                      keys: lookup.keys.filter((_, i) => i !== index) as RowLookup['keys'],
                    })
                  }
                  size="sm"
                />
              )}
            </div>
          ))}
          <datalist id="row-filter-attributes">
            {attributeNames.map((name) => (
              <option key={name} value={name} />
            ))}
          </datalist>
          <div>
            <Button
              color="secondary"
              iconLeading={Plus}
              onPress={() =>
                patchLookup({ keys: [...lookup.keys, { column: '', userAttribute: '' }] })
              }
              size="sm">
              Add a key
            </Button>
          </div>
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <span className="tw:text-sm tw:text-tertiary">The mapping is</span>
            <Select
              ariaLabel="How the mapping is read"
              className="tw:w-64"
              onChange={(next) =>
                patchLookup({ mode: next as NonNullable<RowLookup['mode']> })
              }
              options={LOOKUP_MODES.map((entry) => ({
                value: entry.value,
                label: entry.label,
              }))}
              value={lookup.mode ?? 'SUBQUERY'}
            />
          </div>
          <p className="tw:text-xs tw:text-tertiary">
            Every key must match for a mapping row to count. A person without a
            value for one of the attributes sees no rows. The database compares
            the values, so write them in the mapping exactly as the tables hold
            them, letter case included.
          </p>
          {mode && <p className="tw:text-xs tw:text-tertiary">{mode.help}</p>}
        </div>
      )}
      {kind && <p className="tw:mt-2 tw:text-xs tw:text-tertiary">{kind.help}</p>}
    </div>
  );
}

function ColumnRuleCard({
  rule,
  onChange,
  onRemove,
  vocabulary,
}: {
  rule: ColumnRule;
  onChange: (next: ColumnRule) => void;
  onRemove: () => void;
  vocabulary?: Vocabulary;
}) {
  const masking = rule.masking ?? { function: 'NULLIFY' as const };

  function patchMasking(next: Partial<MaskingSpec>) {
    onChange({ ...rule, masking: { ...masking, ...next } });
  }

  return (
    <div className="tw:rounded-lg tw:border tw:border-secondary tw:p-4">
      <div className="tw:flex tw:items-start tw:justify-between tw:gap-3">
        <p className="tw:text-sm tw:font-medium tw:text-secondary">Columns where</p>
        <Button
          aria-label="Remove this rule"
          color="tertiary"
          iconLeading={Trash01}
          onPress={onRemove}
          size="sm"
        />
      </div>

      <div className="tw:mt-3">
        <SelectorBuilder
          onChange={(next) => onChange({ ...rule, columns: next })}
          subject="column"
          value={rule.columns}
          vocabulary={vocabulary}
        />
      </div>

      <div className="tw:mt-4 tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <span className="tw:text-sm tw:font-medium tw:text-secondary">are</span>
        <Select
          ariaLabel="Action"
          className="tw:w-56"
          onChange={(next) => {
            const action = next as ColumnRule['action'];
            onChange({
              ...rule,
              action,
              // A HIDE or ALLOW carrying a leftover masking spec is a document
              // that says two different things; the compiler would have to pick
              // one, and nobody reading the policy would know which.
              masking: action === 'MASK' ? masking : undefined,
            });
          }}
          options={[
            { value: 'MASK', label: 'masked' },
            { value: 'HIDE', label: 'hidden entirely' },
            { value: 'ALLOW', label: 'left readable' },
          ]}
          value={rule.action}
        />

        {rule.action === 'MASK' && (
          <>
            <Select
              ariaLabel="Masking function"
              className="tw:w-64"
              onChange={(next) =>
                patchMasking({ function: next as MaskingSpec['function'] })
              }
              options={MASK_FUNCTIONS.map((entry) => ({
                value: entry.value,
                label: entry.label,
              }))}
              value={masking.function}
            />
            {masking.function === 'CONSTANT' && (
              <TextField
                ariaLabel="Replacement value"
                className="tw:w-48"
                onChange={(next) => patchMasking({ constant: next })}
                placeholder="***REDACTED***"
                value={masking.constant ?? ''}
              />
            )}
            {masking.function === 'PARTIAL' && (
              <>
                <span className="tw:text-sm tw:text-tertiary">keeping the last</span>
                <TextField
                  ariaLabel="Characters kept"
                  className="tw:w-20"
                  onChange={(next) =>
                    patchMasking({ showLast: Number(next) || undefined })
                  }
                  type="number"
                  value={String(masking.showLast ?? '')}
                />
              </>
            )}
            {masking.function === 'REGEX_REPLACE' && (
              <>
                <TextField
                  ariaLabel="Pattern"
                  className="tw:w-52 tw:font-mono"
                  onChange={(next) => patchMasking({ regex: next })}
                  placeholder="^[^@]+"
                  value={masking.regex ?? ''}
                />
                <span className="tw:text-sm tw:text-tertiary">replaced with</span>
                <TextField
                  ariaLabel="Replacement"
                  className="tw:w-32 tw:font-mono"
                  onChange={(next) => patchMasking({ replacement: next })}
                  placeholder="***"
                  value={masking.replacement ?? ''}
                />
              </>
            )}
            {masking.function === 'ROUNDING' && (
              <TextField
                ariaLabel="Round to"
                className="tw:w-32"
                onChange={(next) => patchMasking({ roundTo: next })}
                placeholder="YEAR"
                value={masking.roundTo ?? ''}
              />
            )}
            {masking.function === 'HASH' && (
              <span className="tw:text-xs tw:text-tertiary">
                Salted per column, so the values stay joinable within this column
                and cannot be lined up against another one.
              </span>
            )}
          </>
        )}
      </div>

      {rule.action === 'MASK' && (
        <div className="tw:mt-4">
          <Field
            hint="Leave empty to mask for everyone this policy covers. Filling it in makes this a cell mask, which native source config cannot express on either engine — the panel on the right will say so."
            label="Only on rows where (optional)">
            <TextField
              onChange={(next) => onChange({ ...rule, condition: next || undefined })}
              placeholder="dept != user.department"
              value={rule.condition ?? ''}
            />
          </Field>
        </div>
      )}
    </div>
  );
}
