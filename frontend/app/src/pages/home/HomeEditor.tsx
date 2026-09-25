import { useState } from 'react';
import {
  ArrowDown,
  ArrowUp,
  Plus,
  RefreshCcw01,
  Trash01,
  XClose,
} from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import type {
  HomeLayout,
  HomeLink,
  HomePreset,
  HomeWidget,
  HomeWidgetType,
} from '../../api/home';
import { WIDGET_SPECS, specOf } from './widgets';
import { PRESET_OPTIONS, columnLabels, columnsOf } from './presets';

/**
 * The panel for arranging your own home page.
 *
 * Buttons, not drag and drop. A drag surface is the obvious design and the
 * wrong one here: it needs a library, it needs a keyboard equivalent to be
 * usable at all, and it needs a touch equivalent to work on the tablet this
 * page gets opened on in a meeting. "Move up" and a column picker need none of
 * those and say exactly what they do.
 *
 * This component owns no state but the draft it is given, so the page behind
 * it re-renders live as things move — you arrange the page by looking at the
 * page, not at a preview of it.
 */

// ------------------------------------------------------- layout operations
// Pure, exported, and the only things that change a layout. Keeping them out
// of the components is what lets the page test "move down puts it second"
// without rendering anything.

/** A new widget of this type, at the end of the given column. */
export function addWidget(
  layout: HomeLayout,
  type: HomeWidgetType,
  column: number
): HomeLayout {
  const spec = specOf(type);
  const widget: HomeWidget = {
    // Unique per widget rather than per type: the same chart twice, drawn two
    // ways, is a reasonable page and the server dedupes ids by dropping the
    // second one.
    id: `${type.toLowerCase()}-${Math.random().toString(36).slice(2, 8)}`,
    type,
    column: Math.min(column, columnsOf(layout.preset) - 1),
    title: null,
    config: { ...spec.defaultConfig },
  };
  return { ...layout, widgets: [...layout.widgets, widget] };
}

export function removeWidget(layout: HomeLayout, id: string): HomeLayout {
  return {
    ...layout,
    widgets: layout.widgets.filter((widget) => widget.id !== id),
  };
}

/**
 * Moves a widget one place within its own column.
 *
 * Order is the order of the array, so this swaps two array positions — the
 * widget's and that of the next one *in the same column*, which is not the
 * next one in the array when the columns interleave.
 */
export function moveWidget(
  layout: HomeLayout,
  id: string,
  delta: -1 | 1
): HomeLayout {
  const index = layout.widgets.findIndex((widget) => widget.id === id);
  if (index < 0) {
    return layout;
  }
  const column = layout.widgets[index].column;
  const siblings = layout.widgets
    .map((widget, at) => ({ widget, at }))
    .filter((entry) => entry.widget.column === column);
  const position = siblings.findIndex((entry) => entry.at === index);
  const target = siblings[position + delta];
  if (!target) {
    return layout;
  }
  const widgets = [...layout.widgets];
  widgets[index] = target.widget;
  widgets[target.at] = layout.widgets[index];
  return { ...layout, widgets };
}

/** Moves a widget to another column, at the end of it. */
export function setWidgetColumn(
  layout: HomeLayout,
  id: string,
  column: number
): HomeLayout {
  const moved = layout.widgets.find((widget) => widget.id === id);
  if (!moved || moved.column === column) {
    return layout;
  }
  return {
    ...layout,
    widgets: [
      ...layout.widgets.filter((widget) => widget.id !== id),
      { ...moved, column },
    ],
  };
}

export function setWidgetTitle(
  layout: HomeLayout,
  id: string,
  title: string
): HomeLayout {
  return {
    ...layout,
    widgets: layout.widgets.map((widget) =>
      widget.id === id ? { ...widget, title: title.trim() ? title : null } : widget
    ),
  };
}

export function setWidgetConfig(
  layout: HomeLayout,
  id: string,
  patch: Record<string, unknown>
): HomeLayout {
  return {
    ...layout,
    widgets: layout.widgets.map((widget) =>
      widget.id === id
        ? { ...widget, config: { ...widget.config, ...patch } }
        : widget
    ),
  };
}

/**
 * Changes the arrangement, pulling widgets back into columns that still exist.
 *
 * The server clamps too, but doing it here means the page you are looking at
 * while you choose is the page you will get — going from three columns to one
 * and seeing nothing move until after a save is how somebody loses a widget
 * without noticing.
 */
