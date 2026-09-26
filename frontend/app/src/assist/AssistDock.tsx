import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Check, Copy01, X } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../api/client';
import {
  assistPolicy,
  assistSql,
  fetchMyLlmSetting,
  type AssistFeature,
  type PolicyDraft,
  type SqlDraft,
} from '../api/llm';
import { AssistChat } from './AssistChat';
import { useAssistStore, type AssistMode } from './assistStore';
import { useOfferedFeatures } from './useAssist';
import cheer from '../assets/mascot/cheer.png';
import greet from '../assets/mascot/greet.png';
import shield from '../assets/mascot/shield.png';
import thinking from '../assets/mascot/thinking.png';
import { NOKRAK, pickLine } from './nokrak';

/**
 * The assistant, in the corner, behind a button.
 *
 * <p>Two things were true at once: people could switch an LLM on in Settings
 * and then find it nowhere, and a mascot loose on the page would be the only
 * thing moving in a console whose whole job is to make a warning noticeable.
 * Docking it solves both. It is one small target in the corner that does
 * nothing at all until it is pressed, and pressing it is the door to the only
 * two places the model is any use — turning a question into SQL on the query
 * console, and turning a sentence into a draft policy in the builder.
 *
 * <p>It is drawn only for an account that has switched the assistant on and
 * whose gateway actually answers. Somebody who has not is shown nothing: an
 * advert for a feature they have not configured is not a feature.
 *
 * <p>The poses are states, not decoration. It changes pose when the state
 * changes and at no other time — no idle animation, no bounce, no attention
 * it did not earn.
 *
 * <p>It has a name, NokRak (น้องรักษ์), and a line to say from the corner: a
 * speech bubble with one of a few short offers of help, picked at random and
 * fitted to the page. It says it once when a session starts, for a few
 * seconds, and otherwise only when pointed at -- a greeting, not a nag.
 *
 * <p>Since M28 it also talks: the panel opens on a conversation (AssistChat)
 * that can look things up and hand back cards, with the page's own job -- the
 * query writer, the policy drafter -- one tab across. Each is drawn only for a
 * role the administrator has offered it to; the server refuses the rest.
 */

/** The four poses, and what each one means. */
const POSE: Record<Pose, { src: string; alt: string }> = {
  // Waiting. The only pose it holds while nothing is happening.
  greet: { src: greet, alt: '' },
  // A request is in flight.
  thinking: { src: thinking, alt: '' },
  // A draft came back and is waiting to be read.
  cheer: { src: cheer, alt: '' },
  // Something was refused or discarded. The shield, not a frown: what happened
  // is that a rule held, and that is the assistant doing its job.
  shield: { src: shield, alt: '' },
};

type Pose = 'greet' | 'thinking' | 'cheer' | 'shield';

