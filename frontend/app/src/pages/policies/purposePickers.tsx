import { Chip as Badge } from '../../components/chips';
import {
  activePurposes,
  findPurpose,
  legalBasisLabel,
  purposeName,
  usablePurpose,
  usePurposes,
  type Purpose,
} from '../../api/purposes';
import type { RequestForm } from '../../api/requestTemplates';
import { Select, TextField, type SelectOption } from './controls';

/**
 * Picking a purpose from the register (FR-21), wherever one is asked for.
 *
 * <p>Every picker stores the register's key and shows its name. What the
 * register lists and has not retired is what may be chosen; a value something
 * already names that the register lacks, or has retired, stays visible and
 * marked, because hiding it would make a saved policy or template look as if it
 * had changed when nobody touched it. It can be taken away, never added back.
 */

/** What choosing a purpose commits to, as the second line of an option. */
export function purposeHint(purpose: Purpose): string | undefined {
  const parts = [
    legalBasisLabel(purpose.legalBasis),
    purpose.sensitiveAllowed ? 'sensitive data allowed' : null,
    purpose.maxDays ? `at most ${purpose.maxDays} days` : null,
  ].filter(Boolean);
  return parts.length > 0 ? parts.join(' · ') : undefined;
}

/**
 * The register's active purposes as options, keyed by key and labelled by
 * name -- plus the current value when it is not one of them, said as what it is.
 */
export function purposeOptions(purposes: Purpose[] | undefined, current?: string | null): SelectOption[] {
  const options: SelectOption[] = activePurposes(purposes).map((purpose) => ({
    value: purpose.key,
    label: purpose.name,
    hint: purposeHint(purpose),
  }));
  const kept = current?.trim();
  if (kept && !options.some((option) => option.value.toLowerCase() === kept.toLowerCase())) {
    const found = findPurpose(purposes, kept);
    options.push({
      value: kept,
      label: found ? `${found.name} (retired)` : kept,
      hint: found ? 'Retired; choose another' : 'Not in the register',
    });
  }
  return options;
}

/**
 * One purpose, or none.
 *
 * <p>The empty string is "none", the way the Query page has always stored it,
 * so a caller keeps its own state as it was and only the options change.
 */
export function PurposeSelect({
  value,
  onChange,
  noneLabel = 'No purpose',
  ariaLabel = 'Purpose',
  className,
}: {
  value: string;
  onChange: (value: string) => void;
  /** The label of the empty choice; null offers none. */
  noneLabel?: string | null;
  ariaLabel?: string;
  className?: string;
}) {
  const { data } = usePurposes();
  const options = [
    ...(noneLabel === null ? [] : [{ value: '', label: noneLabel }]),
    ...purposeOptions(data?.purposes, value),
  ];
  return (
    <Select
      ariaLabel={ariaLabel}
      className={className}
      onChange={onChange}
      options={options}
      placeholder="Choose a purpose"
      value={value}
    />
  );
}

/**
 * Several purposes, as a list to tick.
 *
 * <p>Checkboxes rather than a multi-select: the register is short, and every
 * purpose a policy or a template names is on screen without opening anything.
 * A value already named that the register lacks or has retired is listed after
 * the register, ticked and marked; unticking it drops it for good.
 */
