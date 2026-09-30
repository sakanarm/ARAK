import { useMutation } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { SearchRefraction } from '@untitledui/icons';
import { apiErrorMessage } from '../../api/client';
import { assistFindData, type FoundData, type FoundTable } from '../../api/llm';
import { NokRakPrompt } from '../../assist/NokRakAsk';
import { segments } from '../../lib/fqn';
import { AssetAccessAction } from '../catalog/AssetRequestAccess';
import { AccessBadge } from '../catalog/reach';

/**
 * "Find data" on the query console (M16): somebody says what they are after,
 * in their own words, and NokRak names the tables that hold it.
 *
 * <p>The server checks every table as this person before the model hears of
 * it, so what comes back is only what they may read or ask for, each with the
 * access that check gave. A readable table offers a statement for the editor;
 * one they may only ask for offers the request form. Nothing runs and nothing
 * is sent until they press the button for it.
 */

/** Longest sentence the server takes; said here so the reader learns before asking. */
export const MAX_WANT = 500;

const THAI = /[฀-๿]/;

/** The language the reasons come back in: the one the sentence was written in. */
export function findLanguage(want: string): 'Thai' | 'English' {
  return THAI.test(want) ? 'Thai' : 'English';
}

// Words a bare column name cannot be without quotes on either engine.
const RESERVED = new Set([
  'all', 'and', 'as', 'asc', 'between', 'by', 'case', 'check', 'column', 'cross', 'default',
  'desc', 'distinct', 'else', 'end', 'from', 'full', 'grant', 'group', 'having', 'in', 'inner',
  'into', 'is', 'join', 'key', 'left', 'like', 'limit', 'not', 'null', 'offset', 'on', 'or',
  'order', 'outer', 'primary', 'references', 'right', 'select', 'table', 'then', 'to', 'union',
  'user', 'values', 'when', 'where', 'with',
]);

// Engines where a bare column name means the column whatever its case.
const KEEPS_CASE = new Set(['SQLSERVER', 'MYSQL']);

/**
 * The statement a found table offers: its columns when every one can be
 * written bare, `*` otherwise -- a quoted name is the one thing this page
 * would have to get right per engine, and `*` is never wrong. Postgres folds
 * a bare name to lower case, so there a mixed-case name counts as not bare;
 * SQL Server and MySQL do not care. Schema-qualified, as the explorer inserts
 * it, which on MySQL is the database.
 */
export function draftFor(table: Pick<FoundTable, 'fqn' | 'columns' | 'engine'>): string {
  const bare = KEEPS_CASE.has(table.engine ?? '') ? /^[A-Za-z_][A-Za-z0-9_]*$/ : /^[a-z_][a-z0-9_]*$/;
  const plain =
    table.columns.length > 0 &&
    table.columns.every((column) => bare.test(column) && !RESERVED.has(column.toLowerCase()));
  return `SELECT ${plain ? table.columns.join(', ') : '*'}\nFROM ${segments(table.fqn).slice(-2).join('.')}`;
}

export function FindWithNokRak({
  onUse,
  onClose,
}: {
  /** Put a statement in the editor, on the source the table is reached through when there is one. */
  onUse: (sql: string, sourceId: string | null) => void;
  onClose: () => void;
}) {
  const find = useMutation({
    mutationFn: (want: string): Promise<FoundData> =>
      assistFindData({ want, language: findLanguage(want) }),
  });
  const found = find.data;

  return (
    <NokRakPrompt
      askLabel="Find it"
      error={find.isError ? apiErrorMessage(find.error, 'NokRak could not look for the data.') : null}
      floating
      hint="Only tables you can query or request are shown. Nothing runs, and nothing is requested, until you press the button for it."
      onAsk={(want) => find.mutate(want.slice(0, MAX_WANT))}
      onClose={onClose}
      pending={find.isPending}
      pendingLabel="Looking…"
      placeholder="e.g. Where can I find each customer's email and the branch they belong to?"
      title="Ask NokRak where the data is">
      {found && <Found found={found} onUse={onUse} />}
    </NokRakPrompt>
  );
}

