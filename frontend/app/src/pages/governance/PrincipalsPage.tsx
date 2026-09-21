import { useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { ChevronDown, FilterLines, SearchLg, Users01 } from '@untitledui/icons';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Checkbox } from '@openmetadata/ui-core-components/components/base/checkbox/checkbox';
import { Input } from '@openmetadata/ui-core-components/components/base/input/input';
import { apiErrorMessage } from '../../api/client';
import {
  fetchAttributeVocabulary,
  fetchPrincipals,
  type AttributeCondition,
  type AttributeKey,
  type Principal,
} from '../../api/governance';

/**
 * People, groups and their attributes — the subject half of every policy.
 *
 * Read-only on purpose. Entra and OpenMetadata own this content and the next
 * sync would revert anything typed here, so an edit form would be a promise the
 * platform cannot keep. What this screen is for is the question that comes up
 * while writing a rule: does anybody actually carry the attribute I am about to
 * depend on, and what values does it take? A condition on an attribute nobody
 * has denies everyone, quietly and correctly, which is the hardest kind of
 * mistake to see afterwards.
 *
 * The attribute filter is that question asked directly. Several conditions are
 * ANDed, matching how a subject rule reads its own attribute list, so the count
 * above the results is the number of people that rule would match. Nothing here
 * follows group membership, because the engine does not either: PrincipalLoader
 * reads attributes off the principal's own rows and takes groups from a
 * separate walk. A directory that credited people with their group's attributes
 * would promise matches the engine will not make.
 *
 * <p>The filters live in the rail rather than in a card above the table, and
 * the results in a table rather than in a stack of cards, for the same reason
 * the catalog does: the counts only mean something when they line up in a
 * column where the eye can compare them, and a page whose first screenful is
 * chrome pushes the answer below the fold. Filter state is in the URL, so a
 * narrowed directory — "everyone a rule on clearance=L2 would match" — is a
 * link somebody can send.
 */

/** How the URL and the API both spell a condition: `key` or `key=value`. */
function conditionId(condition: AttributeCondition): string {
  return condition.value ? `${condition.key}=${condition.value}` : condition.key;
}

function parseCondition(raw: string): AttributeCondition {
  const cut = raw.indexOf('=');
  if (cut < 0) {
    return { key: raw };
  }
  const value = raw.slice(cut + 1);
  return { key: raw.slice(0, cut), value: value || undefined };
}

const KINDS = [
  { value: '', label: 'Everyone' },
  { value: 'USER', label: 'People' },
  { value: 'GROUP', label: 'Groups' },
  { value: 'SERVICE', label: 'Service accounts' },
];