export function setPreset(layout: HomeLayout, preset: HomePreset): HomeLayout {
  const last = columnsOf(preset) - 1;
  return {
    preset,
    widgets: layout.widgets.map((widget) => ({
      ...widget,
      column: Math.min(widget.column, last),
    })),
  };
}

// ------------------------------------------------------------- the panel

const ICON_BUTTON =
  'tw:flex tw:size-7 tw:cursor-pointer tw:items-center tw:justify-center' +
  ' tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:text-fg-quaternary' +
  ' tw:transition tw:hover:bg-secondary tw:hover:text-fg-secondary' +
  ' tw:disabled:cursor-not-allowed tw:disabled:opacity-40';

const FIELD =
  'tw:w-full tw:rounded-md tw:border tw:border-secondary tw:bg-primary' +
  ' tw:px-2.5 tw:py-2 tw:text-sm tw:text-primary tw:placeholder:text-quaternary';

export function HomeEditor({
  draft,
  onChange,
  onSave,
  onCancel,
  onReset,
  saving,
  error,
  governanceReader,
  heading = 'Editing your home page',
  subheading = 'Only yours. Nothing here changes what anyone else sees, or what anyone is allowed to read.',
  resetLabel = 'Reset to default',
}: {
  draft: HomeLayout;
  onChange: (layout: HomeLayout) => void;
  onSave: () => void;
  onCancel: () => void;
  onReset: () => void;
  saving: boolean;
  error: string | null;
  /** Whether the governance widgets are offered — mirrors the server. */
  governanceReader: boolean;
  /**
   * What this editor says it is editing. The persona editor (M12b) is the same
   * editor pointed at somebody else's starting page, and the one thing it must
   * not reuse is the sentence promising that nothing here changes what anyone
   * else sees — because there, it does.
   */
  heading?: string;
  subheading?: string;
  resetLabel?: string;
}) {
  const [adding, setAdding] = useState<number | null>(null);
  const labels = columnLabels(draft.preset);
  const offered = WIDGET_SPECS.filter(
    (spec) => governanceReader || !spec.governance
  );

  return (
    <section className="tw:rounded-xl tw:border tw:border-brand tw:bg-primary tw:shadow-xs">
      <header className="tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-3 tw:border-b tw:border-secondary tw:px-5 tw:py-3.5">
        <div>
          <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
            {heading}
          </h2>
          <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">{subheading}</p>
        </div>
        <div className="tw:flex tw:items-center tw:gap-2">
          <Button
            color="tertiary"
            iconLeading={RefreshCcw01}
            isDisabled={saving}
            onPress={onReset}
            size="sm">
            {resetLabel}
          </Button>
          <Button color="secondary" isDisabled={saving} onPress={onCancel} size="sm">
            Cancel
          </Button>
          <Button color="primary" isDisabled={saving} onPress={onSave} size="sm">
            {saving ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </header>

      <div className="tw:flex tw:flex-col tw:gap-5 tw:px-5 tw:py-4">
        {error && (
          <p className="tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-utility-error-700">
            {error}
          </p>
        )}

        {/* ------------------------------------------------------- preset */}
        <div>
          <p className="tw:text-xs tw:font-semibold tw:tracking-wide tw:text-tertiary tw:uppercase">
            Arrangement
          </p>
          <div className="tw:mt-2 tw:flex tw:flex-wrap tw:gap-2">
            {PRESET_OPTIONS.map((option) => {
              const active = option.preset === draft.preset;
              return (
                <button
                  aria-pressed={active}
                  className={`tw:flex tw:w-36 tw:cursor-pointer tw:flex-col tw:gap-2 tw:rounded-lg tw:border tw:px-3 tw:py-2.5 tw:text-left tw:transition ${
                    active
                      ? 'tw:border-brand tw:bg-utility-brand-50'
                      : 'tw:border-secondary tw:bg-primary tw:hover:bg-secondary'
                  }`}
                  key={option.preset}
                  onClick={() => onChange(setPreset(draft, option.preset))}
                  title={option.hint}
                  type="button">
                  <span aria-hidden className="tw:flex tw:h-6 tw:gap-1">
                    {option.weights.map((weight, index) => (
                      <span
                        className="tw:rounded tw:bg-quaternary"
                        key={index}
                        style={{ flexGrow: weight }}
                      />
                    ))}
                  </span>
                  <span className="tw:text-xs tw:font-medium tw:text-primary">
                    {option.label}
                  </span>
                </button>
              );
            })}
          </div>
        </div>

        {/* ------------------------------------------------------ columns */}
        <div className="tw:flex tw:flex-col tw:gap-4">
          {labels.map((label, column) => {
            const inColumn = draft.widgets.filter(
              (widget) => widget.column === column
            );
            return (
              <div key={label}>
                <div className="tw:flex tw:items-center tw:justify-between tw:gap-3">
                  <p className="tw:text-xs tw:font-semibold tw:tracking-wide tw:text-tertiary tw:uppercase">
                    {label}
                  </p>
                  <button
                    className="tw:flex tw:cursor-pointer tw:items-center tw:gap-1 tw:text-xs tw:font-semibold tw:text-brand-secondary tw:hover:underline"
                    onClick={() => setAdding(adding === column ? null : column)}
                    type="button">
                    <Plus className="tw:size-3.5" />
                    Add a panel
                  </button>
                </div>

                {adding === column && (
                  <div className="tw:mt-2 tw:grid tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-2.5 tw:sm:grid-cols-2 tw:lg:grid-cols-3">
                    {offered.map((spec) => (
                      <button
                        className="tw:flex tw:cursor-pointer tw:items-start tw:gap-2.5 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2.5 tw:text-left tw:transition tw:hover:border-brand"
                        key={spec.type}
                        onClick={() => {
                          onChange(addWidget(draft, spec.type, column));
                          setAdding(null);
                        }}
                        type="button">
                        <spec.icon className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
                        <span className="tw:min-w-0">
                          <span className="tw:block tw:text-sm tw:font-medium tw:text-primary">
                            {spec.label}
                          </span>
                          <span className="tw:block tw:text-xs tw:text-tertiary">
                            {spec.blurb}
                          </span>
                        </span>
                      </button>
                    ))}
                  </div>
                )}

                {inColumn.length === 0 ? (
                  <p className="tw:mt-2 tw:rounded-lg tw:border tw:border-dashed tw:border-secondary tw:px-3 tw:py-4 tw:text-center tw:text-xs tw:text-tertiary">
                    This column is empty.
                  </p>
                ) : (
                  <ul className="tw:mt-2 tw:flex tw:flex-col tw:gap-2">
                    {inColumn.map((widget, position) => (
                      <li key={widget.id}>
                        <WidgetRow
                          canMoveDown={position < inColumn.length - 1}
                          canMoveUp={position > 0}
                          columns={labels}
                          draft={draft}
                          onChange={onChange}
                          widget={widget}
                        />
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}

function WidgetRow({
  widget,
  draft,
  onChange,
  canMoveUp,
  canMoveDown,
  columns,
}: {
  widget: HomeWidget;
  draft: HomeLayout;
  onChange: (layout: HomeLayout) => void;
  canMoveUp: boolean;
  canMoveDown: boolean;
  columns: string[];
}) {
  const spec = specOf(widget.type);

  return (
    <div className="tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2.5">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <spec.icon className="tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
        <span className="tw:text-sm tw:font-medium tw:text-primary">
          {spec.label}
        </span>

        <div className="tw:ml-auto tw:flex tw:items-center tw:gap-1.5">
          <button
            aria-label="Move up"
            className={ICON_BUTTON}
            disabled={!canMoveUp}
            onClick={() => onChange(moveWidget(draft, widget.id, -1))}
            type="button">
            <ArrowUp className="tw:size-3.5" />
          </button>
          <button
            aria-label="Move down"
            className={ICON_BUTTON}
            disabled={!canMoveDown}
            onClick={() => onChange(moveWidget(draft, widget.id, 1))}
            type="button">
            <ArrowDown className="tw:size-3.5" />
          </button>
          {columns.length > 1 && (
            <select
              aria-label="Column"
              className="tw:cursor-pointer tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2 tw:py-1 tw:text-xs tw:text-secondary"
              onChange={(event) =>
                onChange(
                  setWidgetColumn(draft, widget.id, Number(event.target.value))
                )
              }
              value={widget.column}>
              {columns.map((label, index) => (
                <option key={label} value={index}>
                  {label}
                </option>
              ))}
            </select>
          )}
          <button
            aria-label={`Remove ${spec.label}`}
            className={ICON_BUTTON}
            onClick={() => onChange(removeWidget(draft, widget.id))}
            type="button">
            <Trash01 className="tw:size-3.5" />
          </button>
        </div>
      </div>

      <div className="tw:mt-2.5 tw:grid tw:gap-2 tw:sm:grid-cols-2">
        <label className="tw:flex tw:flex-col tw:gap-1">
          <span className="tw:text-xs tw:text-tertiary">Heading</span>
          <input
            className={FIELD}
            onChange={(event) =>
              onChange(setWidgetTitle(draft, widget.id, event.target.value))
            }
            placeholder={spec.label}
            value={widget.title ?? ''}
          />
        </label>
        <WidgetConfig draft={draft} onChange={onChange} widget={widget} />
      </div>
    </div>
  );
}

/** The settings a widget has, if it has any. */
function WidgetConfig({
  widget,
  draft,
  onChange,
}: {
  widget: HomeWidget;
  draft: HomeLayout;
  onChange: (layout: HomeLayout) => void;
}) {
  const patch = (fields: Record<string, unknown>) =>
    onChange(setWidgetConfig(draft, widget.id, fields));

  switch (widget.type) {
    case 'CHART_ASSETS_BY_TYPE':
    case 'CHART_POLICIES_BY_STATE':
    case 'CHART_POLICIES_BY_SCOPE':
    case 'CHART_SOURCES_BY_MODE': {
      const shape = widget.config?.shape === 'DONUT' ? 'DONUT' : 'BARS';
      return (
        <div className="tw:flex tw:flex-col tw:gap-1">
          <span className="tw:text-xs tw:text-tertiary">Drawn as</span>
          <div className="tw:flex tw:gap-2">
            {(['BARS', 'DONUT'] as const).map((option) => (
              <button
                aria-pressed={shape === option}
                className={`tw:flex-1 tw:cursor-pointer tw:rounded-md tw:border tw:px-2.5 tw:py-2 tw:text-sm tw:transition ${
                  shape === option
                    ? 'tw:border-brand tw:bg-utility-brand-50 tw:text-primary'
                    : 'tw:border-secondary tw:bg-primary tw:text-secondary tw:hover:bg-secondary'
                }`}
                key={option}
                onClick={() => patch({ shape: option })}
                type="button">
                {option === 'BARS' ? 'Bars' : 'Donut'}
              </button>
            ))}
          </div>
        </div>
      );
    }

    case 'RECENT_POLICIES':
    case 'SOURCES': {
      const limit =
        typeof widget.config?.limit === 'number' ? widget.config.limit : 5;
      return (
        <label className="tw:flex tw:flex-col tw:gap-1">
          <span className="tw:text-xs tw:text-tertiary">
            How many rows (3–12)
          </span>
          <input
            className={FIELD}
            max={12}
            min={3}
            onChange={(event) => patch({ limit: Number(event.target.value) })}
            type="number"
            value={limit}
          />
        </label>
      );
    }

    case 'EXPIRING_ACCESS':
    case 'ACCESS_REQUEST_STATS': {
      // The ranges mirror HomeLayoutValidator, which clamps whatever is sent.
      const range =
        widget.type === 'EXPIRING_ACCESS'
          ? { key: 'withinDays', label: 'Ending within (days, 1–90)', min: 1, max: 90, fallback: 14 }
          : { key: 'days', label: 'Counted over (days, 7–365)', min: 7, max: 365, fallback: 90 };
      const span =
        typeof widget.config?.[range.key] === 'number'
          ? (widget.config[range.key] as number)
          : range.fallback;
      const limit =
        typeof widget.config?.limit === 'number' ? widget.config.limit : 8;
      return (
        <div className="tw:grid tw:grid-cols-2 tw:gap-2">
          <label className="tw:flex tw:flex-col tw:gap-1">
            <span className="tw:text-xs tw:text-tertiary">{range.label}</span>
            <input
              className={FIELD}
              max={range.max}
              min={range.min}
              onChange={(event) =>
                patch({ [range.key]: Number(event.target.value) })
              }
              type="number"
              value={span}
            />
          </label>
          <label className="tw:flex tw:flex-col tw:gap-1">
            <span className="tw:text-xs tw:text-tertiary">Rows (3–20)</span>
            <input
              className={FIELD}
              max={20}
              min={3}
              onChange={(event) => patch({ limit: Number(event.target.value) })}
              type="number"
              value={limit}
            />
          </label>
        </div>
      );
    }

    case 'NOTE':
      return (
        <label className="tw:flex tw:flex-col tw:gap-1 tw:sm:col-span-2">
          <span className="tw:text-xs tw:text-tertiary">Text</span>
          <textarea
            className={`${FIELD} tw:min-h-24`}
            onChange={(event) => patch({ text: event.target.value })}
            placeholder="A reminder, a contact, a standing instruction…"
            value={typeof widget.config?.text === 'string' ? widget.config.text : ''}
          />
        </label>
      );

    case 'HTML':
      return (
        <label className="tw:flex tw:flex-col tw:gap-1 tw:sm:col-span-2">
          <span className="tw:text-xs tw:text-tertiary">
            HTML — scripts, styles, frames and forms are removed when this is saved. For a button
            that opens a page, write{' '}
            <code className="tw:font-mono">{'<a href="https://…" class="arak-button">Open</a>'}</code>; the
            only classes kept are <code className="tw:font-mono">arak-button</code> and{' '}
            <code className="tw:font-mono">arak-button-secondary</code>.
          </span>
          <textarea
            className={`${FIELD} tw:min-h-32 tw:font-mono tw:text-xs`}
            onChange={(event) => patch({ html: event.target.value })}
            placeholder="<h3>Team notes</h3><ul><li>…</li></ul>"
            spellCheck={false}
            value={typeof widget.config?.html === 'string' ? widget.config.html : ''}
          />
        </label>
      );

    case 'VIDEO':
      return (
        <div className="tw:grid tw:gap-2 tw:sm:col-span-2 tw:sm:grid-cols-2">
          <label className="tw:flex tw:flex-col tw:gap-1">
            <span className="tw:text-xs tw:text-tertiary">
              YouTube, Vimeo, or an .mp4 / .webm / .ogg address
            </span>
            <input
              className={FIELD}
              onChange={(event) => patch({ url: event.target.value })}
              placeholder="https://www.youtube.com/watch?v=…"
              value={typeof widget.config?.url === 'string' ? widget.config.url : ''}
            />
          </label>
          <label className="tw:flex tw:flex-col tw:gap-1">
            <span className="tw:text-xs tw:text-tertiary">Caption</span>
            <input
              className={FIELD}
              onChange={(event) => patch({ caption: event.target.value })}
              placeholder="Optional"
              value={
                typeof widget.config?.caption === 'string'
                  ? widget.config.caption
                  : ''
              }
            />
          </label>
        </div>
      );

    case 'LINKS':
      return (
        <LinksConfig
          links={Array.isArray(widget.config?.links) ? (widget.config.links as HomeLink[]) : []}
          onChange={(links) => patch({ links })}
        />
      );

    default:
      return (
        <p className="tw:self-end tw:text-xs tw:text-quaternary">
          {specOf(widget.type).blurb}
        </p>
      );
  }
}

function LinksConfig({
  links,
  onChange,
}: {
  links: HomeLink[];
  onChange: (links: HomeLink[]) => void;
}) {
  const set = (index: number, patch: Partial<HomeLink>) =>
    onChange(links.map((link, at) => (at === index ? { ...link, ...patch } : link)));

  return (
    <div className="tw:flex tw:flex-col tw:gap-2 tw:sm:col-span-2">
      <span className="tw:text-xs tw:text-tertiary">Links (up to 12)</span>
      {links.map((link, index) => (
        <div className="tw:flex tw:items-center tw:gap-2" key={index}>
          <input
            aria-label={`Link ${index + 1} label`}
            className={`${FIELD} tw:sm:w-48`}
            onChange={(event) => set(index, { label: event.target.value })}
            placeholder="Label"
            value={link.label ?? ''}
          />
          <input
            aria-label={`Link ${index + 1} address`}
            className={FIELD}
            onChange={(event) => set(index, { url: event.target.value })}
            placeholder="https://…"
            value={link.url ?? ''}
          />
          <button
            aria-label={`Remove link ${index + 1}`}
            className={ICON_BUTTON}
            onClick={() => onChange(links.filter((_, at) => at !== index))}
            type="button">
            <XClose className="tw:size-3.5" />
          </button>
        </div>
      ))}
      {links.length < 12 && (
        <button
          className="tw:flex tw:w-fit tw:cursor-pointer tw:items-center tw:gap-1 tw:text-xs tw:font-semibold tw:text-brand-secondary tw:hover:underline"
          onClick={() => onChange([...links, { label: '', url: '' }])}
          type="button">
          <Plus className="tw:size-3.5" />
          Add a link
        </button>
      )}
    </div>
  );
}
