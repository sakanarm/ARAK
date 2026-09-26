import { useId, useState, type ReactNode } from 'react';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { X } from '@untitledui/icons';
import greet from '../assets/mascot/greet.png';
import thinking from '../assets/mascot/thinking.png';
import { NOKRAK } from './nokrak';

/**
 * NokRak on the page itself, not only in the corner.
 *
 * <p>The dock already writes SQL and drafts policies, but somebody looking at
 * an empty editor or an empty form reads the page, not the corner. This is the
 * same engine behind a button where the work is. It sends one sentence and
 * hands back text; what the page does with that text is the page's choice, and
 * on both pages it is "put it where you can read it first" -- a statement is
 * not run, and a policy is not saved, let alone switched on.
 */

/** The button that opens the prompt, with NokRak's face instead of an icon. */
export function NokRakButton({
  label,
  open,
  onPress,
  size = 'sm',
}: {
  label: string;
  open: boolean;
  onPress: () => void;
  size?: 'sm' | 'md';
}) {
  return (
    <Button
      aria-expanded={open}
      color="secondary"
      iconLeading={<img alt="" className="tw:size-5 tw:object-contain" src={greet} />}
      onPress={onPress}
      size={size}>
      {label}
    </Button>
  );
}

/**
 * One sentence in, a draft out. `children` is the answer, drawn by the page
 * because only the page knows what "use this" means there.
 */
export function NokRakPrompt({
  title,
  placeholder,
  hint,
  askLabel,
  pendingLabel,
  pending,
  error,
  onAsk,
  onClose,
  disabled = false,
  floating = false,
  children,
}: {
  title: string;
  placeholder: string;
  /** What happens to the answer, said before the reader asks. */
  hint: string;
  askLabel: string;
  pendingLabel: string;
  pending: boolean;
  /** Already worded for the reader. */
  error: string | null;
  onAsk: (text: string) => void;
  onClose: () => void;
  /**
   * Not ready to ask yet (the query console before a source is chosen). The
   * hint says why; a red alert before anybody asked anything reads as a fault.
   */
  disabled?: boolean;
  /** Drawn over the page rather than in its flow, so opening it moves nothing. */
  floating?: boolean;
  children?: ReactNode;
}) {
  const [text, setText] = useState('');
  const id = useId();
  const ask = () => {
    const trimmed = text.trim();
    if (trimmed && !pending && !disabled) onAsk(trimmed);
  };

  return (
    <section
      aria-label={title}
      className={`tw:rounded-xl tw:border tw:border-secondary tw:bg-primary ${
        floating ? 'tw:shadow-xl' : 'tw:shadow-xs'
      }`}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.stopPropagation();
          onClose();
        }
      }}>
      <header
        className={`tw:flex tw:items-center tw:gap-3 tw:border-b tw:border-secondary ${
          floating ? 'tw:px-3 tw:py-2' : 'tw:px-4 tw:py-3'
        }`}>
        <img
          alt=""
          className={`${floating ? 'tw:size-7' : 'tw:size-9'} tw:shrink-0 tw:object-contain`}
          src={pending ? thinking : greet}
        />
        <div className="tw:min-w-0 tw:flex-1">
          <h2 className="tw:text-sm tw:font-semibold tw:text-primary">{title}</h2>
          <p className="tw:text-xs tw:text-tertiary">
            {NOKRAK.name} ({NOKRAK.thai}) drafts. You read it and decide.
          </p>
        </div>
        <button
          aria-label={`Close ${NOKRAK.name}`}
          className="tw:cursor-pointer tw:rounded tw:p-1 tw:text-fg-quaternary tw:hover:bg-primary_hover"
          onClick={onClose}
          type="button">
          <X className="tw:size-4" />
        </button>
      </header>

      <div className={`tw:flex tw:flex-col tw:gap-3 ${floating ? 'tw:p-3' : 'tw:p-4'}`}>
        <label className="tw:sr-only" htmlFor={id}>
          {title}
        </label>
        <textarea
          autoFocus
          className="tw:w-full tw:resize-y tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:text-primary tw:placeholder:text-quaternary"
          id={id}
          maxLength={2000}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => {
            // Enter asks, as it does in the dock: this is a sentence, not a
            // document. Shift+Enter is there for the rare long one.
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              ask();
            }
          }}
          placeholder={placeholder}
          rows={2}
          value={text}
        />
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-3">
          <Button
            color="primary"
            isDisabled={disabled || pending || text.trim().length === 0}
            onPress={ask}
            size="sm">
            {pending ? pendingLabel : askLabel}
          </Button>
          <span className="tw:text-xs tw:text-tertiary">{hint}</span>
        </div>
        {error && (
          <p className="tw:text-sm tw:text-error-primary" role="alert">
            {error}
          </p>
        )}
        {children}
      </div>
    </section>
  );
}