export default function PrincipalsPage() {
  const [params, setParams] = useSearchParams();
  const [searchDraft, setSearchDraft] = useState(params.get('q') ?? '');

  const search = params.get('q') ?? '';
  const type = params.get('type') ?? '';
  const raw = useMemo(() => params.getAll('attr'), [params]);
  const conditions = useMemo(() => raw.map(parseCondition), [raw]);

  const { data: vocabulary } = useQuery({
    queryKey: ['attribute-vocabulary'],
    queryFn: fetchAttributeVocabulary,
    staleTime: 5 * 60 * 1000,
  });

  const { data, isLoading, error, isFetching } = useQuery({
    queryKey: ['principals', type, search, raw],
    queryFn: () =>
      fetchPrincipals({ type, search, attributes: conditions, limit: 200 }),
    placeholderData: keepPreviousData,
  });

  function update(next: (draft: URLSearchParams) => void) {
    const draft = new URLSearchParams(params);
    next(draft);
    setParams(draft, { replace: true });
  }

  function toggle(condition: AttributeCondition) {
    const id = conditionId(condition);
    update((draft) => {
      const current = draft.getAll('attr');
      draft.delete('attr');
      for (const entry of current) {
        if (entry !== id) {
          draft.append('attr', entry);
        }
      }
      if (!current.includes(id)) {
        draft.append('attr', id);
      }
    });
  }

  const filtered = Boolean(search || type || raw.length);
  const rows = data ?? [];

  return (
    <>
      <header className="tw:flex tw:flex-wrap tw:items-end tw:justify-between tw:gap-4">
        <div>
          <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
            People &amp; attributes
          </h1>
          <p className="tw:mt-2 tw:max-w-3xl tw:text-md tw:text-tertiary">
            The identity cache a subject rule is written against. Synced from
            Entra and OpenMetadata, and read-only here — an edit would be
            reverted by the next sync.
          </p>
        </div>
        {/* The other half of "who is this person": what they may do to the
            platform, which is a different question from what data they see and
            lives on a different screen. Said here because this is the page
            somebody is on when they start looking for it. */}
        <p className="tw:text-sm tw:text-tertiary">
          Platform roles are assigned in{' '}
          <Link
            className="tw:font-medium tw:text-brand-secondary tw:hover:underline"
            to="/settings/roles">
            Settings → Roles
          </Link>
        </p>
      </header>

      <section className="tw:mt-6 tw:flex tw:flex-wrap tw:items-center tw:gap-3">
        <form
          className="tw:flex tw:min-w-72 tw:flex-1 tw:items-center tw:gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            update((draft) => {
              if (searchDraft.trim()) {
                draft.set('q', searchDraft.trim());
              } else {
                draft.delete('q');
              }
            });
          }}>
          <div className="tw:min-w-56 tw:flex-1">
            <Input
              aria-label="Search people"
              icon={SearchLg}
              onChange={setSearchDraft}
              placeholder="Name, username or email"
              value={searchDraft}
            />
          </div>
          <Button size="md" type="submit">
            Search
          </Button>
        </form>

        <KindTabs
          onChange={(next) =>
            update((draft) => {
              if (next) {
                draft.set('type', next);
              } else {
                draft.delete('type');
              }
            })
          }
          value={type}
        />

        {filtered && (
          <Button
            color="tertiary"
            onPress={() => {
              setSearchDraft('');
              setParams(new URLSearchParams(), { replace: true });
            }}
            size="md">
            Clear
          </Button>
        )}
      </section>

      {conditions.length > 0 && (
        <section className="tw:mt-3 tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <span className="tw:text-xs tw:text-tertiary">Carrying all of:</span>
          {conditions.map((condition) => (
            <button
              aria-label={`Remove filter ${conditionLabel(condition)}`}
              className="tw:inline-flex tw:items-center tw:gap-1.5 tw:rounded-full tw:bg-brand-primary tw:px-2.5 tw:py-0.5 tw:text-xs tw:text-brand-secondary tw:hover:underline"
              key={conditionId(condition)}
              onClick={() => toggle(condition)}
              type="button">
              {conditionLabel(condition)}
              <span aria-hidden="true">×</span>
            </button>
          ))}
        </section>
      )}

      {error && (
        <p className="tw:mt-6 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'The identity cache could not be read.')}
        </p>
      )}

      <div className="tw:mt-6 tw:flex tw:flex-col tw:gap-6 tw:lg:flex-row tw:lg:items-start">
        <AttributeRail
          active={raw}
          keys={vocabulary?.keys ?? []}
          onToggle={toggle}
        />

        <section className="tw:min-w-0 tw:flex-1">
          <p className="tw:text-sm tw:text-tertiary">
            {isLoading
              ? 'Loading…'
              : `${rows.length} ${rows.length === 1 ? 'principal' : 'principals'}${
                  filtered ? ' matching' : ' cached'
                }`}
            {isFetching && !isLoading && ' · refreshing'}
            {conditions.length > 0 && rows.length > 0 && (
              // The sentence that turns a list of names into an answer: this is
              // exactly the set a subject rule with these attributes reaches.
              <span> — who a subject rule with these attributes would match.</span>
            )}
          </p>

          {!isLoading && rows.length === 0 ? (
            <Empty conditions={conditions.length > 0} filtered={filtered} />
          ) : (
            <PrincipalTable rows={rows} />
          )}
        </section>
      </div>
    </>
  );
}

function conditionLabel(condition: AttributeCondition): string {
  return condition.value
    ? `${condition.key} = ${condition.value}`
    : `${condition.key} · any value`;
}

