/**
 * What to suggest at the caret, worked out from the text alone.
 *
 * <p>Kept apart from the editor and from the network so the rules can be read
 * and tested as rules. The editor supplies the text and the caret; the page
 * supplies what the catalog knows (tables, their columns, whether the reader
 * can open each one); this file only decides which of those belong here.
 *
 * <p>Not a SQL parser, and it does not try to be one. The proxy is what parses
 * and it refuses what it cannot resolve, so a suggestion that is occasionally
 * less clever than it could be costs a keystroke, never a leak.
 */

/** A table the proxy can resolve, by the name a statement uses for it. */
export interface CompletionTable {
  fqn: string;
  schema: string;
  name: string;
}

export type SuggestionKind = 'table' | 'column' | 'keyword';

export interface Suggestion {
  kind: SuggestionKind;
  /** What the list shows. */
  label: string;
  /** What replaces the word being typed. */
  insert: string;
  /** Set on tables: the asset the name resolves to. */
  fqn?: string;
  /** Set on columns: which table it came from, for the hint. */
  detail?: string;
}

export interface CompletionContext {
  /** Where the word being replaced starts; it ends at the caret. */
  from: number;
  /** The part of that word after its last dot, which is what is matched. */
  prefix: string;
  /**
   * `tables` right after FROM or JOIN; `qualified` after `something.`, where
   * `qualifier` says what came before the dot; `any` everywhere else.
   */
  mode: 'tables' | 'qualified' | 'any';
  qualifier?: string;
}

/** Keywords offered as completions: the ones people actually type. */
export const COMPLETION_KEYWORDS = [
  'SELECT',
  'FROM',
  'WHERE',
  'GROUP BY',
  'ORDER BY',
  'HAVING',
  'LIMIT',
  'OFFSET',
  'JOIN',
  'LEFT JOIN',
  'INNER JOIN',
  'ON',
  'AS',
  'AND',
  'OR',
  'NOT',
  'IN',
  'IS NULL',
  'IS NOT NULL',
  'LIKE',
  'BETWEEN',
  'EXISTS',
  'DISTINCT',
  'UNION',
  'WITH',
  'CASE',
  'WHEN',
  'THEN',
  'ELSE',
  'END',
  'COUNT',
  'SUM',
  'AVG',
  'MIN',
  'MAX',
  'COALESCE',
  'ASC',
  'DESC',
];

/** Words that end a FROM list's alias, so `FROM sales.customer WHERE` has none. */
const NOT_AN_ALIAS = new Set(
  (
    'where group order having limit offset join inner left right full outer cross on ' +
    'union using natural as with fetch window'
  ).split(' ')
);

