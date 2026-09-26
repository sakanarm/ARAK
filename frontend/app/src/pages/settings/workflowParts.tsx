import { useQuery } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Plus, Trash01, XClose } from '@untitledui/icons';
import { fetchPrincipals } from '../../api/governance';
import {
  describeOnReject,
  explainOnReject,
  type OnReject,
  type Seat,
  type SeatKind,
  type StageRule,
} from '../../api/accessRequests';
import { joinOf, stepsOf, type AccessWorkflow, type WorkflowDraft, type WorkflowStage } from '../../api/accessWorkflows';
import { FIELD, Select, type SelectOption } from '../policies/controls';

/**
 * The pieces a workflow is written with, shared by the list of workflows and
 * the builder: the stage and seat editors, and the checks the server makes
 * again, said the same way, so the save button can say what is missing
 * before anybody presses it.
 */

const RULES: SelectOption[] = [
  { value: 'ANY', label: 'Any one approves' },
  { value: 'ALL', label: 'Everyone asked approves' },
  { value: 'AT_LEAST', label: 'At least a number approve' },
];

/**
 * The two answers that differ. FIRST_RESPONSE is what VETO already does when
 * any one approval passes, so it is read but no longer offered.
 */
const ON_REJECT: OnReject[] = ['VETO', 'QUORUM'];

const SEAT_KINDS: SelectOption[] = [
  { value: 'ASSET_OWNERS', label: 'Owners of the table', hint: 'As OpenMetadata records them' },
  { value: 'DATA_STEWARD', label: 'Data steward', hint: "The table's dataSteward property" },
  { value: 'DATA_CUSTODIAN', label: 'Data custodian', hint: "The table's dataCustodian property" },
  { value: 'USER', label: 'A person', hint: 'By username or email' },
  { value: 'TEAM', label: 'A team', hint: 'Everyone in it, nested teams too' },
  { value: 'ROLE', label: 'An app role', hint: 'Whoever holds it over the table' },
];

/** Not REQUESTER: everybody holds it, and a stage anybody may pass is no stage. */
const ROLES: SelectOption[] = [
  { value: 'DATA_OWNER', label: 'Data owner' },
  { value: 'POLICY_AUTHOR', label: 'Policy author' },
  { value: 'AUDITOR', label: 'Auditor' },
  { value: 'PLATFORM_ADMIN', label: 'Platform administrator' },
];

export const NAMED: ReadonlySet<SeatKind> = new Set<SeatKind>(['USER', 'TEAM', 'ROLE']);

export const MAX_STAGES = 12;
export const MAX_SEATS = 25;

/** The first thing the server would refuse, said the same way, or null. */
export function problemOf(draft: WorkflowDraft, scopeRequired: boolean): string | null {
  if (!draft.name.trim()) return 'Name the workflow';
  if (scopeRequired && !draft.scopeFqn) return 'Choose the tables it applies to';
  if (draft.stages.length === 0) return 'A workflow needs at least one stage';
  const names = new Set<string>();
  for (const stage of draft.stages) {
    const label = stage.name.trim();
    if (!label) return 'Name every stage';
    const where = `Stage "${label}"`;
    if (names.has(label.toLowerCase())) return `${where} appears twice; give each stage its own name`;
    names.add(label.toLowerCase());
    if (!Number.isInteger(stage.step) || stage.step < 1) return `${where}: steps count from 1`;
    if (stage.approvers.length === 0) return `${where}: name at least one approver`;
    const unnamed = stage.approvers.find((seat) => NAMED.has(seat.kind) && !seat.name?.trim());
    if (unnamed) return `${where}: name the ${unnamed.kind === 'TEAM' ? 'team' : unnamed.kind === 'ROLE' ? 'role' : 'person'}`;
    if (stage.rule === 'AT_LEAST' && !(stage.minApprovals && stage.minApprovals >= 1)) {
      return `${where}: say how many approvals, at least 1`;
    }
  }
  const unnamed = (draft.configurers ?? []).find((seat) => NAMED.has(seat.kind) && !seat.name?.trim());
  if (unnamed) return 'Configured by: name every person, team and role';
  return null;
}

/** "Owner approval, then Security and Compliance together": how the stages will run. */
export function describeSteps(stages: WorkflowStage[]): string {
  const named = stages.filter((stage) => stage.name.trim());
  if (named.length === 0) return '';
  return stepsOf(named)
    .map((group) =>
      group.length === 1
        ? group[0].name.trim()
        : joinOf(group) === 'ANY'
          ? `${group.map((stage) => stage.name.trim()).join(' or ')}, whichever passes first`
          : `${group.map((stage) => stage.name.trim()).join(' and ')} together`
    )
    .join(', then ');
}