/** Everyone / People / Groups / Service accounts, as one segmented control. */
function KindTabs({
  value,
  onChange,
}: {
  value: string;
  onChange: (next: string) => void;
}) {
  return (
    <div
      aria-label="Kind"
      className="tw:inline-flex tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:p-0.5"
      role="group">
      {KINDS.map((kind) => (
        <button
          aria-pressed={value === kind.value}
          className={`tw:rounded-md tw:px-3 tw:py-1.5 tw:text-sm tw:font-medium ${
            value === kind.value
              ? 'tw:bg-secondary tw:text-primary'
              : 'tw:text-tertiary tw:hover:text-primary'
          }`}
          key={kind.value || 'all'}
          onClick={() => onChange(kind.value)}
          type="button">
          {kind.label}
        </button>
      ))}
    </div>
  );
}

/**
 * The attribute vocabulary, as filters.
 *
 * <p>Every key with every value it takes and the number of people carrying it —
 * the rail is the answer to "what can I write a rule on", and the counts are
 * what make it an answer rather than a list. A value carried by nobody is
 * visible here before it is written into a policy.
 */
function AttributeRail({
  keys,
  active,
  onToggle,
}: {
  keys: AttributeKey[];
  active: string[];
  onToggle: (condition: AttributeCondition) => void;
}) {
  // The vocabulary lists a key once per source, so the same key can arrive
  // twice; the values are merged, because a filter does not care which sync
  // produced the row. The people count is taken as the largest of them rather
  // than the sum, since one person can hold the key from two sources and
  // adding those would report more people than the directory holds.
  const merged = useMemo(() => {
    const byKey = new Map<
      string,
      { key: string; principals: number; values: Map<string, number> }
    >();
    for (const entry of keys) {
      const slot = byKey.get(entry.key) ?? {
        key: entry.key,
        principals: 0,
        values: new Map<string, number>(),
      };
      slot.principals = Math.max(slot.principals, entry.principals);
      for (const value of entry.values) {
        slot.values.set(
          value.value,
          Math.max(slot.values.get(value.value) ?? 0, value.principals)
        );
      }
      byKey.set(entry.key, slot);
    }
    return [...byKey.values()].sort((a, b) => a.key.localeCompare(b.key));
  }, [keys]);

  if (merged.length === 0) {
    return null;
  }

  return (
    <aside className="tw:w-full tw:shrink-0 tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:lg:sticky tw:lg:top-4 tw:lg:w-64 tw:lg:self-start">
      <div className="tw:flex tw:items-center tw:gap-2 tw:border-b tw:border-secondary tw:px-4 tw:py-3">
        <FilterLines className="tw:size-4 tw:text-tertiary" />
        <h2 className="tw:text-sm tw:font-semibold tw:text-primary">Attributes</h2>
        {active.length > 0 && (
          <span className="tw:ml-auto tw:rounded-full tw:bg-brand-primary tw:px-2 tw:py-0.5 tw:text-xs tw:font-medium tw:text-brand-secondary">
            {active.length}
          </span>
        )}
      </div>

      {merged.map((entry) => (
        <AttributeGroup
          active={active}
          entry={entry}
          key={entry.key}
          onToggle={onToggle}
        />
      ))}

      <p className="tw:border-t tw:border-secondary tw:px-4 tw:py-3 tw:text-xs tw:text-tertiary">
        Conditions are AND-ed, the way a subject rule reads its own attribute
        list.
      </p>
    </aside>
  );
}

/** Values shown per key before "Show all" is offered. */
const VALUE_PREVIEW = 6;

