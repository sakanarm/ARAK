import { Plus, Trash01 } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import type {
  AttributeCondition,
  PrincipalMatch,
  SubjectRule,
} from '../../generated/entity/policy/policy';
import type { AttributeVocabulary, Principal } from '../../api/governance';
import { Field, Select, TextField } from './controls';

/**
 * Who a policy is about (FR-3.2).
 *
 * Deliberately one form, not four tabs labelled RBAC, ABAC, rule-based and
 * time-based. Those are four ways of describing one predicate, and every block
 * below is ANDed with the others: an author who picks a role and then adds a
 * clearance attribute has written "this role, and only when their clearance
 * says so", which is what they meant. Four tabs would suggest four independent
 * systems and leave people guessing which one wins.
 */

const ATTRIBUTE_OPERATORS: {
  value: AttributeCondition['operator'];
  label: string;
}[] = [
  { value: 'eq', label: 'is' },
  { value: 'ne', label: 'is not' },
  { value: 'in', label: 'is one of' },
  { value: 'notIn', label: 'is none of' },
  { value: 'gte', label: 'is at least' },
  { value: 'lte', label: 'is at most' },
  { value: 'gt', label: 'is above' },
  { value: 'lt', label: 'is below' },
  { value: 'contains', label: 'is or is under' },
  { value: 'exists', label: 'is set' },
  { value: 'notExists', label: 'is not set' },
];

const DAY_PRESETS = [
  { value: '', label: 'every day' },
  { value: 'MON-FRI', label: 'weekdays' },
  { value: 'SAT,SUN', label: 'weekends' },
];

export interface SubjectBuilderProps {
  value?: SubjectRule;
  onChange: (next: SubjectRule) => void;
  attributes?: AttributeVocabulary;
  principals?: Principal[];
}

