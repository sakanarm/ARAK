import type {
  AssetSelector1,
  Policy,
} from '../../generated/entity/policy/policy';

/**
 * Where a condition keeps what it compares against.
 *
 * "Is one of" and "is none of" compare against a list, and the engine reads
 * that list from `values`; every other comparison reads the single `value`.
 * The editor used to write both kinds into `value`, so a list typed as
 * "FINANCE, RISK" was one string nobody carries: "is one of" matched nobody,
 * and "is none of" matched everybody. These helpers are the one place the
 * editor decides which field a row writes.
 */

type Scalar = string | number | boolean;

interface Compared {
  operator: string;
  value?: Scalar | null;
  values?: Scalar[];
}

/** The two comparisons against a list rather than one value. */
export function isListOperator(operator: string | undefined): boolean {
  return operator === 'in' || operator === 'notIn';
}

/** Items typed on one line: split at commas, trimmed, blanks dropped. */
export function splitValues(text: string): string[] {
  return text
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
}

/**
 * The list a condition compares against.
 *
 * A policy saved before the fix keeps it in `value` as one comma-separated
 * string. It is read the way the engine reads it, so the author sees the items
 * they meant, and saving writes them where they belong.
 */
export function listOf(row: Compared): Scalar[] {
  if (row.values?.length) return row.values;
  if (typeof row.value === 'string') return splitValues(row.value);

  return row.value === undefined || row.value === null ? [] : [row.value];
}

/**
 * The row with a new operator, and what it compares against moved to the field
 * that operator reads.
 *
 * Leaving a list for a single value keeps the first item rather than joining
 * them: "is" followed by "FINANCE, RISK" is the same one-string mistake this
 * exists to prevent.
 */
export function withOperator<T extends Compared>(
  row: T,
  operator: T['operator']
): T {
  if (isListOperator(operator)) {
    return { ...row, operator, value: undefined, values: listOf(row) };
  }
  if (isListOperator(row.operator)) {
    return { ...row, operator, value: listOf(row)[0] ?? '', values: undefined };
  }

  return { ...row, operator };
}

function selector<S extends AssetSelector1>(node: S): S {
  const next = { ...node };
  if (node.condition) next.condition = withOperator(node.condition, node.condition.operator);
  if (node.and) next.and = node.and.map(selector);
  if (node.or) next.or = node.or.map(selector);
  if (node.not) next.not = selector(node.not);

  return next;
}

/**
 * The document as it should be saved: every list condition's items in
 * `values`, and nothing left in `value` beside them.
 *
 * Needed for a policy that was opened, not edited, and saved again -- its old
 * rows are shown as a list but were never rewritten, and the server refuses
 * the old shape rather than guessing what it meant.
 */
export function normaliseConditionValues(policy: Policy): Policy {
  const next = { ...policy };
  if (policy.selector) next.selector = selector(policy.selector);
  if (policy.subject?.attributes) {
    next.subject = {
      ...policy.subject,
      attributes: policy.subject.attributes.map((row) => withOperator(row, row.operator)),
    };
  }
  if (policy.data?.columnRules) {
    next.data = {
      ...policy.data,
      columnRules: policy.data.columnRules.map((rule) => ({
        ...rule,
        columns: selector(rule.columns),
      })),
    };
  }

  return next;
}
