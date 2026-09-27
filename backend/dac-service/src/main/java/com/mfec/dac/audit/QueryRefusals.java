package com.mfec.dac.audit;

/**
 * Why a proxied query did not run, sorted into the handful of answers a reader
 * of the query log acts on differently (M10).
 *
 * <p>Read from the text the proxy wrote, because that text is what the log
 * holds for every row back to the first one; a code stored beside it would
 * leave every earlier row uncategorised. The phrases matched here are the
 * rewriter's own ({@code QueryRewriter}, {@code ProxyCapabilities},
 * {@code QueryService}), and {@code QueryRefusalsTest} pins one real message
 * per category so a reworded message fails a test rather than quietly falling
 * into {@link Category#OTHER}.
 */
public final class QueryRefusals {

  private QueryRefusals() {}

  /** What stopped the statement, in the order a reader is likely to care. */
  public enum Category {
    /** A policy said no: the person may ask for access. */
    POLICY_DENY,
    /** A table on a registered source that nothing governs. */
    UNGOVERNED,
    /** The policy needs a treatment the proxy cannot write for this engine. */
    CANNOT_ENFORCE,
    /** Every column of a table is hidden from this person. */
    ALL_COLUMNS_HIDDEN,
    /** Not SQL the proxy could read. */
    UNPARSEABLE,
    /** Anything other than SELECT. */
    NOT_READ_ONLY,
    /** A table named without its schema. */
    UNQUALIFIED,
    /** A shape of SELECT the proxy does not rewrite. */
    UNSUPPORTED,
    /** The source's planner priced it over the ceiling for its engine. */
    TOO_COSTLY,
    /** The source is not registered or is switched off. */
    SOURCE_UNAVAILABLE,
    /** No room for one more read; the same statement later will likely run. */
    BUSY,
    /** Enforced and sent, and the source refused it. */
    SOURCE_ERROR,
    /** Nothing was sent. */
    EMPTY,
    OTHER
  }

  /**
   * @param outcome EXECUTED, REJECTED or FAILED
   * @return null for a statement that ran
   */
  public static Category categorize(String outcome, String reason) {
    if ("EXECUTED".equals(outcome)) {
      return null;
    }
    if ("FAILED".equals(outcome)) {
      return Category.SOURCE_ERROR;
    }
    String text = reason == null ? "" : reason;
    if (text.startsWith("Access to ") && text.contains(" is denied")) {
      return Category.POLICY_DENY;
    }
    if (text.contains(" is not a governed asset on this source")) {
      return Category.UNGOVERNED;
    }
    if (text.startsWith("Policy on ") && text.contains("which the query proxy cannot express")) {
      return Category.CANNOT_ENFORCE;
    }
    if (text.startsWith("Every column of ") && text.endsWith(" is hidden from this principal")) {
      return Category.ALL_COLUMNS_HIDDEN;
    }
    if (text.startsWith("This statement could not be parsed")) {
      return Category.UNPARSEABLE;
    }
    if (text.startsWith("Only SELECT is allowed")) {
      return Category.NOT_READ_ONLY;
    }
    if (text.startsWith("Qualify ") && text.contains(" with its schema")) {
      return Category.UNQUALIFIED;
    }
    if (text.startsWith("The query proxy can only read plain tables")
        || text.startsWith("This form of SELECT is not supported")
        || text.contains(" is read from a position the proxy does not rewrite")
        || text.startsWith("The tables this statement reads could not be listed")) {
      return Category.UNSUPPORTED;
    }
    if (text.startsWith("The source's planner estimates this statement at a cost of ")) {
      return Category.TOO_COSTLY;
    }
    if (text.startsWith("No data source ") || text.endsWith(" is disabled")) {
      return Category.SOURCE_UNAVAILABLE;
    }
    if (text.startsWith("Too many ") && text.contains(" running ")) {
      return Category.BUSY;
    }
    if (text.equals("No SQL was sent")) {
      return Category.EMPTY;
    }
    return Category.OTHER;
  }
}
