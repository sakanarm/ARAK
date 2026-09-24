import type { HomeLayout, HomeWidget, HomeWidgetType } from '../../api/home';
import {
  addWidget,
  moveWidget,
  removeWidget,
  setPreset,
  setWidgetColumn,
  setWidgetConfig,
  setWidgetTitle,
} from './HomeEditor';

/**
 * The rules for rearranging a home page.
 *
 * <p>These are the operations behind every button in the editor, and they are
 * tested without rendering anything on purpose: "move down puts it second" is a
 * fact about a list, and asserting it through a click is a slower way of
 * learning the same thing that also fails when a button's label changes.
 *
 * <p>The case worth having is the interleaved one. Order within a column is
 * the order of one flat array shared by every column, so the widget below a
 * two-column page's first left panel is not the next element of the array --
 * and an implementation that swaps neighbours rather than siblings passes every
 * single-column test and reorders the wrong panel the moment somebody picks a
 * second column.
 */

function widget(
  id: string,
  column: number,
  type: HomeWidgetType = 'NOTE'
): HomeWidget {
  return { id, type, column, title: null, config: {} };
}

/** Left: a, c. Right: b, d -- interleaved in the array, as stored. */
function interleaved(): HomeLayout {
  return {
    preset: 'HALVES',
    widgets: [widget('a', 0), widget('b', 1), widget('c', 0), widget('d', 1)],
  };
}

function idsIn(layout: HomeLayout, column: number): string[] {
  return layout.widgets
    .filter((entry) => entry.column === column)
    .map((entry) => entry.id);
}

describe('layout operations', () => {
  it('adds a widget at the end of the column it was asked for', () => {
    const next = addWidget(interleaved(), 'LINKS', 1);

    expect(idsIn(next, 0)).toEqual(['a', 'c']);
    expect(idsIn(next, 1)).toHaveLength(3);
    const added = next.widgets[next.widgets.length - 1];
    expect(added.type).toBe('LINKS');
    expect(added.column).toBe(1);
    // The spec's default, copied rather than shared, so editing one LINKS
    // widget cannot rewrite the catalogue for every page.
    expect(added.config).toEqual({ links: [] });
  });

  it('clamps an added widget to a column the preset has', () => {
    const single: HomeLayout = { preset: 'SINGLE', widgets: [] };

    expect(addWidget(single, 'SEARCH', 2).widgets[0].column).toBe(0);
  });

  it('gives each added widget an id of its own', () => {
    const once = addWidget({ preset: 'SINGLE', widgets: [] }, 'NOTE', 0);
    const twice = addWidget(once, 'NOTE', 0);

    expect(twice.widgets[0].id).not.toBe(twice.widgets[1].id);
  });

  it('removes only the widget named', () => {
    const next = removeWidget(interleaved(), 'c');

    expect(idsIn(next, 0)).toEqual(['a']);
    expect(idsIn(next, 1)).toEqual(['b', 'd']);
  });

  it('moves a widget past its own column sibling, not its array neighbour', () => {
    const next = moveWidget(interleaved(), 'a', 1);

    expect(idsIn(next, 0)).toEqual(['c', 'a']);
    // The other column is untouched -- the swap did not drag 'b' along.
    expect(idsIn(next, 1)).toEqual(['b', 'd']);
  });

  it('moves a widget up the same way', () => {
    const next = moveWidget(interleaved(), 'd', -1);

    expect(idsIn(next, 1)).toEqual(['d', 'b']);
    expect(idsIn(next, 0)).toEqual(['a', 'c']);
  });

  it('leaves the layout alone at either end of a column', () => {
    const layout = interleaved();

    expect(moveWidget(layout, 'a', -1)).toBe(layout);
    expect(moveWidget(layout, 'c', 1)).toBe(layout);
    expect(moveWidget(layout, 'missing', 1)).toBe(layout);
  });

  it('sends a widget to the end of another column', () => {
    const next = setWidgetColumn(interleaved(), 'a', 1);

    expect(idsIn(next, 0)).toEqual(['c']);
    expect(idsIn(next, 1)).toEqual(['b', 'd', 'a']);
  });

  it('keeps a blank title as no title at all', () => {
    const titled = setWidgetTitle(interleaved(), 'a', 'Team notes');
    expect(titled.widgets[0].title).toBe('Team notes');

    expect(setWidgetTitle(titled, 'a', '   ').widgets[0].title).toBeNull();
  });

  it('merges a config patch rather than replacing the config', () => {
    const layout: HomeLayout = {
      preset: 'SINGLE',
      widgets: [
        { ...widget('v', 0, 'VIDEO'), config: { url: 'x', caption: 'Standup' } },
      ],
    };

    expect(setWidgetConfig(layout, 'v', { url: 'y' }).widgets[0].config).toEqual({
      url: 'y',
      caption: 'Standup',
    });
  });

  it('pulls widgets back into columns the new preset still has', () => {
    const thirds: HomeLayout = {
      preset: 'THIRDS',
      widgets: [widget('a', 0), widget('b', 1), widget('c', 2)],
    };

    const narrowed = setPreset(thirds, 'SINGLE');

    expect(narrowed.preset).toBe('SINGLE');
    // Nothing is dropped on the way down: three columns become one column of
    // three, in the order they were already in.
    expect(idsIn(narrowed, 0)).toEqual(['a', 'b', 'c']);
  });

  it('does not move widgets when the new preset is no narrower', () => {
    const next = setPreset(interleaved(), 'WIDE_LEFT');

    expect(idsIn(next, 0)).toEqual(['a', 'c']);
    expect(idsIn(next, 1)).toEqual(['b', 'd']);
  });
});
