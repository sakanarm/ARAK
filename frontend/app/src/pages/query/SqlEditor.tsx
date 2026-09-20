import { useEffect, useMemo, useRef, type KeyboardEvent } from 'react';

/**
 * A small SQL editor: a gutter, a highlighted layer, and a real textarea on
 * top of it with transparent text.
 *
 * <p>Deliberately not Monaco. The editor is a few hundred lines here against
 * several megabytes of bundle, and nothing on this screen needs completion or a
 * language server — the statement is short, and the part that matters is what
 * comes back underneath it. The two layers must stay in exact typographic
 * lockstep, so the font, size, line height and padding are set once in
 * {@link SURFACE} and used by both.
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

export interface SqlEditorProps {
  value: string;
  onChange: (value: string) => void;
  /** Ctrl/⌘+Enter. Given the editor's current text, not the debounced state. */
  onRun: () => void;
  disabled?: boolean;
}

export default function SqlEditor({
  value,
  onChange,
  onRun,
  disabled,
}: SqlEditorProps) {
  const textarea = useRef<HTMLTextAreaElement>(null);
  const highlight = useRef<HTMLPreElement>(null);
  const gutter = useRef<HTMLDivElement>(null);

  const tokens = useMemo(() => tokenize(value), [value]);
  const lineCount = useMemo(() => value.split('\n').length, [value]);

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
    };
    el.addEventListener('scroll', sync);
    return () => el.removeEventListener('scroll', sync);
  }, []);

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
      event.preventDefault();
      onRun();
      return;
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
          aria-label="SQL"
          className={`${SURFACE} tw:absolute tw:inset-0 tw:w-full tw:resize-none tw:overflow-auto tw:bg-transparent tw:px-3 tw:py-3 tw:text-transparent tw:caret-text-primary tw:outline-none tw:selection:bg-[rgba(41,112,255,0.28)]`}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={onKeyDown}
          ref={textarea}
          spellCheck={false}
          value={value}
        />
      </div>
    </div>
  );
}
