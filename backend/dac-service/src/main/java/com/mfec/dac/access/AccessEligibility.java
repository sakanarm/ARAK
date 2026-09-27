package com.mfec.dac.access;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Stewardship;
import com.mfec.dac.engine.Refusals;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Whether a person can read a table, and if not, whether asking its owner would
 * change that.
 *
 * <p>One answer shared by the query page's refusal and its suggestions, so the
 * two cannot disagree about whether a "Request access" button belongs next to a
 * table.
 *
 * <h2>Three answers, not two</h2>
 *
 * <ul>
 *   <li><b>readable</b> -- the decision already allows it;
 *   <li><b>requestable</b> -- it does not, but the same decision with a grant
 *       from the owner would;
 *   <li><b>neither</b> -- a DENY, or a higher layer that refuses this person,
 *       would still say no after a grant, so the owner's "yes" alone would not
 *       do. A request can still be sent -- the owner can change the policy -- and
 *       the answer says which kind of rule is in the way; which policy it is,
 *       only to somebody who could change it ({@link #toldTo}).
 * </ul>
 *
 * <h2>Before any of them: is the table connected</h2>
 *
 * <p>A table no registered source maps is one ARAK holds the description of and
 * nothing else. Nobody can query it through ARAK, so it is neither readable nor
 * requestable whatever the policies say: a grant on it would be approved,
 * configured and then open nothing. The catalog calls such a table "Not
 * connected", from the same mapping ({@link
 * com.mfec.dac.catalog.CatalogQuery#querySources}), so the badge and the
 * button cannot disagree either.
 */
public class AccessEligibility {

  /** A matched DENY keeps this person out, and approving a request cannot override it. */
  public static final String DENIED = "DENIED";

  /**
   * A layer above the grant admits only the people its policies name, and this
   * person is not one of them, so a grant cannot get them past it.
   */
  public static final String NOT_ADMITTED = "NOT_ADMITTED";

  private final DecisionService decisions;
  private final AccessRequestStore requests;

  public AccessEligibility(DecisionService decisions, AccessRequestStore requests) {
    this.decisions = decisions;
    this.requests = requests;
  }

  /**
   * @param readable a query through ARAK would be allowed now; false for a table
   *     that is not connected, because nothing can read it
   * @param requestable not readable, and a grant from the owner would make it so
   * @param blockedBy when neither: what would still refuse after a grant. Only for
   *     somebody who oversees the table; see {@link #toldTo}
   * @param approvers who would decide a request, per OpenMetadata; empty means
   *     no owner is recorded and a platform administrator decides
   * @param openRequestId the caller's request that is already waiting, if any
   * @param stranded nobody but the caller could decide a request: a stage of
   *     its first step would ask nobody else, and no other administrator exists
   * @param route the stages a request would walk; null when readable or not
   *     connected, when there is no request to make
   * @param queryable a registered, enabled source maps the table, so a query can
   *     run on it at all
   * @param blockedKind when neither on a connected table: {@link #DENIED} or
   *     {@link #NOT_ADMITTED} -- what kind of rule is in the way, which is all a
   *     person who does not oversee the table is told
   * @param blockedByPolicyId the policy {@code blockedBy} names, when it is one
   * @param blockedByPolicy that policy's title: its display name, else its name
   * @param blockedByReason what that policy says, in its own words; null for a
   *     matched DENY, whose own words only say that it matched
   */
  public record Verdict(
      String assetFqn,
      boolean readable,
      boolean requestable,
      String blockedBy,
      List<AccessRequestStore.Approver> approvers,
      String openRequestId,
      boolean stranded,
      AccessRequestStore.Route route,
      boolean queryable,
      String blockedKind,
      UUID blockedByPolicyId,
      String blockedByPolicy,
      String blockedByReason) {

    /** The same answer with the policy left out; the kind of rule is still said. */
    public Verdict withoutPolicy() {
      return new Verdict(
          assetFqn,
          readable,
          requestable,
          null,
          approvers,
          openRequestId,
          stranded,
          route,
          queryable,
          blockedKind,
          null,
          null,
          null);
    }
  }

  /**
   * One catalog row's worth of {@link Verdict}: whether the table is connected
   * and where the caller stands on it.
   *
   * <p>It names no policy and no approver. A badge needs neither, and a page of
   * fifty of them should not double as a list of which rule shuts whom out.
   */
  public record Brief(
      String assetFqn,
      boolean queryable,
      boolean readable,
      boolean requestable,
      String openRequestId,
      String blockedKind) {}

  /** Just the first half, for listing many tables at once: can this person read it now. */
  public boolean readable(String username, String assetFqn, String ip, String purpose) {
    PolicyDecision decision =
        decisions.decide(new DecisionService.Ask(username, assetFqn, null, ip, purpose, null));
    return Boolean.TRUE.equals(decision.getAllowed());
  }

  /** The full answer for one table. */
  public Verdict check(String username, String assetFqn, String ip, String purpose) {
    boolean queryable = requests.querySources(List.of(assetFqn)).containsKey(assetFqn);
    Standing standing = stand(username, assetFqn, queryable, ip, purpose);
    if (standing.readable()) {
      return new Verdict(
          assetFqn, true, false, null, List.of(), null, false, null, true, null, null, null, null);
    }
    String open =
        requests.openRequest(assetFqn, username).map(r -> r.id().toString()).orElse(null);
    if (!queryable) {
      // Nothing to approve and nobody to ask: the owner's "yes" would configure
      // a grant on a table no query can reach. A request already waiting from
      // before the source went away is still the caller's to see.
      return new Verdict(
          assetFqn, false, false, null, List.of(), open, false, null, false, null, null, null, null);
    }
    boolean requestable = standing.requestable();
    DecisionReason blame = standing.blame();
    // The route and who decides are worked out whether or not a grant alone
    // would do: a request is sent either way, and the form says where it goes.
    return new Verdict(
        assetFqn,
        false,
        requestable,
        requestable ? null : blocker(standing.ifGranted()),
        requests.approversFor(assetFqn),
        open,
        requests.nobodyElseDecides(username, assetFqn),
        requests.route(assetFqn),
        true,
        standing.kind(),
        blame == null ? null : blame.getPolicyId(),
        blame == null ? null : title(blame),
        blame == null ? null : ownWords(blame));
  }

  /**
   * {@link #check} for a page of catalog rows at once, cut down to a {@link
   * Brief} each.
   *
   * <p>The same standing as {@link #check} -- the same mapping, the same live
   * decision, the same as-if-granted one -- so a badge on the list never says
   * something the table's own page contradicts. What it saves is the rest:
   * one query for which of them are connected and one for the caller's open
   * requests, instead of one each per row, and no approvers or routes at all.
   * The live decision is the cached one, and the as-if-granted one is asked
   * only for a connected table the caller cannot read.
   *
   * @return one per distinct FQN, in the order asked
   */
  public List<Brief> briefs(
      String username, Collection<String> assetFqns, String ip, String purpose) {
    Set<String> fqns = new LinkedHashSet<>(assetFqns);
    if (fqns.isEmpty()) {
      return List.of();
    }
    Map<String, String> connected = requests.querySources(fqns);
    Map<String, String> open = requests.openRequestIds(fqns, username);
    List<Brief> out = new ArrayList<>(fqns.size());
    for (String fqn : fqns) {
      boolean queryable = connected.containsKey(fqn);
      Standing standing = stand(username, fqn, queryable, ip, purpose);
      out.add(
          new Brief(
              fqn,
              queryable,
              standing.readable(),
              queryable && !standing.readable() && standing.requestable(),
              standing.readable() ? null : open.get(fqn),
              standing.kind()));
    }
    return out;
  }

  /**
   * The verdict as this caller may read it.
   *
   * <p>The policy in the way is named to somebody who oversees the table (see
   * {@link Stewardship#oversees}) or is one of the people a request on it would
   * go to: they are the ones who can change it, and the review page names it to
   * them anyway. Anybody else is told only its kind. Its name and its reason --
   * "subject rule satisfied" -- are the policy author's words, and to the person
   * shut out they read as a diagnostic about somebody else's configuration
   * rather than an answer.
   */
  public static Verdict toldTo(AuthenticatedUser caller, Verdict verdict) {
    if (verdict.blockedBy() == null && verdict.blockedByPolicyId() == null) {
      return verdict;
    }
    return mayNameTheBlocker(caller, verdict) ? verdict : verdict.withoutPolicy();
  }

  private static boolean mayNameTheBlocker(AuthenticatedUser caller, Verdict verdict) {
    if (caller == null) {
      return false;
    }
    if (Stewardship.oversees(caller, verdict.assetFqn())) {
      return true;
    }
    return verdict.approvers() != null
        && verdict.approvers().stream()
            .anyMatch(
                a -> "user".equalsIgnoreCase(a.type()) && caller.username().equalsIgnoreCase(a.name()));
  }

  /**
   * Where one person stands on one table, before anything is said about it.
   *
   * @param ifGranted the decision with a grant in the stack; null when it was
   *     not worth asking -- readable already, or not connected
   */
  private record Standing(boolean queryable, boolean readable, PolicyDecision ifGranted) {

    boolean requestable() {
      return ifGranted != null && Boolean.TRUE.equals(ifGranted.getAllowed());
    }

    /** What still refuses after the grant; null when nothing does, or nothing was asked. */
    DecisionReason blame() {
      return ifGranted == null || requestable() ? null : blocking(ifGranted);
    }

    String kind() {
      if (!queryable || readable || ifGranted == null || requestable()) {
        return null;
      }
      DecisionReason reason = blame();
      return reason != null
              && reason.getEffect() == DecisionReason.Effect.DENY
              && Boolean.TRUE.equals(reason.getMatched())
          ? DENIED
          : NOT_ADMITTED;
    }
  }

  /**
   * The two decisions {@link #check} and {@link #briefs} both rest on. A table
   * that is not connected asks neither: nothing reads it, so there is nothing to
   * decide.
   */
  private Standing stand(
      String username, String assetFqn, boolean queryable, String ip, String purpose) {
    if (!queryable) {
      return new Standing(false, false, null);
    }
    DecisionService.Ask ask = new DecisionService.Ask(username, assetFqn, null, ip, purpose, null);
    if (Boolean.TRUE.equals(decisions.decide(ask).getAllowed())) {
      return new Standing(true, true, null);
    }
    return new Standing(true, false, decisions.decideAsIfGranted(ask));
  }

  /** The policy as its page is titled, or null for one of the engine's own lines. */
  private String title(DecisionReason reason) {
    if (Refusals.engine(reason)) {
      return null;
    }
    if (reason.getPolicyId() == null) {
      return reason.getPolicyName();
    }
    return requests.policyTitle(reason.getPolicyId()).orElse(reason.getPolicyName());
  }

  /** A policy's own reason, unless it only says that its rule matched. */
  private static String ownWords(DecisionReason reason) {
    if (reason.getEffect() == DecisionReason.Effect.DENY && Boolean.TRUE.equals(reason.getMatched())) {
      return null;
    }
    String why = reason.getExplanation();
    return why == null || why.isBlank() ? null : why;
  }

  /**
   * What still says no once the grant is in the stack.
   *
   * <p>A matched DENY first, because it wins outright; otherwise a policy in the
   * higher layer the grant could not pass; otherwise whatever the engine said
   * about composing the layers. {@link Refusals#blame} chooses, as it does for
   * the query console.
   */
  static String blocker(PolicyDecision decision) {
    DecisionReason reason = blocking(decision);
    return reason == null ? "the policy decision" : named(reason);
  }

  /** The reason {@link #blocker} names, or null when no single policy is to blame. */
  static DecisionReason blocking(PolicyDecision decision) {
    return Refusals.blame(decision);
  }

  private static String named(DecisionReason reason) {
    String name = reason.getPolicyName() == null ? "a policy" : reason.getPolicyName();
    return reason.getExplanation() == null || reason.getExplanation().isBlank()
        ? name
        : name + ": " + reason.getExplanation();
  }
}
