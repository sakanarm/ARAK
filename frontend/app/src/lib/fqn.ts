/**
 * Segment-aware handling of fully qualified names, mirroring `Fqns` on the
 * backend.
 *
 * <p>OpenMetadata quotes a segment that itself contains a dot, as in
 * `prod-mssql."Sales.DB".dbo`. Splitting on a bare dot would turn one database
 * into two levels of hierarchy, so the quotes are honoured here too.
 */
export function segments(fqn: string): string[] {
  if (!fqn) {
    return [];
  }
  const out: string[] = [];
  let current = '';
  let quoted = false;
  for (const c of fqn) {
    if (c === '"') {
      quoted = !quoted;
    } else if (c === '.' && !quoted) {
      out.push(current);
      current = '';
    } else {
      current += c;
    }
  }
  out.push(current);
  return out;
}

/** The last segment: the name a person actually calls this thing. */
export function leaf(fqn: string): string {
  const parts = segments(fqn);
  return parts.length === 0 ? fqn : parts[parts.length - 1];
}

/**
 * True when `parent` is a strict ancestor of `child` — by whole segments, so
 * the domain `Finance Ops` is not a child of `Finance`.
 */
export function isAncestor(parent: string, child: string): boolean {
  const a = segments(parent);
  const b = segments(child);
  if (a.length === 0 || a.length >= b.length) {
    return false;
  }
  return a.every((part, index) => part === b[index]);
}

/**
 * A parent long enough that printing it costs more than it explains.
 *
 * <p>Deliberately measured on the parent alone rather than on the whole label:
 * an over-long *leaf* still has to be shown — it is the name of the thing —
 * and the row can truncate it, but an over-long *prefix* pushes that name off
 * the chip entirely.
 */
const PARENT_BUDGET = 18;

/**
 * How a facet is written on a chip.
 *
 * <p>The full FQN is unreadable at chip size once a hierarchy is more than two
 * deep: a real sub-domain here spells out both its ancestors, runs past a
 * hundred characters, wraps the row onto its own line and buries the one
 * segment that distinguishes it. But dropping the parent outright is not safe
 * either — `PII.Sensitive` and `MFEC-PDPA.Sentitive` both end in a word that
 * reads the same, and two different tags that look identical are worse than a
 * long one. So the parent is kept when it is short, elided when it is not, and
 * the whole name always stays in the tooltip.
 */
export function shortFqn(fqn: string): string {
  const parts = segments(fqn);
  if (parts.length <= 1) {
    return fqn;
  }
  const tail = parts[parts.length - 1];
  const parent = parts[parts.length - 2];
  if (parts.length === 2 && parent.length <= PARENT_BUDGET) {
    return `${parent} / ${tail}`;
  }
  if (parent.length <= PARENT_BUDGET) {
    return `… / ${parent} / ${tail}`;
  }
  return `… / ${tail}`;
}
