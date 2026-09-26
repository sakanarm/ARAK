import { useMemo, useState } from 'react';
import {
  Button as AriaButton,
  Dialog,
  DialogTrigger,
  Popover,
} from 'react-aria-components';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { AlertTriangle, BookmarkCheck, ChevronDown, ChevronRight, Save01, SearchLg, Trash01, Users01 } from '@untitledui/icons';
import { apiErrorMessage } from '../../api/client';
import {
  createSavedQuery,
  deleteSavedQuery,
  fetchSavedQueries,
  MAX_DESCRIPTION,
  MAX_NAME,
  NameTakenError,
  sameStatement,
  updateSavedQuery,
  type SavedQuery,
  type SavedQueryDraft,
} from '../../api/savedQueries';
import { Field, TextField } from '../policies/controls';

/**
 * Saving a statement, and finding it again.
 *
 * <p>What is saved is the text. Opening a saved query -- yours or one somebody
 * shared -- puts it in the editor and stops there: it runs when the person
 * looking at it presses Run, as themselves, so a shared query never hands
 * anybody the rows its author saw.
 */

export const SAVED_QUERIES_KEY = ['saved-queries'];

export function useSavedQueries() {
  return useQuery({ queryKey: SAVED_QUERIES_KEY, queryFn: fetchSavedQueries, staleTime: 30_000 });
}

/**
 * The Save button, and what it checks before it saves.
 *
 * <p>Three things are looked at first, because a second copy of the same query
 * under a new name is the usual way a list of saved queries stops being
 * useful: whether the editor was opened from one of your queries (then the
 * choice is to update it or keep it and save a new one), whether the name is
 * one you already use (then it offers to replace that one), and whether the
 * same statement is already saved under another name.
 */
export function SaveQueryButton({
  sql,
  sourceId,
  opened,
  onSaved,
}: {
  sql: string;
  sourceId: string;
  /** The saved query the editor was last opened from, if any. */
  opened: SavedQuery | null;
  onSaved: (saved: SavedQuery) => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <DialogTrigger isOpen={open} onOpenChange={setOpen}>
      <AriaButton
        className="tw:inline-flex tw:cursor-pointer tw:items-center tw:gap-1.5 tw:rounded-lg tw:px-2.5 tw:py-1.5 tw:text-sm tw:font-semibold tw:text-secondary tw:outline-none tw:hover:bg-primary_hover tw:focus-visible:outline-2 tw:focus-visible:outline-brand tw:disabled:cursor-not-allowed tw:disabled:opacity-50"
        isDisabled={sql.trim().length === 0}>
        <Save01 className="tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
        Save
      </AriaButton>
      <Popover
        className="tw:w-96 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-lg tw:outline-none"
        offset={6}
        placement="bottom start">
        <Dialog aria-label="Save query" className="tw:outline-none">
          <SaveForm
            onClose={() => setOpen(false)}
            onSaved={(saved) => {
              onSaved(saved);
              setOpen(false);
            }}
            opened={opened?.mine ? opened : null}
            sourceId={sourceId}
            sql={sql}
          />
        </Dialog>
      </Popover>
    </DialogTrigger>
  );
}

