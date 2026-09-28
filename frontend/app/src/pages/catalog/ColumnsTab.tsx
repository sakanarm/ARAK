import { useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Edit05, SearchLg } from '@untitledui/icons';
import greet from '../../assets/mascot/greet.png';
import { apiErrorMessage, type ColumnDetail, type FacetRow } from '../../api/client';
import {
  fetchColumnDescriptions,
  saveColumnDescriptions,
  type DescriptionSave,
  type WrittenDescription,
} from '../../api/columnDescriptions';
import { DESCRIBE_BATCH, assistDescribeColumns } from '../../api/llm';
import { useAssistReady } from '../../assist/useAssist';
import { plainText } from '../../lib/text';
import { FIELD } from '../policies/controls';
import { FacetChip, columnFacets } from './facets';
import { LocalTagControl } from './LocalTags';
import { Panel } from './panels';

/**
 * A table's columns: what each one is, what it holds and what governs it.
 *
 * <p>Most columns reach ARAK with no description -- OpenMetadata has none to
 * give -- and a description is what a requester and an approver read to decide
 * what a column holds. So whoever governs the table can write them here, and
 * those survive every sync. NokRak can draft the empty ones from the names,
 * types and tags -- or only the columns somebody ticks, described ones too;
 * a draft is put in the form and nothing is saved until the person has read it
 * and pressed Save.
 */