export function PurposeChecklist({
  value,
  onChange,
  label,
  emptyHint,
}: {
  value: string[];
  onChange: (next: string[]) => void;
  label: string;
  /** What an empty list means, where the register has nothing to offer. */
  emptyHint?: string;
}) {
  const { data, isLoading, isError } = usePurposes();
  const purposes = data?.purposes;
  const active = activePurposes(purposes);
  const chosen = new Set(value.map((v) => v.toLowerCase()));
  const listed = new Set(active.map((p) => p.key.toLowerCase()));
  const kept = value.filter((v) => !listed.has(v.toLowerCase()));

  const toggle = (key: string, on: boolean) => {
    if (on) {
      onChange([...value, key]);
    } else {
      onChange(value.filter((v) => v.toLowerCase() !== key.toLowerCase()));
    }
  };

  return (
    <fieldset aria-label={label} className="tw:flex tw:flex-col tw:gap-1.5">
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading the register…</p>}
      {isError && (
        <p className="tw:text-sm tw:text-error-primary">The register of purposes could not be loaded.</p>
      )}
      {data && active.length === 0 && kept.length === 0 && (
        <p className="tw:text-sm tw:text-tertiary">{emptyHint ?? 'The register lists no purpose yet.'}</p>
      )}
      {active.map((purpose) => (
        <label className="tw:flex tw:items-start tw:gap-2 tw:text-sm tw:text-secondary" key={purpose.key}>
          <input
            checked={chosen.has(purpose.key.toLowerCase())}
            className="tw:mt-0.5"
            onChange={(event) => toggle(purpose.key, event.target.checked)}
            type="checkbox"
          />
          <span className="tw:flex tw:flex-col">
            <span className="tw:text-primary">{purpose.name}</span>
            {purposeHint(purpose) && (
              <span className="tw:text-xs tw:text-tertiary">{purposeHint(purpose)}</span>
            )}
          </span>
        </label>
      ))}
      {kept.map((value) => {
        const found = findPurpose(purposes, value);
        return (
          <label className="tw:flex tw:items-start tw:gap-2 tw:text-sm tw:text-secondary" key={value}>
            <input
              checked
              className="tw:mt-0.5"
              onChange={() => toggle(value, false)}
              type="checkbox"
            />
            <span className="tw:flex tw:flex-wrap tw:items-center tw:gap-1.5">
              <span className="tw:text-primary">{found?.name ?? value}</span>
              <Badge color="warning" size="sm" type="pill-color">
                {found ? 'Retired' : 'Not in the register'}
              </Badge>
            </span>
          </label>
        );
      })}
    </fieldset>
  );
}

const NO_PURPOSE = '__none__';

/**
 * The purpose on a request form.
 *
 * <p>A template that lists purposes offers those, by the register's names
 * where the register has them and without any it retired, so the server is
 * not asked for what it would refuse. A template that lists none offers the
 * register. Until the register loads, or if it cannot, the form asks as it did
 * before there was one: typed where a purpose is required, not at all where
 * it is not.
 */
export function RequestPurpose({
  form,
  value,
  onChange,
  placeholder = 'What the data is for',
}: {
  form: RequestForm;
  value: string;
  onChange: (value: string) => void;
  /** For the typed field, when the register is not there. */
  placeholder?: string;
}) {
  const { data } = usePurposes();
  const register = data?.purposes;
  const listed = form.purposes.length > 0;
  if (!listed && !form.purposeRequired && !data) {
    return null;
  }
  const limit = usablePurpose(register, value)?.maxDays;

  let field;
  if (listed) {
    const offered = form.purposes.filter((p) => findPurpose(register, p)?.status !== 'RETIRED');
    field = (
      <Select
        ariaLabel="Purpose"
        className="tw:max-w-sm"
        onChange={(chosen) => onChange(chosen === NO_PURPOSE ? '' : chosen)}
        options={[
          ...(form.purposeRequired ? [] : [{ value: NO_PURPOSE, label: 'No particular purpose' }]),
          ...offered.map((p) => {
            const found = findPurpose(register, p);
            return { value: p, label: found?.name ?? p, hint: found ? purposeHint(found) : undefined };
          }),
        ]}
        placeholder="Choose a purpose"
        value={value || (form.purposeRequired ? '' : NO_PURPOSE)}
      />
    );
  } else if (data) {
    field = (
      <PurposeSelect
        className="tw:max-w-sm"
        noneLabel={form.purposeRequired ? null : 'No particular purpose'}
        onChange={onChange}
        value={value}
      />
    );
  } else {
    field = (
      <TextField
        ariaLabel="Purpose"
        className="tw:max-w-sm"
        onChange={onChange}
        placeholder={placeholder}
        value={value}
      />
    );
  }

  return (
    <div className="tw:flex tw:flex-col tw:gap-1.5">
      <span className="tw:text-sm tw:font-medium tw:text-secondary">
        Purpose {form.purposeRequired && <span className="tw:text-error-primary">*</span>}
      </span>
      {field}
      {limit && (
        <span className="tw:text-xs tw:text-tertiary">
          Access for this purpose lasts at most {limit} days.
        </span>
      )}
    </div>
  );
}

