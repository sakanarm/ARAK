import { useQuery } from '@tanstack/react-query';
import { Chip as Badge } from '../../components/chips';
import { fetchGrantHistory, type GrantHistoryEntry } from '../../api/access';
import { apiErrorMessage } from '../../api/client';
import { Panel } from './panels';

/**
 * What has been granted and revoked on this asset, in order (FR-7.2, FR-8.1).
 *
 * <p>Read from the append-only trail, not from the grants themselves, so a
 * grant that was created and revoked inside the same afternoon is still here
 * after it has vanished from every other view. That is the whole point of a
 * trail; a history assembled from current rows would be a history that quietly
 * drops exactly the events somebody is looking for.
 *
 * <p>This tab is honest about its own scope. It covers grants. Policy edits
 * have their own version history on the policy, and per-query decision audit
 * (FR-8.2) is not built yet — saying so is better than a tab that looks
 * complete and is not.
 */
export function AuditTab({ fqn }: { fqn: string }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['asset-grant-history', fqn],
    queryFn: () => fetchGrantHistory(fqn),
    enabled: Boolean(fqn),
    retry: false,
  });

  return (
    <Panel
      subtitle="Every grant and revocation on this asset, newest first"
      title="Grant history">
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading…</p>}

      {error != null && (
        <p className="tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'The history for this asset could not be read.')}
        </p>
      )}

      {data && data.length === 0 && (
        <p className="tw:text-sm tw:text-tertiary">
          Nothing has ever been granted directly on this asset.
        </p>
      )}

      {data && data.length > 0 && (
        <ol className="tw:space-y-3">
          {data.map((entry) => (
            <HistoryRow entry={entry} key={entry.id} />
          ))}
        </ol>
      )}

      <p className="tw:mt-4 tw:border-t tw:border-secondary tw:pt-3 tw:text-pretty tw:text-xs tw:text-quaternary">
        Grants only. Policy changes are versioned on the policy itself; the
        per-query decision log (FR-8.2) is not built yet, so this page does not
        claim to show who read the table.
      </p>
    </Panel>
  );
}

function HistoryRow({ entry }: { entry: GrantHistoryEntry }) {
  return (
    <li className="tw:flex tw:gap-3">
      <div className="tw:w-36 tw:shrink-0 tw:text-xs tw:text-tertiary">
        {formatInstant(entry.occurredAt)}
      </div>
      <div className="tw:min-w-0">
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <ActionBadge action={entry.action} />
          <span className="tw:text-sm tw:text-primary">
            {entry.targetUsername ?? 'unknown principal'}
          </span>
          {entry.targetSource && (
            <span className="tw:text-xs tw:text-quaternary">
              {entry.targetSource}
            </span>
          )}
          <span className="tw:text-xs tw:text-tertiary">
            by {entry.actor}
          </span>
        </div>
        {entry.action === 'GRANT' && (
          <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
            {entry.validUntil
              ? `until ${formatInstant(entry.validUntil)}`
              : 'no expiry'}
          </p>
        )}
        {entry.reason && (
          <p className="tw:mt-0.5 tw:text-pretty tw:text-xs tw:text-secondary">
            “{entry.reason}”
          </p>
        )}
      </div>
    </li>
  );
}

/**
 * Colour by what the event did to somebody's access.
 *
 * <p>EXPIRE is grey rather than red: nobody decided it, the window simply
 * closed, and an auditor scanning the column should be able to tell the two
 * apart without reading the actor.
 */
function ActionBadge({ action }: { action: GrantHistoryEntry['action'] }) {
  if (action === 'GRANT') {
    return (
      <Badge color="success" size="sm" type="pill-color">
        Granted
      </Badge>
    );
  }
  if (action === 'REVOKE') {
    return (
      <Badge color="error" size="sm" type="pill-color">
        Revoked
      </Badge>
    );
  }
  return (
    <Badge color="gray" size="sm" type="pill-color">
      Expired
    </Badge>
  );
}

function formatInstant(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}
