import {
  CheckCircle,
  Columns03,
  Key01,
  MinusCircle,
  PlayCircle,
  SlashCircle01,
  Users01,
  XCircle,
} from '@untitledui/icons';
import type { DiagramEdge, DiagramNode } from '../../components/diagram/FlowDiagram';
import type { DecisionFlow, DecisionStep } from './accessFlow';

/**
 * One table's access decision drawn the way a policy's is: left to right on a
 * dotted canvas, each check a node with its two ways out.
 *
 * <p>It reads the same {@link DecisionFlow} as the flowchart, so the two cannot
 * disagree; only the drawing differs. Denies are one node, since any one of
 * them ends it. Each layer holding an allow is a node of its own, because the
 * caller has to pass every one. Data policies are one node, since they all
 * apply together. Every way to be refused runs down to the one "Denied" end
 * under the flow, so the strokes never cross a node.
 *
 * <p>A node stands for one or more steps; {@link AccessDiagram.steps} says
 * which, so a click on it can show every policy it holds in full.
 */

export const ENTRY = 'entry';
export const EXIT = 'exit';
export const DENIED = 'denied';
export const DENIES = 'denies';
export const DATA = 'data';

const ICON = 'tw:size-4';

export interface AccessDiagram {
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  /** The steps each selectable node draws. */
  steps: Record<string, DecisionStep[]>;
}

function name(step: DecisionStep): string {
  return step.items[0]?.text ?? step.title;
}

export function accessDiagram(flow: DecisionFlow): AccessDiagram {
  const denies = flow.steps.filter((step) => step.id.startsWith('deny-'));
  const gates = flow.steps.filter((step) => step.id.startsWith('gate-') || step.id === 'grants');
  const data = flow.steps.filter((step) => step.id.startsWith('data-'));
  const nobodyIn = flow.exitTone === 'deny';

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
  const steps: Record<string, DecisionStep[]> = {};

  let from = ENTRY;
  let saying: Pick<DiagramEdge, 'label' | 'tone'> = {};
  let column = 0;
  // The first column past the last check that can refuse: the "Denied" end sits there.
  let afterChecks = 0;
  const refuse = (id: string, label: string) => {
    edges.push({ from: id, to: DENIED, label, tone: 'error', route: 'down', dashed: true });
    afterChecks = column + 1;
  };
  const add = (node: Omit<DiagramNode, 'kind' | 'column' | 'row'>, drawn: DecisionStep[]) => {
    column += 1;
    nodes.push({ kind: 'task', column, row: 0, ...node });
    steps[node.id] = drawn;
    edges.push({ from, to: node.id, ...saying });
    from = node.id;
    saying = {};
  };

  if (denies.length) {
    add(
      {
        id: DENIES,
        eyebrow: 'Deny · check',
        title: denies.length === 1 ? denies[0].title : 'Is the caller caught by any deny?',
        tone: 'error',
        icon: <SlashCircle01 className={ICON} />,
        lines: denies.map((step) => ({ text: `${name(step)} · ${step.layer}` })),
      },
      denies
    );
    refuse(DENIES, 'Yes');
    saying = { label: 'No', tone: 'success' };
  }

  for (const step of gates) {
    const grants = step.id === 'grants';
    add(
      {
        id: step.id,
        eyebrow: grants ? 'Direct grants · check' : `${step.layer} · check`,
        // The flowchart's "Does the caller match any one of these?" is too
        // long for a node; the lines under it list them.
        title: step.items.length > 1 && !grants ? 'Is the caller any of these?' : step.title,
        tone: step.tone === 'empty' ? 'muted' : 'default',
        icon: grants ? <Key01 className={ICON} /> : <Users01 className={ICON} />,
        lines: [
          ...(step.passable
            ? [{ text: 'A direct grant or a narrower layer can let them past', caution: true }]
            : []),
          ...step.items.map((item) => ({
            text: item.detail && !grants ? `${item.text} · ${item.detail}` : item.text,
          })),
        ],
      },
      [step]
    );
    // With nothing to open it, there is no "yes" to take: the flow just ends.
    if (!nobodyIn) {
      refuse(step.id, 'No');
      saying = { label: 'Yes', tone: 'success' };
    }
  }

  if (data.length) {
    add(
      {
        id: DATA,
        eyebrow: 'Data policies',
        title: 'What they see',
        tone: 'warning',
        icon: <Columns03 className={ICON} />,
        lines: data.map((step) => ({ text: `${name(step)} · ${step.who ?? 'everyone'}` })),
      },
      data
    );
  }

  const last = column + 1;
  nodes.push({
    id: EXIT,
    kind: 'end',
    column: last,
    row: 0,
    title: nobodyIn
      ? 'Nobody reads it'
      : flow.exitTone === 'restrict'
        ? 'They read it, narrowed'
        : 'They read it in full',
    tone: nobodyIn ? 'error' : flow.exitTone === 'restrict' ? 'warning' : 'success',
    icon: nobodyIn ? (
      <MinusCircle className={ICON} />
    ) : (
      <CheckCircle className={ICON} />
    ),
    selectable: false,
  });
  edges.push({ from, to: EXIT, ...saying });

  if (afterChecks) {
    nodes.push({
      id: DENIED,
      kind: 'end',
      column: Math.min(afterChecks, last),
      row: 0.7,
      title: 'Denied',
      tone: 'error',
      icon: <XCircle className={ICON} />,
      selectable: false,
    });
  }
  return { nodes, edges, steps };
}