/**
 * What a request form starts on, given the purpose somebody already stated.
 *
 * <p>From a template's list, spelled as the list spells it; from the register,
 * by its key and only while it is in use. Anything else is dropped rather than
 * sent to be refused -- and without a register there is nothing to hold it
 * to, so the form starts empty and the caller decides.
 */
export function startingPurpose(
  form: RequestForm,
  register: Purpose[] | undefined,
  wanted: string | null
): string {
  if (!wanted) {
    return '';
  }
  if (form.purposes.length > 0) {
    return form.purposes.find((p) => p.toLowerCase() === wanted.trim().toLowerCase()) ?? '';
  }
  return usablePurpose(register, wanted)?.key ?? '';
}

/**
 * A request form with a purpose's longest access folded in: no duration past
 * it on offer, and no access until revoked.
 */
export function cappedBy(form: RequestForm, purpose: Purpose | undefined): RequestForm {
  const most = purpose?.maxDays;
  if (!most) {
    return form;
  }
  const maxDays = Math.min(form.maxDays ?? most, most);
  return {
    ...form,
    maxDays,
    allowUntilRevoked: false,
    durations: form.durations.filter((days) => days <= maxDays),
    defaultDays: form.defaultDays === null || form.defaultDays > maxDays ? maxDays : form.defaultDays,
  };
}

/** Days as typed, brought within what a purpose allows. */
export function fitDays(days: string, purpose: Purpose | undefined): string {
  const most = purpose?.maxDays;
  if (!most) {
    return days;
  }
  const asked = days.trim() === '' ? null : Number.parseInt(days, 10);
  return asked === null || asked > most ? String(most) : days;
}

/** What the server says when a request outlasts its purpose, in its words. */
export function purposeDaysProblem(purpose: Purpose | undefined, days: number | null): string | null {
  const most = purpose?.maxDays;
  if (!purpose || !most || (days !== null && days <= most)) {
    return null;
  }
  return `Access for ${purpose.name} lasts at most ${most} days${days === null ? '; choose a number of days' : ''}`;
}

/** A stored purpose by the register's name, where there is room for no more. */
export function PurposeName({ value }: { value: string }) {
  const { data } = usePurposes();
  return <>{purposeName(data?.purposes, value)}</>;
}

/**
 * A stored purpose as an approver should read it: the register's name, and
 * what it rests on. A value the register lacks is shown as it was stored.
 */
export function PurposeLabel({ value }: { value: string }) {
  const { data } = usePurposes();
  const purpose = findPurpose(data?.purposes, value);
  if (!purpose) {
    return <span>{value}</span>;
  }
  const basis = legalBasisLabel(purpose.legalBasis);
  return (
    <span className="tw:inline-flex tw:flex-wrap tw:items-center tw:gap-1.5">
      <span>{purpose.name}</span>
      {basis && (
        <Badge color="gray" size="sm" type="pill-color">
          {basis}
        </Badge>
      )}
      {purpose.sensitiveAllowed && (
        <Badge color="warning" size="sm" type="pill-color">
          Sensitive allowed
        </Badge>
      )}
      {purpose.status === 'RETIRED' && (
        <Badge color="gray" size="sm" type="pill-color">
          Retired
        </Badge>
      )}
    </span>
  );
}
