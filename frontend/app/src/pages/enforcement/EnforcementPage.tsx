import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  CheckCircle,
  ClockRewind,
  FileShield02,
  SearchLg,
} from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage } from '../../api/client';
import {
  applySecureView,
  dryRunSecureView,
  fetchSecureViewCandidates,
  fetchSecureViewHistory,
  rollbackSecureView,
  rowCount,
  type EnforcementOutcome,
  type EnforcementStatus,
  type SecureViewCandidate,
  type SecureViewPreview,
} from '../../api/secureViews';
import { useAuthStore } from '../../auth/authStore';
import { TextField } from '../policies/controls';

/**
 * Enforcement mode 5.1.2 — the secure view (FR-6.1, FR-6.4).
 *
 * Every change starts as a dry run: the DDL that would run, the entitlement
 * rows it would write and delete, and everything the platform noticed that
 * somebody should know first. Nothing is written until an administrator
 * presses Apply on that exact review, and a review that no longer describes
 * the source is refused rather than quietly recomputed.
 *
 * Policy authors and data owners can run the review; only an administrator
 * can put it in front of production. The person who wrote a policy is not the
 * person who should be able to ship it alone.
 */
export default function EnforcementPage() {
  const isAdmin = useAuthStore((state) => state.hasRole('PLATFORM_ADMIN'));
  const [search, setSearch] = useState('');

  const { data, error, isLoading } = useQuery({
    queryKey: ['secure-views', search.trim()],
    queryFn: () => fetchSecureViewCandidates(search.trim() || undefined),
  });

  return (
    <div className="tw:flex tw:flex-col tw:gap-5">
      <header className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-3">
        <div>
          <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">Enforcement</h1>
          <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
            Secure views on registered sources. Run a dry run to read exactly what would change;
            an administrator applies that review, or rolls a view back by the name it was
            created under.
          </p>
        </div>
      </header>

      <div className="tw:relative tw:max-w-md">
        <SearchLg className="tw:pointer-events-none tw:absolute tw:left-3 tw:top-1/2 tw:size-4 tw:-translate-y-1/2 tw:text-fg-quaternary" />
        <TextField
          ariaLabel="Search tables"
          className="tw:w-full tw:pl-9"
          onChange={setSearch}
          placeholder="Search by table name or FQN"
          value={search}
        />
      </div>

      <p className="tw:flex tw:max-w-3xl tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-blue-50 tw:px-3 tw:py-2 tw:text-sm tw:text-secondary">
        <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-brand-primary" />
        <span>
          Applying creates the view and its entitlement rows only. Granting people SELECT on the
          view and revoking the base table (cutover) is a separate step and is not automated yet,
          so an applied view is readable by nobody until it is granted.
        </span>
      </p>

      {error && (
        <Notice tone="error">
          {apiErrorMessage(error, 'The list of tables could not be loaded.')}
        </Notice>
      )}
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading…</p>}

      {data && data.length === 0 && (
        <div className="tw:flex tw:flex-col tw:items-center tw:gap-3 tw:rounded-xl tw:border tw:border-dashed tw:border-secondary tw:bg-primary tw:px-6 tw:py-12 tw:text-center">
          <FileShield02 className="tw:size-7 tw:text-fg-quaternary" />
          <p className="tw:max-w-md tw:text-sm tw:text-tertiary">
            {search.trim()
              ? 'No catalogued table matches that search.'
              : 'No catalogued table sits on an enabled source yet. Register a source and import its catalogue first.'}
          </p>
        </div>
      )}

      {data && data.length > 0 && (
        <ul className="tw:flex tw:flex-col tw:gap-3">
          {data.map((candidate) => (
            <CandidateCard candidate={candidate} isAdmin={isAdmin} key={candidate.assetFqn} />
          ))}
        </ul>
      )}
    </div>
  );
}

