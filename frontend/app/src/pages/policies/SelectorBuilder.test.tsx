import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import SelectorBuilder from './SelectorBuilder';
import type { AssetSelector } from '../../generated/entity/policy/policy';

jest.mock('../../api/client', () => ({
  apiClient: {},
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}));

/**
 * Several values for "is one of" and "is none of" in the asset selector.
 *
 * The engine reads those two from `values`. The form used to write whatever
 * was typed into `value`, so "sales, hr" was one schema named that, and "is
 * none of" it held for every table there is.
 */

/** The builder over a live selector, with the last selector it reported. */
function renderLive(initial: AssetSelector) {
  const seen: { selector: AssetSelector } = { selector: initial };
  function Harness() {
    const [value, setValue] = useState<AssetSelector>(initial);
    return (
      <SelectorBuilder
        onChange={(next) => {
          seen.selector = next;
          setValue(next);
        }}
        value={value}
      />
    );
  }
  render(<Harness />);

  return seen;
}

it('adds each value as its own item in values', () => {
  const seen = renderLive({ condition: { facet: 'schema', operator: 'in', values: [] } });
  const box = screen.getByLabelText('Add to values');

  fireEvent.change(box, { target: { value: 'sales' } });
  fireEvent.keyDown(box, { key: 'Enter' });
  fireEvent.change(box, { target: { value: 'hr' } });
  fireEvent.blur(box);

  expect(seen.selector).toEqual({
    condition: { facet: 'schema', operator: 'in', values: ['sales', 'hr'] },
  });
  expect(screen.getByRole('button', { name: 'Remove sales' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Remove hr' })).toBeInTheDocument();
});

it('does not add a value twice, and Backspace in an empty box takes the last one back', () => {
  const seen = renderLive({
    condition: { facet: 'schema', operator: 'notIn', values: ['sales', 'hr'] },
  });
  const box = screen.getByLabelText('Add to values');

  fireEvent.change(box, { target: { value: 'sales' } });
  fireEvent.keyDown(box, { key: 'Enter' });
  expect(seen.selector.condition?.values).toEqual(['sales', 'hr']);

  fireEvent.keyDown(box, { key: 'Backspace' });
  expect(seen.selector.condition?.values).toEqual(['sales']);
});

it('moves a typed value into the list when the operator becomes "is one of"', async () => {
  const seen = renderLive({ condition: { facet: 'schema', operator: 'eq', value: 'sales, hr' } });

  fireEvent.click(screen.getByRole('button', { name: /operator/i }));
  fireEvent.click(await screen.findByRole('option', { name: 'is one of' }));

  expect(seen.selector).toEqual({
    condition: { facet: 'schema', operator: 'in', values: ['sales', 'hr'] },
  });
  expect(screen.getByRole('button', { name: 'Remove hr' })).toBeInTheDocument();
});
