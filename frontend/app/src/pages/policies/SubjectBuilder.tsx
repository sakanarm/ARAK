import { Fragment, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Plus, Trash01 } from "@untitledui/icons";
import { Button } from "@openmetadata/ui-core-components/components/base/buttons/button";
import type {
  AttributeCondition,
  PrincipalMatch,
  SubjectRule,
} from "../../generated/entity/policy/policy";
import type { AttributeVocabulary, Principal } from "../../api/governance";
import {
  validateExpression,
  type ExpressionVerdict,
} from "../../api/expressions";
import { Field, Select, TextField, ValueList } from "./controls";
import { isListOperator, listOf, withOperator } from "./conditionValues";
import { PurposeChecklist } from "./purposePickers";

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
  value: AttributeCondition["operator"];
  label: string;
}[] = [
  { value: "eq", label: "is" },
  { value: "ne", label: "is not" },
  { value: "in", label: "is one of" },
  { value: "notIn", label: "is none of" },
  { value: "gte", label: "is at least" },
  { value: "lte", label: "is at most" },
  { value: "gt", label: "is above" },
  { value: "lt", label: "is below" },
  { value: "contains", label: "is or is under" },
  { value: "exists", label: "is set" },
  { value: "notExists", label: "is not set" },
];

const DAY_PRESETS = [
  { value: "", label: "every day" },
  { value: "MON-FRI", label: "weekdays" },
  { value: "SAT,SUN", label: "weekends" },
];

type Join = "and" | "or";

/**
 * How the rows inside one block are joined, said inside the block.
 *
 * <p>Two lists of identical-looking rows sit on this screen, one an or and one
 * an and, and the difference decides whether a policy reaches everybody in
 * either group or only the people in both. That is the most expensive thing to
 * get wrong on this form, and a line of hint text above the rows is read once
 * and then forgotten the moment somebody is looking at the rows themselves. So
 * the word also sits between the rows it joins, in the eye line of whoever is
 * adding the second one -- which is the moment the question first arises.
 *
 * <p>It is text, not a colour: an author who cannot tell the blue block from
 * the grey one still reads "or" and "and".
 */
function JoinTag({ join }: { join: Join }) {
  return (
    <span
      className={`tw:rounded tw:px-1.5 tw:py-0.5 tw:text-[10px] tw:font-bold tw:tracking-wider tw:uppercase ${
        join === "or"
          ? "tw:bg-utility-blue-100 tw:text-utility-blue-700"
          : "tw:bg-utility-gray-100 tw:text-utility-gray-700"
      }`}
    >
      {join === "or" ? "any of these" : "all of these"}
    </span>
  );
}

/** The heading of one block, carrying how its rows are joined. */
function SectionHeading({ title, join }: { title: string; join: Join }) {
  return (
    <h3 className="tw:flex tw:items-center tw:gap-2 tw:text-sm tw:font-semibold tw:text-primary">
      {title}
      <JoinTag join={join} />
    </h3>
  );
}

/**
 * The word between two rows.
 *
 * <p>Rendered as a real word rather than a rule with a gap, so it survives
 * being read aloud: a screen reader moving down the list hears "or" between the
 * entries, which is the whole meaning of the list.
 */
