import { fireEvent, render, screen } from '@testing-library/react';
import type { Policy } from '../../generated/entity/policy/policy';
import PolicyFlowChart from './PolicyFlowChart';

/**
 * The chart is an addition, and the thing an addition must never do is take
 * something away. What is pinned here is that it renders the same document the
 * form holds, that clicking a box leads back to the field that writes it, and
 * that a reader with no right to edit gets no buttons offering to.
 */

const POLICY: Policy = {
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

test('the chart draws the gates in the order the engine applies them', () => {
  render(<PolicyFlowChart policy={POLICY} />);

  expect(screen.getByText('Is the asset one of these?')).toBeInTheDocument();
  expect(screen.getByText('Is the caller all of these?')).toBeInTheDocument();
  expect(screen.getAllByText(/If not/)).toHaveLength(2);
});

test('a box sends the author to the step that writes it', () => {
  const edits: number[] = [];
  render(<PolicyFlowChart onEdit={(step) => edits.push(step)} policy={POLICY} />);

  fireEvent.click(screen.getByText('Is the asset one of these?').closest('button')!);
  expect(edits).toEqual([3]);

  fireEvent.click(screen.getByText('Is the caller all of these?').closest('button')!);
  expect(edits).toEqual([3, 4]);
});

test('a reader who cannot edit is offered no way to', () => {
  render(<PolicyFlowChart policy={POLICY} />);

  expect(screen.queryAllByRole('button')).toHaveLength(0);
  expect(screen.queryByText(/Edit in step/)).not.toBeInTheDocument();
});
