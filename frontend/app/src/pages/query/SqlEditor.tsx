import {
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
} from 'react';
import { createPortal } from 'react-dom';
import { Code02, Columns01, Table } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import {
  accept,
  contextAt,
  referencedTables,
  suggest,
  type CompletionContext,
  type CompletionTable,
  type Suggestion,
} from './sqlCompletion';

/**
 * A small SQL editor: a gutter, a highlighted layer, and a real textarea on
 * top of it with transparent text.
 *
 * <p>Deliberately not Monaco. The editor is a few hundred lines here against
 * several megabytes of bundle, and what it needs of a language server is small:
 * the tables this source holds, their columns, and a handful of keywords. That
 * is what {@link sqlCompletion} works out from the text, and the page supplies
 * from the catalog — so a suggested table is one the proxy can resolve, and the
 * list can say beside it whether the reader will be let in or will have to ask.
 *
 * <p>The two layers must stay in exact typographic lockstep, so the font, size,
 * line height and padding are set once in {@link SURFACE} and used by both, and
 * by the hidden copy that finds where the caret is on screen.
 */

const SURFACE =
  'tw:font-mono tw:text-[13px] tw:leading-[20px] tw:tracking-normal tw:whitespace-pre-wrap tw:break-words';

/** Reserved words worth colouring. Not the whole grammar, and not meant to be. */
const KEYWORDS = new Set(
  (
    'select from where group by having order limit offset join inner left right full outer on ' +
    'as and or not in is null like ilike between exists union all distinct with case when then ' +
    'else end asc desc using cross natural over partition window fetch next rows only cast ' +
    'true false interval date timestamp count sum avg min max coalesce nullif extract substring ' +
    'position trim upper lower length round abs current_date current_timestamp'
  ).split(' ')
);

type Token = { text: string; kind: 'kw' | 'str' | 'num' | 'comment' | 'punct' | 'plain' };

/**
 * One pass, left to right.
 *
 * <p>The order of the branches is the whole correctness argument: a quote or a
 * comment opener swallows to its terminator before anything inside it can be
 * mistaken for a keyword, which is why `'select'` in a string stays a string.
 */
function tokenize(sql: string): Token[] {
  const out: Token[] = [];
  let i = 0;

  const push = (text: string, kind: Token['kind']) => {
    const last = out[out.length - 1];
    if (last && last.kind === kind) {
      last.text += text;
    } else {
      out.push({ text, kind });
    }
  };

  while (i < sql.length) {
    const c = sql[i];

    if (c === '-' && sql[i + 1] === '-') {
      const end = sql.indexOf('\n', i);
      const stop = end === -1 ? sql.length : end;
      push(sql.slice(i, stop), 'comment');
      i = stop;
      continue;
    }
    if (c === '/' && sql[i + 1] === '*') {
      const end = sql.indexOf('*/', i + 2);
      const stop = end === -1 ? sql.length : end + 2;
      push(sql.slice(i, stop), 'comment');
      i = stop;
      continue;
    }
    if (c === "'" || c === '"') {
      let j = i + 1;
      // A doubled quote is an escaped quote in SQL, not a terminator.
      while (j < sql.length) {
        if (sql[j] === c && sql[j + 1] === c) {
          j += 2;
          continue;
        }
        if (sql[j] === c) {
          j += 1;
          break;
        }
        j += 1;
      }
      push(sql.slice(i, j), 'str');
      i = j;
      continue;
    }
    if (/[0-9]/.test(c)) {
      let j = i;
      while (j < sql.length && /[0-9.]/.test(sql[j])) j += 1;
      push(sql.slice(i, j), 'num');
      i = j;
      continue;
    }
    if (/[A-Za-z_@]/.test(c)) {
      let j = i;
      while (j < sql.length && /[A-Za-z0-9_@$]/.test(sql[j])) j += 1;
      const word = sql.slice(i, j);
      push(word, KEYWORDS.has(word.toLowerCase()) ? 'kw' : 'plain');
      i = j;
      continue;
    }
    if (/[(),.;*=<>+\-/%|]/.test(c)) {
      push(c, 'punct');
      i += 1;
      continue;
    }
    push(c, 'plain');
    i += 1;
  }
  return out;
}

