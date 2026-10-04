import { useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  CheckCircle,
  ClockRewind,
  Database01,
  Settings01,
} from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Chip as Badge } from '../../../components/chips';
import { apiErrorMessage } from '../../../api/client';
import {
  applyNative,
  checkNative,
  deleteNativeCredential,
  fetchNativeHistory,
  fetchNativeLogins,
  fetchNativePolicy,
  fetchNativeSourceHistory,
  fetchNativeSources,
  mapNativeLogin,
  planNative,
  planNativeRollback,
  rollbackNative,
  setNativeCredential,
  unmapNativeLogin,
  type NativeAuditEntry,
  type NativeCheck,
  type NativeLevel,
  type NativePreview,
  type NativeRole,
  type NativeRollbackPreview,
  type NativeSource,
  type NativeStatus,
} from '../../../api/nativeSubscription';
import { useAuthStore } from '../../../auth/authStore';
import { TextField } from '../controls';
import { MODES } from '../enforcement';

/**
 * Enforcement mode 5.1.1 for a subscription policy: a PostgreSQL role on the
 * source itself (FR-6.2).
 *
 * The role is named after the policy and the source, holds the tables the
 * policy binds, and has as members the logins of the people the policy lets
 * in. Every change starts as a plan that shows the exact SQL; only an
 * administrator applies it, and only that plan. Between applies ARAK takes
 * people out who no longer qualify, and never puts anybody in.
 *
 * A policy author reads the plan and the history here; the push account and
 * the map from people to database logins are an administrator's.
 */
export default function NativePushPanel({ policyId }: { policyId: string }) {
  const isAdmin = useAuthStore((state) => state.hasRole('PLATFORM_ADMIN'));
  const [chosen, setChosen] = useState<string | null>(null);

  const sources = useQuery({ queryKey: ['native-sources'], queryFn: fetchNativeSources });
  const policy = useQuery({
    queryKey: ['native-policy', policyId],
    queryFn: () => fetchNativePolicy(policyId),
  });

  const error = sources.error ?? policy.error;
  if (error) {
    return <Notice tone="error">{apiErrorMessage(error, 'PostgreSQL roles could not be loaded.')}</Notice>;
  }
  if (!sources.data || !policy.data) {
    return <p className="tw:text-sm tw:text-tertiary">Loading…</p>;
  }

  const list = sources.data.data;
  const roleOn = (sourceId: string) =>
    policy.data.roles.find((role) => role.dataSourceId === sourceId) ?? null;
  // Open on a source this policy already has a role on, else the first one
  // that is switched on and set to native, else the first switched on.
  const fallback =
    list.find((source) => roleOn(source.id)) ??
    list.find((source) => source.enabled && isNative(source)) ??
    list.find((source) => source.enabled) ??
    list[0];
  const source = list.find((each) => each.id === chosen) ?? fallback;

  return (
    <div className="tw:flex tw:flex-col tw:gap-5">
      <p className="tw:flex tw:max-w-3xl tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-blue-50 tw:px-3 tw:py-2 tw:text-sm tw:text-secondary">
        <Database01 className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-brand-primary" />
        <span>
          ARAK keeps one role for this policy on each PostgreSQL source set to Native source
          config. The role holds the tables the policy applies to, and its members are the logins
          of the people it lets in. Plan
          first and read the SQL; an administrator applies that plan. Every{' '}
          {sources.data.sweepMinutes} minutes ARAK takes out people who no longer qualify. It
          never adds anyone on its own: new people join at the next apply.
        </span>
      </p>

      {policy.data.unsupported.length > 0 && (
        <div className="tw:flex tw:flex-col tw:gap-1 tw:rounded-lg tw:bg-utility-warning-50 tw:px-3 tw:py-2 tw:text-sm tw:text-warning-primary">
          <p className="tw:flex tw:items-center tw:gap-2 tw:font-medium">
            <AlertTriangle className="tw:size-4 tw:shrink-0" />
            A database role cannot carry this policy
          </p>
          <ul className="tw:list-disc tw:pl-10">
            {policy.data.unsupported.map((reason) => (
              <li key={reason}>{reason}</li>
            ))}
          </ul>
          <p className="tw:pl-6 tw:text-xs">
            Keep it on the query proxy, or split the parts a role can hold into their own policy.
          </p>
        </div>
      )}

      {policy.data.lifecycleState !== 'ACTIVE' && policy.data.unsupported.length === 0 && (
        <p className="tw:max-w-3xl tw:text-sm tw:text-tertiary">
          This policy is {policy.data.lifecycleState.toLowerCase()}, so it lets nobody in yet: a
          plan now gives the role no members.
        </p>
      )}

      {list.length === 0 ? (
        <div className="tw:flex tw:flex-col tw:items-center tw:gap-3 tw:rounded-xl tw:border tw:border-dashed tw:border-secondary tw:bg-primary tw:px-6 tw:py-12 tw:text-center">
          <Database01 className="tw:size-7 tw:text-fg-quaternary" />
          <p className="tw:max-w-md tw:text-sm tw:text-tertiary">
            No PostgreSQL source is registered. Roles are made on PostgreSQL sources only.
          </p>
        </div>
      ) : (
        <>
          {list.length > 1 && (
            <div aria-label="Source" className="tw:flex tw:flex-wrap tw:gap-2" role="group">
              {list.map((each) => (
                <Button
                  aria-pressed={each.id === source.id}
                  color={each.id === source.id ? 'primary' : 'secondary'}
                  key={each.id}
                  onPress={() => setChosen(each.id)}
                  size="sm">
                  {each.name}
                </Button>
              ))}
            </div>
          )}
          <SourceCard
            blocked={policy.data.unsupported.length > 0}
            isAdmin={isAdmin}
            key={source.id}
            policyId={policyId}
            role={roleOn(source.id)}
            source={source}
          />
          {isAdmin && <SourceSetup key={`setup-${source.id}`} source={source} />}
        </>
      )}
    </div>
  );
}