export function cleanSeats(seats: Seat[]): Seat[] {
  return seats.map((seat) =>
    NAMED.has(seat.kind) ? { kind: seat.kind, name: seat.name?.trim() ?? '' } : { kind: seat.kind }
  );
}

/** What the editor holds, turned into what the server keeps. */
export function draftOf(workflow: AccessWorkflow): WorkflowDraft {
  // One join per step, read off its first stage, as the server keeps it.
  const joins = new Map(stepsOf(workflow.stages).map((group) => [group[0].step, joinOf(group)]));
  return {
    name: workflow.name.trim(),
    description: workflow.description?.trim() || null,
    scopeFqn: workflow.scopeFqn,
    enabled: workflow.enabled,
    stages: workflow.stages.map((stage) => ({
      step: stage.step,
      name: stage.name.trim(),
      rule: stage.rule,
      minApprovals: stage.rule === 'AT_LEAST' ? (stage.minApprovals ?? null) : null,
      onReject: stage.onReject,
      approvers: cleanSeats(stage.approvers),
      join: joins.get(stage.step) ?? 'ALL',
    })),
    configurers: cleanSeats(workflow.configurers),
  };
}

/** A fresh stage: any one owner of the table approves. */
export function newStage(step: number, name: string): WorkflowStage {
  return { step, name, rule: 'ANY', minApprovals: null, onReject: 'VETO', approvers: [{ kind: 'ASSET_OWNERS' }] };
}

export function StageEditor({
  stage,
  index,
  onChange,
  onRemove,
}: {
  stage: WorkflowStage;
  index: number;
  onChange: (next: Partial<WorkflowStage>) => void;
  onRemove?: () => void;
}) {
  const n = index + 1;
  const onRejectOptions: SelectOption[] = ON_REJECT.map((value) => ({
    value,
    label: describeOnReject(value, stage.rule, stage.minApprovals),
  }));
  // FIRST_RESPONSE, from before, does what VETO does.
  const onReject: OnReject = stage.onReject === 'QUORUM' ? 'QUORUM' : 'VETO';
  return (
    <div
      aria-label={`Stage ${n}`}
      className="tw:flex tw:flex-col tw:gap-3 tw:rounded-lg tw:border tw:border-secondary tw:px-3 tw:py-3"
      role="group">
      <div className="tw:flex tw:flex-wrap tw:items-end tw:gap-3">
        <label className="tw:flex tw:w-20 tw:flex-col tw:gap-1.5">
          <span className="tw:text-xs tw:font-medium tw:text-secondary">Step</span>
          <input
            aria-label={`Stage ${n} step`}
            className={FIELD}
            inputMode="numeric"
            onChange={(event) => {
              const digits = event.target.value.replace(/[^0-9]/g, '');
              onChange({ step: digits === '' ? 0 : Number.parseInt(digits, 10) });
            }}
            value={stage.step === 0 ? '' : String(stage.step)}
          />
        </label>
        <label className="tw:flex tw:min-w-48 tw:flex-1 tw:flex-col tw:gap-1.5">
          <span className="tw:text-xs tw:font-medium tw:text-secondary">Stage name</span>
          <input
            aria-label={`Stage ${n} name`}
            className={FIELD}
            onChange={(event) => onChange({ name: event.target.value })}
            value={stage.name}
          />
        </label>
        {onRemove && (
          <Button color="secondary" iconLeading={Trash01} onPress={onRemove} size="sm">
            Remove stage
          </Button>
        )}
      </div>
      <div className="tw:flex tw:flex-wrap tw:items-end tw:gap-3">
        <div className="tw:flex tw:min-w-56 tw:flex-col tw:gap-1.5">
          <span className="tw:text-xs tw:font-medium tw:text-secondary">Passes when</span>
          <Select
            ariaLabel={`Stage ${n} rule`}
            onChange={(value) => {
              const rule = value as StageRule;
              onChange({
                rule,
                minApprovals: rule === 'AT_LEAST' ? (stage.minApprovals ?? 2) : null,
                // Everyone approving leaves a rejection nothing to wait for.
                onReject: rule === 'ALL' || stage.onReject === 'FIRST_RESPONSE' ? 'VETO' : stage.onReject,
              });
            }}
            options={RULES}
            value={stage.rule}
          />
        </div>
        {stage.rule === 'AT_LEAST' && (
          <label className="tw:flex tw:w-24 tw:flex-col tw:gap-1.5">
            <span className="tw:text-xs tw:font-medium tw:text-secondary">How many</span>
            <input
              aria-label={`Stage ${n} approvals needed`}
              className={FIELD}
              inputMode="numeric"
              onChange={(event) => {
                const digits = event.target.value.replace(/[^0-9]/g, '');
                onChange({ minApprovals: digits === '' ? null : Number.parseInt(digits, 10) });
              }}
              value={stage.minApprovals == null ? '' : String(stage.minApprovals)}
            />
          </label>
        )}
        <div className="tw:flex tw:min-w-72 tw:flex-1 tw:flex-col tw:gap-1.5">
          <span className="tw:text-xs tw:font-medium tw:text-secondary">If someone says no</span>
          {stage.rule === 'ALL' ? (
            <span className="tw:py-2 tw:text-sm tw:text-primary">{describeOnReject('VETO', 'ALL')}</span>
          ) : (
            <Select
              ariaLabel={`Stage ${n} on reject`}
              onChange={(value) => onChange({ onReject: value as OnReject })}
              options={onRejectOptions}
              value={onReject}
            />
          )}
          <span className="tw:text-xs tw:text-tertiary">
            {explainOnReject(onReject, stage.rule, stage.minApprovals)}
          </span>
        </div>
      </div>
      <div className="tw:flex tw:flex-col tw:gap-1.5">
        <span className="tw:text-xs tw:font-medium tw:text-secondary">Asks</span>
        <SeatsEditor
          label={`Stage ${n} approver`}
          onChange={(approvers) => onChange({ approvers })}
          seats={stage.approvers}
        />
      </div>
    </div>
  );
}

