/**
 * The two containers every tab of the asset page is built from.
 *
 * <p>Lifted out of AssetDetailPage when that page grew tabs: the tabs live in
 * separate files now, and two of them drawing their own not-quite-matching card
 * is how a page stops looking like one page.
 */

export function Panel({
  title,
  subtitle,
  action,
  children,
}: {
  title: string;
  subtitle?: string;
  /** Rendered at the top right — a button that acts on what the panel shows. */
  action?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
      <div className="tw:flex tw:items-start tw:gap-3">
        <div className="tw:min-w-0">
          <h2 className="tw:text-sm tw:font-semibold tw:text-primary">{title}</h2>
          {subtitle && (
            <p className="tw:mt-0.5 tw:text-pretty tw:text-xs tw:text-tertiary">
              {subtitle}
            </p>
          )}
        </div>
        {action && <div className="tw:ml-auto tw:shrink-0">{action}</div>}
      </div>
      <div className="tw:mt-3">{children}</div>
    </section>
  );
}

export function Field({
  label,
  value,
}: {
  label: string;
  value: string | null;
}) {
  if (!value) {
    return null;
  }
  return (
    <div className="tw:flex tw:gap-2">
      <dt className="tw:w-28 tw:shrink-0 tw:text-xs tw:text-tertiary">{label}</dt>
      <dd className="tw:min-w-0 tw:break-all tw:text-sm tw:text-primary">{value}</dd>
    </div>
  );
}
