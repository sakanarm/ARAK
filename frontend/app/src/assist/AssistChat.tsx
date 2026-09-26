import { useEffect, useRef, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Send01, Trash01 } from '@untitledui/icons';
import { apiErrorMessage } from '../api/client';
import { chatWithAssistant, type ChatCard, type ChatMessage } from '../api/llm';
import { Chip as Badge } from '../components/chips';
import { useAssistStore, type ChatEntry } from './assistStore';
import { NOKRAK } from './nokrak';

/**
 * The conversation with NokRak (M28).
 *
 * <p>It can look things up -- the catalogue as this person may see it, their
 * query log, their dashboard -- and it answers in words and cards. A card is
 * something to press: a statement to put in the editor, a draft to load into
 * the builder, a page or a table to open. Pressing one opens or fills and
 * nothing more. Nothing here runs a query, saves a policy or approves anything,
 * and the panel says so.
 *
 * <p>What the model writes is shown as text. Never as markup: an answer is a
 * string from a gateway, and a string that could draw a link or a form in this
 * console would be one prompt away from drawing a convincing one.
 */

/** How many earlier lines go back with each message; the server caps it too. */
const HISTORY = 16;

/** The page the person is on, as the chat is told about it. */
export function pageContext(pathname: string): { path: string; assetFqn: string | null } {
  let assetFqn: string | null = null;
  if (pathname.startsWith('/catalog/') && pathname.length > '/catalog/'.length) {
    const raw = pathname.slice('/catalog/'.length);
    try {
      assetFqn = decodeURIComponent(raw);
    } catch {
      assetFqn = raw;
    }
  }
  return { path: pathname, assetFqn };
}

/**
 * Whether a card's route may be followed. The server only ever builds paths
 * inside this console; this is the second lock on the same door.
 */
export function safeRoute(route: string | null | undefined): string | null {
  if (!route || !route.startsWith('/') || route.startsWith('//') || route.includes('\\')) {
    return null;
  }
  return route;
}

/**
 * The model's answer with the Markdown it was asked not to write taken out.
 *
 * <p>It is drawn as text, so `**bold**` would show its asterisks. Only the
 * emphasis and code marks go; what is left is still rendered as text, never as
 * markup.
 */
