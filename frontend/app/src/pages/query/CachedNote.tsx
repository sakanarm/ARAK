import { Chip as Badge } from '../../components/chips';

/**
 * Says the rows are a read made moments ago, and when (FR-6.3). A result from
 * memory that does not say so is one somebody will take for the table as it
 * stands now, so the age is always shown and a fresh read is one click away.
 */
export default function CachedNote({
  readAt,
  onRunFresh,
  now = Date.now(),
}: {
  readAt: string | null;
  onRunFresh?: () => void;
  now?: number;
}) {
  const at = readAt ? Date.parse(readAt) : Number.NaN;
  const age = Number.isNaN(at) ? null : Math.max(0, Math.round((now - at) / 1000));
  return (
    <span className="tw:flex tw:items-center tw:gap-1.5">
      <span title="The policy was applied to this run as usual; only the source was not asked again. Run fresh to read it now.">
        <Badge color="blue" size="sm" type="pill-color">
          from cache{age === null ? '' : ` · read ${age}s ago`}
        </Badge>
      </span>
      {onRunFresh && (
        <button
          className="tw:cursor-pointer tw:rounded tw:px-1.5 tw:py-0.5 tw:text-xs tw:font-semibold tw:text-brand-secondary tw:hover:bg-secondary"
          onClick={onRunFresh}
          type="button">
          Run fresh
        </button>
      )}
    </span>
  );
}
