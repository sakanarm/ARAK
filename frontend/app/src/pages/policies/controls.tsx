import { createContext, useContext, type ReactNode } from 'react';
import { Select as DesignSelect } from '@openmetadata/ui-core-components/components/base/select/select';

/**
 * The small form controls the policy builder is made of.
 *
 * Text inputs are native elements with the design tokens applied: they read
 * identically, keep a dense row dense, and stay keyboard- and
 * screen-reader-navigable without any wiring.
 *
 * Dropdowns are not. A native `<select>` hands its list to the operating
 * system, which draws it in the OS's own colours with square corners and no
 * relation to anything else on the page — the one control in the console that
 * ignores the theme. So the select here is the design system's, built on
 * react-aria: same popover, radius, hover and check mark as every other menu in
 * the product, and it can carry a line of supporting text under each option,
 * which a native `<option>` cannot.
 */

export const FIELD =
  'tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:text-primary tw:outline-none tw:focus-visible:border-brand';

/**
 * The label a Field has already drawn.
 *
 * A custom select is a button, not a labelable element, so the wrapping
 * `<label>` does not name it the way it names an input. Rather than making
 * every caller repeat the label as an `ariaLabel`, the Field passes it down and
 * the select uses it when nothing more specific was given.
 */
const FieldLabel = createContext<string | undefined>(undefined);

export function Field({
  label,
  hint,
  children,
  className,
}: {
  label: string;
  hint?: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <label className={`tw:flex tw:flex-col tw:gap-1.5 ${className ?? ''}`}>
      <span className="tw:text-sm tw:font-medium tw:text-secondary">{label}</span>
      <FieldLabel.Provider value={label}>{children}</FieldLabel.Provider>
      {hint && <span className="tw:text-xs tw:text-tertiary">{hint}</span>}
    </label>
  );
}

export interface SelectOption {
  value: string;
  label: string;
  /** A second line under the option — what choosing it will mean. */
  hint?: string;
  isDisabled?: boolean;
}

export function Select({
  value,
  onChange,
  options,
  className,
  ariaLabel,
  placeholder,
}: {
  value: string;
  onChange: (value: string) => void;
  options: SelectOption[];
  className?: string;
  ariaLabel?: string;
  placeholder?: string;
}) {
  const inherited = useContext(FieldLabel);

  return (
    <DesignSelect
      aria-label={ariaLabel ?? inherited ?? 'Select'}
      className={className}
      items={options.map((option) => ({
        id: option.value,
        label: option.label,
        supportingText: option.hint,
        isDisabled: option.isDisabled,
      }))}
      onSelectionChange={(key) => {
        // Null only arrives if the list is cleared, which this select never
        // offers; guarding keeps the caller's handler total.
        if (key !== null) {
          onChange(String(key));
        }
      }}
      placeholder={placeholder ?? 'Select'}
      selectedKey={value}
      size="sm">
      {(item) => <DesignSelect.Item {...item} />}
    </DesignSelect>
  );
}

export function TextField({
  value,
  onChange,
  placeholder,
  className,
  ariaLabel,
  list,
  type = 'text',
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  className?: string;
  ariaLabel?: string;
  list?: string;
  type?: string;
}) {
  return (
    <input
      aria-label={ariaLabel}
      className={`${FIELD} ${className ?? ''}`}
      list={list}
      onChange={(event) => onChange(event.target.value)}
      placeholder={placeholder}
      type={type}
      value={value}
    />
  );
}

/** A titled block of the form, numbered so the page reads as a sequence. */
export function Step({
  step,
  title,
  description,
  children,
  action,
}: {
  step: number;
  title: string;
  description: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
      <header className="tw:flex tw:items-start tw:justify-between tw:gap-4">
        <div className="tw:flex tw:items-start tw:gap-3">
          <span className="tw:mt-0.5 tw:flex tw:h-6 tw:w-6 tw:flex-none tw:items-center tw:justify-center tw:rounded-full tw:bg-brand-solid tw:text-xs tw:font-semibold tw:text-white">
            {step}
          </span>
          <div>
            <h2 className="tw:text-md tw:font-semibold tw:text-primary">{title}</h2>
            <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">{description}</p>
          </div>
        </div>
        {action}
      </header>
      <div className="tw:mt-5">{children}</div>
    </section>
  );
}
