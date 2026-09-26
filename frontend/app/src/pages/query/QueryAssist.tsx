import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { BookOpen01, MagicWand01, X } from '@untitledui/icons';
import { apiErrorMessage } from '../../api/client';
import type { Refusal } from '../../api/accessRequests';
import { NokRakPrompt } from '../../assist/NokRakAsk';
import {
  assistExplain,
  assistFix,
  assistSql,
  type SqlDraft,
  type SqlExplanation,
} from '../../api/llm';

/**
 * The assistant's two jobs on the query console (M26): say what a statement
 * does, and suggest a corrected one when the proxy refused it.
 *
 * Neither runs anything. An explanation is text; a correction is text the
 * reader may put in the editor, and then runs like anything they typed -- the
 * proxy rewrites it against their own policy, so a statement the assistant
 * wrote reaches exactly what one they wrote would. The assistant is sent the
 * statement and the catalogue's table and column names, never a row.
 */

// Kept importable from here, where the console has always found it.
export { useAssistReady, useOfferedFeatures } from '../../assist/useAssist';

/** The explanation of the statement in the editor, and which text it was about. */
export function useSqlExplanation() {
  const [about, setAbout] = useState('');
  const explain = useMutation({
    mutationFn: (ask: { sql: string; sourceId: string; engine?: string }) => {
      setAbout(ask.sql);
      return assistExplain({
        sql: ask.sql,
        sourceId: ask.sourceId || undefined,
        engine: ask.engine,
      });
    },
  });
  return { explain, about };
}

export function ExplainButton({
  disabled,
  pending,
  onClick,
}: {
  disabled: boolean;
  pending: boolean;
  onClick: () => void;
}) {
  return (
    <Button
      color="secondary"
      iconLeading={BookOpen01}
      isDisabled={disabled || pending}
      onClick={onClick}
      size="sm">
      {pending ? 'Explaining…' : 'Explain'}
    </Button>
  );
}

export function ExplanationCard({
  explanation,
  error,
  pending,
  about,
  current,
  onDismiss,
}: {
  explanation: SqlExplanation | undefined;
  error: unknown;
  pending: boolean;
  /** The statement that was explained. */
  about: string;
  /** The statement in the editor now. */
  current: string;
  onDismiss: () => void;
}) {
  if (!pending && !explanation && !error) {
    return null;
  }
  const stale = explanation !== undefined && about.trim() !== current.trim();
  return (
    <section
      aria-label="Explanation"
      className="tw:flex tw:max-h-56 tw:shrink-0 tw:flex-col tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
      <header className="tw:flex tw:shrink-0 tw:items-center tw:gap-2 tw:border-b tw:border-secondary tw:px-3 tw:py-2">
        <BookOpen01 className="tw:size-4 tw:text-fg-quaternary" />
        <h2 className="tw:text-sm tw:font-semibold tw:text-primary">What this statement does</h2>
        {explanation && (
          <span className="tw:truncate tw:text-xs tw:text-quaternary">
            {explanation.model}
            {explanation.personal ? ' · your gateway' : ''}
          </span>
        )}
        <button
          aria-label="Dismiss the explanation"
          className="tw:ml-auto tw:cursor-pointer tw:rounded tw:p-1 tw:text-fg-quaternary tw:hover:bg-primary_hover"
          onClick={onDismiss}
          type="button">
          <X className="tw:size-4" />
        </button>
      </header>
      <div className="tw:min-h-0 tw:overflow-auto tw:px-3 tw:py-2">
        {pending ? (
          <p className="tw:text-sm tw:text-tertiary">Reading the statement…</p>
        ) : error ? (
          <p className="tw:text-sm tw:text-error-primary" role="alert">
            {apiErrorMessage(error, 'The assistant could not explain this statement.')}
          </p>
        ) : explanation ? (
          <>
            {stale && (
              <p className="tw:mb-1.5 tw:text-xs tw:font-medium tw:text-warning-primary">
                About an earlier version of the statement — press Explain again for this one.
              </p>
            )}
            {/* Text, never markup: whatever the model wrote is shown as it is. */}
            <p className="tw:whitespace-pre-wrap tw:text-sm tw:text-secondary">{explanation.text}</p>
            <p className="tw:mt-2 tw:text-xs tw:text-quaternary">
              Written from the statement and the catalogue&apos;s table names; the assistant saw no
              rows. Check it before relying on it.
            </p>
          </>
        ) : null}
      </div>
    </section>
  );
}

/**
 * "Fix with AI" under a refusal the statement caused.
 *
 * Drawn only when the server said the refusal is `fixable`. A refusal a policy
 * made is answered with the owner and a request, never with a rewording --
 * that is the line between help with syntax and help around access.
 */
