import { ChevronLeft, ChevronRight } from '@untitledui/icons';
import { Select } from '../pages/policies/controls';

/**
 * The footer of a long list: which page of it this is, and how big a page is.
 *
 * Lifted out of the catalogue rather than written twice. Two lists that page
 * differently teach the reader two habits for the same job, and the second one
 * is always the one that ends up missing the page size control or the last
 * page number — so the rule here is that anything long enough to page uses
 * this, and nothing grows its own.
 */

/** How many rows fit before the reader would rather have another page. */
export const PAGE_SIZES = [15, 25, 50, 100];

export function Pager({
  offset,
  pageSize,
  total,
  /** Names this list for a screen reader, e.g. "Catalog pages". */
  label = 'Pages',
  /** What is being counted, for the page-size control: "Assets per page". */
  noun = 'Rows',
  /** The page sizes on offer, when a list reads better in other steps. */
  sizes = PAGE_SIZES,
  onOffset,
  onPageSize,
}: {
  offset: number;
  pageSize: number;
  total: number;
  label?: string;
  noun?: string;
  sizes?: number[];
  onOffset: (offset: number) => void;
  onPageSize: (size: number) => void;
}) {
  if (total === 0) {
    return null;
  }

  const pages = Math.max(1, Math.ceil(total / pageSize));
  const current = Math.min(pages, Math.floor(offset / pageSize) + 1);

  return (
    <nav
      aria-label={label}
      className="tw:mt-4 tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-3">
      <div className="tw:flex tw:items-center tw:gap-1">
        <button
          aria-label="Previous page"
          className={`tw:cursor-pointer ${STEP}`}
          disabled={current === 1}
          onClick={() => onOffset((current - 2) * pageSize)}
          type="button">
          <ChevronLeft className="tw:size-4" />
        </button>

        {pages > 1 &&
          pageWindow(current, pages).map((page, index) =>
            page === 'gap' ? (
              <span
                className="tw:px-1 tw:text-sm tw:text-quaternary"
                key={`gap-${index}`}>
                …
              </span>
            ) : (
              <button
                aria-current={page === current ? 'page' : undefined}
                className={`tw:cursor-pointer ${page === current ? PAGE_ON : PAGE_OFF}`}
                key={page}
                onClick={() => onOffset((page - 1) * pageSize)}
                type="button">
                {page}
              </button>
            )
          )}

        <button
          aria-label="Next page"
          className={`tw:cursor-pointer ${STEP}`}
          disabled={current === pages}
          onClick={() => onOffset(current * pageSize)}
          type="button">
          <ChevronRight className="tw:size-4" />
        </button>
      </div>

      <div className="tw:flex tw:items-center tw:gap-2">
        <span className="tw:text-xs tw:text-tertiary">Per page</span>
        <Select
          ariaLabel={`${noun} per page`}
          className="tw:w-24"
          onChange={(next) => onPageSize(Number(next))}
          options={sizes.map((size) => ({
            value: String(size),
            label: String(size),
          }))}
          value={String(pageSize)}
        />
      </div>
    </nav>
  );
}

/**
 * Which page numbers to draw, with an ellipsis standing in for the runs left
 * out.
 *
 * Numbers rather than Previous/Next alone because the first question a reader
 * of a long list asks is "how much of this is there", and a pair of arrows
 * never answers it. The last page is always shown for the same reason.
 */
export function pageWindow(current: number, pages: number): Array<number | 'gap'> {
  if (pages <= 7) {
    return Array.from({ length: pages }, (_, i) => i + 1);
  }
  const near = [current - 2, current - 1, current, current + 1, current + 2].filter(
    (page) => page > 1 && page < pages
  );
  const out: Array<number | 'gap'> = [1];
  if (near[0] > 2) {
    out.push('gap');
  }
  out.push(...near);
  if (near[near.length - 1] < pages - 1) {
    out.push('gap');
  }
  out.push(pages);
  return out;
}

/** The arrows either side of the numbers. */
const STEP =
  'tw:flex tw:size-8 tw:items-center tw:justify-center tw:rounded-lg tw:text-tertiary tw:hover:bg-secondary tw:disabled:cursor-not-allowed tw:disabled:text-disabled tw:disabled:hover:bg-transparent';

/** The page the reader is on: filled, because it is a state and not a target. */
const PAGE_ON =
  'tw:flex tw:size-8 tw:items-center tw:justify-center tw:rounded-lg tw:bg-brand-solid tw:text-sm tw:font-semibold tw:text-white';

const PAGE_OFF =
  'tw:flex tw:size-8 tw:items-center tw:justify-center tw:rounded-lg tw:text-sm tw:text-tertiary tw:hover:bg-secondary';