export function ColumnsTab({ assetFqn, columns }: { assetFqn: string; columns: ColumnDetail[] }) {
  const client = useQueryClient();
  const listing = useQuery({
    queryKey: ['column-descriptions', assetFqn],
    queryFn: () => fetchColumnDescriptions(assetFqn),
  });
  const canEdit = listing.data?.canEdit === true;
  const canDraft = useAssistReady('DESCRIBE_COLUMNS');
  const written = useMemo(
    () => new Map((listing.data?.descriptions ?? []).map((d) => [d.columnFqn, d])),
    [listing.data]
  );

  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState(false);
  const [onlyEmpty, setOnlyEmpty] = useState(false);
  // What the form holds, per column FQN; a column not in here shows what is saved.
  const [text, setText] = useState<Record<string, string>>({});
  const [drafted, setDrafted] = useState<Record<string, boolean>>({});
  // Columns ticked for NokRak, by FQN. None ticked means the empty ones.
  const [picked, setPicked] = useState<Set<string>>(() => new Set());
  const [language, setLanguage] = useState('English');
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const stopped = useRef(false);
  const textNow = useRef(text);
  textNow.current = text;

  // Written here, as saved. The listing is the authority; the column's own
  // field is the fallback for the moment between a save and the refetch.
  const saved = (column: ColumnDetail) =>
    written.get(column.fqn)?.description ??
    (column.descriptionSource === 'arak' ? (column.description ?? '') : '');
  const fromOpenMetadata = (column: ColumnDetail) =>
    column.descriptionSource === 'arak' ? '' : plainText(column.description);
  const valueOf = (column: ColumnDetail) => text[column.fqn] ?? saved(column);
  const undescribed = (column: ColumnDetail) => !saved(column).trim() && !fromOpenMetadata(column);

  const changed = columns.filter((column) => valueOf(column).trim() !== saved(column).trim());
  const draftable = columns.filter((column) => !valueOf(column).trim() && !fromOpenMetadata(column));
  const targets = picked.size > 0 ? columns.filter((column) => picked.has(column.fqn)) : draftable;

  const needle = search.trim().toLowerCase();
  const shown = columns.filter(
    (column) =>
      (!needle ||
        column.name.toLowerCase().includes(needle) ||
        plainText(column.description).toLowerCase().includes(needle) ||
        (editing && valueOf(column).toLowerCase().includes(needle))) &&
      // Judged on what is saved, not on the form: a row that vanished the
      // moment somebody started typing in it would be a row they cannot finish.
      (!editing || !onlyEmpty || undescribed(column))
  );

  const governed = columns.filter((column) => columnFacets(column.facets).length > 0).length;
  const described = columns.filter((column) => plainText(column.description)).length;
  const pickedShown = shown.filter((column) => picked.has(column.fqn)).length;

  const pick = (fqns: string[], on: boolean) =>
    setPicked((prev) => {
      const next = new Set(prev);
      for (const fqn of fqns) {
        if (on) next.add(fqn);
        else next.delete(fqn);
      }
      return next;
    });

  const reset = () => {
    stopped.current = true;
    setText({});
    setDrafted({});
    setPicked(new Set());
    setProgress(null);
    setError(null);
  };

  const save = useMutation({
    mutationFn: () =>
      saveColumnDescriptions({
        assetFqn,
        entries: changed.map((column) => {
          const value = valueOf(column).trim();
          return { columnFqn: column.fqn, description: value, assisted: value !== '' && !!drafted[column.fqn] };
        }),
      }),
    onSuccess: (result) => {
      client.setQueryData(['column-descriptions', assetFqn], {
        canEdit: true,
        descriptions: result.descriptions,
      });
      client.invalidateQueries({ queryKey: ['catalog-asset', assetFqn] });
      client.invalidateQueries({ queryKey: ['column-descriptions', assetFqn] });
      reset();
      setEditing(false);
      setNotice(savedSummary(result));
    },
    onError: (e) => setError(apiErrorMessage(e, 'Could not save the descriptions.')),
  });

  /**
   * Drafts the ticked columns, or the empty ones when none is ticked, a batch
   * at a time so a wide table does not wait on one enormous answer. A draft
   * takes a field only if the field still holds what it held when Draft was
   * pressed: whatever the person typed meanwhile is theirs. A ticked column
   * that is already described gets a new draft in the form, and keeps its saved
   * description until somebody presses Save.
   */
  const draft = async () => {
    const todo = targets.map((column) => column.name);
    if (todo.length === 0) return;
    const before = new Map(targets.map((column) => [column.fqn, valueOf(column)]));
    const untouched = (fqn: string, now: string | undefined) => now === undefined || now === before.get(fqn);
    stopped.current = false;
    setError(null);
    setNotice(null);
    setProgress({ done: 0, total: todo.length });
    const byName = new Map(targets.map((column) => [column.name.toLowerCase(), column]));
    let filled = 0;
    let kept = 0;
    try {
      for (let at = 0; at < todo.length && !stopped.current; at += DESCRIBE_BATCH) {
        const batch = todo.slice(at, at + DESCRIBE_BATCH);
        const answer = await assistDescribeColumns({ assetFqn, columns: batch, language });
        if (stopped.current) break;
        const got: Record<string, string> = {};
        for (const d of answer.drafts) {
          const column = byName.get(d.name.toLowerCase());
          // An empty draft is NokRak saying it cannot tell; it never blanks a field.
          if (!column || !d.description.trim()) continue;
          if (untouched(column.fqn, textNow.current[column.fqn])) got[column.fqn] = d.description;
          else kept += 1;
        }
        filled += Object.keys(got).length;
        setText((prev) => {
          const next = { ...prev };
          for (const [fqn, value] of Object.entries(got)) {
            if (untouched(fqn, next[fqn])) next[fqn] = value;
          }
          return next;
        });
        setDrafted((prev) => ({ ...prev, ...Object.fromEntries(Object.keys(got).map((fqn) => [fqn, true])) }));
        setProgress({ done: Math.min(at + batch.length, todo.length), total: todo.length });
      }
      if (!stopped.current) {
        const yours = kept > 0 ? ` ${kept} you had typed in meanwhile kept what you wrote.` : '';
        setNotice(
          filled === 0 && kept === 0
            ? 'NokRak could not tell what any of those columns hold, so nothing was filled in.'
            : `NokRak drafted ${filled} of ${todo.length}.${yours} Read each one before you save: they are worked out from the names and types, not from the data.`
        );
      }
    } catch (e) {
      setError(apiErrorMessage(e, 'NokRak could not draft the descriptions.'));
    } finally {
      setProgress(null);
    }
  };

  const edit = (column: ColumnDetail, value: string) => {
    setText((prev) => ({ ...prev, [column.fqn]: value }));
    if (!value.trim()) setDrafted((prev) => ({ ...prev, [column.fqn]: false }));
  };

  const busy = progress !== null || save.isPending;
  const pickable = editing && canDraft;

  return (
    <Panel
      action={
        canEdit && !editing && columns.length > 0 ? (
          <Button
            color="secondary"
            iconLeading={Edit05}
            onPress={() => {
              reset();
              setNotice(null);
              setEditing(true);
            }}
            size="sm">
            Describe columns
          </Button>
        ) : undefined
      }
      subtitle={`${columns.length} columns · ${governed} carrying governance of their own · ${described} described`}
      title="Columns">
      {columns.length === 0 ? (
        <p className="tw:text-sm tw:text-tertiary">The crawl found no columns on this asset.</p>
      ) : (
        <>
          {editing && (
            <div
              aria-label="Describe columns"
              className="tw:sticky tw:top-0 tw:z-10 tw:mb-3 tw:flex tw:flex-col tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-3"
              role="group">
              <p className="tw:text-xs tw:text-tertiary">
                What you write here is what people asking for this table, and the people approving
                them, read. It stays when OpenMetadata syncs, and takes the place of OpenMetadata&rsquo;s
                description where both exist. Leave a field empty to keep OpenMetadata&rsquo;s.
                {canDraft &&
                  ' Tick columns to have NokRak draft only those (a described one too, to write it again); with none ticked, it drafts the empty ones.'}
              </p>
              <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
                {canDraft && (
                  <>
                    <Button
                      color="secondary"
                      iconLeading={<img alt="" className="tw:size-5 tw:object-contain" src={greet} />}
                      isDisabled={busy || targets.length === 0}
                      onPress={() => void draft()}
                      size="sm">
                      {picked.size > 0
                        ? `Draft ${picked.size} picked with NokRak`
                        : draftable.length === 0
                          ? 'Every column has a description'
                          : `Draft ${draftable.length} empty with NokRak`}
                    </Button>
                    {picked.size > 0 && (
                      <Button color="link-gray" isDisabled={busy} onPress={() => setPicked(new Set())} size="sm">
                        Clear picks
                      </Button>
                    )}
                    <select
                      aria-label="Write the drafts in"
                      className={`${FIELD} tw:py-1.5`}
                      disabled={busy}
                      onChange={(event) => setLanguage(event.target.value)}
                      value={language}>
                      <option value="English">English</option>
                      <option value="Thai">ไทย (Thai)</option>
                    </select>
                  </>
                )}
                <label className="tw:flex tw:items-center tw:gap-1.5 tw:text-sm tw:text-secondary">
                  <input
                    checked={onlyEmpty}
                    onChange={(event) => setOnlyEmpty(event.target.checked)}
                    type="checkbox"
                  />
                  Only columns nobody has described
                </label>
                <div className="tw:ml-auto tw:flex tw:gap-2">
                  <Button
                    color="secondary"
                    isDisabled={save.isPending}
                    onPress={() => {
                      reset();
                      setEditing(false);
                    }}
                    size="sm">
                    Cancel
                  </Button>
                  <Button
                    isDisabled={changed.length === 0 || busy}
                    isLoading={save.isPending}
                    onPress={() => save.mutate()}
                    size="sm">
                    {changed.length === 0
                      ? 'Nothing to save'
                      : `Save ${changed.length} change${changed.length === 1 ? '' : 's'}`}
                  </Button>
                </div>
              </div>
              {progress && (
                <p aria-live="polite" className="tw:flex tw:items-center tw:gap-2 tw:text-xs tw:text-secondary">
                  NokRak is drafting — {progress.done} of {progress.total} done. It sees the names,
                  types and tags, never the data.
                  <Button
                    color="link-gray"
                    onPress={() => {
                      stopped.current = true;
                      setProgress(null);
                    }}
                    size="sm">
                    Stop
                  </Button>
                </p>
              )}
              {error && (
                <p className="tw:text-xs tw:text-error-primary" role="alert">
                  {error}
                </p>
              )}
            </div>
          )}

          {notice && (
            <p aria-live="polite" className="tw:mb-3 tw:text-xs tw:text-secondary">
              {notice}
            </p>
          )}

          {(columns.length > 8 || editing) && (
            <div className="tw:relative tw:mb-3 tw:max-w-sm">
              <SearchLg
                aria-hidden
                className="tw:pointer-events-none tw:absolute tw:left-3 tw:top-1/2 tw:size-4 tw:-translate-y-1/2 tw:text-quaternary"
              />
              <input
                aria-label="Find a column"
                className={`${FIELD} tw:w-full tw:pl-9`}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Find a column by name or description"
                type="search"
                value={search}
              />
            </div>
          )}

          <div className="tw:overflow-x-auto">
            <table className="tw:w-full tw:text-sm">
              <thead>
                <tr className="tw:border-b tw:border-secondary tw:text-left tw:text-xs tw:text-tertiary">
                  {pickable && (
                    <th className="tw:w-8 tw:py-2 tw:pr-2">
                      <input
                        aria-label="Pick every column shown"
                        checked={shown.length > 0 && pickedShown === shown.length}
                        disabled={busy || shown.length === 0}
                        onChange={(event) =>
                          pick(
                            shown.map((column) => column.fqn),
                            event.target.checked
                          )
                        }
                        ref={(box) => {
                          if (box) box.indeterminate = pickedShown > 0 && pickedShown < shown.length;
                        }}
                        type="checkbox"
                      />
                    </th>
                  )}
                  <th className="tw:py-2 tw:pr-3 tw:font-medium">Column</th>
                  <th className="tw:py-2 tw:pr-3 tw:font-medium">Type</th>
                  <th className="tw:py-2 tw:font-medium">Governance</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((column) => (
                  <ColumnRow
                    assetFqn={assetFqn}
                    canEdit={canEdit}
                    column={column}
                    drafted={!!drafted[column.fqn] && valueOf(column).trim() !== ''}
                    editing={editing}
                    fromOpenMetadata={fromOpenMetadata(column)}
                    key={column.id}
                    onChange={(value) => edit(column, value)}
                    onPick={pickable ? (on) => pick([column.fqn], on) : undefined}
                    picked={picked.has(column.fqn)}
                    pickDisabled={busy}
                    value={valueOf(column)}
                    written={written.get(column.fqn)}
                  />
                ))}
              </tbody>
            </table>
            {shown.length === 0 && (
              <p className="tw:py-6 tw:text-center tw:text-sm tw:text-tertiary">
                {needle ? `No column matches “${search.trim()}”.` : 'Every column here has a description.'}
              </p>
            )}
          </div>
        </>
      )}
    </Panel>
  );
}