function Found({
  found,
  onUse,
}: {
  found: FoundData;
  onUse: (sql: string, sourceId: string | null) => void;
}) {
  const outOfReach = found.outOfReach ?? 0;
  return (
    <div className="tw:flex tw:flex-col tw:gap-2">
      {found.keywords.length > 0 && (
        <p className="tw:text-xs tw:text-tertiary">
          Searched the catalogue for{' '}
          {found.keywords.map((keyword, index) => (
            <span key={keyword}>
              {index > 0 && ', '}
              <span className="tw:font-medium tw:text-secondary">{keyword}</span>
            </span>
          ))}
          <span className="tw:text-quaternary"> · {found.model}</span>
        </p>
      )}
      {found.tables.length === 0 ? (
        <p className="tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:px-3 tw:py-2 tw:text-sm tw:text-secondary">
          NokRak found no table you can query or request that holds this.
          {outOfReach > 0 && ` ${outOfReachText(outOfReach, false)}`} Try other words, or{' '}
          <Link className="tw:font-medium tw:text-brand-secondary tw:underline" to="/catalog">
            browse the catalogue
          </Link>
          .
        </p>
      ) : (
        <>
          <ul aria-label="Tables NokRak found" className="tw:flex tw:max-h-[50vh] tw:flex-col tw:gap-2 tw:overflow-y-auto">
            {found.tables.map((table) => (
              <li key={table.fqn}>
                <FoundCard onUse={onUse} table={table} />
              </li>
            ))}
          </ul>
          {outOfReach > 0 && (
            <p className="tw:text-xs tw:text-tertiary">{outOfReachText(outOfReach, true)}</p>
          )}
        </>
      )}
    </div>
  );
}

/**
 * The tables that matched but that the person may neither read nor request.
 * They are counted and never named; without the count, "found no table" read as
 * the data not existing, while the catalogue lists them with the reason.
 */
function outOfReachText(count: number, more: boolean): string {
  const one = count === 1;
  const which = one ? (more ? 'One more table' : 'One table') : `${count}${more ? ' more' : ''} tables`;
  return `${which} matched, but you cannot query or request ${one ? 'it' : 'them'} now: a rule that an approval alone would not lift keeps you out, or no source is connected. The catalogue says why on each table's page.`;
}

function FoundCard({
  table,
  onUse,
}: {
  table: FoundTable;
  onUse: (sql: string, sourceId: string | null) => void;
}) {
  const readable = table.access === 'READABLE';
  return (
    <section
      aria-label={table.fqn}
      className="tw:flex tw:flex-col tw:gap-1.5 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2">
      <header className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <Link
          className="tw:min-w-0 tw:break-all tw:font-mono tw:text-sm tw:font-medium tw:text-primary tw:hover:underline"
          title="Open this table in the catalogue"
          to={`/catalog/${encodeURIComponent(table.fqn)}`}>
          {table.fqn}
        </Link>
        <AccessBadge
          asset={{ assetType: 'TABLE' }}
          brief={{
            assetFqn: table.fqn,
            queryable: true,
            readable,
            requestable: !readable,
            openRequestId: null,
            blockedKind: null,
          }}
        />
      </header>
      {table.description && <p className="tw:text-xs tw:text-tertiary">{table.description}</p>}
      {table.why && <p className="tw:text-sm tw:text-secondary">{table.why}</p>}
      {table.columns.length > 0 && (
        <ul aria-label="Columns that fit" className="tw:flex tw:flex-wrap tw:gap-1">
          {table.columns.map((column) => (
            <li
              className="tw:rounded tw:border tw:border-secondary tw:bg-secondary tw:px-1.5 tw:py-0.5 tw:font-mono tw:text-xs tw:text-secondary"
              key={column}>
              {column}
            </li>
          ))}
        </ul>
      )}
      <footer className="tw:flex tw:flex-wrap tw:items-center tw:gap-2 tw:pt-1">
        {readable ? (
          <>
            <Button
              color="primary"
              iconLeading={SearchRefraction}
              onClick={() => onUse(draftFor(table), table.sourceId)}
              size="sm">
              Put in the editor
            </Button>
            <span className="tw:text-xs tw:text-tertiary">
              Replaces what is in the editor. Masks and row filters still apply when you run it.
            </span>
          </>
        ) : (
          <AssetAccessAction asset={{ fqn: table.fqn, assetType: 'TABLE' }} />
        )}
      </footer>
    </section>
  );
}
