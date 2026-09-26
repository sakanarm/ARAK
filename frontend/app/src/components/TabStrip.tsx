import { useRef, type KeyboardEvent } from 'react';

/**
 * The underlined tab row an asset page opens with, for any page that splits
 * into a few views: a workflow's builder and its history, a policy's reading
 * and its impact.
 *
 * <p>Only the strip: the caller renders the chosen panel, labelled by the tab
 * this returns an id for ({@link tabId}). Arrow keys move along the strip and
 * choose as they go, the way a tablist does.
 */

export interface TabItem<T extends string> {
  id: T;
  label: string;
  /** A number beside the label; omitted, nothing is shown. */
  count?: number;
}

export function tabId(prefix: string, id: string): string {
  return `${prefix}-tab-${id}`;
}

export function panelId(prefix: string, id: string): string {
  return `${prefix}-panel-${id}`;
}

export default function TabStrip<T extends string>({
  tabs,
  value,
  onChange,
  label,
  idPrefix,
}: {
  tabs: TabItem<T>[];
  value: T;
  onChange: (next: T) => void;
  label: string;
  /** Ties each tab to its panel; the caller gives the panel {@link panelId}. */
  idPrefix: string;
}) {
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let next = -1;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    if (next < 0) return;
    event.preventDefault();
    onChange(tabs[next].id);
    refs.current[tabs[next].id]?.focus();
  }

  return (
    <div className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:shadow-xs">
      <div aria-label={label} className="tw:flex tw:gap-2 tw:overflow-x-auto tw:overflow-y-hidden" role="tablist">
        {tabs.map((tab, index) => {
          const active = tab.id === value;
          return (
            <button
              aria-controls={panelId(idPrefix, tab.id)}
              aria-selected={active}
              className={`tw:relative tw:flex tw:shrink-0 tw:cursor-pointer tw:items-center tw:gap-2 tw:px-3 tw:py-3.5 tw:text-sm tw:font-semibold ${
                active ? 'tw:text-brand-secondary' : 'tw:text-tertiary tw:hover:text-primary'
              }`}
              id={tabId(idPrefix, tab.id)}
              key={tab.id}
              onClick={() => onChange(tab.id)}
              onKeyDown={(event) => onKeyDown(event, index)}
              ref={(node) => {
                refs.current[tab.id] = node;
              }}
              role="tab"
              tabIndex={active ? 0 : -1}
              type="button">
              {tab.label}
              {tab.count !== undefined && (
                <span
                  className={`tw:rounded-full tw:px-2 tw:py-0.5 tw:text-xs tw:font-medium tw:tabular-nums ${
                    active ? 'tw:bg-brand-solid tw:text-white' : 'tw:bg-secondary tw:text-tertiary'
                  }`}>
                  {tab.count}
                </span>
              )}
              {active && (
                <span className="tw:absolute tw:inset-x-3 tw:bottom-0 tw:h-0.5 tw:rounded-full tw:bg-brand-solid" />
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}
