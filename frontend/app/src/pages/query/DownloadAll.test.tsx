import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { AxiosError, AxiosHeaders, CanceledError } from 'axios';
import DownloadAll, { downloadFailure, formatBytes } from './DownloadAll';
import { exportQuery } from '../../api/query';
import { download } from '../../lib/tabular';

jest.mock('../../api/query', () => ({
  ...jest.requireActual('../../api/query'),
  exportQuery: jest.fn(),
}));

jest.mock('../../lib/tabular', () => ({
  ...jest.requireActual('../../lib/tabular'),
  download: jest.fn(),
}));

const exportMock = exportQuery as jest.MockedFunction<typeof exportQuery>;
const downloadMock = download as jest.MockedFunction<typeof download>;

const ASK = { sourceId: 'src-1', sql: 'SELECT * FROM sales.customer', purpose: '' };

function failure(status: number | null, data: unknown, headers: Record<string, string> = {}) {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError(
    'failed',
    status === null ? 'ERR_NETWORK' : 'ERR_BAD_REQUEST',
    config,
    null,
    status === null
      ? undefined
      : { status, statusText: '', headers, config, data }
  );
}

describe('DownloadAll', () => {
  beforeEach(() => {
    exportMock.mockReset();
    downloadMock.mockReset();
  });

  it('downloads every row of the statement that ran, named for its table', async () => {
    const file = new Blob(['id\r\n1\r\n']);
    exportMock.mockResolvedValue(file);
    const onFailure = jest.fn();
    render(
      <DownloadAll ask={ASK} assets={['demo-pg.salesdb.sales.customer']} onFailure={onFailure} />
    );

    fireEvent.click(screen.getByRole('button', { name: 'All rows' }));

    await waitFor(() => expect(downloadMock).toHaveBeenCalledTimes(1));
    expect(exportMock).toHaveBeenCalledWith(
      { sourceId: 'src-1', sql: 'SELECT * FROM sales.customer', purpose: null },
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    );
    expect(downloadMock.mock.calls[0][0]).toBe(file);
    expect(downloadMock.mock.calls[0][1]).toMatch(
      /^demo-pg\.salesdb\.sales\.customer-all-rows-.*\.csv$/
    );
    expect(onFailure).toHaveBeenLastCalledWith(null);
    // Back to the button once the file is handed over.
    expect(await screen.findByRole('button', { name: 'All rows' })).toBeInTheDocument();
  });

  it('shows how much has arrived and can be cancelled', async () => {
    let signal: AbortSignal | undefined;
    let progress: ((bytes: number) => void) | undefined;
    exportMock.mockImplementation(
      (_ask, options) =>
        new Promise((_resolve, reject) => {
          signal = options?.signal;
          progress = options?.onProgress;
          signal?.addEventListener('abort', () => reject(new CanceledError()));
        })
    );
    const onFailure = jest.fn();
    render(<DownloadAll ask={ASK} assets={[]} onFailure={onFailure} />);

    fireEvent.click(screen.getByRole('button', { name: 'All rows' }));
    act(() => progress?.(3 * 1024 * 1024 + 200_000));
    expect(screen.getByRole('status')).toHaveTextContent('All rows… 3.2 MB');

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(signal?.aborted).toBe(true);
    expect(await screen.findByRole('button', { name: 'All rows' })).toBeInTheDocument();
    // Cancelling is not a failure to report.
    await waitFor(() => expect(onFailure).toHaveBeenCalledTimes(1));
    expect(onFailure).toHaveBeenCalledWith(null);
    expect(downloadMock).not.toHaveBeenCalled();
  });

  it('reports a refusal in the service’s words', async () => {
    exportMock.mockRejectedValue(
      failure(403, { message: 'Estimated cost 9000 is over the ceiling', fixable: false })
    );
    const onFailure = jest.fn();
    render(<DownloadAll ask={ASK} assets={[]} onFailure={onFailure} />);

    fireEvent.click(screen.getByRole('button', { name: 'All rows' }));

    await waitFor(() =>
      expect(onFailure).toHaveBeenLastCalledWith('Estimated cost 9000 is over the ceiling')
    );
    expect(downloadMock).not.toHaveBeenCalled();
  });

  it('will not download somebody else’s rows, and says why', () => {
    const onFailure = jest.fn();
    render(<DownloadAll ask={ASK} assets={[]} onFailure={onFailure} runAs="analyst_b" />);

    const button = screen.getByRole('button', { name: 'All rows' });
    expect(button).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(button);

    expect(exportMock).not.toHaveBeenCalled();
    expect(onFailure).toHaveBeenCalledWith(expect.stringContaining('analyst_b'));
  });
});

describe('downloadFailure', () => {
  it('tells a cut-off download from a service that is down', () => {
    expect(downloadFailure(failure(null, undefined))).toMatch(/no file was saved/);
  });

  it('says when to try a busy source again', () => {
    expect(
      downloadFailure(failure(429, { message: 'demo-pg is busy.', retryAfterSeconds: 5 }))
    ).toBe('demo-pg is busy. Try the download again in 5 seconds.');
  });
});

describe('formatBytes', () => {
  it('reads as a person would say it', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(2048)).toBe('2 KB');
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB');
  });
});
