import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../../api/client';
import { fetchVocabulary, type GovernanceValue } from '../../api/governance';
import { addLocalTag, fetchLocalTags, removeLocalTag } from '../../api/localTags';
import { FIELD } from '../policies/controls';

/**
 * Attaching a tag in ARAK, for a table or column nobody has tagged in
 * OpenMetadata yet (FR-1.7).
 *
 * <p>Only whoever governs the table sees it: a tag can put a mask on a column,
 * so it takes the same right as granting on the table. Everybody else sees the
 * chips and nothing to press. Every change asks for a reason, because the
 * reason is what the audit trail says.
 */
export function LocalTagControl({
  assetFqn,
  targetFqn,
  carried,
}: {
  /** The table the target is, or belongs to. */
  assetFqn: string;
  /** The table itself, or one of its columns. */
  targetFqn: string;
  /** Tags already applied directly on the target, from any source. */
  carried: string[];
}) {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [tag, setTag] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  // One request per table, shared by every row that asks.
  const listing = useQuery({
    queryKey: ['local-tags', assetFqn],
    queryFn: () => fetchLocalTags(assetFqn),
  });
  const vocabulary = useQuery({
    queryKey: ['vocabulary'],
    queryFn: fetchVocabulary,
    enabled: open,
  });

  const done = () => {
    setTag('');
    setReason('');
    setError(null);
    for (const key of ['catalog-asset', 'local-tags', 'asset-policies']) {
      client.invalidateQueries({ queryKey: [key, assetFqn] });
    }
  };
  const failed = (fallback: string) => (e: unknown) => setError(apiErrorMessage(e, fallback));

  const add = useMutation({
    mutationFn: () => addLocalTag({ targetFqn, tagFqn: tag, reason: reason.trim() }),
    onSuccess: done,
    onError: failed('Could not attach the tag.'),
  });
  const remove = useMutation({
    mutationFn: (tagFqn: string) => removeLocalTag({ targetFqn, tagFqn, reason: reason.trim() }),
    onSuccess: done,
    onError: failed('Could not take the tag off.'),
  });

  if (!listing.data?.canEdit) {
    return null;
  }

  if (!open) {
    return (
      <Button color="link-gray" onPress={() => setOpen(true)} size="sm">
        Edit tags
      </Button>
    );
  }

  const mine = listing.data.tags.filter((t) => t.targetFqn === targetFqn);
  const groups = choices(vocabulary.data?.classifications ?? [], carried);
  const busy = add.isPending || remove.isPending;
  const explained = reason.trim().length > 0;

  return (
    <div
      aria-label={`Tags set in ARAK on ${targetFqn}`}
      className="tw:mt-2 tw:flex tw:max-w-md tw:flex-col tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-3"
      role="group">
      <p className="tw:text-xs tw:text-tertiary">
        Tags set here stay when OpenMetadata syncs, and policies read them like any other.
      </p>

      {mine.length > 0 && (
        <ul className="tw:flex tw:flex-col tw:gap-1">
          {mine.map((t) => (
            <li className="tw:flex tw:items-center tw:gap-2 tw:text-sm" key={t.tagFqn}>
              <span className="tw:font-medium tw:text-primary">{t.tagFqn}</span>
              <span className="tw:truncate tw:text-xs tw:text-tertiary" title={t.reason}>
                by {t.addedBy}
              </span>
              <Button
                aria-label={`Remove ${t.tagFqn}`}
                className="tw:ml-auto"
                color="link-gray"
                isDisabled={!explained || busy}
                onPress={() => remove.mutate(t.tagFqn)}
                size="sm">
                Remove
              </Button>
            </li>
          ))}
        </ul>
      )}

      <select
        aria-label="Tag to attach"
        className={`${FIELD} tw:bg-primary`}
        onChange={(event) => setTag(event.target.value)}
        value={tag}>
        <option value="">{vocabulary.isLoading ? 'Loading tags…' : 'Choose a tag'}</option>
        {groups.map(([classification, tags]) => (
          <optgroup key={classification.fqn} label={classification.displayName ?? classification.name}>
            {tags.map((t) => (
              <option key={t.fqn} value={t.fqn}>
                {t.fqn}
              </option>
            ))}
          </optgroup>
        ))}
      </select>
      <input
        aria-label="Reason"
        className={FIELD}
        maxLength={1000}
        onChange={(event) => setReason(event.target.value)}
        placeholder="Why: this is what the audit trail says"
        value={reason}
      />

      {error && (
        <p className="tw:text-xs tw:text-error-primary" role="alert">
          {error}
        </p>
      )}

      <div className="tw:flex tw:justify-end tw:gap-2">
        <Button color="secondary" onPress={() => setOpen(false)} size="sm">
          Close
        </Button>
        <Button isDisabled={!tag || !explained || busy} onPress={() => add.mutate()} size="sm">
          {add.isPending ? 'Attaching…' : 'Attach tag'}
        </Button>
      </div>
    </div>
  );
}

/**
 * The tags that can still go on the target, by classification: enabled ones
 * only, none it already carries, and nothing from a one-tag-only
 * classification it already has a tag from.
 */
export function choices(
  classifications: GovernanceValue[],
  carried: string[]
): [GovernanceValue, GovernanceValue[]][] {
  const have = new Set(carried);
  return classifications
    .filter((c) => !c.disabled)
    .map((c): [GovernanceValue, GovernanceValue[]] => {
      const tags = flatten(c.children).filter((t) => !t.disabled);
      if (c.mutuallyExclusive && tags.some((t) => have.has(t.fqn))) {
        return [c, []];
      }
      return [c, tags.filter((t) => !have.has(t.fqn))];
    })
    .filter(([, tags]) => tags.length > 0);
}

function flatten(values: GovernanceValue[]): GovernanceValue[] {
  return values.flatMap((v) => [v, ...flatten(v.children ?? [])]);
}
