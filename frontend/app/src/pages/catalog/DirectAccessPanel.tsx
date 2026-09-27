import { useMutation } from '@tanstack/react-query';
import { RefreshCw01, ShieldTick, Users01, User01 } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Chip as Badge } from '../../components/chips';
import {
  checkDirectAccess,
  type DirectAccessCheck,
  type DirectHolder,
  type DirectVerdict,
} from '../../api/access';
import { apiErrorMessage } from '../../api/client';
import { Panel } from './panels';

/**
 * Who can read this table at the source without going through ARAK (FR-6.3.1).
 *
 * <p>The panels above answer who ARAK lets in. This one answers the question
 * they cannot: who does not need to ask. Under the proxy or a secure view every
 * name here other than ARAK's own login is a way around every policy on the
 * page, so the panel says that in as many words rather than leaving a list of
 * role names to be read as reassurance.
 *
 * <p>Run on demand, not on load: it opens a connection to the source and is
 * written to the enforcement trail each time.
 */
export function DirectAccessPanel({ fqn }: { fqn: string }) {
  const check = useMutation({ mutationFn: () => checkDirectAccess(fqn) });
  const result = check.data;

  return (
    <Panel
      action={
        <Button
          color="secondary"
          iconLeading={result ? RefreshCw01 : ShieldTick}
          isDisabled={check.isPending}
          onPress={() => check.mutate()}
          size="sm">
          {check.isPending ? 'Checking…' : result ? 'Check again' : 'Check at the source'}
        </Button>
      }
      subtitle="Who can read this table with their own database login, without ARAK in the way (FR-6.3.1)"
      title="Outside ARAK">
      {check.isError ? (
        <p className="tw:text-sm tw:text-error-primary">
          {apiErrorMessage(check.error, 'The source could not be asked who holds this table.')}
        </p>
      ) : !result ? (
        <p className="tw:text-sm tw:text-tertiary">
          Reads the source's own permissions: grants on the table, its columns and
          its schema, owners, superusers and reader roles. Nothing in the table is
          read.
        </p>
      ) : (
        <DirectAccessResult result={result} />
      )}
    </Panel>
  );
}

const TONE: Record<DirectVerdict, string> = {
  EXPOSED: 'tw:bg-error-primary tw:text-error-primary',
  CLOSED: 'tw:bg-success-primary tw:text-success-primary',
  OPEN: 'tw:bg-secondary tw:text-secondary',
  NOT_FOUND: 'tw:bg-warning-primary tw:text-warning-primary',
};

function DirectAccessResult({ result }: { result: DirectAccessCheck }) {
  const others = result.holders.filter((holder) => !holder.self);
  const own = result.holders.filter((holder) => holder.self);

  return (
    <div className="tw:space-y-3">
      <div
        className={`tw:rounded-lg tw:p-3 tw:text-pretty tw:text-sm ${TONE[result.verdict]}`}
        data-testid="direct-verdict">
        {result.message}
      </div>
      <p className="tw:text-xs tw:text-quaternary">
        {result.source} · {result.engine} · mode {result.mode}
        {result.secureViewInstalled ? ' · secure view installed' : ''} · read as{' '}
        {result.connectedAs ?? 'unknown'} · {new Date(result.checkedAt).toLocaleString()}
      </p>
      {result.verdict !== 'NOT_FOUND' && (
        <div className="tw:space-y-2">
          {others.length === 0 && (
            <p className="tw:text-sm tw:text-tertiary">
              Nobody but ARAK holds this table at the source.
            </p>
          )}
          {others.map((holder) => (
            <HolderRow guarded={result.guarded} holder={holder} key={holder.name} />
          ))}
          {own.map((holder) => (
            <HolderRow guarded={result.guarded} holder={holder} key={holder.name} />
          ))}
        </div>
      )}
    </div>
  );
}

function HolderRow({ holder, guarded }: { holder: DirectHolder; guarded: boolean }) {
  const role = !holder.login;
  // PUBLIC is every login there is, so its members are not worth listing.
  const everyone = holder.name.toUpperCase() === 'PUBLIC';
  const more = holder.memberCount - holder.members.length;

  return (
    <div className="tw:rounded-lg tw:border tw:border-secondary tw:p-3">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        {role ? (
          <Users01 className="tw:size-4 tw:shrink-0 tw:text-tertiary" />
        ) : (
          <User01 className="tw:size-4 tw:shrink-0 tw:text-tertiary" />
        )}
        <span className="tw:font-mono tw:text-sm tw:text-primary">{holder.name}</span>
        <Badge color="gray" size="sm" type="modern">
          {role ? 'Role' : 'Login'}
        </Badge>
        {holder.via.map((via) => (
          <Badge color="gray" key={via} size="sm" type="modern">
            {via}
          </Badge>
        ))}
        {holder.self ? (
          <Badge color="success" size="sm" type="pill-color">
            ARAK itself
          </Badge>
        ) : (
          guarded && (
            <Badge color="error" size="sm" type="pill-color">
              Bypasses ARAK
            </Badge>
          )
        )}
      </div>
      {role && (
        <p className="tw:mt-1.5 tw:text-xs tw:text-tertiary">
          {everyone
            ? 'Every login on the database.'
            : holder.memberCount === 0
            ? 'No login inherits this role.'
            : `Logins that inherit it: ${holder.members.join(', ')}${
                more > 0 ? ` and ${more} more` : ''
              }`}
        </p>
      )}
    </div>
  );
}
