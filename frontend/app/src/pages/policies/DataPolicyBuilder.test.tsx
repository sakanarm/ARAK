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

/*
 * A lookup: department AA sees division A because a mapping table says so.
 * The column is named; the mapping, its keys and how it is read are written
 * beside it.
 */
function type(label: string, text: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value: text } });
}

it('writes a filter whose values come from a mapping table', async () => {
  const seen = renderLive({
    rowFilters: [
      {
        kind: 'IN_LIST',
        userAttribute: 'divisions',
        columns: { condition: { facet: 'tags', operator: 'contains', value: 'Org.Division' } },
      },
    ],
  });

  await pick(/Row filter kind/, 'Column is one of the values a mapping table gives them');
  expect(screen.queryByRole('button', { name: /Pick the column by/ })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: /How the mapping is read/ })).toHaveTextContent(
    'Join it into the query'
  );

  type('Column', 'division');
  type('Value column', 'division');
  type('Mapping table', 'warehouse.sales.ref.department_division');
  type('Mapping column 1', 'department');
  type('Their attribute 1', 'department');
  fireEvent.click(screen.getByRole('button', { name: 'Add a key' }));
  type('Mapping column 2', 'region');
  type('Their attribute 2', 'region');
  await pick(/How the mapping is read/, 'Read the values first');

  expect(seen.data.rowFilters?.[0]).toEqual({
    kind: 'LOOKUP',
    userAttribute: 'divisions',
    column: 'division',
    lookup: {
      table: 'warehouse.sales.ref.department_division',
      keys: [
        { column: 'department', userAttribute: 'department' },
        { column: 'region', userAttribute: 'region' },
      ],
      valueColumn: 'division',
      mode: 'READ_VALUES',
    },
  });
  // A selector the server would refuse on this kind is not carried along.
  expect(seen.data.rowFilters?.[0].columns).toBeUndefined();
  expect(screen.getByText(/may be on another data source/)).toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Remove key 1' }));
  expect(seen.data.rowFilters?.[0].lookup?.keys).toEqual([
    { column: 'region', userAttribute: 'region' },
  ]);
  // The last key stays: a mapping with no key would not save.
  expect(screen.queryByRole('button', { name: /Remove key/ })).not.toBeInTheDocument();
});

it('drops the mapping when the filter becomes another kind', async () => {
  const seen = renderLive({
    rowFilters: [
      {
        kind: 'LOOKUP',
        column: 'division',
        lookup: {
          table: 'warehouse.sales.ref.department_division',
          keys: [{ column: 'department', userAttribute: 'department' }],
          valueColumn: 'division',
        },
      },
    ],
  });
  expect(screen.getByLabelText('Mapping table')).toHaveValue(
    'warehouse.sales.ref.department_division'
  );

  await pick(/Row filter kind/, 'Column is one of their values');

  expect(seen.data.rowFilters?.[0].kind).toBe('IN_LIST');
  expect(seen.data.rowFilters?.[0].column).toBe('division');
  expect(seen.data.rowFilters?.[0].lookup).toBeUndefined();
  expect(screen.queryByLabelText('Mapping table')).not.toBeInTheDocument();
});
