import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { useState } from 'react';
import SubjectBuilder from './SubjectBuilder';
import type { SubjectRule } from '../../generated/entity/policy/policy';

jest.mock('../../api/expressions', () => ({
  validateExpression: () =>
    Promise.resolve({ valid: true, rowDependent: false, unknownAttributes: [] }),
}));

// No register loaded: the purpose rows ask as they did before there was one.
jest.mock('../../api/purposes', () => ({
  ...jest.requireActual('../../api/purposes'),
  usePurposes: () => ({ data: undefined, isLoading: false, isError: false }),
}));

/**
 * How the rows on this form are joined.
 *
 * <p>The form carries two lists of identical-looking rows, one an or and one an
 * and. Which is which decides whether a policy reaches everybody in either
 * group or only the people in both -- the difference between granting a table
 * to two departments and granting it to nobody. It is settled in the engine by
 * {@code SubjectMatcher} and stated on the form by a word; these check that the
 * word the form shows is the one the engine means, because a form that says
 * "or" over an and is worse than a form that says nothing.
 */

const attributes = {
  keys: [
    { key: 'department', source: 'local', principals: 3, values: [] },
    { key: 'clearance', source: 'local', principals: 2, values: [] },
  ],
  appRoles: ['analyst'],
};

/** A rule with two of everything, so every list has something to join. */
const filled: SubjectRule = {
  principals: [{ group: 'Finance' }, { group: 'Risk' }],
  requiredPrincipals: [{ group: 'PrivacyTrained' }, { group: 'Permanent' }],
  attributes: [
    { key: 'department', operator: 'eq', value: 'FINANCE' },
    { key: 'clearance', operator: 'gte', value: 'L2' },
  ],
  time: {
    windows: [
      { days: ['MON-FRI'], from: '08:00', to: '18:00', timezone: 'Asia/Bangkok' },
      { days: ['SAT,SUN'], from: '09:00', to: '12:00', timezone: 'Asia/Bangkok' },
    ],
  },
};

function renderWith(value: SubjectRule) {
  return render(
    <MemoryRouter>
      <SubjectBuilder
        attributes={attributes as never}
        onChange={() => undefined}
        principals={[]}
        value={value}
      />
    </MemoryRouter>
  );
}

/** The block a heading introduces, so "or" is counted where it applies. */
function section(title: string): HTMLElement {
  const heading = screen.getByRole('heading', { name: new RegExp(title, 'i') });
  return heading.parentElement as HTMLElement;
}

describe('how the rows are joined', () => {
  it('calls the first list an any and the requirement list an all', () => {
    renderWith(filled);

    expect(
      within(section('Anyone who is')).getByText('any of these')
    ).toBeInTheDocument();
    expect(
      within(section('And who is also')).getByText('all of these')
    ).toBeInTheDocument();
  });

  it('puts "or" between two entries in the first list and nothing else', () => {
    renderWith(filled);
    const block = within(section('Anyone who is'));

    // Two rows, so exactly one word between them. Two would mean a connector
    // rendered above the first row, which reads as a dangling clause.
    expect(block.getAllByText('or')).toHaveLength(1);
    expect(block.queryByText('and')).not.toBeInTheDocument();
  });

  it('puts "and" between two requirements', () => {
    renderWith(filled);
    const block = within(section('And who is also'));

    expect(block.getAllByText('and')).toHaveLength(1);
    expect(block.queryByText('or')).not.toBeInTheDocument();
  });

  it('puts "and" between two attribute conditions', () => {
    renderWith(filled);
    const block = within(section('And whose attributes say'));

    expect(block.getAllByText('and')).toHaveLength(1);
    expect(block.queryByText('or')).not.toBeInTheDocument();
  });

  it('calls two time windows an any, because either one opens the door', () => {
    // TimeMatcher returns on the first window that contains the instant, so
    // two windows are alternatives. Labelling them "all" would describe a rule
    // that can never hold: no instant is inside two disjoint windows.
    renderWith(filled);
    const block = within(section('And only during'));

    expect(block.getByText('any of these')).toBeInTheDocument();
    expect(block.getAllByText('or')).toHaveLength(1);
  });

  it('says nothing between rows when there is only one row', () => {
    renderWith({ principals: [{ group: 'Finance' }] });
    const block = within(section('Anyone who is'));

    expect(block.queryByText('or')).not.toBeInTheDocument();
  });

  it('shows the word as soon as a second row is added', () => {
    // The point of the connector is to answer the question at the moment it
    // first arises, which is when somebody adds the second entry -- so it has
    // to follow the rows rather than be decided when the form was opened.
    function Harness() {
      const [value, setValue] = useState<SubjectRule>({
        principals: [{ group: 'Finance' }],
      });
      return (
        <SubjectBuilder
          attributes={attributes as never}
          onChange={setValue}
          principals={[]}
          value={value}
        />
      );
    }
    render(
      <MemoryRouter>
        <Harness />
      </MemoryRouter>
    );

    expect(
      within(section('Anyone who is')).queryByText('or')
    ).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Add who' }));

    expect(within(section('Anyone who is')).getAllByText('or')).toHaveLength(1);
  });

  it('keeps the two lists distinguishable when both are empty but shown', () => {
    // An author who starts from a blank form sees both headings before either
    // has a row in it, and the connector cannot help there. The tag is what
    // carries the distinction at that moment.
    renderWith({ principals: [], requiredPrincipals: [{ group: 'x' }] });

    expect(
      within(section('Anyone who is')).getByText('any of these')
    ).toBeInTheDocument();
    expect(
      within(section('And who is also')).getByText('all of these')
    ).toBeInTheDocument();
  });
});