function JoinRow({ join }: { join: Join }) {
  return (
    <div className="tw:flex tw:items-center tw:gap-2">
      <span
        className={`tw:shrink-0 tw:text-[11px] tw:font-bold tw:tracking-wider tw:uppercase ${
          join === "or" ? "tw:text-utility-blue-700" : "tw:text-quaternary"
        }`}
      >
        {join}
      </span>
      <span aria-hidden className="tw:h-px tw:flex-1 tw:bg-border-secondary" />
    </div>
  );
}

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
        join="or"
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
          join="and"
          matches={required}
          onChange={(next) => patch({ requiredPrincipals: next })}
          principals={principals}
          roles={attributes?.appRoles ?? []}
          title="And who is also"
        />
      )}

      {/* --------------------------------------------------- attributes */}
      <div>
        <SectionHeading join="and" title="And whose attributes say" />
        <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
          All of these must hold. The keys and values come from the identity
          cache, so a condition on an attribute nobody carries is visible here
          rather than at the first denied query. For either of several values
          of one attribute, use "is one of"; for either of two attributes,
          write it in the expression below with ||.
        </p>
        <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
          {attributeRows.map((row, index) => (
            <Fragment key={index}>
              {index > 0 && <JoinRow join="and" />}
              <AttributeRow
                attributes={attributes}
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
            </Fragment>
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
                      key: attributes?.keys[0]?.key ?? "department",
                      operator: "eq",
                      value: "",
                    },
                  ],
                })
              }
              size="sm"
            >
              Add attribute condition
            </Button>
          </div>
        </div>
      </div>

      {/* --------------------------------------------------- expression */}
      <Field
        hint="For comparisons that need both sides at once — user.country == asset.prop('dataResidency'), user.department in asset.domains, user.email in asset.owners. This is what lets one policy cover the whole organisation instead of one per table."
        label="And this holds (optional)"
      >
        <TextField
          onChange={(next) => patch({ expression: next || undefined })}
          placeholder="user.country == asset.prop('dataResidency')"
          value={subject.expression ?? ""}
        />
      </Field>
      <ExpressionNote expression={subject.expression} />

      {/* --------------------------------------------------------- time */}
      <div>
        <SectionHeading join="or" title="And only during" />
        <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
          The timezone is part of the rule and never taken from the server
          clock: a policy meaning office hours in Bangkok must not change
          meaning when the service moves region.
        </p>
        <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
          {windows.map((window, index) => (
            <Fragment key={index}>
              {index > 0 && <JoinRow join="or" />}
              <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
                <Select
                  ariaLabel="Days"
                  className="tw:w-36"
                  onChange={(next) => {
                    const copy = [...windows];
                    copy[index] = {
                      ...window,
                      days: next ? next.split(",") : undefined,
                    };
                    patch({ time: { ...subject.time, windows: copy } });
                  }}
                  options={DAY_PRESETS}
                  value={window.days?.join(",") ?? ""}
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
            </Fragment>
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
                        days: ["MON-FRI"],
                        from: "08:00",
                        to: "18:00",
                        timezone: "Asia/Bangkok",
                      },
                    ],
                  },
                })
              }
              size="sm"
            >
              Add time window
            </Button>
          </div>
        </div>
      </div>

      {/* ------------------------------------------------------ context */}
      <div className="tw:grid tw:gap-4 tw:sm:grid-cols-2">
        <Field
          hint="Comma separated. Only trustworthy when the connection reaches the source through us."
          label="From these networks (optional)"
        >
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
            value={subject.context?.ipCidr?.join(", ") ?? ""}
          />
        </Field>
        {/* Not a Field: that is a <label>, and a label around a list of
            checkboxes ticks the first one whenever its heading is clicked. */}
        <div className="tw:flex tw:flex-col tw:gap-1.5">
          <span className="tw:text-sm tw:font-medium tw:text-secondary">
            For these purposes (optional)
          </span>
          <PurposeChecklist
            emptyHint="The register lists no purpose yet, so none can be named."
            label="For these purposes"
            onChange={(next) =>
              patch({
                context: {
                  ...subject.context,
                  purpose: next.length > 0 ? next : undefined,
                },
              })
            }
            value={subject.context?.purpose ?? []}
          />
          <span className="tw:text-xs tw:text-tertiary">
            From the register of purposes. None ticked allows any purpose, or none;
            the caller declares one and it is recorded in the audit log.
          </span>
        </div>
      </div>
    </div>
  );
}