export function plainText(text: string): string {
  return text
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/__(.+?)__/g, '$1')
    .replace(/`([^`\n]+)`/g, '$1')
    .replace(/^#{1,6}\s+/gm, '');
}

/** The earlier lines as the model is sent them: its own and the person's only. */
export function historyFrom(entries: ChatEntry[]): ChatMessage[] {
  return entries
    .filter((entry): entry is ChatEntry & { role: 'user' | 'assistant' } =>
      entry.role === 'user' || entry.role === 'assistant'
    )
    .filter((entry) => entry.text.trim().length > 0)
    .map((entry) => ({ role: entry.role, content: entry.text }))
    .slice(-HISTORY);
}

const SUGGESTIONS = [
  'Which tables hold customer email addresses?',
  'How many queries were refused this week?',
  'Take me to the policy builder',
];

export function AssistChat({
  onPending,
  onUsed,
}: {
  /** Tells the panel when to change pose. */
  onPending?: (pending: boolean, failed: boolean) => void;
  /** Called after a card has put something on a page, so the panel can close. */
  onUsed: () => void;
}) {
  const entries = useAssistStore((state) => state.chat);
  const addChat = useAssistStore((state) => state.addChat);
  const clearChat = useAssistStore((state) => state.clearChat);
  const ask = useAssistStore((state) => state.ask);
  const takeAsk = useAssistStore((state) => state.takeAsk);
  const sourceId = useAssistStore((state) => state.sourceId);
  const { pathname } = useLocation();

  const [message, setMessage] = useState('');
  const box = useRef<HTMLTextAreaElement>(null);
  const log = useRef<HTMLDivElement>(null);

  const turn = useMutation({
    mutationFn: (sent: { message: string; history: ChatMessage[] }) => {
      const { path, assetFqn } = pageContext(pathname);
      return chatWithAssistant({ ...sent, path, assetFqn, sourceId });
    },
    onSuccess: (reply) =>
      addChat({ role: 'assistant', text: reply.text, cards: reply.cards, model: reply.model }),
    onError: (error) =>
      addChat({ role: 'error', text: apiErrorMessage(error, 'NokRak could not answer.') }),
  });

  const lastFailed = entries.length > 0 && entries[entries.length - 1].role === 'error';
  useEffect(() => {
    onPending?.(turn.isPending, lastFailed);
  }, [turn.isPending, lastFailed, onPending]);

  function send(text: string) {
    const trimmed = text.trim();
    if (!trimmed || turn.isPending) {
      return;
    }
    // The history is taken before this line joins it: the server adds the new
    // message itself, and sending it twice would make the model answer twice.
    const history = historyFrom(useAssistStore.getState().chat);
    addChat({ role: 'user', text: trimmed });
    setMessage('');
    turn.mutate({ message: trimmed, history });
  }

  // A question asked from search arrives here and is sent as if typed.
  useEffect(() => {
    if (ask && !turn.isPending) {
      takeAsk();
      send(ask.text);
    }
    // send is recreated every render; the handoff is what matters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ask]);

  useEffect(() => {
    box.current?.focus();
  }, []);

  // Newest at the bottom, and the bottom in view.
  useEffect(() => {
    if (log.current) {
      log.current.scrollTop = log.current.scrollHeight;
    }
  }, [entries.length, turn.isPending]);

  return (
    <div className="tw:flex tw:min-h-0 tw:flex-1 tw:flex-col">
      <div
        aria-live="polite"
        className="tw:min-h-0 tw:flex-1 tw:space-y-3 tw:overflow-y-auto tw:p-4"
        data-testid="chat-log"
        ref={log}>
        {entries.length === 0 && (
          <div className="tw:text-sm tw:text-tertiary">
            <p>
              Ask me to find a table, write a query, draft a policy, or take you to
              a page. I only see what you are allowed to see.
            </p>
            <div className="tw:mt-3 tw:flex tw:flex-col tw:items-start tw:gap-1.5">
              {SUGGESTIONS.map((suggestion) => (
                <button
                  className="tw:cursor-pointer tw:rounded-full tw:border tw:border-secondary tw:px-3 tw:py-1 tw:text-left tw:text-xs tw:text-secondary tw:hover:border-brand tw:hover:text-brand-secondary"
                  key={suggestion}
                  onClick={() => send(suggestion)}
                  type="button">
                  {suggestion}
                </button>
              ))}
            </div>
          </div>
        )}

        {entries.map((entry, index) => (
          <Entry entry={entry} key={index} onUsed={onUsed} />
        ))}

        {turn.isPending && (
          <p className="tw:text-xs tw:text-quaternary" role="status">
            {NOKRAK.name} is thinking…
          </p>
        )}
      </div>

      <div className="tw:border-t tw:border-secondary tw:p-3">
        <label className="tw:sr-only" htmlFor="assist-chat">
          Message {NOKRAK.name}
        </label>
        <textarea
          className="tw:w-full tw:resize-none tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2.5 tw:py-2 tw:text-sm tw:text-primary tw:placeholder:text-quaternary"
          id="assist-chat"
          maxLength={4000}
          onChange={(event) => setMessage(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              send(message);
            }
          }}
          placeholder="Ask anything about your data…"
          ref={box}
          rows={2}
          value={message}
        />
        <div className="tw:mt-2 tw:flex tw:items-center tw:gap-2">
          <Button
            iconLeading={Send01}
            isDisabled={!message.trim() || turn.isPending}
            onPress={() => send(message)}
            size="sm">
            Send
          </Button>
          {entries.length > 0 && (
            <Button
              color="tertiary"
              iconLeading={Trash01}
              isDisabled={turn.isPending}
              onPress={clearChat}
              size="sm">
              New conversation
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}

function Entry({ entry, onUsed }: { entry: ChatEntry; onUsed: () => void }) {
  if (entry.role === 'user') {
    return (
      <div className="tw:flex tw:justify-end">
        <p className="tw:max-w-[85%] tw:rounded-2xl tw:rounded-br-sm tw:bg-brand-solid tw:px-3 tw:py-2 tw:text-sm tw:whitespace-pre-line tw:text-white">
          {entry.text}
        </p>
      </div>
    );
  }
  if (entry.role === 'error') {
    return (
      <p
        className="tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-3 tw:text-xs tw:text-error-primary"
        role="alert">
        {entry.text}
      </p>
    );
  }
  return (
    <div className="tw:max-w-[95%]">
      {/* Text, not markup, whatever the model sent. */}
      <p className="tw:rounded-2xl tw:rounded-bl-sm tw:bg-secondary tw:px-3 tw:py-2 tw:text-sm tw:break-words tw:whitespace-pre-line tw:text-primary">
        {plainText(entry.text)}
      </p>
      {entry.cards && entry.cards.length > 0 && (
        <div className="tw:mt-2 tw:space-y-2">
          {entry.cards.map((card, index) => (
            <Card card={card} key={index} onUsed={onUsed} />
          ))}
        </div>
      )}
    </div>
  );
}

export function Card({ card, onUsed }: { card: ChatCard; onUsed: () => void }) {
  const navigate = useNavigate();
  const deliverSql = useAssistStore((state) => state.deliverSql);
  const deliverPolicy = useAssistStore((state) => state.deliverPolicy);
  const route = safeRoute(card.route);

  const frame =
    'tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:p-3 tw:text-sm';

  if (card.kind === 'sql' && card.text) {
    return (
      <div className={frame} data-testid="chat-card-sql">
        <p className="tw:text-xs tw:font-semibold tw:text-secondary">{card.title}</p>
        <pre className="tw:mt-1.5 tw:max-h-48 tw:overflow-auto tw:rounded-md tw:bg-secondary tw:p-2 tw:font-mono tw:text-xs tw:text-primary">
          {card.text}
        </pre>
        <div className="tw:mt-2">
          <Button
            onPress={() => {
              deliverSql(card.text ?? '', card.sourceId);
              navigate('/query');
              onUsed();
            }}
            size="sm">
            Put it in the editor
          </Button>
        </div>
        <p className="tw:mt-1.5 tw:text-xs tw:text-quaternary">
          Not run. Read it, then run it yourself; your policies apply.
        </p>
      </div>
    );
  }

  if (card.kind === 'policy' && card.text) {
    return (
      <div className={frame} data-testid="chat-card-policy">
        <p className="tw:text-xs tw:font-semibold tw:text-secondary">{card.title}</p>
        <pre className="tw:mt-1.5 tw:max-h-48 tw:overflow-auto tw:rounded-md tw:bg-secondary tw:p-2 tw:font-mono tw:text-xs tw:text-primary">
          {card.text}
        </pre>
        <div className="tw:mt-2">
          <Button
            onPress={() => {
              deliverPolicy(card.text ?? '');
              navigate('/policies/new');
              onUsed();
            }}
            size="sm">
            Load into the builder
          </Button>
        </div>
        <p className="tw:mt-1.5 tw:text-xs tw:text-quaternary">
          A draft. Nothing switches on until you save and activate it.
        </p>
      </div>
    );
  }

  if (card.kind === 'asset' && route) {
    return (
      <div className={`${frame} tw:flex tw:items-start tw:gap-2`} data-testid="chat-card-asset">
        <div className="tw:min-w-0 tw:flex-1">
          <Link
            className="tw:block tw:truncate tw:font-medium tw:text-brand-secondary tw:hover:underline"
            title={card.title}
            to={route}>
            {card.title}
          </Link>
          {card.text && (
            <p className="tw:mt-0.5 tw:line-clamp-2 tw:text-xs tw:text-tertiary">{card.text}</p>
          )}
        </div>
        {card.access === 'READABLE' && (
          <span className="tw:shrink-0">
            <Badge color="success" size="sm" type="color">
              You can query it
            </Badge>
          </span>
        )}
        {card.access === 'REQUESTABLE' && (
          <span className="tw:shrink-0">
            <Badge color="warning" size="sm" type="color">
              Request access
            </Badge>
          </span>
        )}
      </div>
    );
  }

  if (route) {
    return (
      <Link
        className={`${frame} tw:block tw:font-medium tw:text-brand-secondary tw:hover:border-brand`}
        data-testid="chat-card-link"
        to={route}>
        {card.title} →
      </Link>
    );
  }

  return null;
}