const COLOUR: Record<Token['kind'], string> = {
  kw: 'tw:text-fg-brand-primary tw:font-semibold',
  str: 'tw:text-success-primary',
  num: 'tw:text-warning-primary',
  comment: 'tw:text-quaternary tw:italic',
  punct: 'tw:text-tertiary',
  plain: 'tw:text-primary',
};

/** What the page knows that the text does not. */
export interface SqlCompletionSource {
  /** Tables the proxy can resolve on the chosen source. */
  tables: CompletionTable[];
  /** Column names by table FQN; a table missing here is still being fetched. */
  columns: Record<string, string[] | undefined>;
  /**
   * Whether the reader can open each table, by FQN. Left out when the answer
   * would be about somebody else — "run as" — rather than about the reader.
   */
  readable?: Record<string, boolean | undefined>;
  /** Asked for when a suggestion needs the columns of these tables. */
  onNeedColumns?: (fqns: string[]) => void;
}

export interface SqlEditorProps {
  value: string;
  onChange: (value: string) => void;
  /** Ctrl/⌘+Enter. Given the editor's current text, not the debounced state. */
  onRun: () => void;
  disabled?: boolean;
  /** Left out, the editor offers nothing and Tab only indents. */
  completion?: SqlCompletionSource;
}

/** The list is never taller than this, so it cannot cover the whole editor. */
const LIST_HEIGHT = 264;
const LIST_WIDTH = 360;