export default function SubjectBuilder({
  value,
  onChange,
  attributes,
  principals,
}: SubjectBuilderProps) {
  const subject: SubjectRule = value ?? {};

  function patch(next: Partial<SubjectRule>) {
    onChange({ ...subject, ...next });
  }

  const matches = subject.principals ?? [];
  const required = subject.requiredPrincipals ?? [];
  const attributeRows = subject.attributes ?? [];
  const windows = subject.time?.windows ?? [];

  return (
    <div className="tw:flex tw:flex-col tw:gap-6">
      {/* ---------------------------------------------------------- who */}
      <PrincipalList
        addLabel="Add who"
        hint="Any one of these is enough. Two groups here mean somebody in either of them. Leave it empty to mean everyone, and narrow with the conditions below."
        matches={matches}
        onChange={(next) => patch({ principals: next })}
        principals={principals}
        roles={attributes?.appRoles ?? []}
        title="Anyone who is"
      />

      {/*
        The second list, and the reason there are two.

        Membership questions arrive in both shapes and one list can only answer
        one of them. "Anyone in Finance or Risk" is an or; "and they must also
        be in the group that has completed the privacy training" is an and, and
        adding the training group as a third entry above would widen the grant
        to everyone who has completed it — the opposite of what was meant. So
        the or list says who is in scope and this one says what every one of
        them must additionally hold.

        Shown only once the first list has something in it, or on its own if an
        author starts here: an empty form with two identical-looking lists is
        how the distinction gets missed.
      */}
      {(matches.length > 0 || required.length > 0) && (
        <PrincipalList
          addLabel="Add requirement"
          hint="Every one of these must hold as well. Two groups here mean somebody in both of them."
          matches={required}
          onChange={(next) => patch({ requiredPrincipals: next })}
          principals={principals}
          roles={attributes?.appRoles ?? []}
          title="And who is also"
        />
      )}

      {/* --------------------------------------------------- attributes */}
      <div>
        <h3 className="tw:text-sm tw:font-semibold tw:text-primary">
          And whose attributes say
        </h3>
        <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
          All of these must hold. The keys and values come from the identity
          cache, so a condition on an attribute nobody carries is visible here
          rather than at the first denied query.
        </p>
        <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
          {attributeRows.map((row, index) => (
            <AttributeRow
              attributes={attributes}
              key={index}
              onChange={(next) => {
                const copy = [...attributeRows];
                copy[index] = next;
                patch({ attributes: copy });
              }}
              onRemove={() =>
                patch({
                  attributes: attributeRows.filter((_, i) => i !== index),
                })
              }
              row={row}
            />
          ))}
          <div>
            <Button
              color="secondary"
              iconLeading={Plus}
              onPress={() =>
                patch({
                  attributes: [
                    ...attributeRows,
                    {
                      key: attributes?.keys[0]?.key ?? 'department',
                      operator: 'eq',
                      value: '',
                    },
                  ],
                })
              }
              size="sm">
              Add attribute condition
            </Button>
          </div>
        </div>
      </div>

      {/* --------------------------------------------------- expression */}
      <Field
        hint="For comparisons that need both sides at once — user.country == asset.prop('dataResidency'), user.department in asset.domains, user.email in asset.owners. This is what lets one policy cover the whole organisation instead of one per table."
        label="And this holds (optional)">
        <TextField
          onChange={(next) => patch({ expression: next || undefined })}
          placeholder="user.country == asset.prop('dataResidency')"
          value={subject.expression ?? ''}
        />
      </Field>

      {/* --------------------------------------------------------- time */}
      <div>
        <h3 className="tw:text-sm tw:font-semibold tw:text-primary">
          And only during
        </h3>
        <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
          The timezone is part of the rule and never taken from the server clock:
          a policy meaning office hours in Bangkok must not change meaning when
          the service moves region.
        </p>
        <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
          {windows.map((window, index) => (
            <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2" key={index}>
              <Select
                ariaLabel="Days"
                className="tw:w-36"
                onChange={(next) => {
                  const copy = [...windows];
                  copy[index] = {
                    ...window,
                    days: next ? next.split(',') : undefined,
                  };
                  patch({ time: { ...subject.time, windows: copy } });
                }}
                options={DAY_PRESETS}
                value={window.days?.join(',') ?? ''}
              />
              <TextField
                // Wide enough for the AM/PM segment the browser adds in a
                // 12-hour locale. At w-24 that segment is clipped, so a window
                // ending at 18:00 renders as `06:00` and reads as six in the
                // morning -- a policy misread by twelve hours, in the one
                // screen whose job is to say exactly when access holds.
                ariaLabel="From"
                className="tw:w-36"
                onChange={(next) => {
                  const copy = [...windows];
                  copy[index] = { ...window, from: next };
                  patch({ time: { ...subject.time, windows: copy } });
                }}
                type="time"
                value={window.from}
              />
              <span className="tw:text-sm tw:text-tertiary">to</span>
              <TextField
                ariaLabel="To"
                className="tw:w-36"
                onChange={(next) => {
                  const copy = [...windows];
                  copy[index] = { ...window, to: next };
                  patch({ time: { ...subject.time, windows: copy } });
                }}
                type="time"
                value={window.to}
              />
              <TextField
                ariaLabel="Timezone"
                className="tw:w-48"
                onChange={(next) => {
                  const copy = [...windows];
                  copy[index] = { ...window, timezone: next };
                  patch({ time: { ...subject.time, windows: copy } });
                }}
                placeholder="Asia/Bangkok"
                value={window.timezone}
              />
              <Button
                aria-label="Remove this window"
                color="tertiary"
                iconLeading={Trash01}
                onPress={() =>
                  patch({
                    time: {
                      ...subject.time,
                      windows: windows.filter((_, i) => i !== index),
                    },
                  })
                }
                size="sm"
              />
            </div>
          ))}
          <div>
            <Button
              color="secondary"
              iconLeading={Plus}
              onPress={() =>
                patch({
                  time: {
                    ...subject.time,
                    windows: [
                      ...windows,
                      {
                        days: ['MON-FRI'],
                        from: '08:00',
                        to: '18:00',
                        timezone: 'Asia/Bangkok',
                      },
                    ],
                  },
                })
              }
              size="sm">
              Add time window
            </Button>
          </div>
        </div>
      </div>

      {/* ------------------------------------------------------ context */}
      <div className="tw:grid tw:gap-4 tw:sm:grid-cols-2">
        <Field
          hint="Comma separated. Only trustworthy when the connection reaches the source through us."
          label="From these networks (optional)">
          <TextField
            onChange={(next) =>
              patch({
                context: {
                  ...subject.context,
                  ipCidr: splitList(next),
                },
              })
            }
            placeholder="10.0.0.0/8"
            value={subject.context?.ipCidr?.join(', ') ?? ''}
          />
        </Field>
        <Field
          hint="Declared by the caller and recorded in the audit log."
          label="For these purposes (optional)">
          <TextField
            onChange={(next) =>
              patch({
                context: {
                  ...subject.context,
                  purpose: splitList(next),
                },
              })
            }
            placeholder="fraud-analysis"
            value={subject.context?.purpose?.join(', ') ?? ''}
          />
        </Field>
      </div>
    </div>
  );
}