function SourceCard({
  policyId,
  source,
  role,
  isAdmin,
  blocked,
}: {
  policyId: string;
  source: NativeSource;
  role: NativeRole | null;
  isAdmin: boolean;
  blocked: boolean;
}) {
  const client = useQueryClient();
  const [level, setLevel] = useState<NativeLevel>(role?.accessLevel ?? 'READ');
  const [preview, setPreview] = useState<NativePreview | null>(null);
  const [check, setCheck] = useState<NativeCheck | null>(null);
  const [rollbackPlan, setRollbackPlan] = useState<NativeRollbackPreview | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);
  const [showHistory, setShowHistory] = useState(false);

  const refresh = () => {
    void client.invalidateQueries({ queryKey: ['native-policy', policyId] });
    void client.invalidateQueries({ queryKey: ['native-sources'] });
    void client.invalidateQueries({ queryKey: ['native-history', policyId, source.id] });
  };
  const clear = () => {
    setPreview(null);
    setCheck(null);
    setRollbackPlan(null);
    setOutcome(null);
  };

  const plan = useMutation({
    mutationFn: () => planNative(policyId, source.id, level),
    onMutate: clear,
    onSuccess: (result) => {
      setPreview(result);
      refresh();
    },
    onError: () => refresh(),
  });

  const apply = useMutation({
    mutationFn: (reviewId: string) => applyNative(policyId, source.id, reviewId),
    onSuccess: (result) => {
      setPreview(null);
      setOutcome(
        `Applied: ${result.statements} statement${result.statements === 1 ? '' : 's'} ran. ` +
          `${result.role.members.length} login${result.role.members.length === 1 ? '' : 's'} ` +
          `in ${result.role.roleName}.`,
      );
      refresh();
    },
    // A plan is spent whether or not the apply went through. Clear it and let
    // them plan again.
    onError: () => {
      setPreview(null);
      refresh();
    },
  });

  const verify = useMutation({
    mutationFn: () => checkNative(policyId, source.id),
    onMutate: clear,
    onSuccess: (result) => {
      setCheck(result);
      refresh();
    },
    onError: () => refresh(),
  });

  const readRollback = useMutation({
    mutationFn: () => planNativeRollback(policyId, source.id),
    onMutate: clear,
    onSuccess: (result) => setRollbackPlan(result),
  });

  const rollback = useMutation({
    mutationFn: () => rollbackNative(policyId, source.id),
    onSuccess: (result) => {
      setRollbackPlan(null);
      setOutcome(
        `Rolled back: ${result.statements} statement${result.statements === 1 ? '' : 's'} ran. ` +
          `${result.role.roleName} is gone.`,
      );
      refresh();
    },
    onError: () => {
      setRollbackPlan(null);
      refresh();
    },
  });

  const installed = role !== null && role.status !== 'ROLLED_BACK';
  const native = isNative(source);
  const busy =
    plan.isPending || apply.isPending || verify.isPending || readRollback.isPending || rollback.isPending;
  const failure = plan.error ?? apply.error ?? verify.error ?? readRollback.error ?? rollback.error;

  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
      <div className="tw:flex tw:flex-wrap tw:items-start tw:gap-4 tw:px-5 tw:py-4">
        <span
          aria-hidden
          className="tw:flex tw:size-10 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-brand-50">
          <Database01 className="tw:size-5 tw:text-fg-brand-primary" />
        </span>

        <div className="tw:min-w-0 tw:flex-1">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <h2 className="tw:truncate tw:text-md tw:font-semibold tw:text-primary">
              {source.name}
            </h2>
            <Badge color={statusColor(role?.status)} size="sm" type="pill-color">
              {statusLabel(role?.status)}
            </Badge>
            {!source.enabled && (
              <Badge color="gray" size="sm" type="pill-color">
                Source switched off
              </Badge>
            )}
            {!source.credential.configured && (
              <Badge color="warning" size="sm" type="pill-color">
                No push account
              </Badge>
            )}
            {!native && (
              <Badge color="warning" size="sm" type="pill-color">
                Not set to native
              </Badge>
            )}
          </div>
          <dl className="tw:mt-3 tw:grid tw:gap-x-6 tw:gap-y-1 tw:text-xs tw:sm:grid-cols-2">
            <Pair label="Database" value={source.database} />
            <Pair label={installed ? 'Role' : 'Would create'} value={role?.roleName ?? 'a role named after this policy'} />
            {installed && (
              <>
                <Pair label="Level" value={levelLabel(role.accessLevel)} />
                <Pair
                  label="Holds"
                  value={`${count(role.tables.length, 'table')}, ${count(role.members.length, 'login')}`}
                />
              </>
            )}
            {role?.lastAppliedAt && (
              <Pair
                label="Last change"
                value={`${formatWhen(role.lastAppliedAt)}${
                  role.lastAppliedBy ? ` by ${role.lastAppliedBy}` : ''
                }`}
              />
            )}
            {role?.lastCheckedAt && <Pair label="Last checked" value={formatWhen(role.lastCheckedAt)} />}
          </dl>
          {role?.detail && <p className="tw:mt-2 tw:text-xs tw:text-tertiary">{role.detail}</p>}
          {role?.lastError && (
            <p className="tw:mt-2 tw:text-xs tw:text-error-primary">
              Last attempt failed: {role.lastError}
            </p>
          )}
        </div>
      </div>

      {!native && (
        <div className="tw:border-t tw:border-secondary tw:px-5 tw:py-3">
          <p className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-warning-50 tw:px-3 tw:py-2 tw:text-sm tw:text-warning-primary">
            <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
            <span>
              {source.name} is enforced by {modeTitle(source.mode)}. A connection has one mode for
              its subscription and data policies alike, so roles are pushed only to a source set to
              Native source config. Change its mode under Sources to plan here.
              {installed &&
                ' The role still on it lets nobody in: the sweep takes its members out. Roll it back to drop it.'}
            </span>
          </p>
        </div>
      )}

      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2 tw:border-t tw:border-secondary tw:px-5 tw:py-3">
        <div aria-label="Access level" className="tw:flex tw:gap-1" role="group">
          {(['BROWSE', 'READ'] as NativeLevel[]).map((each) => (
            <Button
              aria-pressed={level === each}
              color={level === each ? 'primary' : 'secondary'}
              isDisabled={busy || !native}
              key={each}
              onPress={() => setLevel(each)}
              size="sm">
              {levelLabel(each)}
            </Button>
          ))}
        </div>
        <p className="tw:text-xs tw:text-tertiary">
          {level === 'BROWSE'
            ? 'Connect and see the schemas; no rows.'
            : 'Browse, and SELECT on every table the policy applies to.'}
        </p>
        <div className="tw:ml-auto tw:flex tw:flex-wrap tw:gap-2">
          <Button
            color="secondary"
            iconLeading={ClockRewind}
            onPress={() => setShowHistory((open) => !open)}
            size="sm">
            {showHistory ? 'Hide history' : 'History'}
          </Button>
          {installed && (
            <Button color="secondary" isDisabled={busy} onPress={() => verify.mutate()} size="sm">
              {verify.isPending ? 'Reading the source…' : 'Check'}
            </Button>
          )}
          {isAdmin && installed && (
            <Button
              color="secondary-destructive"
              isDisabled={busy}
              onPress={() => readRollback.mutate()}
              size="sm">
              Roll back
            </Button>
          )}
          <Button
            color="primary"
            isDisabled={busy || blocked || !native}
            onPress={() => plan.mutate()}
            size="sm">
            {plan.isPending ? 'Planning…' : 'Plan'}
          </Button>
        </div>
      </div>

      {failure && (
        <div className="tw:border-t tw:border-secondary tw:px-5 tw:py-3">
          <Notice tone="error">{apiErrorMessage(failure, 'That did not go through.')}</Notice>
        </div>
      )}

      {outcome && (
        <div className="tw:border-t tw:border-secondary tw:px-5 tw:py-3">
          <Notice tone="success">{outcome}</Notice>
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

      {check && <CheckResult check={check} onClose={() => setCheck(null)} />}

      {rollbackPlan && (
        <div className="tw:flex tw:flex-col tw:gap-3 tw:border-t tw:border-secondary tw:bg-utility-error-50 tw:px-5 tw:py-4">
          <p className="tw:text-sm tw:text-error-primary">
            Drop {rollbackPlan.role}? Its members lose what it gave them at once. Grants somebody
            else made on these tables stay.
          </p>
          <Script sql={rollbackPlan.script} title="Will run" />
          <Notes notes={rollbackPlan.notes} />
          <div className="tw:flex tw:justify-end tw:gap-2">
            <Button
              color="secondary"
              isDisabled={rollback.isPending}
              onPress={() => setRollbackPlan(null)}
              size="sm">
              Cancel
            </Button>
            <Button
              color="primary-destructive"
              isDisabled={rollback.isPending}
              onPress={() => rollback.mutate()}
              size="sm">
              {rollback.isPending ? 'Rolling back…' : 'Drop the role'}
            </Button>
          </div>
        </div>
      )}

      {showHistory && (
        <AuditTable
          empty="Nobody has planned anything for this policy on this source yet."
          queryFn={() => fetchNativeHistory(policyId, source.id)}
          queryKey={['native-history', policyId, source.id]}
        />
      )}
    </section>
  );
}

