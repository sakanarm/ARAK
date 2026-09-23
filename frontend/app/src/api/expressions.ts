import { apiClient } from './client';

/**
 * The expression language, as the engine describes it (FR-3.2).
 *
 * Nothing here is written down twice. The reference is a document the backend
 * serves out of the engine's own jar, so the page renders whatever grammar
 * this deployment is actually running rather than whatever was true when
 * somebody last edited a markdown file. The types below describe that
 * document's shape and nothing else — no example, no operator and no facet
 * name lives in the front end.
 */

/** One member of a root, e.g. `asset.tier`, with the spellings that also work. */
export interface ReferenceMember {
  name: string;
  aliases: string[];
  yields: string;
}

/**
 * A root an expression can start from.
 *
 * `open` is the distinction that matters when reading the page. A closed root
 * has a fixed list of members and a typo in one is refused on save. An open
 * root resolves anything to a lookup, so a typo parses perfectly and then
 * quietly makes the whole rule undecidable.
 */
export interface ReferenceRoot {
  root: string;
  title: string;
  open: boolean;
  note: string;
  members: ReferenceMember[];
}

export interface ReferenceOperator {
  op: string;
  meaning: string;
  note: string;
}

/** One worked answer: a person, a table, and what the engine says about them. */
export interface ReferenceCase {
  given: string;
  principal: Record<string, unknown>;
  asset: Record<string, unknown>;
  context?: Record<string, unknown>;
  expect: 'TRUE' | 'FALSE' | 'UNKNOWN' | 'ROW_DEPENDENT';
}

export interface ReferenceExample {
  id: string;
  title: string;
  expression: string;
  explanation: string;
  cases: ReferenceCase[];
}

export interface ExpressionReference {
  summary: string;
  truth: string;
  roots: ReferenceRoot[];
  operators: ReferenceOperator[];
  examples: ReferenceExample[];
  rejected: { expression: string; why: string }[];
}

/**
 * What the parser makes of an expression.
 *
 * `unknownAttributes` is the warning rather than the error, and the two are
 * not interchangeable. A name nobody in the directory carries today may be one
 * somebody carries tomorrow, so it cannot refuse the save — but it is the only
 * kind of mistake the grammar cannot catch, and an ALLOW nobody can decide
 * grants nothing while reading back exactly as it was typed.
 */
export interface ExpressionVerdict {
  valid: boolean;
  message: string | null;
  position: number;
  rowDependent: boolean;
  userAttributes: string[];
  unknownAttributes: string[];
}

export async function fetchExpressionReference(): Promise<ExpressionReference> {
  const { data } = await apiClient.get<ExpressionReference>('/v1/expressions/reference');
  return data;
}

export async function validateExpression(expression: string): Promise<ExpressionVerdict> {
  const { data } = await apiClient.post<ExpressionVerdict>('/v1/expressions/validate', {
    expression,
  });
  return data;
}