export default function SqlEditor({
  value,
  onChange,
  onRun,
  disabled,
  completion,
}: SqlEditorProps) {
  const textarea = useRef<HTMLTextAreaElement>(null);
  const highlight = useRef<HTMLPreElement>(null);
  const gutter = useRef<HTMLDivElement>(null);
  const mirror = useRef<HTMLDivElement>(null);
  const marker = useRef<HTMLSpanElement>(null);
  const listId = useId();

  const tokens = useMemo(() => tokenize(value), [value]);
  const lineCount = useMemo(() => value.split('\n').length, [value]);

  // Where the caret was when the list was last asked for. Held apart from the
  // text because the text is the page's state and arrives a render later.
  const [caret, setCaret] = useState<number | null>(null);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [anchor, setAnchor] = useState<{ top: number; left: number; above: boolean } | null>(
    null
  );

  const context: CompletionContext | null = useMemo(
    () => (completion && caret !== null ? contextAt(value, Math.min(caret, value.length)) : null),
    [completion, caret, value]
  );

  const suggestions: Suggestion[] = useMemo(
    () =>
      open && completion && context
        ? suggest(value, context, completion.tables, completion.columns)
        : [],
    [open, completion, context, value]
  );
  const showing = suggestions.length > 0 && !disabled;

  // Columns are fetched per table and only once something needs them: the
  // table after the dot, or every table the statement already names.
  const needed = useMemo(() => {
    if (!open || !completion || !context) return [];
    const referenced = referencedTables(value, completion.tables);
    if (context.mode === 'qualified') {
      const qualifier = (context.qualifier ?? '').toLowerCase();
      const table =
        referenced.get(qualifier) ??
        completion.tables.find((t) => `${t.schema}.${t.name}`.toLowerCase() === qualifier);
      return table ? [table.fqn] : [];
    }
    if (context.mode === 'any') {
      return [...new Set([...referenced.values()].map((t) => t.fqn))];
    }
    return [];
  }, [open, completion, context, value]);
  const neededKey = needed.join('\n');
  const onNeedColumns = completion?.onNeedColumns;
  useEffect(() => {
    if (needed.length > 0) onNeedColumns?.(needed);
    // The key stands for the list, which is rebuilt on every keystroke.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [neededKey, onNeedColumns]);

  // Both layers scroll with the textarea rather than with the wheel, so a
  // caret moved by the keyboard keeps the highlight underneath it.
  useEffect(() => {
    const el = textarea.current;
    if (!el) return;
    const sync = () => {
      if (highlight.current) {
        highlight.current.scrollTop = el.scrollTop;
        highlight.current.scrollLeft = el.scrollLeft;
      }
      if (gutter.current) {
        gutter.current.scrollTop = el.scrollTop;
      }
      // The list is placed against the caret on screen, which has just moved.
      setOpen(false);
    };
    el.addEventListener('scroll', sync);
    return () => el.removeEventListener('scroll', sync);
  }, []);

  // Placed in the viewport, not inside the editor: the editor clips, and a
  // list cut off at the bottom of a 200px pane is a list with two rows in it.
  useLayoutEffect(() => {
    if (!showing || !marker.current || !textarea.current) {
      setAnchor(null);
      return;
    }
    const rect = marker.current.getBoundingClientRect();
    const scroll = textarea.current.scrollTop;
    const top = rect.top - scroll;
    const below = top + 20 + LIST_HEIGHT <= window.innerHeight;
    setAnchor({
      top: below ? top + 22 : Math.max(8, top - 4),
      left: Math.max(8, Math.min(rect.left, window.innerWidth - LIST_WIDTH - 8)),
      above: !below,
    });
  }, [showing, context?.from, value]);

  useEffect(() => {
    if (!showing) return;
    const close = () => setOpen(false);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);
    return () => {
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', close, true);
    };
  }, [showing]);

  useEffect(() => {
    setActive(0);
  }, [context?.from, context?.mode, context?.qualifier]);

  function handleChange(event: ChangeEvent<HTMLTextAreaElement>) {
    const el = event.target;
    onChange(el.value);
    if (!completion) return;
    setCaret(el.selectionStart);
    // Typing opens the list; deleting keeps it as it was, so backing out of a
    // typo does not throw a list at somebody who had closed it.
    const kind = (event.nativeEvent as InputEvent).inputType ?? 'insertText';
    if (kind.startsWith('insert')) {
      setOpen(true);
    }
  }

  function take(suggestion: Suggestion) {
    const el = textarea.current;
    if (!el || !context) return;
    const next = accept(value, el.selectionStart, context, suggestion);
    onChange(next.text);
    setOpen(false);
    setCaret(next.caret);
    requestAnimationFrame(() => {
      el.focus();
      el.selectionStart = next.caret;
      el.selectionEnd = next.caret;
    });
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
      event.preventDefault();
      onRun();
      return;
    }
    if (completion && event.ctrlKey && event.key === ' ') {
      event.preventDefault();
      setCaret(event.currentTarget.selectionStart);
      setOpen(true);
      return;
    }
    if (showing) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        const step = event.key === 'ArrowDown' ? 1 : -1;
        setActive((index) => (index + step + suggestions.length) % suggestions.length);
        return;
      }
      if (event.key === 'Enter' || event.key === 'Tab') {
        event.preventDefault();
        take(suggestions[Math.min(active, suggestions.length - 1)]);
        return;
      }
      if (event.key === 'Escape') {
        // Stopped here, so the page does not also read it as "leave full screen".
        event.preventDefault();
        event.stopPropagation();
        setOpen(false);
        return;
      }
    }
    if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End', 'PageUp', 'PageDown'].includes(event.key)) {
      setOpen(false);
    }
    if (event.key === 'Tab') {
      // Tab indents here instead of leaving the field. That traps keyboard
      // users, so Escape-then-Tab is the way out and the hint below says so.
      event.preventDefault();
      const el = event.currentTarget;
      const { selectionStart: start, selectionEnd: end } = el;
      const next = `${value.slice(0, start)}  ${value.slice(end)}`;
      onChange(next);
      requestAnimationFrame(() => {
        el.selectionStart = start + 2;
        el.selectionEnd = start + 2;
      });
    }
  }

  const activeId = showing ? `${listId}-${Math.min(active, suggestions.length - 1)}` : undefined;

  const list =
    showing && anchor
      ? createPortal(
          <div
            className="tw:fixed tw:z-80 tw:flex tw:flex-col tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-lg"
            style={{
              top: anchor.top,
              left: anchor.left,
              width: LIST_WIDTH,
              maxHeight: LIST_HEIGHT,
              transform: anchor.above ? 'translateY(-100%)' : undefined,
            }}>
          <ul
            aria-label="Suggestions"
            className="tw:min-h-0 tw:flex-1 tw:overflow-auto tw:p-1"
            id={listId}
            role="listbox">
            {suggestions.map((suggestion, index) => (
              <SuggestionRow
                active={index === Math.min(active, suggestions.length - 1)}
                id={`${listId}-${index}`}
                key={`${suggestion.kind}:${suggestion.label}`}
                onPick={() => take(suggestion)}
                onPoint={() => setActive(index)}
                readable={
                  suggestion.fqn ? completion?.readable?.[suggestion.fqn] : undefined
                }
                suggestion={suggestion}
              />
            ))}
          </ul>
          {/* The keys, said once under the list rather than learned by
              accident; hidden from assistive tech, which announces the
              listbox and its own navigation already. */}
          <div
            aria-hidden="true"
            className="tw:flex tw:shrink-0 tw:items-center tw:gap-3 tw:border-t tw:border-secondary tw:bg-secondary tw:px-3 tw:py-1.5 tw:text-xs tw:text-quaternary">
            <span>
              <Key>↑</Key> <Key>↓</Key> move
            </span>
            <span>
              <Key>Enter</Key> or <Key>Tab</Key> insert
            </span>
            <span>
              <Key>Esc</Key> close
            </span>
          </div>
          </div>,
          document.body
        )
      : null;

  return (
    <div className="tw:relative tw:flex tw:min-h-0 tw:flex-1 tw:overflow-hidden tw:rounded-lg tw:border tw:border-secondary tw:bg-primary">
      <div
        aria-hidden="true"
        className="tw:shrink-0 tw:select-none tw:overflow-hidden tw:border-r tw:border-secondary tw:bg-secondary tw:py-3 tw:text-right"
        ref={gutter}>
        {Array.from({ length: lineCount }, (_, index) => (
          <div
            className={`${SURFACE} tw:px-2.5 tw:text-quaternary`}
            key={index}>
            {index + 1}
          </div>
        ))}
      </div>

      <div className="tw:relative tw:min-w-0 tw:flex-1">
        <pre
          aria-hidden="true"
          className={`${SURFACE} tw:pointer-events-none tw:absolute tw:inset-0 tw:overflow-auto tw:px-3 tw:py-3`}
          ref={highlight}>
          {tokens.map((token, index) => (
            <span className={COLOUR[token.kind]} key={index}>
              {token.text}
            </span>
          ))}
          {/* Keeps the last line visible when the text ends in a newline. */}
          {'\n'}
        </pre>

        {/* A copy of the text up to the start of the word being completed,
            laid out exactly as the layers above, so the marker at its end sits
            where that word starts on screen. Invisible and unscrolled; the
            textarea's own scroll is subtracted when the list is placed. */}
        {completion && (
          <div
            aria-hidden="true"
            className={`${SURFACE} tw:pointer-events-none tw:invisible tw:absolute tw:inset-x-0 tw:top-0 tw:overflow-hidden tw:px-3 tw:py-3`}
            ref={mirror}>
            {value.slice(0, context?.from ?? 0)}
            <span ref={marker}>{'​'}</span>
          </div>
        )}

        {/* The selection is drawn by the textarea, on top of the layer that
            holds the visible text. An opaque selection colour therefore hides
            the very thing being selected — it has to let the layer below
            through.

            The caret is named explicitly and not left as `currentColor`: the
            text here is transparent so that the highlighted layer shows
            through, and a caret that follows the text colour is therefore
            transparent too. It was drawn, in the right place, in nothing.

            Named through the utility and not as `caret-[var(--color-...)]`:
            the `tw:` prefix renames every theme variable to `--tw-color-*`,
            so the raw name compiles to a rule that resolves to nothing and
            fails exactly as silently as the bug it was meant to fix. */}
        <textarea
          aria-activedescendant={activeId}
          aria-autocomplete={completion ? 'list' : undefined}
          aria-controls={showing ? listId : undefined}
          aria-label="SQL"
          className={`${SURFACE} tw:absolute tw:inset-0 tw:w-full tw:resize-none tw:overflow-auto tw:bg-transparent tw:px-3 tw:py-3 tw:text-transparent tw:caret-text-primary tw:outline-none tw:selection:bg-[rgba(41,112,255,0.28)]`}
          disabled={disabled}
          onBlur={() => setOpen(false)}
          onChange={handleChange}
          onKeyDown={onKeyDown}
          onMouseDown={() => setOpen(false)}
          ref={textarea}
          spellCheck={false}
          value={value}
        />
      </div>

      {list}
    </div>
  );
}

