import { type ComponentType, type ReactNode, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
  CheckCircle,
  Code02,
  Database01,
  EyeOff,
  Globe01,
  Key01,
  Settings02,
  Table,
} from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage } from '../../api/client';
import {
  ENFORCEMENT_MODES,
  fetchSources,
  type EnforcementMode as SourceMode,
  type Source,
} from '../../api/sources';
import type { Policy } from '../../generated/entity/policy/policy';
import type { AssetSelector } from '../../generated/type/facet';
import { engineLabel, useSourceEngines } from '../../engines';
import { MODES, type EnforcementMode } from './enforcement';

/**
 * Where a new policy runs, chosen before the form opens.
 *
 * The kind, the connection and the enforcement mode are three questions the
 * form used to leave to the side: the kind was a menu inside step one, the
 * connection was whatever step three happened to select, and the mode was a
 * panel in the rail with an engine menu of its own. Asked here first, each
 * answer does one plain thing to what follows:
 *
 * - the kind is the document's policyType;
 * - the connection narrows the selector to that source's service, so the policy
 *   cannot reach a table on another source by accident;
 * - the mode is what the builder checks the policy against as it is written.
 *
 * The mode is not written into the policy. A policy is enforced by the mode its
 * source is set to, and one that only held in a single mode would stop holding
 * the day somebody switched the source -- quietly, which is the failure the
 * capability matrix exists to prevent. So the choice drives the warnings.
 *
 * On one connection only the mode it is set to can be chosen, and choosing the
 * connection chooses it: offering another would let an author check a policy
 * against a mode that will never carry it. Every connection keeps all three,
 * because each connection there brings its own.
 */

export type PolicyKind = Policy['policyType'];

export interface PolicyTarget {
  kind: PolicyKind;
  /** Null for every connection: the selector alone decides. */
  source: Source | null;
  mode: EnforcementMode;
}

/** The service a source's assets are catalogued under, as the importer names it. */
export function serviceOf(source: Source): string {
  const linked = source.omServiceFqn?.trim();
  return linked ? linked : source.name;
}

/** The selector that covers every asset of one source and nothing else. */
export function sourceSelector(source: Source): AssetSelector {
  return { condition: { facet: 'service', operator: 'eq', value: serviceOf(source) } };
}

/**
 * Whether a selector is still exactly what choosing a connection wrote.
 *
 * Only then may choosing another connection replace it. Anything the author
 * added since is theirs, and swapping it for a new source would throw it away.
 */
export function isSourceSelector(selector: AssetSelector | undefined): boolean {
  const condition = selector?.condition;
  return (
    Boolean(selector) &&
    Object.keys(selector!).length === 1 &&
    condition?.facet === 'service' &&
    condition.operator === 'eq' &&
    typeof condition.value === 'string'
  );
}

const KINDS: {
  kind: PolicyKind;
  title: string;
  summary: string;
  icon: ComponentType<{ className?: string }>;
  tone: string;
}[] = [
  {
    kind: 'SUBSCRIPTION',
    title: 'Subscription',
    summary: 'Who gets in: whether somebody reaches the table at all.',
    icon: Key01,
    tone: 'tw:bg-utility-brand-50 tw:text-utility-brand-600',
  },
  {
    kind: 'DATA',
    title: 'Data',
    summary: 'What they see: row filters, masked and hidden columns.',
    icon: EyeOff,
    tone: 'tw:bg-utility-purple-50 tw:text-utility-purple-600',
  },
];

const MODE_ICONS: Record<EnforcementMode, ComponentType<{ className?: string }>> = {
  PROXY: Code02,
  SECURE_VIEW: Table,
  NATIVE_CONFIG: Settings02,
};

/** How a source's current mode reads on its card. */
function modeLabel(mode: SourceMode): string {
  return ENFORCEMENT_MODES.find((entry) => entry.value === mode)?.label ?? mode;
}

function modeColor(mode: SourceMode) {
  switch (mode) {
    case 'NATIVE_CONFIG':
      return 'warning' as const;
    case 'SECURE_VIEW':
      return 'blue' as const;
    case 'PROXY':
      return 'purple' as const;
    default:
      return 'gray' as const;
  }
}

/**
 * The one mode a connection allows here, or null when it allows any.
 *
 * A connection set to nothing yet allows any: the policy is written ahead of
 * the day an administrator picks its mode.
 */
export function lockedMode(source: Source | null): EnforcementMode | null {
  const current = source?.defaultEnforcementMode;
  return current && current !== 'NONE' ? current : null;
}

/**
 * What choosing this mode on this source means, or null when it means nothing
 * more than what the card says.
 */
