import { CheckCircle, Dataflow03, PlayCircle, Settings01, UserCheck01, XCircle } from '@untitledui/icons';
import { describeOnReject, describeRule, describeSeat } from '../../api/accessRequests';
import { joinOf, stepsOf, type AccessWorkflow } from '../../api/accessWorkflows';
import type { DiagramEdge, DiagramNode } from '../../components/diagram/FlowDiagram';

/**
 * A workflow as the builder draws it: submitted, then each step's stages side
 * by side, then whoever configures the access, then access in place.
 *
 * <p>A step with one stage says its two ways out itself: approve goes on,
 * reject goes down to the one end every refusal reaches. A step whose stages
 * run together meets at a decision first, where the step's join is said once:
 * either the request goes on only when all of them approve and stops when any
 * one refuses, or the first to approve moves it on and it stops only when
 * every one refuses.
 *
 * <p>Stage nodes are named {@code stage-<index>} by their place in
 * {@code workflow.stages}, so the inspector edits the stage that was clicked.
 */

export const START = 'start';
export const CONFIGURE = 'configure';
export const END = 'end';
export const REJECTED = 'rejected';

export function stageNodeId(index: number): string {
  return `stage-${index}`;
}

/** The index a stage node stands for, or null for any other node. */
export function stageIndexOf(id: string | null): number | null {
  const match = id ? /^stage-(\d+)$/.exec(id) : null;
  return match ? Number(match[1]) : null;
}

const ICON = 'tw:size-4';

export function workflowDiagram(workflow: AccessWorkflow): { nodes: DiagramNode[]; edges: DiagramEdge[] } {
  const indexed = workflow.stages.map((stage, index) => ({ ...stage, index }));
  const groups = stepsOf(indexed);
  const widest = Math.max(1, ...groups.map((group) => group.length));
  const main = (widest - 1) / 2;

  const nodes: DiagramNode[] = [
    {
      id: START,
      kind: 'start',
      column: 0,
      row: main,
      title: 'Request submitted',
      icon: <PlayCircle className={ICON} />,
    },
  ];
  const edges: DiagramEdge[] = [];

  let column = 1;
  // What the next step is reached from, and what that edge says.
  let from = [START];
  let saying: { label?: string; tone?: DiagramEdge['tone'] } = {};

  groups.forEach((group, groupIndex) => {
    const together = group.length > 1;
    const anyOne = joinOf(group) === 'ANY';
    group.forEach((stage, i) => {
      const id = stageNodeId(stage.index);
      nodes.push({
        id,
        kind: 'task',
        column,
        row: main - (group.length - 1) / 2 + i,
        eyebrow: `Step ${groupIndex + 1}${together ? (anyOne ? ' · any one' : ' · together') : ''}`,
        title: stage.name.trim() || 'Unnamed stage',
        icon: <UserCheck01 className={ICON} />,
        lines: [
          { text: `Asks ${stage.approvers.map(describeSeat).join(', ') || 'nobody yet'}`, caution: stage.approvers.length === 0 },
          { text: describeRule(stage.rule, stage.minApprovals ?? null) },
          { text: describeOnReject(stage.onReject, stage.rule, stage.minApprovals) },
        ],
      });
      from.forEach((source, n) =>
        edges.push({ from: source, to: id, ...(n === 0 && i === 0 ? saying : { tone: saying.tone }) })
      );
    });

    if (together) {
      const join = `join-${group[0].step}`;
      nodes.push({
        id: join,
        kind: 'join',
        column: column + 1,
        row: main,
        title: anyOne ? `Any one of step ${groupIndex + 1} is enough` : `All of step ${groupIndex + 1} decide`,
        icon: <Dataflow03 className={ICON} />,
        selectable: false,
      });
      group.forEach((stage) => edges.push({ from: stageNodeId(stage.index), to: join }));
      edges.push({ from: join, to: REJECTED, label: anyOne ? 'All reject' : 'Any rejects', tone: 'error', route: 'down' });
      from = [join];
      saying = { label: anyOne ? 'Any one approves' : 'All approve', tone: 'success' };
      column += 2;
    } else {
      const id = stageNodeId(group[0].index);
      edges.push({ from: id, to: REJECTED, label: 'Reject', tone: 'error', route: 'down' });
      from = [id];
      saying = { label: 'Approve', tone: 'success' };
      column += 1;
    }
  });

  nodes.push({
    id: CONFIGURE,
    kind: 'task',
    column,
    row: main,
    eyebrow: 'Once approved',
    title: 'Configure access',
    icon: <Settings01 className={ICON} />,
    lines: [
      {
        text:
          workflow.configurers.length === 0
            ? 'By the owners of the table or its data custodian'
            : `By ${workflow.configurers.map(describeSeat).join(', ')}`,
      },
      { text: 'A grant, or a change to a policy they name' },
    ],
  });
  from.forEach((source) => edges.push({ from: source, to: CONFIGURE, ...saying }));

  nodes.push({
    id: END,
    kind: 'end',
    column: column + 1,
    row: main,
    title: 'Access in place',
    tone: 'success',
    icon: <CheckCircle className={ICON} />,
    selectable: false,
  });
  edges.push({ from: CONFIGURE, to: END });

  if (groups.length > 0) {
    nodes.push({
      id: REJECTED,
      kind: 'end',
      column: column,
      row: widest - 0.3,
      title: 'Rejected',
      tone: 'error',
      icon: <XCircle className={ICON} />,
      selectable: false,
    });
  }
  return { nodes, edges };
}
