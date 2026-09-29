import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import DataPolicyBuilder from './DataPolicyBuilder';
import type { DataPolicy } from '../../generated/entity/policy/policy';

jest.mock('../../api/client', () => ({
  apiClient: {},
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}));

/**
 * A row filter picks the column it compares by name, or by tag.
 *
 * By name, one policy fits only the tables that call the column the same
 * thing. By tag, the engine finds the column in each table, so the one
 * policy covers a branch column named differently in each.
 */

/** The builder over a live data policy, with the last one it reported. */
function renderLive(initial: DataPolicy) {
  const seen: { data: DataPolicy } = { data: initial };
  function Harness() {
    const [value, setValue] = useState<DataPolicy>(initial);
    return (
      <DataPolicyBuilder
        onChange={(next) => {
          seen.data = next;
          setValue(next);
        }}
        value={value}
      />
    );
  }
  render(<Harness />);

  return seen;
}

const byName: DataPolicy = {
  rowFilters: [
    { kind: 'ATTRIBUTE_COMPARE', column: 'branch_code', operator: 'eq', userAttribute: 'branch' },
  ],
};

async function pick(select: RegExp, option: string) {
  fireEvent.click(screen.getByRole('button', { name: select }));
  fireEvent.click(await screen.findByRole('option', { name: option }));
}

it('picks the column by tag instead of by name', async () => {
  const seen = renderLive(byName);
  expect(screen.getByLabelText('Column')).toHaveValue('branch_code');

  await pick(/Pick the column by/, 'Column tagged');

  expect(seen.data.rowFilters?.[0]).toEqual({
    kind: 'ATTRIBUTE_COMPARE',
    operator: 'eq',
    userAttribute: 'branch',
    columns: { condition: { facet: 'tags', operator: 'contains', value: '' } },
  });
  expect(screen.queryByLabelText('Column')).not.toBeInTheDocument();
  expect(screen.getByText(/A table without such a column shows no rows/)).toBeInTheDocument();
});

it('goes back to a named column and drops the selector', async () => {
  const seen = renderLive({
    rowFilters: [
      {
        kind: 'IN_LIST',
        userAttribute: 'branches',
        columns: { condition: { facet: 'tags', operator: 'contains', value: 'Org.Branch' } },
      },
    ],
  });

  await pick(/Pick the column by/, 'Column named');

  expect(seen.data.rowFilters?.[0]).toEqual({
    kind: 'IN_LIST',
    userAttribute: 'branches',
    column: '',
  });
  expect(screen.getByLabelText('Column')).toHaveValue('');
});

it('drops the selector when the kind compares no column', async () => {
  const seen = renderLive({
    rowFilters: [
      {
        kind: 'ATTRIBUTE_COMPARE',
        operator: 'eq',
        userAttribute: 'branch',
        columns: { condition: { facet: 'tags', operator: 'contains', value: 'Org.Branch' } },
      },
    ],
  });

  await pick(/Row filter kind/, 'No rows at all');

  expect(seen.data.rowFilters?.[0].kind).toBe('ALWAYS_FALSE');
  expect(seen.data.rowFilters?.[0].columns).toBeUndefined();
});
