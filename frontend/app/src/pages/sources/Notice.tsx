import type { ReactNode } from 'react';
import { AlertTriangle, CheckCircle, InfoCircle } from '@untitledui/icons';

/** One line of outcome under a form or a card: what happened, in its colour. */
export function Notice({
  tone,
  children,
}: {
  tone: 'error' | 'success' | 'warning' | 'info';
  children: ReactNode;
}) {
  const Icon = tone === 'success' ? CheckCircle : tone === 'info' ? InfoCircle : AlertTriangle;
  const colour = {
    error: 'tw:bg-utility-error-50 tw:text-error-primary',
    success: 'tw:bg-utility-success-50 tw:text-success-primary',
    warning: 'tw:bg-utility-warning-50 tw:text-warning-primary',
    info: 'tw:bg-secondary tw:text-tertiary',
  }[tone];

  return (
    <p className={`tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:px-3 tw:py-2 tw:text-sm ${colour}`}>
      <Icon className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
      <span className="tw:min-w-0">{children}</span>
    </p>
  );
}