function Review({
  preview,
  isAdmin,
  pending,
  onApply,
  onDiscard,
}: {
  preview: NativePreview;
  isAdmin: boolean;
  pending: boolean;
  onApply: () => void;
  onDiscard: () => void;
}) {
  const blocked = preview.blockers.length > 0;

  return (
    <div className="tw:flex tw:flex-col tw:gap-4 tw:border-t tw:border-secondary tw:px-5 tw:py-4">
      <div className="tw:flex tw:flex-wrap tw:items-baseline tw:justify-between tw:gap-2">
        <h3 className="tw:text-sm tw:font-semibold tw:text-primary">
          Plan for {preview.role} — nothing has changed on the source
        </h3>
        <p className="tw:text-xs tw:text-quaternary">Plan expires {formatWhen(preview.expiresAt)}</p>
      </div>

      <dl className="tw:grid tw:gap-3 tw:text-sm tw:sm:grid-cols-4">
        <Figure label="People decided" value={preview.principals} />
        <Figure label="Logins in the role" value={preview.members.length} />
        <Figure label="Tables" value={preview.tables.length} />
        <Figure label="Statements" value={preview.changes.length} />
      </dl>

      {preview.satisfied && !blocked && (
        <Notice tone="success">The source already matches this policy. There is nothing to apply.</Notice>
      )}

      {blocked && (
        <Messages
          items={preview.blockers}
          title="This plan cannot be applied"
          tone="error"
        />
      )}
      {preview.warnings.length > 0 && <Messages items={preview.warnings} tone="warning" />}

      <Group title={`Members (${preview.members.length})`}>
        {preview.members.length === 0 ? (
          <p className="tw:text-xs tw:text-tertiary">Nobody. The role would hold the tables for no one.</p>
        ) : (
          <ul className="tw:flex tw:flex-col tw:gap-1 tw:text-xs">
            {preview.members.map((member) => (
              <li className="tw:flex tw:flex-wrap tw:gap-2" key={member.login}>
                <span className="tw:font-mono tw:text-primary">{member.login}</span>
                <span className="tw:text-tertiary">for {member.people.join(', ')}</span>
              </li>
            ))}
          </ul>
        )}
      </Group>

      {preview.excluded.length > 0 && (
        <Group title={`Kept out (${preview.excluded.length})`}>
          <ul className="tw:flex tw:flex-col tw:gap-2 tw:text-xs">
            {preview.excluded.map((person) => (
              <li key={`${person.person}-${person.login}`}>
                <p>
                  <span className="tw:font-medium tw:text-primary">{person.person}</span>{' '}
                  <span className="tw:font-mono tw:text-tertiary">({person.login})</span>
                </p>
                <p className="tw:text-tertiary">{person.reasons.join(' ')}</p>
                {person.lost.length > 0 && (
                  <p className="tw:text-warning-primary">
                    Loses the whole role, including {person.lost.join(', ')}.
                  </p>
                )}
              </li>
            ))}
          </ul>
        </Group>
      )}

      {preview.unmapped.length > 0 && (
        <Group title={`Let in, but no login here (${preview.unmapped.length})`}>
          <p className="tw:text-xs tw:text-tertiary">
            {preview.unmapped.join(', ')}. An administrator maps a login for each before they can
            be members.
          </p>
        </Group>
      )}

      {preview.sharedRefused.length > 0 && (
        <Group title="Shared logins left out">
          <p className="tw:text-xs tw:text-tertiary">
            {preview.sharedRefused.join(', ')}. More than one person uses each of these, and not
            all of them are let in.
          </p>
        </Group>
      )}

      {preview.exemptions.length > 0 && (
        <Group title="Exempt">
          <p className="tw:text-xs tw:text-tertiary">{preview.exemptions.join(', ')}</p>
        </Group>
      )}

      <Group title={`Tables (${preview.tables.length})`}>
        <div className="tw:flex tw:flex-wrap tw:gap-1">
          {preview.tables.map((table) => (
            <Badge color="gray" key={table} size="sm" type="pill-color">
              {table}
            </Badge>
          ))}
        </div>
      </Group>

      {preview.otherReaders.length > 0 && (
        <Group title="Others who can already read these tables">
          <p className="tw:text-xs tw:text-tertiary">
            {preview.otherReaders.join(', ')}. Their grants were not made by ARAK and are left
            alone.
          </p>
        </Group>
      )}

      <Script sql={preview.applyScript} title="Will run" />
      <Script sql={preview.rollbackScript} title="Rollback, if needed" />

      <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-end tw:gap-2">
        {!isAdmin && (
          <p className="tw:mr-auto tw:text-xs tw:text-tertiary">
            Only a platform administrator can apply a plan.
          </p>
        )}
        <Button color="secondary" isDisabled={pending} onPress={onDiscard} size="sm">
          Discard
        </Button>
        {isAdmin && (
          <Button
            color="primary"
            isDisabled={pending || blocked || preview.satisfied}
            onPress={onApply}
            size="sm">
            {pending ? 'Applying…' : 'Apply this plan'}
          </Button>
        )}
      </div>
    </div>
  );
}

