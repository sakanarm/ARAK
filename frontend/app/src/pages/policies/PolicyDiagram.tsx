import {
  CheckCircle,
  Columns03,
  Database01,
  FilterLines,
  MinusCircle,
  PlayCircle,
  ShieldTick,
  SlashCircle01,
  Users01,
  XCircle,
} from '@untitledui/icons';
import type { Policy } from '../../generated/entity/policy/policy';
import FlowDiagram, {
  type DiagramEdge,
  type DiagramNode,
  type DiagramTone,
} from '../../components/diagram/FlowDiagram';
import { buildFlow, LANE_TITLES, type FlowStep, type FlowTone } from './policyFlow';

/**
 * The policy drawn the way the access workflow builder draws a workflow: left
 * to right on a dotted canvas, each gate a node with its two ways out.
 *
 * <p>It reads the same {@link buildFlow} model as the flowchart, so the two
 * cannot disagree about what a policy says; only the drawing differs. A gate's
 * yes goes on to the next node; its no goes down to the one end every "no"
 * reaches -- the policy does not apply -- which sits under the flow, to the
 * right of the gates, so the strokes run along the lane below and never cross
 * a node.
 *
 * <p>Each node is named after the {@link FlowStep} it draws, so the builder can
 * send a click on it to the step of the form that writes it.
 */

export const ENTRY = 'entry';
export const EXIT = 'exit';
export const SKIPPED = 'skipped';

const ICON = 'tw:size-4';

const TONE: Record<FlowTone, DiagramTone> = {
  set: 'default',
  empty: 'muted',
  open: 'warning',
  deny: 'error',
};

function iconOf(step: FlowStep) {
  if (step.id === 'gate-which') return <Database01 className={ICON} />;
  if (step.id === 'gate-who') return <Users01 className={ICON} />;
  if (step.id.startsWith('outcome-row')) return <FilterLines className={ICON} />;
  if (step.id.startsWith('outcome-column')) return <Columns03 className={ICON} />;
  if (step.tone === 'deny') return <SlashCircle01 className={ICON} />;
  if (step.tone === 'empty') return <MinusCircle className={ICON} />;
  return <ShieldTick className={ICON} />;
}

/** The last node's short name; the flowchart's sentence says the rest. */
function exitTitle(policy: Policy): string {
  if (policy.policyType === 'SUBSCRIPTION') {
    return policy.effect === 'DENY' ? 'Nothing is read' : 'On to data policies';
  }
  return 'They see the rest';
}

export function policyDiagram(policy: Policy): {
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  /** The form step each node is written in, for the builder. */
  formSteps: Record<string, number>;
} {
  const flow = buildFlow(policy);
  const formSteps: Record<string, number> = {};
  const nodes: DiagramNode[] = [
    {
      id: ENTRY,
      kind: 'start',
      column: 0,
      row: 0,
      title: 'Read requested',
      icon: <PlayCircle className={ICON} />,
      selectable: false,
    },
  ];
  const edges: DiagramEdge[] = [];

  let from = ENTRY;
  let saying: Pick<DiagramEdge, 'label' | 'tone'> = {};
  // The first column past the gates: the "no" lane ends there.
  let afterGates = 0;
  let anyNo = false;

  flow.steps.forEach((step, index) => {
    const column = index + 1;
    const lines = step.items.length
      ? step.items.map((item) => ({ text: item.text, caution: item.caution }))
      : step.note
        ? [{ text: step.note }]
        : [];
    nodes.push({
      id: step.id,
      kind: 'task',
      column,
      row: 0,
      eyebrow: step.kind === 'gate' ? `${LANE_TITLES[step.lane]} · check` : LANE_TITLES[step.lane],
      title: step.title,
      tone: TONE[step.tone],
      icon: iconOf(step),
      lines,
      selectable: step.step !== undefined,
    });
    if (step.step !== undefined) formSteps[step.id] = step.step;
    edges.push({ from, to: step.id, ...saying });
    from = step.id;
    saying = {};
    if (step.kind === 'gate') {
      afterGates = column + 1;
      if (step.otherwise) {
        anyNo = true;
        edges.push({ from: step.id, to: SKIPPED, label: 'No', tone: 'neutral', route: 'down', dashed: true });
        saying = { label: 'Yes', tone: 'success' };
      }
    }
  });

  const last = flow.steps.length + 1;
  const denied = policy.policyType === 'SUBSCRIPTION' && policy.effect === 'DENY';
  nodes.push({
    id: EXIT,
    kind: 'end',
    column: last,
    row: 0,
    title: exitTitle(policy),
    tone: denied ? 'error' : 'success',
    icon: denied ? <XCircle className={ICON} /> : <CheckCircle className={ICON} />,
    selectable: false,
  });
  edges.push({ from, to: EXIT, ...saying });

  if (anyNo) {
    nodes.push({
      id: SKIPPED,
      kind: 'end',
      column: Math.min(afterGates, last),
      row: 0.7,
      title: 'Policy does not apply',
      tone: 'muted',
      icon: <MinusCircle className={ICON} />,
      selectable: false,
    });
  }
  return { nodes, edges, formSteps };
}

export interface PolicyDiagramProps {
  policy: Policy;
  /** Given in the builder: a click on a node opens the step that writes it. */
  onEdit?: (form: number) => void;
}

export default function PolicyDiagram({ policy, onEdit }: PolicyDiagramProps) {
  const flow = buildFlow(policy);
  const { nodes, edges, formSteps } = policyDiagram(policy);
  const noes = flow.steps.filter((step) => step.otherwise);

  return (
    <div className="tw:flex tw:flex-col tw:gap-3">
      <FlowDiagram
        edges={edges}
        label={`How ${policy.displayName || policy.name || 'this policy'} decides`}
        nodes={nodes}
        onSelect={
          onEdit
            ? (id) => {
                const form = formSteps[id];
                if (form !== undefined) onEdit(form);
              }
            : undefined
        }
        overlay={
          <span className="tw:inline-flex tw:items-center tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-2.5 tw:py-1 tw:text-xs tw:text-tertiary tw:shadow-xs">
            <span className="tw:font-semibold tw:text-secondary">{flow.kind}</span>
            <span aria-hidden="true">·</span>
            <span>{flow.scope}</span>
            {onEdit && (
              <>
                <span aria-hidden="true">·</span>
                <span>Click a step to edit it</span>
              </>
            )}
          </span>
        }
      />

      {noes.length > 0 && (
        <dl className="tw:grid tw:gap-2 tw:text-sm tw:sm:grid-cols-2">
          {noes.map((step) => (
            <div
              className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2"
              key={step.id}>
              <dt className="tw:text-xs tw:font-medium tw:text-quaternary">
                If not · {step.title}
              </dt>
              <dd className="tw:text-secondary">{step.otherwise}</dd>
            </div>
          ))}
        </dl>
      )}

      <p
        className={`tw:rounded-xl tw:border tw:p-3 tw:text-sm ${
          flow.gateOpen
            ? 'tw:border-warning tw:bg-warning-primary tw:text-warning-primary'
            : 'tw:border-secondary tw:bg-secondary tw:text-secondary'
        }`}>
        {flow.gate}
      </p>
    </div>
  );
}