function CandidateCard({
  candidate,
  isAdmin,
}: {
  candidate: SecureViewCandidate;
  isAdmin: boolean;
}) {
  const client = useQueryClient();
  const [preview, setPreview] = useState<SecureViewPreview | null>(null);
  const [outcome, setOutcome] = useState<{ what: string; result: EnforcementOutcome } | null>(
    null,
  );
  const [confirmRollback, setConfirmRollback] = useState(false);
  const [showHistory, setShowHistory] = useState(false);

  const refresh = () => {
    void client.invalidateQueries({ queryKey: ['secure-views'] });
    void client.invalidateQueries({ queryKey: ['secure-view-history', candidate.assetFqn] });
  };

  const dryRun = useMutation({
    mutationFn: () => dryRunSecureView(candidate.assetFqn),
    onMutate: () => {
      setOutcome(null);
      setConfirmRollback(false);
    },
    onSuccess: (result) => {
      setPreview(result);
      refresh();
    },
    onError: () => refresh(),
  });

  const apply = useMutation({
    mutationFn: (reviewId: string) => applySecureView(candidate.assetFqn, reviewId),
    onSuccess: (result) => {
      setPreview(null);
      setOutcome({ what: 'Applied', result });
      refresh();
    },
    // The review is spent whether or not the apply went through, so the one
    // on screen can no longer be applied. Clear it and let them run another.
    onError: () => {
      setPreview(null);
      refresh();
    },
  });

  const rollback = useMutation({
    mutationFn: () => rollbackSecureView(candidate.assetFqn),
    onSuccess: (result) => {
      setConfirmRollback(false);
      setPreview(null);
      setOutcome({ what: 'Rolled back', result });
      refresh();
    },
    onError: () => {
      setConfirmRollback(false);
      refresh();
    },
  });

  const installed = candidate.status === 'APPLIED' || candidate.status === 'DRIFTED';
  const busy = dryRun.isPending || apply.isPending || rollback.isPending;
  const failure = dryRun.error ?? apply.error ?? rollback.error;

  return (
    <li className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
      <div className="tw:flex tw:flex-wrap tw:items-start tw:gap-4 tw:px-5 tw:py-4">
        <span
          aria-hidden
          className="tw:flex tw:size-10 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-brand-50">
          <FileShield02 className="tw:size-5 tw:text-fg-brand-primary" />
        </span>

        <div className="tw:min-w-0 tw:flex-1">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <h2 className="tw:truncate tw:text-md tw:font-semibold tw:text-primary">
              {candidate.displayName || candidate.table}
            </h2>
            <Badge color={statusColor(candidate.status)} size="sm" type="pill-color">
              {statusLabel(candidate.status)}
            </Badge>
            <Badge color="gray" size="sm" type="pill-color">
              {candidate.sourceName}
            </Badge>
          </div>
          <p className="tw:mt-1 tw:truncate tw:font-mono tw:text-xs tw:text-tertiary">
            {candidate.assetFqn}
          </p>
          <dl className="tw:mt-3 tw:grid tw:gap-x-6 tw:gap-y-1 tw:text-xs tw:sm:grid-cols-2">
            <Pair label="Base table" value={`${candidate.schema}.${candidate.table}`} />
            <Pair
              label={installed ? 'Secure view' : 'Would create'}
              value={candidate.secureObject}
            />
            {candidate.lastAppliedAt && (
              <Pair
                label="Last change"
                value={`${formatWhen(candidate.lastAppliedAt)}${
                  candidate.lastAppliedBy ? ` by ${candidate.lastAppliedBy}` : ''
                }`}
              />
            )}
          </dl>
          {candidate.lastError && (
            <p className="tw:mt-2 tw:text-xs tw:text-error-primary">
              Last attempt failed: {candidate.lastError}
            </p>
          )}
        </div>

        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <Button
            color="secondary"
            iconLeading={ClockRewind}
            onPress={() => setShowHistory((open) => !open)}
            size="sm">
            {showHistory ? 'Hide history' : 'History'}
          </Button>
          <Button
            color="secondary"
            isDisabled={busy}
            onPress={() => dryRun.mutate()}
            size="sm">
            {dryRun.isPending ? 'Reading the source…' : 'Dry run'}
          </Button>
          {isAdmin && installed && !confirmRollback && (
            <Button
              color="secondary-destructive"
              isDisabled={busy}
              onPress={() => setConfirmRollback(true)}
              size="sm">
              Roll back
            </Button>
          )}
        </div>
      </div>

      {confirmRollback && (
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-3 tw:border-t tw:border-secondary tw:bg-utility-error-50 tw:px-5 tw:py-3">
          <p className="tw:flex-1 tw:text-sm tw:text-error-primary">
            Drop {candidate.secureObject}? Anyone reading the view loses it at once. The
            entitlement rows stay; they grant nothing on their own.
          </p>
          <Button
            color="secondary"
            isDisabled={rollback.isPending}
            onPress={() => setConfirmRollback(false)}
            size="sm">
            Cancel
          </Button>
          <Button
            color="primary-destructive"
            isDisabled={rollback.isPending}
            onPress={() => rollback.mutate()}
            size="sm">
            {rollback.isPending ? 'Rolling back…' : 'Drop the view'}
          </Button>
        </div>
      )}

      {failure && (
        <div className="tw:border-t tw:border-secondary tw:px-5 tw:py-3">
          <Notice tone="error">{apiErrorMessage(failure, 'That did not go through.')}</Notice>
        </div>
      )}

      {outcome && (
        <div className="tw:border-t tw:border-secondary tw:px-5 tw:py-3">
          <Notice tone="success">
            {outcome.what}: {outcome.result.statements} statement
            {outcome.result.statements === 1 ? '' : 's'} ran
            {outcome.what === 'Applied'
              ? `, ${outcome.result.inserted} entitlement row${
                  outcome.result.inserted === 1 ? '' : 's'
                } written and ${outcome.result.deleted} removed.`
              : '.'}
          </Notice>
        </div>
      )}

      {preview && (
        <Review
          isAdmin={isAdmin}
          onApply={() => apply.mutate(preview.reviewId)}
          onDiscard={() => setPreview(null)}
          pending={apply.isPending}
          preview={preview}
        />
      )}

      {showHistory && <History fqn={candidate.assetFqn} />}
    </li>
  );
}