const WORD = /[A-Za-z0-9_$"]/;

/**
 * True when the caret sits inside a string literal or a comment, where no
 * suggestion is wanted. Scans the same way the highlighter does, so the two
 * cannot disagree about where a string ends.
 */
export function insideLiteral(sql: string, caret: number): boolean {
  let i = 0;
  while (i < caret) {
    const c = sql[i];
    if (c === '-' && sql[i + 1] === '-') {
      const end = sql.indexOf('\n', i);
      if (end === -1 || end >= caret) return true;
      i = end;
      continue;
    }
    if (c === '/' && sql[i + 1] === '*') {
      const end = sql.indexOf('*/', i + 2);
      if (end === -1 || end + 2 > caret) return true;
      i = end + 2;
      continue;
    }
    if (c === "'") {
      let j = i + 1;
      while (j < sql.length) {
        if (sql[j] === "'" && sql[j + 1] === "'") {
          j += 2;
          continue;
        }
        if (sql[j] === "'") break;
        j += 1;
      }
      if (j >= caret) return true;
      i = j + 1;
      continue;
    }
    i += 1;
  }
  return false;
}

/** Where the caret is, in the terms the suggestion rules need. Null for "suggest nothing". */
export function contextAt(sql: string, caret: number): CompletionContext | null {
  if (caret < 0 || caret > sql.length || insideLiteral(sql, caret)) {
    return null;
  }
  let from = caret;
  while (from > 0 && (WORD.test(sql[from - 1]) || sql[from - 1] === '.')) {
    from -= 1;
  }
  const word = sql.slice(from, caret);
  const dot = word.lastIndexOf('.');
  if (dot >= 0) {
    return {
      from: from + dot + 1,
      prefix: unquote(word.slice(dot + 1)),
      mode: 'qualified',
      qualifier: unquote(word.slice(0, dot)),
    };
  }
  const before = sql.slice(0, from).replace(/\s+$/, '');
  const previous = /([A-Za-z_]+)$/.exec(before)?.[1]?.toLowerCase();
  const mode = previous === 'from' || previous === 'join' ? 'tables' : 'any';
  return { from, prefix: unquote(word), mode };
}

/**
 * The tables a statement already names, by every name the rest of it can use:
 * `schema.table`, the bare table name, and the alias when there is one.
 */
export function referencedTables(
  sql: string,
  tables: CompletionTable[]
): Map<string, CompletionTable> {
  const byName = new Map<string, CompletionTable>();
  for (const table of tables) {
    byName.set(`${table.schema}.${table.name}`.toLowerCase(), table);
  }
  const out = new Map<string, CompletionTable>();
  const pattern = /\b(?:from|join)\s+([A-Za-z0-9_$".]+)(?:\s+(?:as\s+)?([A-Za-z_][A-Za-z0-9_$]*))?/gi;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(sql)) !== null) {
    const parts = unquote(match[1]).split('.');
    const key = parts.slice(-2).join('.').toLowerCase();
    const table = byName.get(key);
    if (!table) continue;
    out.set(key, table);
    out.set(table.name.toLowerCase(), table);
    const alias = match[2];
    if (alias && !NOT_AN_ALIAS.has(alias.toLowerCase())) {
      out.set(alias.toLowerCase(), table);
    }
  }
  return out;
}

/** How many suggestions the list shows; more than this is a search, not a hint. */
export const MAX_SUGGESTIONS = 12;

/**
 * The suggestions for this context, best first.
 *
 * @param columns what is known of each table's columns, by FQN; a table not in
 *   it yet simply contributes nothing until the page has fetched it
 */
export function suggest(
  sql: string,
  context: CompletionContext,
  tables: CompletionTable[],
  columns: Record<string, string[] | undefined>
): Suggestion[] {
  const prefix = context.prefix.toLowerCase();
  const matches = (text: string) => text.toLowerCase().startsWith(prefix);

  if (context.mode === 'tables') {
    return rank(
      tables
        .filter((t) => matches(t.name) || matches(t.schema) || matches(`${t.schema}.${t.name}`))
        .map((t) => tableSuggestion(t, `${t.schema}.${t.name}`)),
      prefix
    );
  }

  if (context.mode === 'qualified') {
    const qualifier = (context.qualifier ?? '').toLowerCase();
    const referenced = referencedTables(sql, tables);
    const table =
      referenced.get(qualifier) ??
      tables.find((t) => `${t.schema}.${t.name}`.toLowerCase() === qualifier);
    if (table) {
      return rank(
        (columns[table.fqn] ?? [])
          .filter(matches)
          .map((name) => ({ kind: 'column' as const, label: name, insert: name, detail: table.name })),
        prefix
      );
    }
    // `sales.` — a schema, so what follows is one of its tables.
    return rank(
      tables
        .filter((t) => t.schema.toLowerCase() === qualifier && matches(t.name))
        .map((t) => tableSuggestion(t, t.name)),
      prefix
    );
  }

  if (prefix.length === 0) {
    return [];
  }
  const inScope = new Map<string, Suggestion>();
  for (const table of new Set(referencedTables(sql, tables).values())) {
    for (const name of columns[table.fqn] ?? []) {
      if (matches(name) && !inScope.has(name.toLowerCase())) {
        inScope.set(name.toLowerCase(), {
          kind: 'column',
          label: name,
          insert: name,
          detail: table.name,
        });
      }
    }
  }
  const keywords = COMPLETION_KEYWORDS.filter(matches).map((word) => ({
    kind: 'keyword' as const,
    label: word,
    insert: word,
  }));
  // Columns first: a statement's own columns are the likelier word, and a
  // keyword is short enough to finish by typing.
  return [...rank([...inScope.values()], prefix), ...keywords]
    .filter((s) => s.label.toLowerCase() !== prefix)
    .slice(0, MAX_SUGGESTIONS);
}

/**
 * The text after accepting a suggestion, and where the caret goes.
 *
 * A table after FROM gets a trailing space, because the next thing typed is
 * never glued to it; everything else is left for the reader to continue.
 */
export function accept(
  sql: string,
  caret: number,
  context: CompletionContext,
  suggestion: Suggestion
): { text: string; caret: number } {
  let end = caret;
  while (end < sql.length && WORD.test(sql[end])) end += 1;
  const tail = sql.slice(end);
  const spacer = suggestion.kind === 'table' && !/^\s/.test(tail) ? ' ' : '';
  const insert = suggestion.insert + spacer;
  return {
    text: sql.slice(0, context.from) + insert + tail,
    caret: context.from + insert.length,
  };
}

function tableSuggestion(table: CompletionTable, insert: string): Suggestion {
  return {
    kind: 'table',
    label: `${table.schema}.${table.name}`,
    insert,
    fqn: table.fqn,
  };
}

/** Exact-start on the short name before anything else, then alphabetical. */
function rank(items: Suggestion[], prefix: string): Suggestion[] {
  const short = (s: Suggestion) => s.label.split('.').pop()!.toLowerCase();
  return [...items]
    .sort((a, b) => {
      const aHit = short(a).startsWith(prefix) ? 0 : 1;
      const bHit = short(b).startsWith(prefix) ? 0 : 1;
      return aHit - bHit || a.label.localeCompare(b.label);
    })
    .slice(0, MAX_SUGGESTIONS);
}

function unquote(text: string): string {
  return text.replace(/"/g, '');
}

/** `service.database.schema.table` as the proxy wants it written. */
export function asCompletionTable(fqn: string): CompletionTable | null {
  const parts = fqn.split('.');
  if (parts.length < 2) return null;
  return { fqn, schema: parts[parts.length - 2], name: parts[parts.length - 1] };
}
