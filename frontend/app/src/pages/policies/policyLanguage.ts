import type {
  AssetSelector,
  ColumnRule,
  FacetCondition,
  MaskingSpec,
  Policy,
  PrincipalMatch,
  RowFilter,
  SubjectRule,
} from '../../generated/entity/policy/policy';

/**
 * Reads a policy back as a sentence.
 *
 * A policy document is a tree of facets, operators and masking functions, and
 * nobody approving one should have to hold that tree in their head to know what
 * they are agreeing to. The sentence is generated from the same document the
 * engine evaluates, so it cannot drift from what will actually happen — which
 * is the whole reason it is worth having rather than a description field
 * somebody forgot to update.
 */

const FACET_WORDS: Record<string, string> = {
  classifications: 'classification',
  tags: 'tag',
  glossaries: 'glossary',
  terms: 'glossary term',
  domains: 'domain',
  dataProducts: 'data product',
  owners: 'owner',
  tier: 'tier',
  certification: 'certification',
  customProperty: 'property',
  service: 'service',
  database: 'database',
  schema: 'schema',
  table: 'table',
  columnName: 'column name',
  dataType: 'data type',
};

const OPERATOR_WORDS: Record<string, string> = {
  contains: 'under',
  eq: 'exactly',
  ne: 'not',
  startsWith: 'starting with',
  matches: 'matching',
  in: 'one of',
  notIn: 'none of',
  gt: 'above',
  gte: 'at least',
  lt: 'below',
  lte: 'at most',
  exists: 'set',
  notExists: 'not set',
};

const MASK_WORDS: Record<string, string> = {
  NULLIFY: 'blanked out',
  CONSTANT: 'replaced with a fixed value',
  HASH: 'hashed',
  PARTIAL: 'partly hidden',
  REGEX_REPLACE: 'rewritten',
  ROUNDING: 'rounded',
  CONDITIONAL: 'masked conditionally',
};

export function describeCondition(condition: FacetCondition): string {
  const facet =
    condition.facet === 'customProperty' && condition.property
      ? `property ${condition.property}`
      : (FACET_WORDS[condition.facet] ?? condition.facet);
  const operator = OPERATOR_WORDS[condition.operator] ?? condition.operator;

  if (condition.operator === 'exists' || condition.operator === 'notExists') {
    return `${facet} is ${operator}`;
  }
  const value =
    condition.values?.length
      ? condition.values.join(', ')
      : String(condition.value ?? '');
  return `${facet} ${operator} ${value || '…'}`;
}

export function describeSelector(selector?: AssetSelector): string {
  if (!selector) return 'nothing';
  const parts: string[] = [];
  if (selector.condition) parts.push(describeCondition(selector.condition));
  if (selector.and?.length) {
    parts.push(selector.and.map((s) => describeSelector(s)).join(' and '));
  }
  if (selector.or?.length) {
    parts.push(`(${selector.or.map((s) => describeSelector(s)).join(' or ')})`);
  }
  if (selector.not) parts.push(`not ${describeSelector(selector.not)}`);
  // An empty selector binds to nothing. Saying "everything" here would be the
  // most dangerous possible mistranslation, so it says what it does.
  return parts.length ? parts.join(' and ') : 'nothing';
}

/** One identity entry. Fields within an entry are ANDed, so all of them show. */
export function describePrincipal(match: PrincipalMatch): string {
  const parts: string[] = [];
  if (match.role) parts.push(`anyone with the role ${match.role}`);
  if (match.team) parts.push(`in the team ${match.team}`);
  if (match.group) parts.push(`in the group ${match.group}`);
  if (match.user) parts.push(match.user);
  if (match.assetOwner) parts.push('whoever owns the asset');
  return parts.length ? parts.join(' and ') : 'nobody';
}

export function describeSubject(subject?: SubjectRule): string {
  if (!subject) return 'everyone';
  const clauses: string[] = [];

  if (subject.principals?.length) {
    clauses.push(subject.principals.map(describePrincipal).join(' or '));
  }
  // Joined with "and", and never folded into the list above. This sentence is
  // what an author checks the policy against before publishing it, and a rule
  // that reads as a wider "or" than it enforces is the one mistranslation that
  // would have somebody publish a grant they meant to restrict.
  if (subject.requiredPrincipals?.length) {
    clauses.push(
      `who is also ${subject.requiredPrincipals.map(describePrincipal).join(' and ')}`
    );
  }
  for (const attribute of subject.attributes ?? []) {
    const operator = OPERATOR_WORDS[attribute.operator] ?? attribute.operator;
    const value = attribute.values?.length
      ? attribute.values.join(', ')
      : String(attribute.value ?? '');
    clauses.push(`whose ${attribute.key} is ${operator} ${value}`);
  }
  if (subject.expression) clauses.push(`where ${subject.expression}`);

  for (const window of subject.time?.windows ?? []) {
    clauses.push(
      `between ${window.from} and ${window.to} ${window.timezone}` +
        (window.days?.length ? ` on ${window.days.join(', ')}` : '')
    );
  }
  if (subject.context?.ipCidr?.length) {
    clauses.push(`connecting from ${subject.context.ipCidr.join(', ')}`);
  }
  if (subject.context?.purpose?.length) {
    clauses.push(`for the purpose ${subject.context.purpose.join(' or ')}`);
  }
  return clauses.length ? clauses.join(', ') : 'everyone';
}

