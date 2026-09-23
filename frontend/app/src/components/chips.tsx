import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import type { BadgeColor } from '@openmetadata/ui-core-components/components/base/badges/badges';
import type {
  BadgeColors,
  BadgeTypes,
  Sizes,
} from '@openmetadata/ui-core-components/components/base/badges/badge-types';
import type { ReactNode } from 'react';

/**
 * The chip's own weight: a solid fill, no edge.
 *
 * <p>The stock pill is a 50-level fill behind 700-level text inside a 200-level
 * hairline, which is the right treatment for a badge that appears once. Our
 * screens carry six or seven side by side at 12px, and at that density the
 * hairline is the part that fails: a one-pixel outline at a colour that close to
 * the fill renders as a grey smudge on a non-integer boundary, and six of them
 * in a row read as a tally of boxes rather than as labels. Dropping the edge and
 * letting a 100-level fill carry the shape gives each chip one job -- be a patch
 * of its own hue -- which is what the colour was for.
 *
 * <p>The scale flips in dark mode (`utility-blue-100` maps to `blue-900`), so
 * stepping 50 to 100 stays a step in the same direction on both themes rather
 * than becoming a step towards the background on one.
 */
const CHIP_WEIGHT: Record<BadgeColors | 'gray', string> = {
  gray: 'tw:bg-utility-gray-100 tw:text-utility-gray-700',
  brand: 'tw:bg-utility-brand-100 tw:text-utility-brand-700',
  error: 'tw:bg-utility-error-100 tw:text-utility-error-700',
  warning: 'tw:bg-utility-warning-100 tw:text-utility-warning-700',
  success: 'tw:bg-utility-success-100 tw:text-utility-success-700',
  'gray-blue': 'tw:bg-utility-gray-blue-100 tw:text-utility-gray-blue-700',
  'blue-light': 'tw:bg-utility-blue-light-100 tw:text-utility-blue-light-700',
  blue: 'tw:bg-utility-blue-100 tw:text-utility-blue-700',
  indigo: 'tw:bg-utility-indigo-100 tw:text-utility-indigo-700',
  purple: 'tw:bg-utility-purple-100 tw:text-utility-purple-700',
  pink: 'tw:bg-utility-pink-100 tw:text-utility-pink-700',
  orange: 'tw:bg-utility-orange-100 tw:text-utility-orange-700',
  'blue-dark': 'tw:bg-utility-blue-dark-100 tw:text-utility-gray-700',
};

/**
 * The same hue one step back, for a facet that was inherited rather than set
 * here.
 *
 * <p>This used to be `opacity-70` over the whole chip, which faded the text
 * along with the fill and left a pale shape nobody could read -- and inherited
 * facets are the majority on most rows, so most of the row was the faded kind.
 * Dropping the fill a step while leaving the text at full strength keeps the
 * distinction visible without spending legibility on it. The arrow says the
 * rest, and carries the distinction on its own for anyone who cannot separate
 * two neighbouring steps of one hue.
 */
const CHIP_INHERITED: Record<BadgeColors | 'gray', string> = {
  gray: 'tw:bg-utility-gray-50 tw:text-utility-gray-700',
  brand: 'tw:bg-utility-brand-50 tw:text-utility-brand-700',
  error: 'tw:bg-utility-error-50 tw:text-utility-error-700',
  warning: 'tw:bg-utility-warning-50 tw:text-utility-warning-700',
  success: 'tw:bg-utility-success-50 tw:text-utility-success-700',
  'gray-blue': 'tw:bg-utility-gray-blue-50 tw:text-utility-gray-blue-700',
  'blue-light': 'tw:bg-utility-blue-light-50 tw:text-utility-blue-light-700',
  blue: 'tw:bg-utility-blue-50 tw:text-utility-blue-700',
  indigo: 'tw:bg-utility-indigo-50 tw:text-utility-indigo-700',
  purple: 'tw:bg-utility-purple-50 tw:text-utility-purple-700',
  pink: 'tw:bg-utility-pink-50 tw:text-utility-pink-700',
  orange: 'tw:bg-utility-orange-50 tw:text-utility-orange-700',
  'blue-dark': 'tw:bg-utility-blue-dark-50 tw:text-utility-gray-700',
};

/** The weight classes for one colour, in either strength. */
export function badgeWeight(
  colour: BadgeColors | 'gray',
  inherited = false
): string {
  const map = inherited ? CHIP_INHERITED : CHIP_WEIGHT;
  return `tw:font-medium ${map[colour] ?? map.gray}`;
}

interface ChipProps<T extends BadgeTypes> {
  type?: T;
  size?: Sizes;
  color?: BadgeColor<T>;
  children: ReactNode;
  className?: string;
  bordered?: boolean;
  'data-testid'?: string;
}

/**
 * The design system's `Badge`, at the weight this app draws badges.
 *
 * <p>Every screen here uses badges the way a catalog uses them -- several in a
 * row, carrying meaning rather than decorating a heading -- so the weight
 * belongs in one place rather than at sixty call sites. Pages import this as
 * `Badge`, which keeps the call sites reading as ordinary design-system usage
 * and means the treatment can be changed or dropped in a single file.
 *
 * <p>The vendored component is left untouched: `cx` merges `className` last, so
 * these classes win over the stock `bg` and `text` without a fork to maintain
 * against upstream. The edge is a prop rather than a class, so it is turned off
 * through `bordered` -- a caller that genuinely wants one back can still pass
 * `bordered`, and it will win, because an explicit prop beats this default.
 *
 * <p>`modern` is deliberately exempt from both. It is the white chip with a
 * hairline border, and it is what an owner's name and other non-classification
 * labels use; tinting those would put a person's name in competition with the
 * governance facets beside it, and the border is the only thing giving a white
 * chip a shape at all.
 */
export function Chip<T extends BadgeTypes>({
  className,
  bordered,
  ...props
}: ChipProps<T>) {
  const type = props.type ?? ('pill-color' as T);
  const colour = (props.color ?? 'gray') as BadgeColors;
  const modern = type === 'modern';
  const weighted = modern
    ? className
    : `${badgeWeight(colour)}${className ? ` ${className}` : ''}`;

  return (
    <Badge
      {...props}
      bordered={bordered ?? modern}
      className={weighted}
      type={type}
    />
  );
}
