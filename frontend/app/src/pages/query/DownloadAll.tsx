import { useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { Loading02 } from '@untitledui/icons';
import { apiErrorMessage } from '../../api/client';
import { busyOf, exportQuery } from '../../api/query';
import { download, exportName } from '../../lib/tabular';

/** What a download is of: the statement that ran, not whatever is in the editor now. */
export interface DownloadAsk {
  sourceId: string;
  sql: string;
  purpose: string;
}

/** Bytes as a person reads them; the total is not known until the end. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Why a download did not end in a file, in the words the reader needs. */
export function downloadFailure(error: unknown): string {
  const busy = busyOf(error);
  if (busy) {
    return `${busy.message} Try the download again in ${busy.retryAfterSeconds} seconds.`;
  }
  if (axios.isAxiosError(error) && !error.response) {
    // The server cuts the response rather than end it early, so a read that
    // failed part-way arrives here, as a connection that stopped.
    return 'The download stopped before the last row, so no file was saved. The attempt and how far it got are in the query log.';
  }
  return apiErrorMessage(error, 'The download was not started and the service did not say why.');
}

/**
 * "All rows": every row the statement returns, as CSV (FR-6.3).
 *
 * <p>The grid stops at the row limit because it is drawn in the page and the
 * whole answer is held in memory; past a few thousand rows that is a frozen
 * tab. So the screen keeps its limit and this is the way to the rest: the
 * server runs the same statement through the same policy and writes the file
 * as the source reads it. Masks and row filters are in the file exactly as on
 * screen, and the download is audited as one.
 *
 * <p>Always as the person clicking. A result run as somebody else is theirs to
 * look at, not to take away, so the button explains itself instead.
 */
export default function DownloadAll({
  ask,
  assets,
  runAs,
  onFailure,
}: {
  ask: DownloadAsk;
  assets: string[];
  /** Whose rows the result on screen is, when that is not the reader. */
  runAs?: string | null;
  /** A reason to show, or null to clear the last one. */
  onFailure: (message: string | null) => void;
}) {
  const [bytes, setBytes] = useState<number | null>(null);
  const controller = useRef<AbortController | null>(null);

  // Leaving the page is not a reason to keep a source connection open.
  useEffect(() => () => controller.current?.abort(), []);

  async function start() {
    const abort = new AbortController();
    controller.current = abort;
    onFailure(null);
    setBytes(0);
    try {
      const file = await exportQuery(
        { sourceId: ask.sourceId, sql: ask.sql, purpose: ask.purpose || null },
        { signal: abort.signal, onProgress: setBytes }
      );
      download(file, exportName(assets.length ? [`${assets[0]}-all-rows`] : ['query-all-rows'], 'csv'));
    } catch (error) {
      if (!axios.isCancel(error)) {
        onFailure(downloadFailure(error));
      }
    } finally {
      if (controller.current === abort) {
        controller.current = null;
        setBytes(null);
      }
    }
  }

  if (bytes !== null) {
    return (
      <span className="tw:flex tw:items-center tw:gap-1.5 tw:text-xs tw:text-tertiary" role="status">
        <Loading02 className="tw:size-3.5 tw:animate-spin tw:text-fg-quaternary" />
        All rows… {formatBytes(bytes)}
        <button
          className="tw:cursor-pointer tw:rounded tw:px-1.5 tw:py-0.5 tw:text-xs tw:font-semibold tw:text-tertiary tw:hover:bg-secondary tw:hover:text-primary"
          onClick={() => {
            controller.current?.abort();
            controller.current = null;
            setBytes(null);
          }}
          type="button">
          Cancel
        </button>
      </span>
    );
  }

  const blocked = runAs
    ? `These are ${runAs}'s rows. A download is always of your own, so clear “Run as”, run it again and download from there.`
    : null;
  return (
    <button
      aria-disabled={blocked ? true : undefined}
      className={`tw:rounded tw:px-1.5 tw:py-0.5 tw:text-xs tw:font-semibold ${
        blocked
          ? 'tw:cursor-not-allowed tw:text-quaternary'
          : 'tw:cursor-pointer tw:text-brand-secondary tw:hover:bg-secondary'
      }`}
      onClick={() => {
        if (blocked) {
          onFailure(blocked);
          return;
        }
        void start();
      }}
      title={
        blocked ??
        'Download every row the statement returns as CSV, not only the ones on screen. The same policy and masks apply, and the download is audited.'
      }
      type="button">
      All rows
    </button>
  );
}
