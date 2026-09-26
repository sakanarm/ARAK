import type { ImportReport } from '../../api/sources';
import { Notice } from './Notice';

/** How many names a list shows before it says how many more there are. */
const SHOWN = 8;

/**
 * What one import found, as the counts and then the names that need a person.
 *
 * New columns come first among the names: each one is unprotected until a
 * policy covers it, which makes it the only line on this report that can be a
 * security problem rather than a housekeeping one.
 */
export function ImportReportView({ report }: { report: ImportReport }) {
  return (
    <div className="tw:flex tw:flex-col tw:gap-3">
      <dl className="tw:grid tw:grid-cols-2 tw:gap-3 tw:sm:grid-cols-4">
        <Stat label="Tables and views" value={report.tables} />
        <Stat label="Columns" value={report.columns} />
        <Stat label="New tables" value={report.newTables} />
        <Stat label="Left out by the scope" value={report.excluded} />
      </dl>

      {report.newColumns.length > 0 && (
        <Notice tone="warning">
          {report.newColumns.length === 1 ? 'One column is' : `${report.newColumns.length} columns are`}{' '}
          new since the last import and unprotected until a policy covers{' '}
          {report.newColumns.length === 1 ? 'it' : 'them'}: <Names names={report.newColumns} />
        </Notice>
      )}
      {report.missingTables.length > 0 && (
        <Notice tone="warning">
          Gone from the database and marked orphaned: <Names names={report.missingTables} />
        </Notice>
      )}
      {report.outOfScope.length > 0 && (
        <Notice tone="info">
          Catalogued before and outside the scope now, so kept as they were and not re-read:{' '}
          <Names names={report.outOfScope} />
        </Notice>
      )}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2">
      <dt className="tw:text-xs tw:text-tertiary">{label}</dt>
      <dd className="tw:text-lg tw:font-semibold tw:text-primary">{value.toLocaleString()}</dd>
    </div>
  );
}

function Names({ names }: { names: string[] }) {
  const rest = names.length - SHOWN;
  return (
    <>
      <span className="tw:font-mono tw:text-xs">{names.slice(0, SHOWN).join(', ')}</span>
      {rest > 0 && ` and ${rest.toLocaleString()} more`}.
    </>
  );
}