export default function AssistDock() {
  const open = useAssistStore((state) => state.open);
  const setOpen = useAssistStore((state) => state.setOpen);
  const mode = useAssistStore((state) => state.mode);

  // Asked once and kept: whether the assistant exists for this account is a
  // setting, not a live reading, and re-asking on every navigation would put a
  // request on the wire for a button most people never press.
  const { data: setting } = useQuery({
    queryKey: ['llm-me'],
    queryFn: fetchMyLlmSetting,
    staleTime: 5 * 60_000,
  });

  // Closed on the way out, so the panel is never found hanging open over a
  // page that has no idea it is there.
  useEffect(() => {
    if (!open) {
      return;
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, setOpen]);

  const ready = Boolean(setting?.available && setting.enabled);
  const offered = useOfferedFeatures(ready);

  if (!ready) {
    return null;
  }

  return (
    <>
      {open && (
        <AssistPanel mode={mode} offered={offered ?? []} onClose={() => setOpen(false)} />
      )}
      <DockButton mode={mode} onToggle={() => setOpen(!open)} open={open} />
    </>
  );
}

/**
 * The button itself.
 *
 * <p>Bottom right, clear of the content column, and small enough that it never
 * competes with the page. It sits above everything because the one thing worse
 * than a mascot in the corner is half a mascot behind a table header.
 */
function DockButton({
  mode,
  open,
  onToggle,
}: {
  mode: AssistMode;
  open: boolean;
  onToggle: () => void;
}) {
  const [line, setLine] = useState(() => pickLine(mode));
  const [hover, setHover] = useState(false);
  // Once per browser session, and only for a moment.
  const [greeting, setGreeting] = useState(() => !greetedThisSession());

  // A new page may be a new kind of page; the line should fit where it is.
  useEffect(() => {
    setLine(pickLine(mode));
  }, [mode]);

  useEffect(() => {
    if (!greeting) {
      return;
    }
    markGreeted();
    const timer = window.setTimeout(() => setGreeting(false), 7000);
    return () => window.clearTimeout(timer);
  }, [greeting]);

  function pointAt() {
    setLine(pickLine(mode));
    setHover(true);
  }

  const speaking = !open && (greeting || hover);

  return (
    <>
    {speaking && (
      <div
        aria-hidden
        className="tw:fixed tw:right-22 tw:bottom-7 tw:z-50 tw:max-w-64 tw:rounded-2xl tw:rounded-br-sm tw:border tw:border-secondary tw:bg-primary tw:px-3.5 tw:py-2.5 tw:shadow-lg"
        data-testid="nokrak-bubble">
        <p className="tw:text-xs tw:font-semibold tw:text-brand-secondary">
          {NOKRAK.name} <span className="tw:font-normal tw:text-quaternary">· {NOKRAK.thai}</span>
        </p>
        <p className="tw:mt-0.5 tw:text-sm tw:text-secondary">{line}</p>
      </div>
    )}
    <button
      aria-controls="assist-panel"
      aria-expanded={open}
      aria-label={open ? 'Close the assistant' : 'Open the assistant'}
      className="tw:fixed tw:right-5 tw:bottom-5 tw:z-50 tw:flex tw:size-14 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-full tw:border tw:border-secondary tw:bg-primary tw:shadow-lg tw:outline-focus-ring tw:transition tw:duration-150 tw:hover:scale-105 tw:hover:border-brand tw:focus-visible:outline-2"
      onBlur={() => setHover(false)}
      onClick={() => {
        setGreeting(false);
        onToggle();
      }}
      onFocus={pointAt}
      onMouseEnter={pointAt}
      onMouseLeave={() => setHover(false)}
      title={
        mode === 'sql'
          ? 'Ask for a query in plain words'
          : mode === 'policy'
            ? 'Describe a rule and get a draft'
            : 'The assistant'
      }
      type="button">
      {open ? (
        <X className="tw:size-5 tw:text-tertiary" />
      ) : (
        <img
          alt=""
          className="tw:size-11 tw:object-contain"
          src={POSE.greet.src}
        />
      )}

      {/* A dot rather than a pose change, and only where the assistant can
          actually do something on this page. It is the smallest signal that
          says "there is something here" without moving. */}
      {!open && mode !== 'idle' && (
        <span className="tw:absolute tw:top-0.5 tw:right-0.5 tw:size-3 tw:rounded-full tw:border-2 tw:border-primary tw:bg-brand-solid" />
      )}
    </button>
    </>
  );
}

const GREETED = 'arak.nokrak.greeted';

// Storage can throw (private windows, blocked site data); a greeting that
// cannot be remembered is simply not shown again this page load.
function greetedThisSession(): boolean {
  try {
    return window.sessionStorage.getItem(GREETED) === '1';
  } catch {
    return true;
  }
}

function markGreeted() {
  try {
    window.sessionStorage.setItem(GREETED, '1');
  } catch {
    // Nothing to do: see greetedThisSession.
  }
}

/** Which job serves the page the panel was opened on, if any. */
const PAGE_JOB: Record<Exclude<AssistMode, 'idle'>, AssistFeature> = {
  sql: 'WRITE_SQL',
  policy: 'DRAFT_POLICY',
};

type Tab = 'chat' | 'page';

function AssistPanel({
  mode,
  offered,
  onClose,
}: {
  mode: AssistMode;
  offered: AssistFeature[];
  onClose: () => void;
}) {
  const chatOn = offered.includes('CHAT');
  const pageOn = mode !== 'idle' && offered.includes(PAGE_JOB[mode]);
  const ask = useAssistStore((state) => state.ask);
  // The page's own job first where there is one: somebody who opens the panel
  // on the query console most likely wants the query written.
  const [tab, setTab] = useState<Tab>(pageOn ? 'page' : 'chat');
  const [chatPose, setChatPose] = useState<Pose>('greet');
  const onChatPending = useCallback((pending: boolean, failed: boolean) => {
    setChatPose(pending ? 'thinking' : failed ? 'shield' : 'greet');
  }, []);

  // A question from search is for the conversation, wherever the panel was.
  useEffect(() => {
    if (ask && chatOn) {
      setTab('chat');
    }
  }, [ask, chatOn]);

  const shown: Tab | null =
    tab === 'page' && pageOn ? 'page' : chatOn ? 'chat' : pageOn ? 'page' : null;

  const sourceId = useAssistStore((state) => state.sourceId);
  const engine = useAssistStore((state) => state.engine);
  const deliverSql = useAssistStore((state) => state.deliverSql);
  const deliverPolicy = useAssistStore((state) => state.deliverPolicy);

  const [question, setQuestion] = useState('');
  const box = useRef<HTMLTextAreaElement>(null);

  // Focus goes to the one control anybody opened this for.
  useEffect(() => {
    box.current?.focus();
  }, [mode, shown]);

  const sql = useMutation({
    mutationFn: () =>
      assistSql({
        question,
        sourceId: sourceId ?? '',
        engine: engine ?? undefined,
      }),
  });

  const policy = useMutation({
    mutationFn: () => assistPolicy({ intent: question, sourceId }),
  });

  const job = mode === 'policy' ? policy : sql;
  const onPage = shown === 'page';
  const draft = mode === 'policy' ? policy.data : sql.data;

  // A refusal is not a failure. The server answers 200 with an empty draft and
  // a sentence saying why -- the model would not answer from these tables, or
  // it answered with something that was not a read-only query and it was
  // thrown away. Both are the guardrail working, so both get the shield.
  const refused = mode === 'sql' && Boolean(sql.data?.problem);
  const pagePose: Pose = job.isPending
    ? 'thinking'
    : job.isError || refused
      ? 'shield'
      : draft
        ? 'cheer'
        : 'greet';
  const pose: Pose = shown === 'chat' ? chatPose : pagePose;

  function submit() {
    if (!question.trim() || job.isPending) {
      return;
    }
    if (mode === 'policy') {
      policy.mutate();
    } else {
      sql.mutate();
    }
  }

  return (
    <aside
      aria-label="Assistant"
      className={`tw:fixed tw:right-5 tw:bottom-22 tw:z-50 tw:flex tw:max-h-[70vh] ${shown === 'chat' ? 'tw:h-[min(70vh,40rem)] ' : ''}tw:w-96 tw:max-w-[calc(100vw-2.5rem)] tw:flex-col tw:overflow-hidden tw:rounded-2xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xl`}
      id="assist-panel">
      <header className="tw:flex tw:items-center tw:gap-3 tw:border-b tw:border-secondary tw:bg-secondary tw:px-4 tw:py-3">
        <img
          alt=""
          className="tw:size-10 tw:shrink-0 tw:object-contain"
          src={POSE[pose].src}
        />
        <div className="tw:min-w-0 tw:flex-1">
          <p className="tw:text-sm tw:font-semibold tw:text-primary">
            {NOKRAK.name}{' '}
            <span className="tw:text-xs tw:font-normal tw:text-tertiary">
              · {NOKRAK.thai} · Assistant
            </span>
          </p>
          <p className="tw:truncate tw:text-xs tw:text-tertiary">
            {shown === 'chat'
              ? 'Finds, explains and drafts. You decide.'
              : mode === 'sql'
                ? 'Writes a query against this source'
                : mode === 'policy'
                  ? 'Drafts a policy for you to check'
                  : 'Open a query or a policy to use it'}
          </p>
        </div>
        <button
          aria-label="Close"
          className="tw:cursor-pointer tw:rounded-md tw:p-1 tw:text-tertiary tw:hover:bg-primary_hover"
          onClick={onClose}
          type="button">
          <X className="tw:size-4" />
        </button>
      </header>

      {chatOn && pageOn && (
        <div
          aria-label="What the assistant does"
          className="tw:flex tw:gap-1 tw:border-b tw:border-secondary tw:px-3 tw:pt-2"
          role="tablist">
          <PanelTab onSelect={() => setTab('chat')} selected={shown === 'chat'}>
            Chat
          </PanelTab>
          <PanelTab onSelect={() => setTab('page')} selected={shown === 'page'}>
            {mode === 'sql' ? 'Write a query' : 'Draft a policy'}
          </PanelTab>
        </div>
      )}

      {shown === 'chat' ? (
        <AssistChat onPending={onChatPending} onUsed={onClose} />
      ) : (
      <div className="tw:min-h-0 tw:flex-1 tw:overflow-y-auto tw:p-4">
        {!onPage ? (
          <Idle />
        ) : (
          <>
            <label
              className="tw:text-xs tw:font-medium tw:text-secondary"
              htmlFor="assist-question">
              {mode === 'sql'
                ? 'What do you want to know?'
                : 'What should the rule say?'}
            </label>
            <textarea
              className="tw:mt-1 tw:w-full tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2.5 tw:py-2 tw:text-sm tw:text-primary tw:placeholder:text-quaternary"
              id="assist-question"
              onChange={(event) => setQuestion(event.target.value)}
              onKeyDown={(event) => {
                // Enter sends, because this is one sentence and not a
                // document. Shift+Enter is there for the rare long one.
                if (event.key === 'Enter' && !event.shiftKey) {
                  event.preventDefault();
                  submit();
                }
              }}
              placeholder={
                mode === 'sql'
                  ? 'How many customers were added last month, by branch?'
                  : 'Analysts in Finance may read customer, but never the citizen ID'
              }
              ref={box}
              rows={3}
              value={question}
            />

            {mode === 'sql' && !sourceId && (
              <p className="tw:mt-2 tw:text-xs tw:text-warning-primary">
                Choose a source on the query page first — the tables it can
                write against come from whatever you have selected.
              </p>
            )}

            <div className="tw:mt-3 tw:flex tw:items-center tw:gap-2">
              <Button
                isDisabled={
                  !question.trim() ||
                  job.isPending ||
                  (mode === 'sql' && !sourceId)
                }
                onPress={submit}
                size="sm">
                {job.isPending
                  ? 'Thinking…'
                  : mode === 'sql'
                    ? 'Write the query'
                    : 'Draft the policy'}
              </Button>
              {draft && (
                <span className="tw:text-xs tw:text-quaternary">
                  {draft.model}
                  {draft.personal ? ' · your gateway' : ''}
                </span>
              )}
            </div>

            {job.isError && (
              <p className="tw:mt-3 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-3 tw:text-xs tw:text-error-primary">
                {apiErrorMessage(job.error, 'The assistant could not answer.')}
              </p>
            )}

            {mode === 'sql' && sql.data && (
              <SqlResult
                draft={sql.data}
                onUse={(text) => {
                  deliverSql(text);
                  onClose();
                }}
              />
            )}

            {mode === 'policy' && policy.data && (
              <PolicyResult
                draft={policy.data}
                onUse={(text) => {
                  deliverPolicy(text);
                  onClose();
                }}
              />
            )}
          </>
        )}
      </div>
      )}

      {/*
        Standing, not dismissible, and worded as two facts rather than a
        warning. Both are load-bearing: the model is sent names, types and
        tags and never a row of data, and nothing it writes takes effect until
        a person runs it or saves it (FR-2.6).
      */}
      <footer className="tw:border-t tw:border-secondary tw:bg-secondary tw:px-4 tw:py-2.5">
        <p className="tw:text-xs tw:text-quaternary">
          It is shown column names and tags, never rows. Nothing it writes is
          run or saved until you do it.
        </p>
      </footer>
    </aside>
  );
}

/** What it says when the open page has nothing for it to do. */
function PanelTab({
  selected,
  onSelect,
  children,
}: {
  selected: boolean;
  onSelect: () => void;
  children: string;
}) {
  return (
    <button
      aria-selected={selected}
      className={`tw:-mb-px tw:cursor-pointer tw:border-b-2 tw:px-3 tw:py-1.5 tw:text-sm tw:font-medium ${
        selected
          ? 'tw:border-brand tw:text-brand-secondary'
          : 'tw:border-transparent tw:text-tertiary tw:hover:text-secondary'
      }`}
      onClick={onSelect}
      role="tab"
      type="button">
      {children}
    </button>
  );
}

function Idle() {
  return (
    <div className="tw:text-sm tw:text-tertiary">
      <p>The assistant works in two places:</p>
      <ul className="tw:mt-3 tw:space-y-2">
        <li>
          <Link
            className="tw:font-medium tw:text-brand-secondary tw:hover:underline"
            to="/query">
            Query
          </Link>{' '}
          — say what you want to know and get a statement to run.
        </li>
        <li>
          <Link
            className="tw:font-medium tw:text-brand-secondary tw:hover:underline"
            to="/policies/new">
            New policy
          </Link>{' '}
          — say the rule in a sentence and get a draft to check.
        </li>
      </ul>
      <p className="tw:mt-4 tw:text-xs tw:text-quaternary">
        It never sees your data and it cannot activate anything. Both places
        hand you text that you review yourself.
      </p>
    </div>
  );
}

function SqlResult({
  draft,
  onUse,
}: {
  draft: SqlDraft;
  onUse: (sql: string) => void;
}) {
  if (draft.problem) {
    return (
      <p className="tw:mt-3 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-3 tw:text-xs tw:text-tertiary">
        {draft.problem}
      </p>
    );
  }
  if (!draft.sql) {
    return null;
  }
  return (
    <div className="tw:mt-3">
      <pre className="tw:max-h-56 tw:overflow-auto tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-3 tw:font-mono tw:text-xs tw:text-primary">
        {draft.sql}
      </pre>
      {draft.tables.length > 0 && (
        <p className="tw:mt-2 tw:text-xs tw:text-quaternary">
          Written against {draft.tables.join(', ')}
        </p>
      )}
      <div className="tw:mt-3 tw:flex tw:gap-2">
        <Button onPress={() => onUse(draft.sql)} size="sm">
          Put it in the editor
        </Button>
        <CopyButton text={draft.sql} />
      </div>
      {/* Said every time, because the one that gets run without reading is the
          one that was trusted the tenth time. Policy still applies to it --
          this cannot widen what somebody may see -- but it can very easily
          answer a different question than the one that was asked. */}
      <p className="tw:mt-2 tw:text-xs tw:text-quaternary">
        Read it before you run it. Your policies still apply, but the query may
        not mean what you asked for.
      </p>
    </div>
  );
}

function PolicyResult({
  draft,
  onUse,
}: {
  draft: PolicyDraft;
  onUse: (document: string) => void;
}) {
  return (
    <div className="tw:mt-3">
      <pre className="tw:max-h-56 tw:overflow-auto tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-3 tw:font-mono tw:text-xs tw:text-primary">
        {draft.document}
      </pre>
      <div className="tw:mt-3 tw:flex tw:gap-2">
        <Button onPress={() => onUse(draft.document)} size="sm">
          Load into the builder
        </Button>
        <CopyButton text={draft.document} />
      </div>
      {/* It lands as a draft and it stays one. Whoever activates it is
          somebody who read it, which is the separation the spec asks for. */}
      <p className="tw:mt-2 tw:text-xs tw:text-quaternary">
        It arrives as a draft. Check every condition before you activate it —
        the assistant cannot.
      </p>
    </div>
  );
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <Button
      color="secondary"
      iconLeading={copied ? Check : Copy01}
      onPress={() => {
        navigator.clipboard?.writeText(text).then(
          () => {
            setCopied(true);
            window.setTimeout(() => setCopied(false), 1500);
          },
          () => {
            // A refused clipboard is not worth an error state; the text is on
            // screen and selectable either way.
          }
        );
      }}
      size="sm">
      {copied ? 'Copied' : 'Copy'}
    </Button>
  );
}
