import { useState } from 'react';
import { Download01, File06, Table } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Dropdown } from '@openmetadata/ui-core-components/components/base/dropdown/dropdown';
import { apiErrorMessage, type AssetQuery } from '../../api/client';
import { download, exportName, toCsv, toXlsxBook } from '../../lib/tabular';
import {
  EXPORT_CAP,
  assetGrid,
  collectAssets,
  collectDetails,
  columnGrid,
} from './catalogExport';

type Kind = 'csv-assets' | 'csv-columns' | 'xlsx';

/**
 * Download what the list is showing, every page of it, as CSV or Excel.
 *
 * <p>The tables file needs only the list itself. Columns need each table's
 * own page, so those two take longer and say how far along they are.
 */
export function CatalogExportMenu({
  query,
}: {
  query: Omit<AssetQuery, 'limit' | 'offset'>;
}) {
  const [progress, setProgress] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  async function run(kind: Kind) {
    setProblem(null);
    setProgress('Reading the list…');
    try {
      const { assets, total } = await collectAssets(query, ({ done, total: of }) =>
        setProgress(`Reading the list… ${done} of ${of}`)
      );
      const details =
        kind === 'csv-assets'
          ? []
          : await collectDetails(assets, ({ done, total: of }) =>
              setProgress(`Reading columns… ${done} of ${of} tables`)
            );
      const name = (extension: string, part?: string) =>
        exportName([part ? `catalog-${part}` : 'catalog'], extension);
      if (kind === 'csv-assets') {
        download(toCsv(assetGrid(assets)), name('csv', 'assets'));
      } else if (kind === 'csv-columns') {
        download(toCsv(columnGrid(details)), name('csv', 'columns'));
      } else {
        download(
          toXlsxBook([
            { name: 'Assets', grid: assetGrid(assets, details) },
            { name: 'Columns', grid: columnGrid(details) },
          ]),
          name('xlsx')
        );
      }
      if (total > EXPORT_CAP) {
        setProblem(`Only the first ${EXPORT_CAP} of ${total} were exported. Narrow the filters for the rest.`);
      }
    } catch (error) {
      setProblem(apiErrorMessage(error, 'The catalog could not be exported.'));
    } finally {
      setProgress(null);
    }
  }

  return (
    <div className="tw:flex tw:items-center tw:gap-2">
      {progress && (
        <span aria-live="polite" className="tw:text-xs tw:text-tertiary">
          {progress}
        </span>
      )}
      {problem && !progress && (
        <span className="tw:text-xs tw:text-warning-primary" role="status">
          {problem}
        </span>
      )}
      <Dropdown.Root>
        <Button
          color="secondary"
          iconLeading={Download01}
          isDisabled={progress !== null}
          size="md">
          Export
        </Button>
        <Dropdown.Popover className="tw:w-64">
          <Dropdown.Menu selectionMode="none">
            <Dropdown.Item
              icon={File06}
              label="CSV — assets"
              onAction={() => void run('csv-assets')}
            />
            <Dropdown.Item
              icon={File06}
              label="CSV — columns"
              onAction={() => void run('csv-columns')}
            />
            <Dropdown.Separator />
            <Dropdown.Item
              icon={Table}
              label="Excel — assets and columns"
              onAction={() => void run('xlsx')}
            />
          </Dropdown.Menu>
        </Dropdown.Popover>
      </Dropdown.Root>
    </div>
  );
}