function CheckResult({ check, onClose }: { check: NativeCheck; onClose: () => void }) {
  return (
    <div className="tw:flex tw:flex-col tw:gap-3 tw:border-t tw:border-secondary tw:px-5 tw:py-4">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <h3 className="tw:text-sm tw:font-semibold tw:text-primary">Checked against the source</h3>
        <Badge color={statusColor(check.status)} size="sm" type="pill-color">
          {statusLabel(check.status)}
        </Badge>
        <Button className="tw:ml-auto" color="secondary" onPress={onClose} size="sm">
          Close
        </Button>
      </div>
      {check.satisfied ? (
        <Notice tone="success">The role on the source is what the policy says.</Notice>
      ) : (
        <p className="tw:text-sm tw:text-tertiary">
          {check.drifted
            ? 'Somebody changed the role on the source by hand. ARAK does not repair it on its own; plan and apply to put it back.'
            : 'The policy has moved on since the last apply. Plan and apply to bring the role up to date.'}
        </p>
      )}
      {check.blockers.length > 0 && <Messages items={check.blockers} tone="error" />}
      {check.warnings.length > 0 && <Messages items={check.warnings} tone="warning" />}
      {!check.satisfied && <Script sql={check.applyScript} title="An apply would run" />}
    </div>
  );
}

