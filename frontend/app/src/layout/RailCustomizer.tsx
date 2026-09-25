import { useState } from 'react';
import { Dialog, Heading, Modal, ModalOverlay } from 'react-aria-components';
import { ChevronDown, ChevronUp, DotsGrid } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Toggle } from '@openmetadata/ui-core-components/components/base/toggle/toggle';
import type { RailDensity, RailEntry } from '../api/rail';
import type { RailItem } from './navigation';

/**
 * Customize: which sections one person keeps in the rail, and in what order.
 *
 * Drag a row, or use its arrows -- the arrows are the way for a keyboard, and
 * the drag is the way for everybody else. Nothing changes until Save, so a
 * row dragged to the wrong place is one Cancel away from where it was.
 *
 * It lists only what this account may open. Hiding a section is not giving
 * anything up: the page is still there by its address and from wherever else
 * it is linked, and showing one grants nothing the account did not have.
 */
const SIZES: { value: RailDensity; label: string; hint: string }[] = [
  { value: 'comfortable', label: 'Comfortable', hint: 'Large rows and icons' },
  { value: 'compact', label: 'Compact', hint: 'Narrower, shorter rows' },
];

export default function RailCustomizer({
  items,
  arranged,
  density,
  saving,
  error,
  onClose,
  onSave,
  onReset,
}: {
  items: RailItem[];
  /** Whether this person has an arrangement of their own to reset. */
  arranged: boolean;
  /** How large the rail is drawn now. */
  density: RailDensity;
  saving: boolean;
  error: string | null;
  onClose: () => void;
  onSave: (sections: RailEntry[], density: RailDensity) => void;
  onReset: () => void;
}) {
  // Mounted only while open, so each opening starts from the rail as it is
  // rather than from a draft somebody abandoned last time.
  const [draft, setDraft] = useState(items);
  const [size, setSize] = useState(density);
  const [dragging, setDragging] = useState<number | null>(null);

  const move = (from: number, to: number) => {
    if (to < 0 || to >= draft.length || from === to) {
      return;
    }
    setDraft((current) => {
      const next = [...current];
      const [item] = next.splice(from, 1);
      next.splice(to, 0, item);
      return next;
    });
  };

  const toggle = (index: number, shown: boolean) =>
    setDraft((current) => current.map((item, i) => (i === index ? { ...item, shown } : item)));

  const shownCount = draft.filter((item) => item.shown).length;
  const dirty =
    size !== density ||
    draft.some((item, i) => item.section !== items[i]?.section || item.shown !== items[i]?.shown);

  return (
    <ModalOverlay
      className="tw:fixed tw:inset-0 tw:z-50 tw:flex tw:items-center tw:justify-center tw:bg-overlay/70 tw:p-4 tw:backdrop-blur-sm"
      isDismissable
      isOpen
      onOpenChange={(open) => {
        if (!open) onClose();
      }}>
      <Modal className="tw:w-full tw:max-w-xl">
        <Dialog className="tw:flex tw:max-h-[85vh] tw:flex-col tw:overflow-hidden tw:rounded-2xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xl tw:outline-none">
          <div className="tw:border-b tw:border-secondary tw:px-6 tw:pt-5 tw:pb-4">
            <Heading className="tw:text-lg tw:font-semibold tw:text-primary" slot="title">
              Customize your rail
            </Heading>
            <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
              Pick the sections you keep on the left, and drag them into the order you use them.
              Only you see this, and it changes nobody&rsquo;s access &mdash; a hidden section is
              still one link away.
            </p>
          </div>

          <div className="tw:border-b tw:border-secondary tw:px-6 tw:py-3">
            <p className="tw:mb-2 tw:text-xs tw:font-semibold tw:text-tertiary" id="rail-size-label">
              Size
            </p>
            <div
              aria-labelledby="rail-size-label"
              className="tw:grid tw:grid-cols-2 tw:gap-2"
              role="radiogroup">
              {SIZES.map((option) => {
                const chosen = size === option.value;
                return (
                  <button
                    aria-checked={chosen}
                    className={`tw:flex tw:flex-col tw:items-start tw:rounded-lg tw:border tw:px-3 tw:py-2 tw:text-left tw:outline-focus-ring tw:transition tw:duration-100 tw:focus-visible:outline-2 ${
                      chosen
                        ? 'tw:border-brand tw:bg-brand-primary'
                        : 'tw:border-secondary tw:hover:border-primary tw:hover:bg-primary_hover'
                    }`}
                    key={option.value}
                    onClick={() => setSize(option.value)}
                    role="radio"
                    type="button">
                    <span
                      className={`tw:text-sm tw:font-semibold ${
                        chosen ? 'tw:text-brand-secondary' : 'tw:text-primary'
                      }`}>
                      {option.label}
                    </span>
                    <span className="tw:text-xs tw:text-tertiary">{option.hint}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <ul
            aria-label="Rail sections"
            className="tw:flex tw:min-h-0 tw:flex-1 tw:flex-col tw:gap-1.5 tw:overflow-y-auto tw:px-4 tw:py-4">
            {draft.map((item, index) => {
              const Icon = item.section.icon;
              const label = item.section.label;
              return (
                <li
                  className={`tw:group tw:flex tw:items-center tw:gap-3 tw:rounded-xl tw:border tw:px-3 tw:py-2.5 tw:transition tw:duration-150 ${
                    dragging === index
                      ? 'tw:scale-[1.01] tw:border-brand tw:bg-brand-primary tw:shadow-md'
                      : 'tw:border-secondary tw:bg-primary tw:hover:border-primary tw:hover:shadow-xs'
                  } ${item.shown ? '' : 'tw:bg-secondary'}`}
                  data-shown={item.shown}
                  draggable
                  key={item.section.href}
                  onDragEnd={() => setDragging(null)}
                  onDragOver={(event) => {
                    event.preventDefault();
                    if (dragging !== null && dragging !== index) {
                      move(dragging, index);
                      setDragging(index);
                    }
                  }}
                  onDragStart={(event) => {
                    event.dataTransfer.effectAllowed = 'move';
                    event.dataTransfer.setData('text/plain', item.section.href);
                    setDragging(index);
                  }}>
                  <DotsGrid
                    aria-hidden
                    className="tw:size-4 tw:shrink-0 tw:cursor-grab tw:text-fg-quaternary tw:group-hover:text-fg-tertiary"
                  />
                  <span
                    className={`tw:flex tw:size-9 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:transition ${
                      item.shown ? 'tw:bg-brand-primary' : 'tw:bg-tertiary'
                    }`}>
                    {Icon && (
                      <Icon
                        aria-hidden
                        className={`tw:size-5 ${item.shown ? 'tw:text-fg-brand-primary' : 'tw:text-fg-quaternary'}`}
                      />
                    )}
                  </span>
                  <div className="tw:min-w-0 tw:flex-1">
                    <p
                      className={`tw:flex tw:items-center tw:gap-2 tw:text-sm tw:font-semibold ${
                        item.shown ? 'tw:text-primary' : 'tw:text-tertiary'
                      }`}>
                      <span className="tw:truncate">{label}</span>
                      {item.section.defaultShown === false && (
                        <span className="tw:shrink-0 tw:rounded-full tw:bg-secondary tw:px-2 tw:py-0.5 tw:text-xs tw:font-medium tw:text-tertiary">
                          Also in Settings
                        </span>
                      )}
                    </p>
                    <p className="tw:truncate tw:text-xs tw:text-quaternary" title={item.section.description}>
                      {item.section.description}
                    </p>
                  </div>
                  <div className="tw:flex tw:shrink-0 tw:items-center">
                    <button
                      aria-label={`Move ${label} up`}
                      className="tw:rounded-md tw:p-1 tw:text-fg-quaternary tw:outline-focus-ring tw:hover:bg-primary_hover tw:hover:text-fg-secondary tw:focus-visible:outline-2 tw:disabled:opacity-30"
                      disabled={index === 0}
                      onClick={() => move(index, index - 1)}
                      type="button">
                      <ChevronUp aria-hidden className="tw:size-4" />
                    </button>
                    <button
                      aria-label={`Move ${label} down`}
                      className="tw:rounded-md tw:p-1 tw:text-fg-quaternary tw:outline-focus-ring tw:hover:bg-primary_hover tw:hover:text-fg-secondary tw:focus-visible:outline-2 tw:disabled:opacity-30"
                      disabled={index === draft.length - 1}
                      onClick={() => move(index, index + 1)}
                      type="button">
                      <ChevronDown aria-hidden className="tw:size-4" />
                    </button>
                  </div>
                  <Toggle
                    aria-label={`Show ${label} in the rail`}
                    isSelected={item.shown}
                    onChange={(shown) => toggle(index, shown)}
                    size="sm"
                  />
                </li>
              );
            })}
          </ul>

          <div className="tw:border-t tw:border-secondary tw:bg-secondary_subtle tw:px-6 tw:py-4">
            {error && (
              <p className="tw:mb-3 tw:rounded-lg tw:bg-error-primary tw:p-3 tw:text-sm tw:text-error-primary" role="alert">
                {error}
              </p>
            )}
            {shownCount === 0 && (
              <p className="tw:mb-3 tw:text-sm tw:text-warning-primary" role="status">
                Keep at least one section in the rail.
              </p>
            )}
            <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-3">
              <p className="tw:text-xs tw:text-tertiary">
                {shownCount} of {draft.length} shown
              </p>
              <div className="tw:ml-auto tw:flex tw:items-center tw:gap-2">
                <Button
                  color="link-gray"
                  isDisabled={!arranged || saving}
                  onPress={onReset}
                  size="sm">
                  Reset to default
                </Button>
                <Button color="secondary" onPress={onClose} size="sm">
                  Cancel
                </Button>
                <Button
                  isDisabled={!dirty || shownCount === 0 || saving}
                  onPress={() =>
                    onSave(
                      draft.map((item) => ({ href: item.section.href, shown: item.shown })),
                      size
                    )
                  }
                  size="sm">
                  {saving ? 'Saving…' : 'Save'}
                </Button>
              </div>
            </div>
          </div>
        </Dialog>
      </Modal>
    </ModalOverlay>
  );
}
