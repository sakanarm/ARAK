import { fireEvent, render, screen, within } from '@testing-library/react';
import type { Policy } from '../../generated/entity/policy/policy';
import PolicyDiagram, { ENTRY, EXIT, policyDiagram, SKIPPED } from './PolicyDiagram';

/**
 * The diagram is a third reading of the same document the text and the
 * flowchart read. Pinned here: it draws the gates in the order the engine
 * applies them, every "no" reaches the one end that says the policy does not
 * apply, and a click on a node leads to the step of the form that writes it.
 */

const DATA: Policy = {
  name: 'mask-pii',
  policyType: 'DATA',
  scopeLevel: 'ORG',
  selector: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
  subject: { attributes: [{ key: 'clearance', operator: 'gte', value: 'L2' }] },
  data: {
    columnRules: [
      {
        columns: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
        action: 'MASK',
        masking: { function: 'NULLIFY' },
      },
    ],
  },
};

const DENY: Policy = {
  name: 'deny-offshore',
  policyType: 'SUBSCRIPTION',
  scopeLevel: 'ORG',
  effect: 'DENY',
  selector: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
  subject: { attributes: [{ key: 'country', operator: 'ne', value: 'TH' }] },
};

test('the nodes run entry, which assets, who, outcome, exit — left to right', () => {
  const { nodes } = policyDiagram(DATA);
  const along = nodes.filter((node) => node.row === 0).sort((a, b) => a.column - b.column);

  expect(along.map((node) => node.id)).toEqual([
    ENTRY,
    'gate-which',
    'gate-who',
    'outcome-column-0',
    EXIT,
  ]);
});

test('each gate says yes onward and no down to the one end where the policy does not apply', () => {
  const { nodes, edges } = policyDiagram(DATA);

  const noes = edges.filter((edge) => edge.to === SKIPPED);
  expect(noes.map((edge) => edge.from)).toEqual(['gate-which', 'gate-who']);
  expect(noes.every((edge) => edge.route === 'down' && edge.label === 'No')).toBe(true);
  expect(edges.find((edge) => edge.from === 'gate-which' && edge.to === 'gate-who')?.label).toBe('Yes');

  // The down stroke turns right along the lane below, so its end must sit to
  // the right of every gate that reaches it.
  const skipped = nodes.find((node) => node.id === SKIPPED)!;
  const gates = nodes.filter((node) => node.id.startsWith('gate-'));
  expect(gates.every((gate) => gate.column < skipped.column)).toBe(true);
  expect(skipped.row).toBeGreaterThan(0);
});

test('a gate everyone passes has no way out, and a denial is drawn in red', () => {
  const open = policyDiagram({ ...DENY, subject: undefined });
  expect(open.edges.filter((edge) => edge.to === SKIPPED).map((edge) => edge.from)).toEqual([
    'gate-which',
  ]);
  expect(open.nodes.find((node) => node.id === 'gate-who')?.tone).toBe('warning');

  const { nodes } = policyDiagram(DENY);
  expect(nodes.find((node) => node.id === 'outcome-subscription')?.tone).toBe('error');
  expect(nodes.find((node) => node.id === EXIT)?.title).toBe('Nothing is read');
});

test('a node sends the author to the step that writes it', () => {
  const edits: number[] = [];
  render(<PolicyDiagram onEdit={(step) => edits.push(step)} policy={DATA} />);
  const figure = screen.getByRole('figure');

  fireEvent.click(within(figure).getByRole('button', { name: /Is the asset one of these/ }));
  fireEvent.click(within(figure).getByRole('button', { name: /Is the caller all of these/ }));
  fireEvent.click(within(figure).getByRole('button', { name: /Then these columns are/ }));

  expect(edits).toEqual([3, 4, 5]);
  expect(screen.getByText('Click a step to edit it')).toBeInTheDocument();
});

test('a reader who cannot edit is offered no node to press', () => {
  render(<PolicyDiagram policy={DATA} />);
  const figure = screen.getByRole('figure');

  expect(within(figure).queryByRole('button', { name: /Is the asset/ })).not.toBeInTheDocument();
  expect(screen.getAllByText(/If not ·/)).toHaveLength(2);
});