export function SeatsEditor({
  seats,
  onChange,
  label,
}: {
  seats: Seat[];
  onChange: (seats: Seat[]) => void;
  /** Names each control: "Stage 1 approver 2 kind". */
  label: string;
}) {
  const set = (index: number, seat: Seat) => onChange(seats.map((s, i) => (i === index ? seat : s)));
  return (
    <div className="tw:flex tw:flex-col tw:gap-2">
      {seats.map((seat, index) => {
        const where = `${label} ${index + 1}`;
        return (
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2" key={index}>
            <Select
              ariaLabel={`${where} kind`}
              className="tw:w-56"
              onChange={(value) => {
                const kind = value as SeatKind;
                set(index, { kind, name: kind === 'ROLE' ? 'DATA_OWNER' : NAMED.has(kind) ? '' : null });
              }}
              options={SEAT_KINDS}
              value={seat.kind}
            />
            {seat.kind === 'ROLE' && (
              <Select
                ariaLabel={`${where} role`}
                className="tw:w-56"
                onChange={(value) => set(index, { kind: 'ROLE', name: value })}
                options={ROLES}
                value={seat.name ?? 'DATA_OWNER'}
              />
            )}
            {(seat.kind === 'USER' || seat.kind === 'TEAM') && (
              <NameField
                ariaLabel={`${where} name`}
                kind={seat.kind}
                onChange={(name) => set(index, { kind: seat.kind, name })}
                value={seat.name ?? ''}
              />
            )}
            <button
              aria-label={`Remove ${where}`}
              className="tw:flex tw:size-8 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-md tw:text-quaternary tw:hover:bg-secondary tw:hover:text-secondary"
              onClick={() => onChange(seats.filter((_, i) => i !== index))}
              type="button">
              <XClose className="tw:size-4" />
            </button>
          </div>
        );
      })}
      <div>
        <Button
          color="secondary"
          iconLeading={Plus}
          isDisabled={seats.length >= MAX_SEATS}
          onPress={() => onChange([...seats, { kind: 'USER', name: '' }])}
          size="sm">
          {`Add ${label.toLowerCase().replace(/^stage \d+ /, '')}`}
        </Button>
      </div>
    </div>
  );
}

/**
 * A username or a team, typed, with the directory's matches offered.
 *
 * <p>Free text because a seat is matched by name when its step opens -- a
 * person who joins after the workflow is saved is still found -- but the
 * suggestions come from the directory, so a typo is the exception.
 */
function NameField({
  kind,
  value,
  onChange,
  ariaLabel,
}: {
  kind: 'USER' | 'TEAM';
  value: string;
  onChange: (value: string) => void;
  ariaLabel: string;
}) {
  const search = value.trim();
  const { data } = useQuery({
    queryKey: ['workflow-seat-names', kind, search],
    queryFn: () => fetchPrincipals({ type: kind === 'USER' ? 'USER' : 'GROUP', search, limit: 8 }),
    enabled: search.length > 0,
    staleTime: 30 * 1000,
  });
  const listId = `${ariaLabel.replace(/\s+/g, '-').toLowerCase()}-options`;
  return (
    <>
      <input
        aria-label={ariaLabel}
        className={`${FIELD} tw:w-56`}
        list={listId}
        onChange={(event) => onChange(event.target.value)}
        placeholder={kind === 'USER' ? 'Username or email' : 'Team name'}
        value={value}
      />
      <datalist id={listId}>
        {(data ?? []).map((principal) => (
          <option key={principal.id} value={principal.username}>
            {principal.displayName ?? principal.username}
          </option>
        ))}
      </datalist>
    </>
  );
}
