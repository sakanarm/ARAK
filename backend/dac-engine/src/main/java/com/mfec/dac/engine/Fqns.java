package com.mfec.dac.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Segment-aware fully-qualified-name matching.
 *
 * <p>Every hierarchical facet in the model — tags, glossary terms, domains and
 * sub-domains, and the physical service/database/schema/table chain — is
 * addressed by a dot-separated FQN. Matching them with a raw
 * {@code LIKE 'Finance.%'} is the classic way to get this wrong: it makes the
 * domain {@code Finance Ops} a child of {@code Finance}, and it makes
 * {@code FinanceX} match too. Everything here compares whole segments.
 *
 * <p>OpenMetadata quotes a segment that itself contains a dot, as in
 * {@code prod-mssql."Sales.DB".dbo}. The splitter honours those quotes, so a
 * database literally named {@code Sales.DB} stays one segment rather than
 * silently becoming two levels of hierarchy.
 */
public final class Fqns {

  private Fqns() {}

  /** Splits an FQN into segments, treating a double-quoted run as one segment. */
  public static List<String> segments(String fqn) {
    List<String> out = new ArrayList<>();
    if (fqn == null || fqn.isEmpty()) {
      return out;
    }
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < fqn.length(); i++) {
      char c = fqn.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (c == '.' && !quoted) {
        out.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    out.add(current.toString());
    return out;
  }

  /** Number of segments; 0 for a null or empty FQN. */
  public static int depth(String fqn) {
    return (fqn == null || fqn.isEmpty()) ? 0 : segments(fqn).size();
  }

  /**
   * Exact match on the segment sequence, so the quoted and unquoted spellings
   * of the same name compare equal.
   */
  public static boolean equal(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return segments(a).equals(segments(b));
  }

  /**
   * True when {@code candidate} is {@code ancestor} or sits underneath it.
   *
   * <p>This is the {@code contains} operator: {@code domains contains 'Finance'}
   * covers {@code Finance.Risk.Credit} and every sub-domain added later, which
   * is the whole point of writing a policy against a domain rather than a list
   * of tables.
   */
  public static boolean isDescendantOrSelf(String candidate, String ancestor) {
    if (candidate == null || ancestor == null) {
      return false;
    }
    List<String> c = segments(candidate);
    List<String> a = segments(ancestor);
    if (a.size() > c.size()) {
      return false;
    }
    return c.subList(0, a.size()).equals(a);
  }

  /** The leaf segment — {@code dbo} of {@code prod-mssql.SalesDB.dbo}. */
  public static String leaf(String fqn) {
    List<String> segs = segments(fqn);
    return segs.isEmpty() ? null : segs.get(segs.size() - 1);
  }

  /** Joins segments back into an FQN, quoting any segment containing a dot. */
  public static String join(String... segs) {
    StringBuilder sb = new StringBuilder();
    for (String s : segs) {
      if (s == null || s.isEmpty()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append('.');
      }
      sb.append(s.indexOf('.') >= 0 ? '"' + s + '"' : s);
    }
    return sb.toString();
  }
}