function SaveForm({
  sql,
  sourceId,
  opened,
  onSaved,
  onClose,
}: {
  sql: string;
  sourceId: string;
  opened: SavedQuery | null;
  onSaved: (saved: SavedQuery) => void;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const { data: saved } = useSavedQueries();
  const mine = useMemo(() => (saved ?? []).filter((query) => query.mine), [saved]);

  // Opened from one of yours: updating it is the likelier intent, so it is the
  // default, and "as new" is one click away.
  const [asNew, setAsNew] = useState(opened === null);
  const [name, setName] = useState(opened?.name ?? '');
  const [description, setDescription] = useState(opened?.description ?? '');
  const [shared, setShared] = useState(opened?.shared ?? false);
  const [serverClash, setServerClash] = useState<{ name: string; id: string | null } | null>(null);

  const target = asNew ? null : opened;
  const trimmed = name.trim();
  const clash =
    mine.find((query) => query.name.trim().toLowerCase() === trimmed.toLowerCase() && query.id !== target?.id) ??
    null;
  const twin = mine.find((query) => sameStatement(query.sql, sql) && query.id !== target?.id) ?? null;
  const unchanged =
    target !== null &&
    sameStatement(target.sql, sql) &&
    target.name === trimmed &&
    (target.description ?? '') === description.trim() &&
    target.shared === shared &&
    (target.sourceId ?? '') === sourceId;

  const draft: SavedQueryDraft = {
    name: trimmed,
    description: description.trim() || null,
    sourceId: sourceId || null,
    sql,
    shared,
  };

  const save = useMutation({
    mutationFn: (into: string | null) => (into ? updateSavedQuery(into, draft) : createSavedQuery(draft)),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: SAVED_QUERIES_KEY });
      onSaved(result);
    },
    onError: (error) => {
      if (error instanceof NameTakenError) {
        setServerClash({ name: trimmed, id: error.existingId });
      }
    },
  });

  // A name taken by another of yours: saving would be refused, so the button
  // becomes the question instead -- replace that one, or pick another name.
  const replaceId = clash?.id ?? (serverClash?.name === trimmed ? serverClash.id : null);
  const nameTaken = clash !== null || serverClash?.name === trimmed;
  const tooLong = trimmed.length > MAX_NAME || description.trim().length > MAX_DESCRIPTION;
  const blocked = trimmed.length === 0 || tooLong || save.isPending;

  return (
    <div className="tw:flex tw:flex-col tw:gap-4 tw:p-4">
      <h2 className="tw:text-sm tw:font-semibold tw:text-primary">Save query</h2>

      {opened && (
        <div className="tw:flex tw:flex-col tw:gap-1.5" role="radiogroup" aria-label="Save how">
          <Choice checked={!asNew} onSelect={() => setAsNew(false)}>
            Update <strong>{opened.name}</strong>
          </Choice>
          <Choice
            checked={asNew}
            onSelect={() => {
              setAsNew(true);
              if (name.trim() === opened.name) {
                setName(`${opened.name} (copy)`);
              }
            }}>
            Keep it, and save this as a new query
          </Choice>
        </div>
      )}

      <Field label="Name">
        <TextField ariaLabel="Name" onChange={setName} placeholder="Monthly sales by branch" value={name} />
      </Field>
      <Field hint="Optional. What it answers, for whoever opens it later." label="Description">
        <TextField ariaLabel="Description" onChange={setDescription} value={description} />
      </Field>

      <label className="tw:flex tw:cursor-pointer tw:items-start tw:gap-2 tw:text-sm tw:text-secondary">
        <input
          checked={shared}
          className="tw:mt-0.5 tw:size-4 tw:accent-brand-600"
          onChange={(event) => setShared(event.target.checked)}
          type="checkbox"
        />
        <span>
          Share with everyone
          <span className="tw:block tw:text-xs tw:text-tertiary">
            They get the statement, not your results: each person who runs it sees only what their own
            access allows. Values typed into the statement are visible to them.
          </span>
        </span>
      </label>

      {twin && (
        <Notice>
          This statement is already saved as <strong>{twin.name}</strong>.
        </Notice>
      )}
      {nameTaken && (
        <Notice tone="warning">
          You already have a query called <strong>{clash?.name ?? trimmed}</strong>. Replace it, or choose another
          name.
        </Notice>
      )}
      {tooLong && (
        <Notice tone="warning">
          A name is at most {MAX_NAME} characters, a description at most {MAX_DESCRIPTION}.
        </Notice>
      )}
      {save.error && !(save.error instanceof NameTakenError) && (
        <Notice tone="warning">{apiErrorMessage(save.error, 'The query was not saved.')}</Notice>
      )}

      <div className="tw:flex tw:justify-end tw:gap-2">
        <Button color="secondary" onClick={onClose} size="sm">
          Cancel
        </Button>
        {nameTaken ? (
          <Button
            color="primary"
            isDisabled={blocked || !replaceId}
            onClick={() => replaceId && save.mutate(replaceId)}
            size="sm">
            Replace it
          </Button>
        ) : (
          <Button
            color="primary"
            isDisabled={blocked || unchanged}
            onClick={() => save.mutate(target?.id ?? null)}
            size="sm">
            {save.isPending ? 'Saving…' : target ? 'Update' : 'Save'}
          </Button>
        )}
      </div>
    </div>
  );
}