function splitList(value: string): string[] | undefined {
  const parts = value
    .split(",")
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
  join,
  matches,
  onChange,
  roles,
  principals,
}: {
  title: string;
  hint: string;
  addLabel: string;
  join: Join;
  matches: PrincipalMatch[];
  onChange: (next: PrincipalMatch[]) => void;
  roles: string[];
  principals?: Principal[];
}) {
  return (
    <div>
      <SectionHeading join={join} title={title} />
      <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">{hint}</p>
      <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
        {matches.map((match, index) => (
          <Fragment key={index}>
            {index > 0 && <JoinRow join={join} />}
            <PrincipalRow
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
          </Fragment>
        ))}
        <div>
          <Button
            color="secondary"
            iconLeading={Plus}
            onPress={() => onChange([...matches, { group: "" }])}
            size="sm"
          >
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
    ? "assetOwner"
    : match.role !== undefined
      ? "role"
      : match.team !== undefined
        ? "team"
        : match.group !== undefined
          ? "group"
          : "user";

  const groups = (principals ?? []).filter(
    (principal) => principal.principalType === "GROUP",
  );
  const users = (principals ?? []).filter(
    (principal) => principal.principalType === "USER",
  );

  return (
    <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
      <Select
        ariaLabel="Kind of subject"
        className="tw:w-48"
        onChange={(next) => {
          switch (next) {
            case "assetOwner":
              return onChange({ assetOwner: true });
            case "role":
              return onChange({ role: roles[0] ?? "" });
            case "team":
              return onChange({ team: "" });
            case "group":
              return onChange({ group: "" });
            default:
              return onChange({ user: "" });
          }
        }}
        options={[
          { value: "role", label: "in the platform role" },
          { value: "team", label: "in the team" },
          { value: "group", label: "in the group" },
          { value: "user", label: "this person" },
          { value: "assetOwner", label: "an owner of the asset" },
        ]}
        value={kind}
      />

      {kind === "assetOwner" ? (
        <p className="tw:text-sm tw:text-tertiary">
          Whoever OpenMetadata lists as an owner, followed as ownership changes
          — no policy edit when the team does.
        </p>
      ) : kind === "role" ? (
        <Select
          ariaLabel="Role"
          className="tw:w-64"
          onChange={(next) => onChange({ role: next })}
          options={roles.map((role) => ({ value: role, label: role }))}
          value={match.role ?? ""}
        />
      ) : (
        <>
          <TextField
            ariaLabel="Name"
            className="tw:w-64"
            list={`principals-${kind}`}
            onChange={(next) =>
              onChange(
                kind === "team"
                  ? { team: next }
                  : kind === "group"
                    ? { group: next }
                    : { user: next },
              )
            }
            placeholder={kind === "user" ? "username or email" : "name"}
            value={match.team ?? match.group ?? match.user ?? ""}
          />
          <datalist id={`principals-${kind}`}>
            {(kind === "user" ? users : groups).map((principal) => (
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
          onChange(withOperator(row, next as AttributeCondition["operator"]))
        }
        options={ATTRIBUTE_OPERATORS.map((operator) => ({
          value: operator.value,
          label: operator.label,
        }))}
        value={row.operator}
      />

      {row.operator !== "exists" && row.operator !== "notExists" && (
        <>
          {isListOperator(row.operator) ? (
            <ValueList
              className="tw:w-72"
              list={known ? `attribute-values-${known.key}` : undefined}
              onChange={(next) =>
                onChange({ ...row, value: undefined, values: next })
              }
              placeholder="FINANCE, then Enter"
              values={listOf(row)}
            />
          ) : (
            <TextField
              ariaLabel="Value"
              className="tw:w-56"
              list={known ? `attribute-values-${known.key}` : undefined}
              onChange={(next) => onChange({ ...row, value: next })}
              placeholder="FINANCE"
              value={String(row.value ?? "")}
            />
          )}
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

/**
 * The link to the grammar, and what the parser makes of what was typed.
 *
 * Outside the {@link Field} rather than in its hint, for two reasons: an anchor
 * nested in a `<label>` gives a click two meanings, and the verdict has to be
 * able to appear and disappear without the field's spacing moving under the
 * author's cursor.
 *
 * The check is the engine's own, reached over the network, so what it says here
 * is what will happen on save. It is debounced because it runs on a keystroke
 * and half-typed expressions are wrong by definition -- reporting that would be
 * nagging rather than helping.
 */
function ExpressionNote({ expression }: { expression?: string }) {
  const [verdict, setVerdict] = useState<ExpressionVerdict | null>(null);

  useEffect(() => {
    const text = expression?.trim();
    if (!text) {
      setVerdict(null);
      return;
    }
    let live = true;
    const timer = setTimeout(() => {
      validateExpression(text)
        .then((next) => {
          if (live) {
            setVerdict(next);
          }
        })
        .catch(() => {
          // A validation that cannot be reached must not look like a verdict.
          // The save will still be checked by the same parser on the server.
          if (live) {
            setVerdict(null);
          }
        });
    }, 400);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [expression]);

  return (
    <div className="tw:-mt-1 tw:flex tw:flex-col tw:gap-1">
      <Link
        className="tw:text-xs tw:text-brand-secondary tw:underline"
        target="_blank"
        to="/docs/expressions"
      >
        Syntax and worked examples
      </Link>
      {verdict && !verdict.valid && (
        <span className="tw:text-xs tw:text-error-primary">
          {verdict.message}
        </span>
      )}
      {verdict?.valid && verdict.rowDependent && (
        <span className="tw:text-xs tw:text-warning-primary">
          This reads row data, which a subject rule cannot see — a subscription
          is decided before there is a row. It belongs in a data policy row
          filter.
        </span>
      )}
      {verdict?.valid &&
        !verdict.rowDependent &&
        verdict.unknownAttributes.length > 0 && (
          <span className="tw:text-xs tw:text-warning-primary">
            Nobody in the directory carries{" "}
            {verdict.unknownAttributes.map((name) => `user.${name}`).join(", ")}
            . This will save, and then grant nothing until somebody does.
          </span>
        )}
    </div>
  );
}