function ColumnRow({
  assetFqn,
  column,
  canEdit,
  editing,
  value,
  fromOpenMetadata,
  drafted,
  written,
  onChange,
  onPick,
  picked,
  pickDisabled,
}: {
  assetFqn: string;
  column: ColumnDetail;
  canEdit: boolean;
  editing: boolean;
  value: string;
  fromOpenMetadata: string;
  drafted: boolean;
  written: WrittenDescription | undefined;
  onChange: (value: string) => void;
  /** Set while NokRak may be pointed at single columns. */
  onPick?: (on: boolean) => void;
  picked: boolean;
  pickDisabled: boolean;
}) {
  const own = columnFacets(column.facets);
  const description = plainText(column.description);

  return (
    <tr className="tw:border-b tw:border-secondary tw:last:border-0">
      {onPick && (
        <td className="tw:w-8 tw:py-2 tw:pr-2 tw:align-top">
          <input
            aria-label={`Pick ${column.name} for NokRak`}
            checked={picked}
            className="tw:mt-0.5"
            disabled={pickDisabled}
            onChange={(event) => onPick(event.target.checked)}
            type="checkbox"
          />
        </td>
      )}
      <td className={`tw:py-2 tw:pr-3 tw:align-top ${editing ? 'tw:w-1/2 tw:min-w-72' : ''}`}>
        <span className="tw:font-medium tw:text-primary">{column.name}</span>
        {editing ? (
          <>
            <textarea
              aria-label={`Description of ${column.name}`}
              className={`${FIELD} tw:mt-1 tw:block tw:w-full tw:resize-y`}
              maxLength={2000}
              onChange={(event) => onChange(event.target.value)}
              placeholder={
                fromOpenMetadata
                  ? `OpenMetadata says: ${fromOpenMetadata}`
                  : 'What does this column hold?'
              }
              rows={2}
              value={value}
            />
            {drafted && (
              <span className="tw:mt-1 tw:inline-block tw:rounded tw:bg-brand-primary tw:px-1.5 tw:py-0.5 tw:text-xs tw:text-brand-secondary">
                NokRak draft — read it before you save
              </span>
            )}
          </>
        ) : (
          description && (
            <p className="tw:mt-0.5 tw:max-w-md tw:text-xs tw:text-tertiary">
              {description}
              {column.descriptionSource === 'arak' && (
                <span
                  className="tw:ml-1.5 tw:rounded tw:bg-secondary tw:px-1 tw:py-px tw:text-[10px] tw:font-medium tw:uppercase tw:text-quaternary"
                  title={writtenBy(written)}>
                  ARAK
                </span>
              )}
            </p>
          )
        )}
      </td>
      <td className="tw:py-2 tw:pr-3 tw:align-top tw:font-mono tw:text-xs tw:text-tertiary">
        {column.dataType ?? '—'}
        {column.dataLength ? `(${column.dataLength})` : ''}
        {column.nullable === false && <span className="tw:ml-1 tw:text-quaternary">NOT NULL</span>}
      </td>
      <td className="tw:py-2 tw:align-top">
        {own.length > 0 ? (
          <div className="tw:flex tw:flex-wrap tw:gap-1.5">
            {own.map((facet) => (
              <FacetChip facet={facet} key={facetKey(facet)} />
            ))}
          </div>
        ) : (
          // A steward sees the Edit tags button in this cell instead; a dash
          // in front of it read as part of the button.
          !canEdit && <span className="tw:text-xs tw:text-quaternary">—</span>
        )}
        <LocalTagControl assetFqn={assetFqn} carried={directTags(column.facets)} targetFqn={column.fqn} />
      </td>
    </tr>
  );
}

function writtenBy(written: WrittenDescription | undefined): string {
  if (!written) return 'Written in ARAK';
  const when = new Date(written.writtenAt);
  const day = Number.isNaN(when.getTime()) ? '' : ` on ${when.toLocaleDateString()}`;
  return `Written in ARAK by ${written.writtenBy}${day}${written.assisted ? ', from a NokRak draft' : ''}`;
}

function savedSummary(result: DescriptionSave): string {
  const parts = [];
  if (result.set > 0) parts.push(`${result.set} written`);
  if (result.cleared > 0) parts.push(`${result.cleared} taken away`);
  return parts.length === 0 ? 'Nothing had changed.' : `Saved: ${parts.join(', ')}.`;
}

/** The tags applied right on a column, whichever source put them there. */
function directTags(facets: FacetRow[]): string[] {
  return facets
    .filter((facet) => facet.facetType === 'tags' && facet.direct && !facet.inheritedFrom)
    .map((facet) => facet.facetFqn);
}

function facetKey(facet: FacetRow): string {
  return `${facet.facetType}:${facet.facetFqn}:${facet.property ?? ''}:${facet.depth}`;
}
