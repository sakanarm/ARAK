import { useEffect, useState } from 'react';
import { Clock } from '@untitledui/icons';
import type { Busy } from '../../api/query';

/**
 * The service had no room for one more read, so nothing was sent (FR-6.3
 * concurrency limit).
 *
 * Drawn apart from a refusal on purpose: nothing about the statement or the
 * person is wrong, so there is no access to ask for and nothing to correct.
 * The one useful thing is the same statement again once a slot is free, which
 * the button offers after the wait the server asked for.
 */
export default function BusyNote({
  busy,
  onRetry,
}: {
  busy: Busy;
  onRetry?: () => void;
}) {
  const [left, setLeft] = useState(busy.retryAfterSeconds);

  useEffect(() => {
    setLeft(busy.retryAfterSeconds);
    const timer = window.setInterval(() => {
      setLeft((seconds) => {
        if (seconds <= 1) {
          window.clearInterval(timer);
          return 0;
        }
        return seconds - 1;
      });
    }, 1_000);
    return () => window.clearInterval(timer);
  }, [busy]);

  return (
    <section
      aria-label="Busy"
      className="tw:shrink-0 tw:rounded-lg tw:border tw:border-warning tw:bg-warning-primary tw:p-4">
      <h2 className="tw:flex tw:items-center tw:gap-2 tw:text-sm tw:font-semibold tw:text-warning-primary">
        <Clock className="tw:size-4" />
        Busy — not run
      </h2>
      <p className="tw:mt-1 tw:text-sm tw:text-primary">{busy.message}</p>
      <p className="tw:mt-1 tw:text-xs tw:text-tertiary">
        Nothing reached the source. This is a limit on how many queries run at
        once, not a decision about your access.
      </p>
      {onRetry && (
        <button
          className="tw:mt-3 tw:cursor-pointer tw:rounded tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-1 tw:text-sm tw:font-semibold tw:text-secondary tw:hover:bg-secondary tw:disabled:cursor-not-allowed tw:disabled:opacity-50"
          disabled={left > 0}
          onClick={onRetry}
          type="button">
          {left > 0 ? `Try again in ${left}s` : 'Try again'}
        </button>
      )}
    </section>
  );
}
