import { AlertTriangle } from '@untitledui/icons';
import type { Concern } from '../../api/sensitiveData';

/**
 * What the purpose named meets on a table holding sensitive data (M31b), said
 * on a request form before it is sent: under warn the request still goes and
 * whoever decides it is told; under enforce it would be refused.
 */
export default function PurposeConcerns({ concerns }: { concerns: Concern[] }) {
  if (concerns.length === 0) return null;
  return (
    <div className="tw:flex tw:flex-col tw:gap-2">
      {concerns.map((concern) => {
        const refused = concern.mode === 'ENFORCE';
        return (
          <p
            aria-label={refused ? 'Refused for this purpose' : 'Sensitive data'}
            className={`tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:px-3 tw:py-2 tw:text-sm ${
              refused
                ? 'tw:bg-utility-error-50 tw:text-error-primary'
                : 'tw:bg-utility-warning-50 tw:text-warning-primary'
            }`}
            key={concern.table}
            role={refused ? 'alert' : 'note'}>
            <AlertTriangle aria-hidden className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
            <span className="tw:min-w-0 tw:break-words">
              {concern.message}.{' '}
              {refused
                ? 'A request for it would be refused.'
                : 'You can still ask; whoever decides is told.'}
            </span>
          </p>
        );
      })}
    </div>
  );
}

/** The first refusal, as a form's reason for not sending yet, or null. */
export function refusedBy(concerns: Concern[]): string | null {
  const refused = concerns.find((concern) => concern.mode === 'ENFORCE');
  return refused ? 'Choose a purpose sensitive data may be used for' : null;
}