/**
 * The push account and the map from people to database logins, for one
 * source. Administrators only, and the account is never read back: the
 * server says whether one is set and by which scheme.
 */
function SourceSetup({ source }: { source: NativeSource }) {
  const [open, setOpen] = useState(!source.credential.configured);

  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
      <button
        aria-expanded={open}
        className="tw:flex tw:w-full tw:items-center tw:gap-3 tw:px-5 tw:py-3 tw:text-left"
        onClick={() => setOpen((was) => !was)}
        type="button">
        <Settings01 className="tw:size-4 tw:text-fg-quaternary" />
        <span className="tw:text-sm tw:font-semibold tw:text-primary">
          Source setup for {source.name}
        </span>
        <span className="tw:text-xs tw:text-tertiary">
          {source.credential.configured ? 'Push account set' : 'No push account'} ·{' '}
          {count(source.logins, 'login')} mapped
        </span>
        <span className="tw:ml-auto tw:text-xs tw:text-brand-secondary">{open ? 'Hide' : 'Show'}</span>
      </button>
      {open && (
        <div className="tw:flex tw:flex-col tw:gap-5 tw:border-t tw:border-secondary tw:px-5 tw:py-4">
          <PushAccount source={source} />
          <Logins source={source} />
          <div>
            <p className="tw:text-xs tw:font-medium tw:text-secondary">Setup history</p>
            <AuditTable
              empty="Nothing has been set up on this source yet."
              queryFn={() => fetchNativeSourceHistory(source.id)}
              queryKey={['native-source-history', source.id]}
            />
          </div>
        </div>
      )}
    </section>
  );
}

