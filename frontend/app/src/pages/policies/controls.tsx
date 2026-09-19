import type { ReactNode } from 'react';

/**
 * The small form controls the policy builder is made of.
 *
 * A rule builder is dozens of narrow dropdowns on one line, and the design
 * system's Select is built for a labelled field in a column layout. Native
 * elements with the same tokens read identically, keep the row dense, and stay
 * keyboard- and screen-reader-navigable without any wiring — the same choice
 * the catalog filters already made.
 */

export const FIELD =
  'tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:text-primary tw:outline-none tw:focus-visible:border-brand';

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
      {children}
      {hint && <span className="tw:text-xs tw:text-tertiary">{hint}</span>}
    </label>
  );
}

export function Select({
  value,
  onChange,
  options,
  className,
  ariaLabel,
}: {
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
  className?: string;
  ariaLabel?: string;
}) {
  return (
    <select
      aria-label={ariaLabel}
      className={`${FIELD} ${className ?? ''}`}
      onChange={(event) => onChange(event.target.value)}
      value={value}>
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
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