function AttributeGroup({
  entry,
  active,
  onToggle,
}: {
  entry: { key: string; principals: number; values: Map<string, number> };
  active: string[];
  onToggle: (condition: AttributeCondition) => void;
}) {
  const [open, setOpen] = useState(true);
  const [all, setAll] = useState(false);

  const values = useMemo(
    () => [...entry.values.entries()].sort((a, b) => a[0].localeCompare(b[0])),
    [entry.values]
  );
  const shown = all ? values : values.slice(0, VALUE_PREVIEW);
  const selected = active.filter(
    (id) => id === entry.key || id.startsWith(`${entry.key}=`)
  ).length;

  return (
    <div className="tw:border-b tw:border-secondary tw:last:border-b-0">
      <button
        aria-expanded={open}
        className="tw:flex tw:w-full tw:items-center tw:gap-2 tw:px-4 tw:py-2.5 tw:text-left tw:hover:bg-secondary"
        onClick={() => setOpen((it) => !it)}
        type="button">
        <span className="tw:flex-1 tw:truncate tw:text-sm tw:font-medium tw:text-secondary">
          {entry.key}
        </span>
        {selected > 0 && (
          <span className="tw:rounded-full tw:bg-brand-primary tw:px-1.5 tw:text-xs tw:font-medium tw:text-brand-secondary">
            {selected}
          </span>
        )}
        <ChevronDown
          className={`tw:size-4 tw:shrink-0 tw:text-quaternary tw:transition-transform tw:duration-150 ${
            open ? '' : 'tw:-rotate-90'
          }`}
        />
      </button>

      {open && (
        <div className="tw:px-2 tw:pb-3">
          {/* First, and named as its own condition: "carries this at all" is
              the question behind "is this attribute populated yet", and it is
              not the same as any of the values below it. */}
          <RailRow
            count={entry.principals}
            isSelected={active.includes(entry.key)}
            label="any value"
            name={`${entry.key} · any value`}
            onToggle={() => onToggle({ key: entry.key })}
            quiet
          />
          {shown.map(([value, principals]) => (
            <RailRow
              count={principals}
              isSelected={active.includes(`${entry.key}=${value}`)}
              key={value}
              label={value}
              name={`${entry.key} = ${value}`}
              onToggle={() => onToggle({ key: entry.key, value })}
            />
          ))}
          {values.length > VALUE_PREVIEW && (
            <button
              className="tw:px-2 tw:pt-1 tw:text-xs tw:font-medium tw:text-fg-brand-primary tw:hover:underline"
              onClick={() => setAll((it) => !it)}
              type="button">
              {all ? 'Show less' : `Show all ${values.length}`}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function RailRow({
  name,
  label,
  count,
  isSelected,
  onToggle,
  quiet,
}: {
  /** The accessible name — the whole condition, since "L2" alone says nothing. */
  name: string;
  label: string;
  count: number;
  isSelected: boolean;
  onToggle: () => void;
  quiet?: boolean;
}) {
  return (
    <div
      className="tw:flex tw:items-center tw:gap-2 tw:rounded-md tw:px-2 tw:py-1.5 tw:hover:bg-secondary"
      title={name}>
      <Checkbox
        aria-label={name}
        isSelected={isSelected}
        onChange={onToggle}
        size="sm"
      />
      <button
        // Named with the whole condition, not just the label: "L2" on its own
        // is not something a screen reader user can act on.
        aria-label={name}
        className={`tw:min-w-0 tw:flex-1 tw:cursor-pointer tw:truncate tw:text-left tw:text-sm ${
          quiet ? 'tw:text-tertiary tw:italic' : 'tw:text-secondary'
        }`}
        onClick={onToggle}
        tabIndex={-1}
        type="button">
        {label}
      </button>
      <span className="tw:shrink-0 tw:text-xs tw:tabular-nums tw:text-quaternary">
        {count}
      </span>
    </div>
  );
}

function PrincipalTable({ rows }: { rows: Principal[] }) {
  return (
    <div className="tw:mt-4 tw:overflow-x-auto tw:rounded-xl tw:border tw:border-secondary tw:bg-primary">
      <table className="tw:w-full tw:text-sm">
        <thead>
          <tr className="tw:border-b tw:border-secondary tw:text-left">
            <Th>Name</Th>
            <Th>Kind</Th>
            <Th>Source</Th>
            <Th align="right">Attributes</Th>
            <Th align="right">Membership</Th>
            <Th>Platform role</Th>
          </tr>
        </thead>
        <tbody>
          {rows.map((principal) => (
            <PrincipalRow key={principal.id} principal={principal} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Th({
  children,
  align,
}: {
  children: React.ReactNode;
  align?: 'right';
}) {
  return (
    <th
      className={`tw:px-4 tw:py-2.5 tw:text-xs tw:font-medium tw:text-tertiary ${
        align === 'right' ? 'tw:text-right' : ''
      }`}>
      {children}
    </th>
  );
}

function PrincipalRow({ principal }: { principal: Principal }) {
  const isGroup = principal.principalType === 'GROUP';
  const membership = isGroup
    ? `${principal.memberCount} member${principal.memberCount === 1 ? '' : 's'}`
    : `${principal.groupCount} group${principal.groupCount === 1 ? '' : 's'}`;

  return (
    <tr className="tw:border-b tw:border-secondary tw:last:border-0 tw:hover:bg-secondary">
      <td className="tw:px-4 tw:py-3">
        <Link
          className="tw:font-medium tw:text-primary tw:hover:text-brand-secondary"
          to={`/principals/${encodeURIComponent(principal.id)}`}>
          {principal.displayName || principal.username}
        </Link>
        <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
          {principal.username}
          {principal.email && ` · ${principal.email}`}
        </p>
      </td>
      <td className="tw:px-4 tw:py-3">
        <div className="tw:flex tw:items-center tw:gap-1.5">
          <Badge color="gray" size="sm" type="modern">
            {principal.principalType.toLowerCase()}
          </Badge>
          {!principal.enabled && (
            <Badge color="warning" size="sm" type="pill-color">
              disabled
            </Badge>
          )}
        </div>
      </td>
      <td className="tw:px-4 tw:py-3">
        <Badge color="blue-light" size="sm" type="pill-color">
          {principal.source}
        </Badge>
      </td>
      <td className="tw:px-4 tw:py-3 tw:text-right tw:tabular-nums tw:text-tertiary">
        {principal.attributeCount || '—'}
      </td>
      <td className="tw:px-4 tw:py-3 tw:text-right tw:whitespace-nowrap">
        {/* A group is opened for exactly one reason, so the count is the door. */}
        {isGroup && principal.memberCount > 0 ? (
          <Link
            className="tw:inline-flex tw:items-center tw:gap-1 tw:text-brand-secondary tw:hover:underline"
            to={`/principals/${encodeURIComponent(principal.id)}`}>
            <Users01 className="tw:size-3.5" />
            {membership}
          </Link>
        ) : (
          <span className="tw:text-tertiary">{membership}</span>
        )}
      </td>
      <td className="tw:px-4 tw:py-3">
        {principal.appRoles.length === 0 ? (
          <span className="tw:text-quaternary">—</span>
        ) : (
          <div className="tw:flex tw:flex-wrap tw:gap-1">
            {principal.appRoles.map((role) => (
              <Badge color="brand" key={role} size="sm" type="pill-color">
                {role.replace(/_/g, ' ').toLowerCase()}
              </Badge>
            ))}
          </div>
        )}
      </td>
    </tr>
  );
}

function Empty({
  filtered,
  conditions,
}: {
  filtered: boolean;
  conditions: boolean;
}) {
  return (
    <div className="tw:mt-4 tw:rounded-xl tw:border tw:border-dashed tw:border-secondary tw:p-10 tw:text-center">
      <Users01 className="tw:mx-auto tw:size-6 tw:text-quaternary" />
      <p className="tw:mt-3 tw:text-sm tw:font-medium tw:text-primary">
        {conditions
          ? 'Nobody carries all of those'
          : filtered
            ? 'Nobody matches that'
            : 'The identity cache is empty'}
      </p>
      <p className="tw:mx-auto tw:mt-1 tw:max-w-md tw:text-sm tw:text-tertiary">
        {conditions
          ? // The quiet failure this page exists to prevent.
            'A subject rule written this way would match no one — which is a denial, not an error, and one worth noticing before the policy is active.'
          : filtered
            ? 'Try a shorter search, or a different kind.'
            : 'No sync has run yet. Until it does, an ABAC condition would match nobody.'}
      </p>
    </div>
  );
}
