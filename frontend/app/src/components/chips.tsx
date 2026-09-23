import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import type { BadgeColor } from '@openmetadata/ui-core-components/components/base/badges/badges';
import type {
  BadgeColors,
  BadgeTypes,
  Sizes,
} from '@openmetadata/ui-core-components/components/base/badges/badge-types';
import type { ReactNode } from 'react';

/**
 * The chip's own weight, one step up from the design system's default.
 *
 * <p>The stock pill is a 50-level fill behind 700-level text inside a 200-level
 * hairline, which is the right treatment for a badge that appears once. Our
 * screens carry six or seven side by side at 12px, and at that density the fill
 * reads as a smudge and the outline all but disappears. A hundred-level fill, a
 * three-hundred-level edge and a medium weight keep each chip a distinct object
 * at the same hue, which is what the colour is for.
 *
 * <p>The scale flips in dark mode (`utility-blue-100` maps to `blue-900`
 * there), so stepping 50→100 and 200→300 stays a step in the same direction on
 * both themes rather than becoming a step towards the background on one.
 */
const CHIP_WEIGHT: Record<BadgeColors | 'gray', string> = {
  gray: 'tw:bg-utility-gray-100 tw:text-utility-gray-700 tw:outline-utility-gray-300',
  brand:
    'tw:bg-utility-brand-100 tw:text-utility-brand-700 tw:outline-utility-brand-300',
  error:
    'tw:bg-utility-error-100 tw:text-utility-error-700 tw:outline-utility-error-300',
  warning:
    'tw:bg-utility-warning-100 tw:text-utility-warning-700 tw:outline-utility-warning-300',
  success:
    'tw:bg-utility-success-100 tw:text-utility-success-700 tw:outline-utility-success-300',
  'gray-blue':
    'tw:bg-utility-gray-blue-100 tw:text-utility-gray-blue-700 tw:outline-utility-gray-blue-300',
  'blue-light':
    'tw:bg-utility-blue-light-100 tw:text-utility-blue-light-700 tw:outline-utility-blue-light-300',
  blue: 'tw:bg-utility-blue-100 tw:text-utility-blue-700 tw:outline-utility-blue-300',
  indigo:
    'tw:bg-utility-indigo-100 tw:text-utility-indigo-700 tw:outline-utility-indigo-300',
  purple:
    'tw:bg-utility-purple-100 tw:text-utility-purple-700 tw:outline-utility-purple-300',
  pink: 'tw:bg-utility-pink-100 tw:text-utility-pink-700 tw:outline-utility-pink-300',
  orange:
    'tw:bg-utility-orange-100 tw:text-utility-orange-700 tw:outline-utility-orange-300',
  'blue-dark':
    'tw:bg-utility-blue-dark-100 tw:text-utility-gray-700 tw:outline-utility-gray-blue-300',
};

/**
 * The same hue one step back, for a facet that was inherited rather than set
 * here.
 *
 * <p>This used to be `opacity-70` over the whole chip, which faded the text
 * along with the fill and left a pale shape nobody could read — and inherited
 * facets are the majority on most rows, so most of the row was the faded kind.
 * Dropping the fill a step while leaving the text at full strength keeps the
 * distinction visible without spending legibility on it. The ↑ says the rest.
 */
const CHIP_INHERITED: Record<BadgeColors | 'gray', string> = {
  gray: 'tw:bg-utility-gray-50 tw:text-utility-gray-700 tw:outline-utility-gray-200',
  brand:
    'tw:bg-utility-brand-50 tw:text-utility-brand-700 tw:outline-utility-brand-200',
  error:
    'tw:bg-utility-error-50 tw:text-utility-error-700 tw:outline-utility-error-200',
  warning:
    'tw:bg-utility-warning-50 tw:text-utility-warning-700 tw:outline-utility-warning-200',
  success:
    'tw:bg-utility-success-50 tw:text-utility-success-700 tw:outline-utility-success-200',
  'gray-blue':
    'tw:bg-utility-gray-blue-50 tw:text-utility-gray-blue-700 tw:outline-utility-gray-blue-200',
  'blue-light':
    'tw:bg-utility-blue-light-50 tw:text-utility-blue-light-700 tw:outline-utility-blue-light-200',
  blue: 'tw:bg-utility-blue-50 tw:text-utility-blue-700 tw:outline-utility-blue-200',
  indigo:
    'tw:bg-utility-indigo-50 tw:text-utility-indigo-700 tw:outline-utility-indigo-200',
  purple:
    'tw:bg-utility-purple-50 tw:text-utility-purple-700 tw:outline-utility-purple-200',
  pink: 'tw:bg-utility-pink-50 tw:text-utility-pink-700 tw:outline-utility-pink-200',
  orange:
    'tw:bg-utility-orange-50 tw:text-utility-orange-700 tw:outline-utility-orange-200',
  'blue-dark':
    'tw:bg-utility-blue-dark-50 tw:text-utility-gray-700 tw:outline-utility-gray-blue-200',
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
 * <p>Every screen here uses badges the way a catalog uses them — several in a
 * row, carrying meaning rather than decorating a heading — so the weight
 * belongs in one place rather than at sixty call sites. Pages import this as
 * `Badge`, which keeps the call sites reading as ordinary design-system usage
 * and means the treatment can be changed or dropped in a single file.
 *
 * <p>The vendored component is left untouched: `cx` merges `className` last, so
 * these classes win over the stock `bg`/`text`/`outline` without a fork to
 * maintain against upstream.
 *
 * <p>`modern` is deliberately exempt. It is the white chip with a hairline
 * border, and it is what an owner's name and other non-classification labels
 * use; tinting those would put a person's name in competition with the
 * governance facets beside it.
 */
export function Chip<T extends BadgeTypes>({
  className,
  ...props
}: ChipProps<T>) {
  const type = props.type ?? ('pill-color' as T);
  const colour = (props.color ?? 'gray') as BadgeColors;
  const weighted =
    type === 'modern'
      ? className
      : `${badgeWeight(colour)}${className ? ` ${className}` : ''}`;

  return <Badge {...props} className={weighted} type={type} />;
}
