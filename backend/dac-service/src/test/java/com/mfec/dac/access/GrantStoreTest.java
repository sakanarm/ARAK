package com.mfec.dac.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.entity.policy.Policy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two pieces of a grant that decide access without touching a database.
 *
 * <p>{@link GrantStore#asPolicy} is where a grant stops being a row and becomes
 * something the engine evaluates. Everything FR-7 promises about composition
 * rests on the shape it produces here: a SUBSCRIPTION ALLOW at the TABLE layer
 * naming exactly one principal. Get the effect or the layer wrong and a grant
 * becomes either useless or able to overrule a global policy, and neither
 * shows up as a failure until somebody sees data they should not.
 *
 * <p>{@code liveAt} is the other half: a window the store filters on in SQL and
 * the page filters on in Java, so the two have to agree on what "live" means at
 * each of its three edges.
 */
class GrantStoreTest {

  private static final Instant NOON = Instant.parse("2026-09-23T12:00:00Z");

  @Nested
  @DisplayName("a grant rendered as a policy")
  class AsPolicy {

    @Test
    @DisplayName("allows, subscribes, and lands at the table layer")
    void shape() {
      Policy policy = GrantStore.asPolicy(grant("analyst_a", "USER", null, null));

      // ALLOW, because a grant only ever opens a door -- there is no such thing
      // as a grant that takes access away; that is what a policy is for.
      assertThat(policy.getEffect()).isEqualTo(Policy.Effect.ALLOW);
      // SUBSCRIPTION, because a grant says "you may reach this table" and says
      // nothing about what is masked inside it.
      assertThat(policy.getPolicyType()).isEqualTo(Policy.PolicyType.SUBSCRIPTION);
      // TABLE rather than ORG: composition is by intersection and the layer
      // decides nothing on its own, but a grant that entered at ORG would read
      // as an organisation-wide rule in every explanation it appears in.
      assertThat(policy.getScopeLevel()).isEqualTo(ResolvedColumnMask.ScopeLevel.TABLE);
      assertThat(policy.getScopeFqn()).isEqualTo("prod-pg.SalesDB.dbo.customer");
      assertThat(policy.getLifecycleState()).isEqualTo(Policy.LifecycleState.ACTIVE);
    }

    @Test
    @DisplayName("names the holder as a user or as a group, never as both")
    void principal() {
      Policy toPerson = GrantStore.asPolicy(grant("analyst_a", "USER", null, null));
      assertThat(toPerson.getSubject().getPrincipals()).singleElement().satisfies(match -> {
        assertThat(match.getUser()).isEqualTo("analyst_a");
        assertThat(match.getGroup()).isNull();
      });

      // The same row with principal_type GROUP has to come out the other way
      // round, because the engine resolves membership for `group` and compares
      // the username for `user`. Swapping them silently grants nobody anything.
      Policy toGroup = GrantStore.asPolicy(grant("finance-team", "GROUP", null, null));
      assertThat(toGroup.getSubject().getPrincipals()).singleElement().satisfies(match -> {
        assertThat(match.getGroup()).isEqualTo("finance-team");
        assertThat(match.getUser()).isNull();
      });
    }

    @Test
    @DisplayName("carries its window, so a simulation of next week gets the right answer")
    void window() {
      Instant from = NOON;
      Instant until = NOON.plus(30, ChronoUnit.DAYS);

      Policy policy = GrantStore.asPolicy(grant("analyst_a", "USER", from, until));

      // The store already filters the window in SQL. Carrying it on the policy
      // as well is not redundancy: `policiesFor` reads at a caller-supplied
      // instant, and FR-5.2's "what will this look like next Tuesday" is only
      // answerable if the window travels with the object.
      assertThat(policy.getValidFrom()).isEqualTo(from);
      assertThat(policy.getValidUntil()).isEqualTo(until);
    }

    @Test
    @DisplayName("can be traced back to the row it came from")
    void explainable() {
      UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
      GrantStore.StoredGrant stored =
          new GrantStore.StoredGrant(
              id,
              "prod-pg.SalesDB.dbo.customer",
              UUID.randomUUID(),
              "analyst_a",
              "Ann Analyst",
              "USER",
              "local",
              "manual",
              null,
              NOON,
              null,
              "Quarter-end reconciliation",
              "owner_o",
              NOON,
              null,
              null,
              null);

      Policy policy = GrantStore.asPolicy(stored);

      // FR-5.4: an explanation names the policy, so the name has to be enough
      // to find the grant again a year later when the reason is the only thing
      // anybody still cares about.
      assertThat(policy.getName()).isEqualTo("grant:" + id);
      assertThat(policy.getDisplayName()).isEqualTo("Direct grant to Ann Analyst");
      assertThat(policy.getDescription()).isEqualTo("Quarter-end reconciliation");
      assertThat(policy.getUpdatedBy()).isEqualTo("owner_o");
    }

    @Test
    @DisplayName("falls back to the username when there is no display name")
    void unnamed() {
      Policy policy = GrantStore.asPolicy(grant("bot_sync", "SERVICE", null, null));

      // Service accounts usually have no display name, and "Direct grant to
      // null" is what a reader sees in the audit trail if this is skipped.
      assertThat(policy.getDisplayName()).isEqualTo("Direct grant to bot_sync");
    }
  }

  @Nested
  @DisplayName("the window a grant is live in")
  class LiveAt {

    @Test
    @DisplayName("an open-ended grant is live from its start and stays live")
    void openEnded() {
      GrantStore.StoredGrant grant = grant("analyst_a", "USER", NOON, null);

      assertThat(grant.liveAt(NOON.minusSeconds(1))).isFalse();
      assertThat(grant.liveAt(NOON)).isTrue();
      assertThat(grant.liveAt(NOON.plus(3650, ChronoUnit.DAYS))).isTrue();
    }

    @Test
    @DisplayName("the end is exclusive and the start is not")
    void edges() {
      Instant until = NOON.plus(1, ChronoUnit.DAYS);
      GrantStore.StoredGrant grant = grant("analyst_a", "USER", NOON, until);

      // Half-open on purpose, and asserted because the two filters that decide
      // access -- this one and the SQL in `policiesFor` -- have to agree at the
      // instant the window closes. "Until noon tomorrow" reads as not including
      // noon tomorrow, and both sides implement that.
      assertThat(grant.liveAt(NOON)).isTrue();
      assertThat(grant.liveAt(until.minusMillis(1))).isTrue();
      assertThat(grant.liveAt(until)).isFalse();
    }

    @Test
    @DisplayName("a revoked grant is dead even inside its window")
    void revoked() {
      GrantStore.StoredGrant live = grant("analyst_a", "USER", NOON, NOON.plus(30, ChronoUnit.DAYS));
      GrantStore.StoredGrant tombstoned =
          new GrantStore.StoredGrant(
              live.id(),
              live.assetFqn(),
              live.principalId(),
              live.username(),
              live.displayName(),
              live.principalType(),
              live.principalSource(),
              live.source(),
              live.requestId(),
              live.validFrom(),
              live.validUntil(),
              live.reason(),
              live.grantedBy(),
              live.grantedAt(),
              NOON.plusSeconds(60),
              "owner_o",
              "Left the team");

      // The tombstone wins over the window, not the other way round. A revoke
      // that only took effect once the grant expired anyway would be a revoke
      // that does nothing on exactly the grants somebody urgently wanted gone.
      assertThat(live.liveAt(NOON.plusSeconds(120))).isTrue();
      assertThat(tombstoned.liveAt(NOON.plusSeconds(120))).isFalse();
    }
  }

  private static GrantStore.StoredGrant grant(
      String username, String type, Instant from, Instant until) {
    return new GrantStore.StoredGrant(
        UUID.randomUUID(),
        "prod-pg.SalesDB.dbo.customer",
        UUID.randomUUID(),
        username,
        null,
        type,
        "local",
        "manual",
        null,
        from == null ? NOON : from,
        until,
        "Because the owner said so",
        "owner_o",
        NOON,
        null,
        null,
        null);
  }
}