export function describeMasking(masking?: MaskingSpec): string {
  if (!masking) return 'masked';
  const word = MASK_WORDS[masking.function] ?? 'masked';
  if (masking.function === 'PARTIAL' && masking.showLast) {
    return `partly hidden, keeping the last ${masking.showLast}`;
  }
  if (masking.function === 'CONSTANT' && masking.constant) {
    return `replaced with ${masking.constant}`;
  }
  if (masking.function === 'ROUNDING' && masking.roundTo) {
    return `rounded to ${masking.roundTo}`;
  }
  return word;
}

/** The column a row filter compares: named, or picked in each table by its tags. */
function rowFilterColumn(filter: RowFilter): string {
  return filter.columns
    ? `the column (${describeSelector(filter.columns)})`
    : `${filter.column}`;
}

export function describeRowFilter(filter: RowFilter): string {
  switch (filter.kind) {
    case 'ALWAYS_FALSE':
      return 'no rows at all — the shape of the table stays visible, the contents do not';
    case 'ATTRIBUTE_COMPARE':
      return `only rows where ${rowFilterColumn(filter)} ${
        OPERATOR_WORDS[filter.operator ?? 'eq'] ?? filter.operator
      } their own ${filter.userAttribute}`;
    case 'IN_LIST':
      return `only rows whose ${rowFilterColumn(filter)} is one of their ${filter.userAttribute} values`;
    case 'ENTITLEMENT_JOIN':
      return `only rows they are entitled to, matched on ${filter.entitlementKey}`;
    case 'LOOKUP': {
      const lookup = filter.lookup;
      if (!lookup?.table) {
        return `no rows until a mapping table is chosen for ${rowFilterColumn(filter)}`;
      }
      const keys = (lookup.keys ?? [])
        .map((key) => `${key.column} is one of their ${key.userAttribute}`)
        .join(' and ');
      const read = lookup.mode === 'READ_VALUES' ? ', read when the query runs' : '';
      return `only rows whose ${rowFilterColumn(filter)} is one of the ${lookup.valueColumn} values in ${lookup.table} where ${keys}${read}`;
    }
    case 'RAW_PREDICATE':
      return `only rows where ${filter.rawPredicate}`;
    default:
      return 'a row filter';
  }
}

export function describeColumnRule(rule: ColumnRule): string {
  const columns = describeSelector(rule.columns);
  const verb =
    rule.action === 'HIDE'
      ? 'removed from the results entirely'
      : rule.action === 'ALLOW'
        ? 'left readable'
        : describeMasking(rule.masking);
  const condition = rule.condition ? `, but only where ${rule.condition}` : '';
  return `columns selected by ${columns} are ${verb}${condition}`;
}

/** The whole policy, as the sentence shown beside the form. */
export function describePolicy(policy: Policy): string[] {
  const lines: string[] = [];
  const scope =
    policy.scopeLevel === 'ORG'
      ? 'Across the whole organisation'
      : `Within ${policy.scopeFqn || `this ${policy.scopeLevel.toLowerCase()}`}`;

  lines.push(`${scope}, for assets matching ${describeSelector(policy.selector)}:`);

  if (policy.policyType === 'SUBSCRIPTION') {
    const effect = policy.effect === 'DENY' ? 'is denied' : 'is allowed';
    lines.push(`access ${effect} to ${describeSubject(policy.subject)}.`);
  } else {
    if (policy.subject) {
      lines.push(`for ${describeSubject(policy.subject)},`);
    }
    for (const filter of policy.data?.rowFilters ?? []) {
      lines.push(`show ${describeRowFilter(filter)}.`);
    }
    for (const rule of policy.data?.columnRules ?? []) {
      lines.push(`${describeColumnRule(rule)}.`);
    }
    if (!policy.data?.rowFilters?.length && !policy.data?.columnRules?.length) {
      lines.push('nothing is restricted yet — add a row filter or a column rule.');
    }
  }

  if (policy.allowLocalOverride) {
    lines.push(
      'A direct grant, or a policy on a single table, may let somebody in that this policy does not. Every time that happens it is recorded in the audit log.'
    );
  } else {
    lines.push(
      'Nobody reaches these assets without matching this policy. A direct grant on one table cannot get past it — it will show as in force and admit nobody.'
    );
  }
  return lines;
}