function PushAccount({ source }: { source: NativeSource }) {
  const client = useQueryClient();
  const [byReference, setByReference] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [reference, setReference] = useState('');
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [done, setDone] = useState<string | null>(null);

  const refresh = () => {
    void client.invalidateQueries({ queryKey: ['native-sources'] });
    void client.invalidateQueries({ queryKey: ['native-source-history', source.id] });
  };

  const save = useMutation({
    mutationFn: () =>
      setNativeCredential(
        source.id,
        byReference ? { credentialRef: reference.trim() } : { username: username.trim(), password },
      ),
    onMutate: () => setDone(null),
    onSuccess: () => {
      // The secret leaves the page as soon as the server has it.
      setPassword('');
      setReference('');
      setUsername('');
      setDone('Push account saved.');
      refresh();
    },
  });

  const remove = useMutation({
    mutationFn: () => deleteNativeCredential(source.id),
    onMutate: () => setDone(null),
    onSuccess: () => {
      setConfirmRemove(false);
      setDone('Push account removed.');
      refresh();
    },
  });

  const { credential } = source;
  const ready = byReference ? reference.trim() !== '' : username.trim() !== '' && password !== '';

  return (
    <div className="tw:flex tw:flex-col tw:gap-3">
      <div>
        <p className="tw:text-xs tw:font-medium tw:text-secondary">Push account</p>
        <p className="tw:mt-1 tw:text-xs tw:text-tertiary">
          {credential.configured
            ? `Set${credential.scheme ? ` (${credential.scheme})` : ''}${
                credential.updatedAt ? ` ${formatWhen(credential.updatedAt)}` : ''
              }${credential.updatedBy ? ` by ${credential.updatedBy}` : ''}. It is never shown again.`
            : 'None. Nothing can be planned or applied until one is set.'}
        </p>
        <p className="tw:mt-1 tw:max-w-3xl tw:text-xs tw:text-tertiary">
          A login with CREATEROLE that is not a superuser, and not the read-only account the query
          proxy uses. It needs to own, or hold GRANT OPTION on, the tables the policies bind.
        </p>
      </div>

      <div aria-label="How to give the account" className="tw:flex tw:gap-1" role="group">
        <Button
          aria-pressed={!byReference}
          color={byReference ? 'secondary' : 'primary'}
          onPress={() => setByReference(false)}
          size="sm">
          Login and password
        </Button>
        <Button
          aria-pressed={byReference}
          color={byReference ? 'primary' : 'secondary'}
          onPress={() => setByReference(true)}
          size="sm">
          Reference
        </Button>
      </div>

      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        {byReference ? (
          <TextField
            ariaLabel="Credential reference"
            className="tw:w-96"
            onChange={setReference}
            placeholder="vault://… · azurekeyvault://… · env:NAME"
            value={reference}
          />
        ) : (
          <>
            <TextField
              ariaLabel="Push login"
              className="tw:w-56"
              onChange={setUsername}
              placeholder="Login"
              value={username}
            />
            <TextField
              ariaLabel="Push password"
              className="tw:w-56"
              onChange={setPassword}
              placeholder="Password"
              type="password"
              value={password}
            />
          </>
        )}
        <Button
          color="primary"
          isDisabled={!ready || save.isPending}
          onPress={() => save.mutate()}
          size="sm">
          {save.isPending ? 'Saving…' : credential.configured ? 'Replace' : 'Save'}
        </Button>
        {credential.configured && !confirmRemove && (
          <Button color="secondary-destructive" onPress={() => setConfirmRemove(true)} size="sm">
            Remove
          </Button>
        )}
        {confirmRemove && (
          <>
            <span className="tw:text-xs tw:text-error-primary">
              Without it nothing can be planned or applied, and the sweep stops taking people
              out.
            </span>
            <Button color="secondary" onPress={() => setConfirmRemove(false)} size="sm">
              Cancel
            </Button>
            <Button
              color="primary-destructive"
              isDisabled={remove.isPending}
              onPress={() => remove.mutate()}
              size="sm">
              Remove the account
            </Button>
          </>
        )}
      </div>

      {(save.error ?? remove.error) && (
        <Notice tone="error">
          {apiErrorMessage(save.error ?? remove.error, 'The push account was not changed.')}
        </Notice>
      )}
      {done && <Notice tone="success">{done}</Notice>}
    </div>
  );
}