function Review({
  preview,
  isAdmin,
  pending,
  onApply,
  onDiscard,
}: {
  preview: SecureViewPreview;
  isAdmin: boolean;
  pending: boolean;
  onApply: () => void;
  onDiscard: () => void;
}) {
  const { dryRun } = preview;
  const inserts = rowCount(dryRun.rows.insert);
  const deletes = rowCount(dryRun.rows.delete);
  const nothingToDo = inserts === 0 && deletes === 0;

  return (
    <div className="tw:flex tw:flex-col tw:gap-4 tw:border-t tw:border-secondary tw:px-5 tw:py-4">
      <div className="tw:flex tw:flex-wrap tw:items-baseline tw:justify-between tw:gap-2">
        <h3 className="tw:text-sm tw:font-semibold tw:text-primary">
          Dry run — nothing has been written
        </h3>
        <p className="tw:text-xs tw:text-quaternary">
          Review expires {formatWhen(preview.expiresAt)}
        </p>
      </div>

      <dl className="tw:grid tw:gap-3 tw:text-sm tw:sm:grid-cols-4">
        <Figure label="People decided" value={preview.principals} />
        <Figure label="Allowed to read" value={preview.allowed} />
        <Figure label="Rows to write" value={inserts} />
        <Figure label="Rows to remove" value={deletes} />
      </dl>

      {nothingToDo && (
        <p className="tw:text-sm tw:text-tertiary">
          The entitlement tables already say what the policies say. Applying would still replace
          the view with the one below.
        </p>
      )}

      {preview.warnings.length > 0 && (
        <ul className="tw:flex tw:flex-col tw:gap-2">
          {preview.warnings.map((warning) => (
            <li
              className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-warning-50 tw:px-3 tw:py-2 tw:text-sm tw:text-warning-primary"
              key={warning}>
              <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
              <span>{warning}</span>
            </li>
          ))}
        </ul>
      )}

      <div>
        <p className="tw:text-xs tw:font-medium tw:text-secondary">
          Columns read from the source now ({preview.liveColumns.length})
        </p>
        <div className="tw:mt-1 tw:flex tw:flex-wrap tw:gap-1">
          {preview.liveColumns.map((column) => (
            <Badge
              color={preview.uncataloguedColumns.includes(column) ? 'warning' : 'gray'}
              key={column}
              size="sm"
              type="pill-color">
              {column}
            </Badge>
          ))}
        </div>
      </div>

      <Script title="Will run" sql={dryRun.applyScript} />
      <Script title="Rollback, if needed" sql={dryRun.rollbackScript} />

      {dryRun.notes.length > 0 && (
        <ul className="tw:list-disc tw:pl-5 tw:text-xs tw:text-tertiary">
          {dryRun.notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      )}

      <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-end tw:gap-2">
        {!isAdmin && (
          <p className="tw:mr-auto tw:text-xs tw:text-tertiary">
            Only a platform administrator can apply a review.
          </p>
        )}
        <Button color="secondary" isDisabled={pending} onPress={onDiscard} size="sm">
          Discard
        </Button>
        {isAdmin && (
          <Button color="primary" isDisabled={pending} onPress={onApply} size="sm">
            {pending ? 'Applying…' : 'Apply this review'}
          </Button>
        )}
      </div>
    </div>
  );
}

function History({ fqn }: { fqn: string }) {
  const { data, error, isLoading } = useQuery({
    queryKey: ['secure-view-history', fqn],
    queryFn: () => fetchSecureViewHistory(fqn),
  });

  return (
    <div className="tw:border-t tw:border-secondary tw:px-5 tw:py-4">
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading…</p>}
      {error && (
        <Notice tone="error">{apiErrorMessage(error, 'The history could not be loaded.')}</Notice>
      )}
      {data && data.history.length === 0 && (
        <p className="tw:text-sm tw:text-tertiary">Nobody has run anything on this table yet.</p>
      )}
      {data && data.history.length > 0 && (
        <div className="tw:overflow-x-auto">
          <table className="tw:w-full tw:text-left tw:text-xs">
            <thead className="tw:text-quaternary">
              <tr>
                <th className="tw:py-1 tw:pr-4 tw:font-medium">When</th>
                <th className="tw:py-1 tw:pr-4 tw:font-medium">Who</th>
                <th className="tw:py-1 tw:pr-4 tw:font-medium">Action</th>
                <th className="tw:py-1 tw:pr-4 tw:font-medium">Outcome</th>
                <th className="tw:py-1 tw:font-medium">Detail</th>
              </tr>
            </thead>
            <tbody className="tw:text-tertiary">
              {data.history.map((entry) => (
                <tr className="tw:border-t tw:border-secondary tw:align-top" key={entry.id}>
                  <td className="tw:whitespace-nowrap tw:py-1.5 tw:pr-4">
                    {formatWhen(entry.occurredAt)}
                  </td>
                  <td className="tw:py-1.5 tw:pr-4">{entry.actor}</td>
                  <td className="tw:py-1.5 tw:pr-4">{actionLabel(entry.action)}</td>
                  <td className="tw:py-1.5 tw:pr-4">
                    <Badge color={outcomeColor(entry.outcome)} size="sm" type="pill-color">
                      {entry.outcome.replace('_', ' ').toLowerCase()}
                    </Badge>
                  </td>
                  <td className="tw:py-1.5">{entry.detail ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function Script({ title, sql }: { title: string; sql: string }) {
  return (
    <div>
      <p className="tw:text-xs tw:font-medium tw:text-secondary">{title}</p>
      <pre className="tw:mt-1 tw:max-h-80 tw:overflow-auto tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-3 tw:font-mono tw:text-[12px] tw:leading-5 tw:text-primary">
        {sql || '(nothing)'}
      </pre>
    </div>
  );
}

function Figure({ label, value }: { label: string; value: number }) {
  return (
    <div className="tw:rounded-lg tw:border tw:border-secondary tw:px-3 tw:py-2">
      <dt className="tw:text-xs tw:text-quaternary">{label}</dt>
      <dd className="tw:text-lg tw:font-semibold tw:text-primary">{value.toLocaleString()}</dd>
    </div>
  );
}

function Pair({ label, value }: { label: string; value: string }) {
  return (
    <div className="tw:flex tw:gap-2">
      <dt className="tw:shrink-0 tw:text-quaternary">{label}</dt>
      <dd className="tw:truncate tw:text-tertiary">{value}</dd>
    </div>
  );
}

function Notice({ tone, children }: { tone: 'error' | 'success'; children: React.ReactNode }) {
  const Icon = tone === 'error' ? AlertTriangle : CheckCircle;
  return (
    <p
      className={`tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:px-3 tw:py-2 tw:text-sm ${
        tone === 'error'
          ? 'tw:bg-utility-error-50 tw:text-error-primary'
          : 'tw:bg-utility-success-50 tw:text-success-primary'
      }`}>
      <Icon className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
      <span>{children}</span>
    </p>
  );
}

export function statusLabel(status: EnforcementStatus): string {
  switch (status) {
    case 'APPLIED':
      return 'Applied';
    case 'DRIFTED':
      return 'Drifted';
    case 'FAILED':
      return 'Failed';
    case 'PENDING':
      return 'Pending';
    default:
      return 'Not enforced';
  }
}

function statusColor(status: EnforcementStatus) {
  switch (status) {
    case 'APPLIED':
      return 'success' as const;
    case 'DRIFTED':
      return 'warning' as const;
    case 'FAILED':
      return 'error' as const;
    default:
      return 'gray' as const;
  }
}

function outcomeColor(outcome: string) {
  switch (outcome) {
    case 'APPLIED':
    case 'ROLLED_BACK':
      return 'success' as const;
    case 'STALE':
    case 'REFUSED':
      return 'warning' as const;
    case 'FAILED':
      return 'error' as const;
    default:
      return 'gray' as const;
  }
}

function actionLabel(action: string): string {
  switch (action) {
    case 'DRY_RUN':
      return 'Dry run';
    case 'APPLY':
      return 'Apply';
    default:
      return 'Roll back';
  }
}

function formatWhen(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}