export function modeNote(source: Source | null, mode: EnforcementMode): string | null {
  const planned =
    mode === 'NATIVE_CONFIG'
      ? 'ARAK does not push native config to a source yet. The policy is written and checked for it, and enforced meanwhile by the mode its source is set to.'
      : null;
  if (!source) {
    return (
      planned ??
      'Each connection enforces with its own mode. This is the one the policy is checked against while you write it.'
    );
  }
  const current = source.defaultEnforcementMode;
  if (current === 'NONE') {
    return `Nothing is enforced on ${source.name} yet. Until an administrator sets its mode under Sources, this policy is written but not applied.`;
  }
  if (current !== mode) {
    return `${source.name} is enforced by ${modeLabel(current).toLowerCase()} today, and stays that way: writing the policy for another mode does not switch the connection. An administrator changes it under Sources.`;
  }
  return planned;
}

export default function PolicyTargetPicker({
  initial,
  onPick,
}: {
  initial: { kind: PolicyKind; sourceId: string | null; mode: EnforcementMode | null };
  onPick: (target: PolicyTarget) => void;
}) {
  const [kind, setKind] = useState<PolicyKind>(initial.kind);
  // Undefined until chosen; null is "every connection", which is a choice.
  const [sourceId, setSourceId] = useState<string | null | undefined>(
    initial.sourceId === null && initial.mode === null ? undefined : initial.sourceId
  );
  const [mode, setMode] = useState<EnforcementMode | null>(initial.mode);

  const sources = useQuery({ queryKey: ['sources'], queryFn: fetchSources, retry: false });
  const { data: engines } = useSourceEngines();

  const source =
    sourceId === undefined || sourceId === null
      ? null
      : (sources.data?.find((entry) => entry.id === sourceId) ?? null);
  const chosen = sourceId === null || source !== null;
  const engine = source ? engines?.find((entry) => entry.id === source.engine) : undefined;
  // The query API refuses a policy it cannot express on the engine, so an
  // engine it has nothing for is not offered as a place to write for it.
  const proxyMissing = Boolean(engine && engine.proxyCapabilities.length === 0);
  const locked = lockedMode(source);
  const ready =
    chosen &&
    mode !== null &&
    !(mode === 'PROXY' && proxyMissing) &&
    (locked === null || mode === locked);

  return (
    <>
      <Link
        className="tw:inline-flex tw:items-center tw:gap-1 tw:text-sm tw:text-tertiary tw:hover:text-primary"
        to="/policies">
        <ArrowLeft className="tw:size-4" />
        Policies
      </Link>

      <header className="tw:mt-4 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5 tw:shadow-xs">
        <h1 className="tw:text-xl tw:font-semibold tw:text-primary">New policy</h1>
        <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">
          Say what kind of policy it is, where it runs and how it will be enforced.
          The form opens after that, with its checks already pointed at the right place.
        </p>
        <ol aria-label="Progress" className="tw:mt-4 tw:flex tw:flex-wrap tw:items-center tw:gap-2 tw:text-sm">
          <li aria-current="step" className="tw:flex tw:items-center tw:gap-2 tw:font-medium tw:text-primary">
            <span className="tw:flex tw:size-6 tw:items-center tw:justify-center tw:rounded-full tw:bg-brand-solid tw:text-xs tw:font-semibold tw:text-white">
              1
            </span>
            Where it runs
          </li>
          <li aria-hidden className="tw:h-px tw:w-8 tw:bg-border-secondary" />
          <li className="tw:flex tw:items-center tw:gap-2 tw:text-tertiary">
            <span className="tw:flex tw:size-6 tw:items-center tw:justify-center tw:rounded-full tw:border tw:border-secondary tw:text-xs tw:font-semibold">
              2
            </span>
            Configure the policy
          </li>
        </ol>
      </header>

      <div className="tw:mt-6 tw:flex tw:flex-col tw:gap-6 tw:pb-28">
        <Section
          description="Subscription and data policies are different jobs, written by different people."
          title="What kind of policy">
          <div className="tw:grid tw:gap-3 tw:sm:grid-cols-2">
            {KINDS.map((entry) => (
              <Choice
                icon={<IconTile icon={entry.icon} tone={entry.tone} />}
                key={entry.kind}
                label={`${entry.title} policy`}
                onSelect={() => setKind(entry.kind)}
                selected={kind === entry.kind}>
                <p className="tw:text-sm tw:font-semibold tw:text-primary">{entry.title}</p>
                <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">{entry.summary}</p>
              </Choice>
            ))}
          </div>
        </Section>

        <Section
          description="The policy covers the assets of the connection chosen here, and step 3 narrows that down. Every connection leaves it to step 3 alone."
          title="Which connection">
          {sources.isError ? (
            <p className="tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
              {apiErrorMessage(sources.error, 'The connections could not be listed.')}
            </p>
          ) : (
            <div className="tw:grid tw:gap-3 tw:sm:grid-cols-2 tw:xl:grid-cols-3">
              <Choice
                icon={<IconTile icon={Globe01} tone="tw:bg-utility-gray-50 tw:text-utility-gray-600" />}
                label="Every connection"
                onSelect={() => setSourceId(null)}
                selected={sourceId === null}>
                <p className="tw:text-sm tw:font-semibold tw:text-primary">Every connection</p>
                <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">
                  Organisation-wide. Whatever step 3 selects, on any source.
                </p>
              </Choice>
              {sources.isPending
                ? [0, 1].map((index) => (
                    <div
                      aria-hidden
                      className="tw:h-28 tw:animate-pulse tw:rounded-xl tw:border tw:border-secondary tw:bg-secondary"
                      key={index}
                    />
                  ))
                : (sources.data ?? []).map((entry) => (
                    <Choice
                      icon={<IconTile icon={Database01} tone="tw:bg-utility-blue-50 tw:text-utility-blue-600" />}
                      key={entry.id}
                      label={entry.name}
                      onSelect={() => {
                        setSourceId(entry.id);
                        const only = lockedMode(entry);
                        const noProxy =
                          engines?.find((known) => known.id === entry.engine)?.proxyCapabilities
                            .length === 0;
                        if (only && !(only === 'PROXY' && noProxy)) setMode(only);
                        else if (only) setMode(null);
                      }}
                      selected={sourceId === entry.id}>
                      <p className="tw:truncate tw:text-sm tw:font-semibold tw:text-primary">{entry.name}</p>
                      <p className="tw:mt-0.5 tw:truncate tw:text-sm tw:text-tertiary">
                        {engineLabel(engines, entry.engine)}
                        {entry.engineVersion ? ` ${entry.engineVersion}` : ''}
                        {' · '}
                        {entry.assetCount} {entry.assetCount === 1 ? 'table' : 'tables'}
                      </p>
                      <div className="tw:mt-2 tw:flex tw:flex-wrap tw:gap-1.5">
                        <Badge color={modeColor(entry.defaultEnforcementMode)} size="sm" type="pill-color">
                          {modeLabel(entry.defaultEnforcementMode)}
                        </Badge>
                        {!entry.enabled && (
                          <Badge color="warning" size="sm" type="pill-color">
                            Disabled
                          </Badge>
                        )}
                      </div>
                    </Choice>
                  ))}
            </div>
          )}
          {sources.data?.length === 0 && (
            <p className="tw:mt-3 tw:text-sm tw:text-tertiary">
              No connection is registered yet. An administrator adds one under{' '}
              <Link className="tw:font-medium tw:text-brand-secondary tw:hover:underline" to="/sources">
                Sources
              </Link>
              ; until then a policy can still be written for every connection.
            </p>
          )}
        </Section>

        <Section
          description={
            source
              ? `What ${source.name} can carry. The builder checks the policy against the mode chosen here as you write it.`
              : 'The builder checks the policy against the mode chosen here as you write it.'
          }
          title="How it will be enforced">
          <div className="tw:grid tw:gap-3 tw:lg:grid-cols-3">
            {MODES.map((entry) => {
              const inUse = source?.defaultEnforcementMode === entry.mode;
              const unavailable = entry.mode === 'PROXY' && proxyMissing;
              const notSet = locked !== null && entry.mode !== locked;
              return (
                <Choice
                  disabled={unavailable || notSet}
                  icon={<IconTile icon={MODE_ICONS[entry.mode]} tone="tw:bg-utility-purple-50 tw:text-utility-purple-600" />}
                  key={entry.mode}
                  label={entry.title}
                  onSelect={() => setMode(entry.mode)}
                  selected={mode === entry.mode}>
                  <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
                    <p className="tw:text-sm tw:font-semibold tw:text-primary">{entry.title}</p>
                    {inUse && (
                      <Badge color="success" size="sm" type="pill-color">
                        In use on this connection
                      </Badge>
                    )}
                    {entry.mode === 'NATIVE_CONFIG' && (
                      <Badge color="gray" size="sm" type="pill-color">
                        Checked, not applied yet
                      </Badge>
                    )}
                    {unavailable && (
                      <Badge color="gray" size="sm" type="pill-color">
                        Not on {engineLabel(engines, source?.engine)}
                      </Badge>
                    )}
                    {notSet && !unavailable && (
                      <Badge color="gray" size="sm" type="pill-color">
                        Not set on this connection
                      </Badge>
                    )}
                  </div>
                  <p className="tw:mt-1 tw:text-sm tw:text-tertiary">{entry.summary}</p>
                  <p className="tw:mt-2 tw:text-xs tw:text-quaternary">Needs: {entry.requirement}.</p>
                </Choice>
              );
            })}
          </div>
          {source && locked && (
            <p className="tw:mt-3 tw:text-sm tw:text-tertiary" data-testid="mode-locked">
              {source.name} is enforced by {modeLabel(locked).toLowerCase()}, so that is the
              mode this policy is written for. An administrator changes a connection's mode
              under{' '}
              <Link className="tw:font-medium tw:text-brand-secondary tw:hover:underline" to="/sources">
                Sources
              </Link>
              .
            </p>
          )}
          {chosen && mode && modeNote(source, mode) && (
            <p className="tw:mt-3 tw:flex tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-3 tw:text-sm tw:text-tertiary">
              <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:flex-none tw:text-warning-primary" />
              {modeNote(source, mode)}
            </p>
          )}
        </Section>
      </div>

      {/* Held at the foot of the window, so the answer so far and the way on
          stay in view however far down the connections run. */}
      <div className="tw:sticky tw:bottom-0 tw:-mx-1 tw:mt-2 tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-3 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4 tw:shadow-lg">
        <p className="tw:min-w-0 tw:text-sm tw:text-tertiary" data-testid="target-summary">
          {chosen ? (
            <>
              <span className="tw:font-medium tw:text-primary">
                {kind === 'DATA' ? 'Data' : 'Subscription'} policy
              </span>
              {' on '}
              <span className="tw:font-medium tw:text-primary">
                {source ? source.name : 'every connection'}
              </span>
              {mode && (
                <>
                  {', enforced by '}
                  <span className="tw:font-medium tw:text-primary">
                    {MODES.find((entry) => entry.mode === mode)?.title}
                  </span>
                </>
              )}
            </>
          ) : (
            'Choose a connection and how it will be enforced.'
          )}
        </p>
        <Button
          iconTrailing={ArrowRight}
          isDisabled={!ready}
          onPress={() => ready && onPick({ kind, source, mode: mode! })}
          size="md">
          Configure the policy
        </Button>
      </div>
    </>
  );
}