export function FixWithAi({
  refusal,
  sql,
  sourceId,
  engine,
  onUse,
}: {
  refusal: Refusal;
  /** The statement that was refused, not whatever the editor holds now. */
  sql: string;
  sourceId: string;
  engine?: string;
  onUse: (sql: string) => void;
}) {
  const [dismissed, setDismissed] = useState(false);
  const fix = useMutation({
    mutationFn: (): Promise<SqlDraft> =>
      assistFix({ sql, error: refusal.message, sourceId, engine }),
    onMutate: () => setDismissed(false),
  });

  if (!refusal.fixable || !sql.trim() || !sourceId) {
    return null;
  }
  const draft = dismissed ? undefined : fix.data;

  return (
    <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
      {!draft && (
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <Button
            color="secondary"
            iconLeading={MagicWand01}
            isDisabled={fix.isPending}
            onClick={() => fix.mutate()}
            size="sm">
            {fix.isPending ? 'Looking for the mistake…' : 'Fix with AI'}
          </Button>
          <span className="tw:text-xs tw:text-tertiary">
            Suggests a corrected statement. Nothing runs until you press Run.
          </span>
        </div>
      )}
      {fix.isError && !dismissed && (
        <p className="tw:text-sm tw:text-error-primary" role="alert">
          {apiErrorMessage(fix.error, 'The assistant could not suggest a fix.')}
        </p>
      )}
      {draft &&
        (draft.problem || !draft.sql ? (
          <div className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2">
            <p className="tw:flex-1 tw:text-sm tw:text-secondary">
              {draft.problem ?? 'The assistant did not suggest a statement.'}
            </p>
            <button
              aria-label="Dismiss the suggestion"
              className="tw:cursor-pointer tw:rounded tw:p-1 tw:text-fg-quaternary tw:hover:bg-primary_hover"
              onClick={() => setDismissed(true)}
              type="button">
              <X className="tw:size-4" />
            </button>
          </div>
        ) : (
          <section
            aria-label="Suggested statement"
            className="tw:rounded-lg tw:border tw:border-secondary tw:bg-primary">
            <header className="tw:flex tw:items-center tw:gap-2 tw:border-b tw:border-secondary tw:px-3 tw:py-2">
              <MagicWand01 className="tw:size-4 tw:text-fg-quaternary" />
              <h3 className="tw:text-sm tw:font-semibold tw:text-primary">Suggested statement</h3>
              <span className="tw:truncate tw:text-xs tw:text-quaternary">{draft.model}</span>
            </header>
            <pre className="tw:max-h-40 tw:overflow-auto tw:whitespace-pre-wrap tw:px-3 tw:py-2 tw:font-mono tw:text-xs tw:text-primary">
              {draft.sql}
            </pre>
            <footer className="tw:flex tw:flex-wrap tw:items-center tw:gap-2 tw:border-t tw:border-secondary tw:px-3 tw:py-2">
              <Button color="primary" onClick={() => onUse(draft.sql)} size="sm">
                Use this
              </Button>
              <Button color="secondary" onClick={() => setDismissed(true)} size="sm">
                Dismiss
              </Button>
              <span className="tw:text-xs tw:text-tertiary">
                Puts it in the editor. It is checked against your access when you run it.
              </span>
            </footer>
          </section>
        ))}
    </div>
  );
}

/**
 * "Write it for me": a question in plain words, a statement back (M25 on the
 * page rather than only in the dock).
 *
 * The same `/v1/llm/assist/sql` the dock calls, with the source and dialect on
 * screen. The answer is shown before it reaches the editor, and reaching the
 * editor is all "Use this" does -- the reader still presses Run, and the run
 * goes through the proxy like a statement they typed.
 */
export function WriteWithNokRak({
  sourceId,
  engine,
  onUse,
  onClose,
}: {
  sourceId: string;
  engine?: string;
  onUse: (sql: string) => void;
  onClose: () => void;
}) {
  const write = useMutation({
    mutationFn: (question: string): Promise<SqlDraft> =>
      assistSql({ question, sourceId, engine }),
  });
  const draft = write.data;

  return (
    <NokRakPrompt
      askLabel="Write it"
      disabled={!sourceId}
      error={write.isError ? apiErrorMessage(write.error, 'NokRak could not write a statement.') : null}
      floating
      hint={
        sourceId
          ? 'Nothing reaches the editor until you press Use this, and nothing runs until you press Run.'
          : 'Choose a source first, so NokRak writes against its tables.'
      }
      onAsk={(question) => write.mutate(question)}
      onClose={onClose}
      pending={write.isPending}
      pendingLabel="Writing…"
      placeholder="e.g. How many customers signed up each month this year?"
      title="Ask NokRak to write the query">
      {draft &&
        (draft.problem || !draft.sql ? (
          <p className="tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:px-3 tw:py-2 tw:text-sm tw:text-secondary">
            {draft.problem ?? 'NokRak did not write a statement for that. Try asking another way.'}
          </p>
        ) : (
          <section
            aria-label="Statement NokRak wrote"
            className="tw:rounded-lg tw:border tw:border-secondary tw:bg-primary">
            <header className="tw:flex tw:items-center tw:gap-2 tw:border-b tw:border-secondary tw:px-3 tw:py-2">
              <MagicWand01 className="tw:size-4 tw:text-fg-quaternary" />
              <h3 className="tw:text-sm tw:font-semibold tw:text-primary">Suggested statement</h3>
              <span className="tw:truncate tw:text-xs tw:text-quaternary">{draft.model}</span>
            </header>
            <pre className="tw:max-h-60 tw:overflow-auto tw:whitespace-pre-wrap tw:px-3 tw:py-2 tw:font-mono tw:text-xs tw:text-primary">
              {draft.sql}
            </pre>
            {draft.tables.length > 0 && (
              <p className="tw:border-t tw:border-secondary tw:px-3 tw:py-2 tw:text-xs tw:text-tertiary">
                Written from the columns of{' '}
                <span className="tw:font-mono">{draft.tables.join(', ')}</span>
              </p>
            )}
            <footer className="tw:flex tw:flex-wrap tw:items-center tw:gap-2 tw:border-t tw:border-secondary tw:px-3 tw:py-2">
              <Button color="primary" onClick={() => onUse(draft.sql)} size="sm">
                Use this
              </Button>
              <span className="tw:text-xs tw:text-tertiary">
                Replaces what is in the editor. It is checked against your access when you run it.
              </span>
            </footer>
          </section>
        ))}
    </NokRakPrompt>
  );
}
