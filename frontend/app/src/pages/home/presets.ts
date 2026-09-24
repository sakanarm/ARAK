import type { CSSProperties } from 'react';
import type { HomePreset } from '../../api/home';
import { PRESET_COLUMNS } from '../../api/home';

/**
 * The arrangements a page can take, and what each one looks like.
 *
 * Five fixed presets rather than free placement, because the thing people
 * actually want from a dashboard editor is "make that one wide" — and a
 * free-form grid buys that at the price of a drag surface, collision rules and
 * a layout that breaks the first time somebody opens it on a laptop.
 */
export const PRESET_OPTIONS: {
  preset: HomePreset;
  label: string;
  hint: string;
  /** Relative widths, one per column. */
  weights: number[];
}[] = [
  {
    preset: 'SINGLE',
    label: 'One column',
    hint: 'Everything full width. Best for search and reading.',
    weights: [1],
  },
  {
    preset: 'HALVES',
    label: 'Two equal',
    hint: 'Two columns of the same width.',
    weights: [1, 1],
  },
  {
    preset: 'WIDE_LEFT',
    label: 'Wide left',
    hint: 'A main column with a narrow one beside it.',
    weights: [2, 1],
  },
  {
    preset: 'WIDE_RIGHT',
    label: 'Wide right',
    hint: 'A narrow column first, then the main one.',
    weights: [1, 2],
  },
  {
    preset: 'THIRDS',
    label: 'Three columns',
    hint: 'Three equal columns. Wide screens only.',
    weights: [1, 1, 1],
  },
];

const BY_PRESET = new Map(PRESET_OPTIONS.map((option) => [option.preset, option]));

/** How many columns a preset has, defaulting safely for an unknown one. */
export function columnsOf(preset: HomePreset): number {
  return PRESET_COLUMNS[preset] ?? 1;
}

export function weightsOf(preset: HomePreset): number[] {
  return BY_PRESET.get(preset)?.weights ?? [1];
}

/**
 * The grid's column track, handed to CSS as a custom property.
 *
 * Not a Tailwind class: the weights come from stored data, and Tailwind only
 * generates a utility it has seen written out in a source file. The media
 * query that makes this apply above xl lives with `.arak-home-grid` in the
 * stylesheet.
 */
export function gridStyle(preset: HomePreset): CSSProperties {
  return {
    ['--arak-home-cols' as string]: weightsOf(preset)
      .map((weight) => `${weight}fr`)
      .join(' '),
  };
}

/** The column names a person picks from when moving a widget. */
export function columnLabels(preset: HomePreset): string[] {
  const count = columnsOf(preset);
  if (count === 1) {
    return ['Column'];
  }
  if (count === 2) {
    return ['Left', 'Right'];
  }
  return ['Left', 'Middle', 'Right'];
}