function SuggestionRow({
  suggestion,
  active,
  id,
  readable,
  onPick,
  onPoint,
}: {
  suggestion: Suggestion;
  active: boolean;
  id: string;
  readable: boolean | undefined;
  onPick: () => void;
  onPoint: () => void;
}) {
  const row = useRef<HTMLLIElement>(null);
  useEffect(() => {
    if (active) row.current?.scrollIntoView?.({ block: 'nearest' });
  }, [active]);

  return (
    <li
      aria-selected={active}
      className={`tw:flex tw:cursor-pointer tw:items-center tw:gap-2.5 tw:rounded-md tw:px-2 tw:py-1.5 tw:text-sm ${
        active ? 'tw:bg-active' : 'tw:hover:bg-primary_hover'
      }`}
      id={id}
      // Mouse down, not click: a click lands after the textarea has lost focus
      // and closed the list, so the row it was aimed at is already gone.
      onMouseDown={(event) => {
        event.preventDefault();
        onPick();
      }}
      onMouseEnter={onPoint}
      ref={row}
      role="option">
      <KindIcon kind={suggestion.kind} />
      <span className="tw:min-w-0 tw:flex-1 tw:truncate tw:font-mono tw:text-[13px] tw:text-primary">
        {suggestion.label}
      </span>
      {suggestion.detail && (
        <span className="tw:shrink-0 tw:truncate tw:text-xs tw:text-tertiary">
          {suggestion.detail}
        </span>
      )}
      {readable === true && (
        <Badge color="success" size="sm" type="pill-color">
          Readable
        </Badge>
      )}
      {readable === false && (
        <Badge color="warning" size="sm" type="pill-color">
          Request needed
        </Badge>
      )}
    </li>
  );
}

const KIND: Record<Suggestion['kind'], { icon: typeof Table; tile: string }> = {
  table: { icon: Table, tile: 'tw:bg-utility-brand-50 tw:text-fg-brand-primary' },
  column: { icon: Columns01, tile: 'tw:bg-utility-blue-50 tw:text-utility-blue-600' },
  keyword: { icon: Code02, tile: 'tw:bg-secondary tw:text-fg-quaternary' },
};

/** The small tile the catalog puts beside an entity, so a table reads as a table at a glance. */
function KindIcon({ kind }: { kind: Suggestion['kind'] }) {
  const { icon: Icon, tile } = KIND[kind] ?? KIND.keyword;
  return (
    <span
      aria-hidden="true"
      className={`tw:flex tw:size-6 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-md ${tile}`}
      title={kind}>
      <Icon className="tw:size-3.5" />
    </span>
  );
}

function Key({ children }: { children: string }) {
  return (
    <kbd className="tw:rounded tw:border tw:border-secondary tw:bg-primary tw:px-1 tw:font-sans tw:text-[11px] tw:text-tertiary">
      {children}
    </kbd>
  );
}