function Section({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: ReactNode;
}) {
  return (
    <section aria-label={title}>
      <h2 className="tw:text-md tw:font-semibold tw:text-primary">{title}</h2>
      <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">{description}</p>
      <div className="tw:mt-3">{children}</div>
    </section>
  );
}

function IconTile({
  icon: Icon,
  tone,
}: {
  icon: ComponentType<{ className?: string }>;
  tone: string;
}) {
  return (
    <span className={`tw:flex tw:size-10 tw:flex-none tw:items-center tw:justify-center tw:rounded-lg ${tone}`}>
      <Icon className="tw:size-5" />
    </span>
  );
}

/**
 * One answer, as a card that is pressed rather than a radio beside a label.
 *
 * A button with aria-pressed: each group is a set of these, and a reader
 * hears which one is chosen without a radio group's arrow-key handling, which
 * would make a disabled card awkward to explain.
 */
function Choice({
  label,
  selected,
  disabled,
  onSelect,
  icon,
  children,
}: {
  label: string;
  selected: boolean;
  disabled?: boolean;
  onSelect: () => void;
  icon: ReactNode;
  children: ReactNode;
}) {
  return (
    <button
      aria-label={label}
      aria-pressed={selected}
      className={`tw:relative tw:flex tw:min-w-0 tw:items-start tw:gap-3 tw:rounded-xl tw:border tw:p-4 tw:text-left tw:outline-focus-ring tw:transition tw:focus-visible:outline-2 ${
        disabled
          ? 'tw:cursor-not-allowed tw:border-secondary tw:bg-secondary tw:opacity-60'
          : selected
            ? 'tw:cursor-pointer tw:border-brand tw:bg-brand-primary tw:shadow-md'
            : 'tw:cursor-pointer tw:border-secondary tw:bg-primary tw:shadow-xs tw:hover:border-brand tw:hover:shadow-md'
      }`}
      disabled={disabled}
      onClick={onSelect}
      type="button">
      {icon}
      <div className="tw:min-w-0 tw:flex-1 tw:pr-5">{children}</div>
      {selected && (
        <CheckCircle className="tw:absolute tw:top-3 tw:right-3 tw:size-5 tw:text-brand-secondary" />
      )}
    </button>
  );
}