function Logins({ source }: { source: NativeSource }) {
  const client = useQueryClient();
  const [username, setUsername] = useState('');
  const [login, setLogin] = useState('');

  const logins = useQuery({
    queryKey: ['native-logins', source.id],
    queryFn: () => fetchNativeLogins(source.id),
  });

  const refresh = () => {
    void client.invalidateQueries({ queryKey: ['native-logins', source.id] });
    void client.invalidateQueries({ queryKey: ['native-sources'] });
    void client.invalidateQueries({ queryKey: ['native-source-history', source.id] });
  };

  const map = useMutation({
    mutationFn: () => mapNativeLogin(source.id, username.trim(), login.trim()),
    onSuccess: () => {
      setUsername('');
      setLogin('');
      refresh();
    },
  });

  const unmap = useMutation({
    mutationFn: (who: string) => unmapNativeLogin(source.id, who),
    onSuccess: () => refresh(),
  });

  return (
    <div className="tw:flex tw:flex-col tw:gap-3">
      <div>
        <p className="tw:text-xs tw:font-medium tw:text-secondary">Database logins</p>
        <p className="tw:mt-1 tw:max-w-3xl tw:text-xs tw:text-tertiary">
          Which login on {source.database} belongs to which person. ARAK never creates logins; the
          login must already exist. Nobody can map their own.
        </p>
      </div>

      {logins.isLoading && <p className="tw:text-sm tw:text-tertiary">Loading…</p>}
      {logins.data && logins.data.length === 0 && (
        <p className="tw:text-xs tw:text-tertiary">No login is mapped yet.</p>
      )}
      {logins.data && logins.data.length > 0 && (
        <table className="tw:w-full tw:max-w-3xl tw:text-left tw:text-xs">
          <thead className="tw:text-quaternary">
            <tr>
              <th className="tw:py-1 tw:pr-4 tw:font-medium">Person</th>
              <th className="tw:py-1 tw:pr-4 tw:font-medium">Login</th>
              <th className="tw:py-1 tw:font-medium">
                <span className="tw:sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody className="tw:text-tertiary">
            {logins.data.map((row) => (
              <tr className="tw:border-t tw:border-secondary" key={`${row.principalId}-${row.login}`}>
                <td className="tw:py-1.5 tw:pr-4">
                  {row.displayName ? `${row.displayName} (${row.username})` : row.username}
                </td>
                <td className="tw:py-1.5 tw:pr-4 tw:font-mono">{row.login}</td>
                <td className="tw:py-1.5 tw:text-right">
                  <Button
                    aria-label={`Unmap ${row.username}`}
                    color="tertiary"
                    isDisabled={unmap.isPending}
                    onPress={() => unmap.mutate(row.username)}
                    size="sm">
                    Unmap
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <TextField
          ariaLabel="ARAK username"
          className="tw:w-56"
          onChange={setUsername}
          placeholder="ARAK username"
          value={username}
        />
        <TextField
          ariaLabel="Database login"
          className="tw:w-56"
          onChange={setLogin}
          placeholder="Login on the source"
          value={login}
        />
        <Button
          color="secondary"
          isDisabled={!username.trim() || !login.trim() || map.isPending}
          onPress={() => map.mutate()}
          size="sm">
          Map
        </Button>
      </div>

      {(logins.error ?? map.error ?? unmap.error) && (
        <Notice tone="error">
          {apiErrorMessage(logins.error ?? map.error ?? unmap.error, 'The logins were not changed.')}
        </Notice>
      )}
    </div>
  );
}

function AuditTable({
  queryKey,
  queryFn,
  empty,
}: {
  queryKey: unknown[];
  queryFn: () => Promise<NativeAuditEntry[]>;
  empty: string;
}) {
  const { data, error, isLoading } = useQuery({ queryKey, queryFn });

  return (
    <div className="tw:border-t tw:border-secondary tw:px-5 tw:py-4">
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading…</p>}
      {error && (
        <Notice tone="error">{apiErrorMessage(error, 'The history could not be loaded.')}</Notice>
      )}
      {data && data.length === 0 && <p className="tw:text-sm tw:text-tertiary">{empty}</p>}
      {data && data.length > 0 && (
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
              {data.map((entry) => (
                <tr className="tw:border-t tw:border-secondary tw:align-top" key={entry.id}>
                  <td className="tw:whitespace-nowrap tw:py-1.5 tw:pr-4">
                    {formatWhen(entry.occurredAt)}
                  </td>
                  <td className="tw:py-1.5 tw:pr-4">{entry.actor}</td>
                  <td className="tw:py-1.5 tw:pr-4">{actionLabel(entry.action)}</td>
                  <td className="tw:py-1.5 tw:pr-4">
                    <Badge color={outcomeColor(entry.outcome)} size="sm" type="pill-color">
                      {entry.outcome.replaceAll('_', ' ').toLowerCase()}
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

function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <p className="tw:mb-1 tw:text-xs tw:font-medium tw:text-secondary">{title}</p>
      {children}
    </div>
  );
}

function Messages({
  items,
  tone,
  title,
}: {
  items: string[];
  tone: 'error' | 'warning';
  title?: string;
}) {
  const colours =
    tone === 'error'
      ? 'tw:bg-utility-error-50 tw:text-error-primary'
      : 'tw:bg-utility-warning-50 tw:text-warning-primary';
  return (
    <div className="tw:flex tw:flex-col tw:gap-2">
      {title && <p className={`tw:text-sm tw:font-medium ${tone === 'error' ? 'tw:text-error-primary' : 'tw:text-warning-primary'}`}>{title}</p>}
      <ul className="tw:flex tw:flex-col tw:gap-2">
        {items.map((item) => (
          <li
            className={`tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:px-3 tw:py-2 tw:text-sm ${colours}`}
            key={item}>
            <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
            <span>{item}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Notes({ notes }: { notes: string[] }) {
  if (notes.length === 0) {
    return null;
  }
  return (
    <ul className="tw:list-disc tw:pl-5 tw:text-xs tw:text-tertiary">
      {notes.map((note) => (
        <li key={note}>{note}</li>
      ))}
    </ul>
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

function Notice({ tone, children }: { tone: 'error' | 'success'; children: ReactNode }) {
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

/** Roles are pushed only to a source whose one mode is native source config. */
export function isNative(source: NativeSource): boolean {
  return source.mode === 'NATIVE_CONFIG';
}

/** The mode a source is set to, as the Sources screen names it. */
export function modeTitle(mode: string | null): string {
  if (!mode || mode === 'NONE') {
    return 'no mode';
  }
  return MODES.find((entry) => entry.mode === mode)?.title ?? mode;
}

function count(n: number, noun: string): string {
  return `${n.toLocaleString()} ${noun}${n === 1 ? '' : 's'}`;
}

export function levelLabel(level: NativeLevel): string {
  return level === 'BROWSE' ? 'Browse' : 'Read';
}

export function statusLabel(status: NativeStatus | undefined): string {
  switch (status) {
    case 'APPLIED':
      return 'Applied';
    case 'DRIFTED':
      return 'Drifted';
    case 'FAILED':
      return 'Failed';
    case 'PENDING':
      return 'Behind the policy';
    case 'ROLLED_BACK':
      return 'Rolled back';
    default:
      return 'No role yet';
  }
}

function statusColor(status: NativeStatus | undefined) {
  switch (status) {
    case 'APPLIED':
      return 'success' as const;
    case 'DRIFTED':
    case 'PENDING':
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
    case 'IN_SYNC':
    case 'CHANGED':
      return 'success' as const;
    case 'STALE':
    case 'REFUSED':
    case 'DRIFTED':
    case 'PENDING':
      return 'warning' as const;
    case 'FAILED':
      return 'error' as const;
    default:
      return 'gray' as const;
  }
}

export function actionLabel(action: string): string {
  switch (action) {
    case 'DRY_RUN':
      return 'Plan';
    case 'APPLY':
      return 'Apply';
    case 'DRIFT_CHECK':
      return 'Check';
    case 'ROLLBACK':
      return 'Roll back';
    case 'EXPIRE':
      return 'Sweep';
    case 'CONFIGURE':
      return 'Setup';
    default:
      return action.replaceAll('_', ' ').toLowerCase();
  }
}

function formatWhen(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}