function splitList(value: string): string[] | undefined {
  const parts = value
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
  return parts.length ? parts : undefined;
}

/**
 * One of the two identity lists.
 *
 * Both are the same control because they hold the same kind of entry; what
 * differs is how the entries are joined, and that is carried by the heading and
 * the hint rather than by a different-looking widget. An author reads "Anyone
 * who is / And who is also" as one sentence, which is what the two lists
 * compose into.
 */
function PrincipalList({
  title,
  hint,
  addLabel,
  matches,
  onChange,
  roles,
  principals,
}: {
  title: string;
  hint: string;
  addLabel: string;
  matches: PrincipalMatch[];
  onChange: (next: PrincipalMatch[]) => void;
  roles: string[];
  principals?: Principal[];
}) {
  return (
    <div>
      <h3 className="tw:text-sm tw:font-semibold tw:text-primary">{title}</h3>
      <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">{hint}</p>
      <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
        {matches.map((match, index) => (
          <PrincipalRow
            key={index}
            match={match}
            onChange={(next) => {
              const copy = [...matches];
              copy[index] = next;
              onChange(copy);
            }}
            onRemove={() => onChange(matches.filter((_, i) => i !== index))}
            principals={principals}
            roles={roles}
          />
        ))}
        <div>
          <Button
            color="secondary"
            iconLeading={Plus}
            onPress={() => onChange([...matches, { group: '' }])}
            size="sm">
            {addLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}

/** One entry in either identity list. */
function PrincipalRow({
  match,
  onChange,
  onRemove,
  roles,
  principals,
}: {
  match: PrincipalMatch;
  onChange: (next: PrincipalMatch) => void;
  onRemove: () => void;
  roles: string[];
  principals?: Principal[];
}) {
  const kind = match.assetOwner
    ? 'assetOwner'
    : match.role !== undefined
      ? 'role'
      : match.team !== undefined
        ? 'team'
        : match.group !== undefined
          ? 'group'
          : 'user';

  const groups = (principals ?? []).filter(
    (principal) => principal.principalType === 'GROUP'
  );
  const users = (principals ?? []).filter(
    (principal) => principal.principalType === 'USER'
  );

  return (
    <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
      <Select
        ariaLabel="Kind of subject"
        className="tw:w-48"
        onChange={(next) => {
          switch (next) {
            case 'assetOwner':
              return onChange({ assetOwner: true });
            case 'role':
              return onChange({ role: roles[0] ?? '' });
            case 'team':
              return onChange({ team: '' });
            case 'group':
              return onChange({ group: '' });
            default:
              return onChange({ user: '' });
          }
        }}
        options={[
          { value: 'role', label: 'in the platform role' },
          { value: 'team', label: 'in the team' },
          { value: 'group', label: 'in the group' },
          { value: 'user', label: 'this person' },
          { value: 'assetOwner', label: 'an owner of the asset' },
        ]}
        value={kind}
      />

      {kind === 'assetOwner' ? (
        <p className="tw:text-sm tw:text-tertiary">
          Whoever OpenMetadata lists as an owner, followed as ownership changes —
          no policy edit when the team does.
        </p>
      ) : kind === 'role' ? (
        <Select
          ariaLabel="Role"
          className="tw:w-64"
          onChange={(next) => onChange({ role: next })}
          options={roles.map((role) => ({ value: role, label: role }))}
          value={match.role ?? ''}
        />
      ) : (
        <>
          <TextField
            ariaLabel="Name"
            className="tw:w-64"
            list={`principals-${kind}`}
            onChange={(next) =>
              onChange(
                kind === 'team'
                  ? { team: next }
                  : kind === 'group'
                    ? { group: next }
                    : { user: next }
              )
            }
            placeholder={kind === 'user' ? 'username or email' : 'name'}
            value={match.team ?? match.group ?? match.user ?? ''}
          />
          <datalist id={`principals-${kind}`}>
            {(kind === 'user' ? users : groups).map((principal) => (
              <option key={principal.id} value={principal.username}>
                {principal.displayName ?? principal.source}
              </option>
            ))}
          </datalist>
        </>
      )}

      <Button
        aria-label="Remove"
        color="tertiary"
        iconLeading={Trash01}
        onPress={onRemove}
        size="sm"
      />
    </div>
  );
}

function AttributeRow({
  row,
  onChange,
  onRemove,
  attributes,
}: {
  row: AttributeCondition;
  onChange: (next: AttributeCondition) => void;
  onRemove: () => void;
  attributes?: AttributeVocabulary;
}) {
  const known = attributes?.keys.find((key) => key.key === row.key);

  return (
    <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
      <TextField
        ariaLabel="Attribute"
        className="tw:w-52"
        list="attribute-keys"
        onChange={(next) => onChange({ ...row, key: next })}
        placeholder="department"
        value={row.key}
      />
      <datalist id="attribute-keys">
        {(attributes?.keys ?? []).map((key) => (
          <option key={`${key.key}-${key.source}`} value={key.key}>
            {`${key.principals} people · ${key.source}`}
          </option>
        ))}
      </datalist>

      <Select
        ariaLabel="Operator"
        className="tw:w-40"
        onChange={(next) =>
          onChange({ ...row, operator: next as AttributeCondition['operator'] })
        }
        options={ATTRIBUTE_OPERATORS.map((operator) => ({
          value: operator.value,
          label: operator.label,
        }))}
        value={row.operator}
      />

      {row.operator !== 'exists' && row.operator !== 'notExists' && (
        <>
          <TextField
            ariaLabel="Value"
            className="tw:w-56"
            list={known ? `attribute-values-${known.key}` : undefined}
            onChange={(next) => onChange({ ...row, value: next })}
            placeholder="FINANCE"
            value={String(row.value ?? '')}
          />
          {known && (
            <datalist id={`attribute-values-${known.key}`}>
              {known.values.map((value) => (
                // The carrier count rides along as the option's label: an
                // author picking a value should see that it reaches three
                // people, not learn it after publishing.
                <option key={value.value} value={value.value}>
                  {value.principals}
                </option>
              ))}
            </datalist>
          )}
        </>
      )}

      {!known && row.key && (
        <span className="tw:text-xs tw:text-warning-primary">
          nobody carries this attribute yet
        </span>
      )}

      <Button
        aria-label="Remove"
        color="tertiary"
        iconLeading={Trash01}
        onPress={onRemove}
        size="sm"
      />
    </div>
  );
}