function Choice({
  checked,
  onSelect,
  children,
}: {
  checked: boolean;
  onSelect: () => void;
  children: React.ReactNode;
}) {
  return (
    <label className="tw:flex tw:cursor-pointer tw:items-center tw:gap-2 tw:text-sm tw:text-secondary">
      <input checked={checked} className="tw:size-4 tw:accent-brand-600" onChange={onSelect} type="radio" />
      <span>{children}</span>
    </label>
  );
}

function Notice({ tone = 'info', children }: { tone?: 'info' | 'warning'; children: React.ReactNode }) {
  return (
    <p
      className={`tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:px-3 tw:py-2 tw:text-xs ${
        tone === 'warning' ? 'tw:bg-warning-primary tw:text-warning-primary' : 'tw:bg-secondary tw:text-secondary'
      }`}
      role={tone === 'warning' ? 'alert' : 'status'}>
      {tone === 'warning' ? (
        <AlertTriangle className="tw:mt-px tw:size-3.5 tw:shrink-0" />
      ) : (
        <BookmarkCheck className="tw:mt-px tw:size-3.5 tw:shrink-0" />
      )}
      <span>{children}</span>
    </p>
  );
}

/**
 * Your saved queries and the ones shared with you, under the schema tree.
 *
 * <p>Clicking one opens it into the editor, with its source, and does nothing
 * else. It does not run: what was saved may be a heavy statement, and a
 * shared one is somebody else's text that its opener should read first.
 */
export function SavedQueriesPanel({
  onOpen,
  openedId,
}: {
  onOpen: (query: SavedQuery) => void;
  openedId: string | null;
}) {
  const queryClient = useQueryClient();
  const { data, isLoading, error } = useSavedQueries();
  const [expanded, setExpanded] = useState(true);
  const [search, setSearch] = useState('');
  const [confirming, setConfirming] = useState<string | null>(null);

  const remove = useMutation({
    mutationFn: deleteSavedQuery,
    onSuccess: () => {
      setConfirming(null);
      void queryClient.invalidateQueries({ queryKey: SAVED_QUERIES_KEY });
    },
  });

  const wanted = search.trim().toLowerCase();
  const shown = (data ?? []).filter(
    (query) =>
      !wanted ||
      query.name.toLowerCase().includes(wanted) ||
      (query.description ?? '').toLowerCase().includes(wanted) ||
      query.sql.toLowerCase().includes(wanted)
  );
  const mine = shown.filter((query) => query.mine);
  const others = shown.filter((query) => !query.mine);

  return (
    <section
      aria-label="Saved queries"
      className={`tw:flex tw:flex-col tw:overflow-hidden tw:rounded-lg tw:border tw:border-secondary tw:bg-primary ${
        expanded ? 'tw:max-h-[45%] tw:min-h-40' : 'tw:shrink-0'
      }`}>
      <button
        aria-expanded={expanded}
        className="tw:flex tw:w-full tw:cursor-pointer tw:items-center tw:gap-2 tw:p-3 tw:text-left tw:text-sm tw:font-semibold tw:text-primary"
        onClick={() => setExpanded((value) => !value)}
        type="button">
        {expanded ? (
          <ChevronDown className="tw:size-4 tw:text-quaternary" />
        ) : (
          <ChevronRight className="tw:size-4 tw:text-quaternary" />
        )}
        <Save01 className="tw:size-4 tw:text-tertiary" />
        Saved queries
        {data && <span className="tw:ml-auto tw:text-xs tw:font-medium tw:text-quaternary">{data.length}</span>}
      </button>

      {expanded && (
        <>
          <div className="tw:flex tw:items-center tw:gap-2 tw:border-b tw:border-secondary tw:px-3 tw:pb-2">
            <SearchLg className="tw:size-4 tw:shrink-0 tw:text-quaternary" />
            <TextField
              ariaLabel="Search saved queries"
              className="tw:min-w-0 tw:flex-1"
              onChange={setSearch}
              placeholder="Find a saved query"
              value={search}
            />
          </div>
          <div className="tw:min-h-0 tw:flex-1 tw:overflow-auto tw:p-2">
            {isLoading && <p className="tw:px-2 tw:py-1 tw:text-xs tw:text-tertiary">Loading…</p>}
            {error && (
              <p className="tw:px-2 tw:py-1 tw:text-xs tw:text-error-primary">
                {apiErrorMessage(error, 'Saved queries could not be read.')}
              </p>
            )}
            {data && data.length === 0 && (
              <p className="tw:px-2 tw:py-1 tw:text-xs tw:text-tertiary">
                Nothing saved yet. Write a statement and press Save to keep it here.
              </p>
            )}
            {data && data.length > 0 && shown.length === 0 && (
              <p className="tw:px-2 tw:py-1 tw:text-xs tw:text-tertiary">No saved query matches.</p>
            )}
            {mine.length > 0 && <Group title="Mine" />}
            {mine.map((query) => (
              <Entry
                confirming={confirming === query.id}
                deleting={remove.isPending && remove.variables === query.id}
                key={query.id}
                onAskDelete={() => setConfirming(query.id)}
                onCancelDelete={() => setConfirming(null)}
                onDelete={() => remove.mutate(query.id)}
                onOpen={() => onOpen(query)}
                opened={openedId === query.id}
                query={query}
              />
            ))}
            {others.length > 0 && <Group title="Shared with me" />}
            {others.map((query) => (
              <Entry key={query.id} onOpen={() => onOpen(query)} opened={openedId === query.id} query={query} />
            ))}
            {remove.error && (
              <p className="tw:px-2 tw:py-1 tw:text-xs tw:text-error-primary">
                {apiErrorMessage(remove.error, 'The query was not deleted.')}
              </p>
            )}
          </div>
        </>
      )}
    </section>
  );
}

function Group({ title }: { title: string }) {
  return (
    <h3 className="tw:px-2 tw:pb-1 tw:pt-2 tw:text-xs tw:font-semibold tw:uppercase tw:tracking-wide tw:text-quaternary">
      {title}
    </h3>
  );
}

function Entry({
  query,
  opened,
  onOpen,
  confirming,
  deleting,
  onAskDelete,
  onCancelDelete,
  onDelete,
}: {
  query: SavedQuery;
  opened: boolean;
  onOpen: () => void;
  confirming?: boolean;
  deleting?: boolean;
  onAskDelete?: () => void;
  onCancelDelete?: () => void;
  onDelete?: () => void;
}) {
  return (
    <div
      className={`tw:group tw:rounded-md tw:px-2 tw:py-1.5 ${opened ? 'tw:bg-brand-primary' : 'tw:hover:bg-primary_hover'}`}>
      <div className="tw:flex tw:items-center tw:gap-1">
        <button
          className="tw:min-w-0 tw:flex-1 tw:cursor-pointer tw:text-left"
          onClick={onOpen}
          title={`Open in the editor (it does not run):\n\n${query.sql}`}
          type="button">
          <span className="tw:flex tw:items-center tw:gap-1.5 tw:text-sm tw:font-medium tw:text-primary">
            <span className="tw:truncate">{query.name}</span>
            {query.shared && query.mine && (
              <Users01 aria-label="Shared with everyone" className="tw:size-3.5 tw:shrink-0 tw:text-quaternary" />
            )}
          </span>
          <span className="tw:block tw:truncate tw:text-xs tw:text-tertiary">
            {query.mine ? query.description || firstLine(query.sql) : `by ${query.owner}`}
          </span>
        </button>
        {query.mine && !confirming && (
          <button
            aria-label={`Delete ${query.name}`}
            className="tw:shrink-0 tw:cursor-pointer tw:rounded tw:p-1 tw:text-quaternary tw:opacity-0 tw:group-hover:opacity-100 tw:hover:text-error-primary tw:focus-visible:opacity-100"
            onClick={onAskDelete}
            type="button">
            <Trash01 className="tw:size-3.5" />
          </button>
        )}
      </div>
      {confirming && (
        <div className="tw:mt-1 tw:flex tw:items-center tw:gap-2 tw:text-xs">
          <span className="tw:text-secondary">Delete it?</span>
          <button
            className="tw:cursor-pointer tw:font-semibold tw:text-error-primary"
            disabled={deleting}
            onClick={onDelete}
            type="button">
            {deleting ? 'Deleting…' : 'Delete'}
          </button>
          <button className="tw:cursor-pointer tw:text-tertiary" onClick={onCancelDelete} type="button">
            Keep
          </button>
        </div>
      )}
    </div>
  );
}

function firstLine(sql: string) {
  return sql.trim().split('\n')[0];
}
