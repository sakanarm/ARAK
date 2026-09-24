package com.mfec.dac.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.compiler.sql.RowEntitlementMaintainer;
import com.mfec.dac.source.jdbc.SecureViewApplier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReviewedPlansTest {

  private static final SecureViewApplier.DryRun DRY =
      new SecureViewApplier.DryRun(
          RowEntitlementMaintainer.Rows.NONE,
          new RowEntitlementMaintainer.Maintenance(
              RowEntitlementMaintainer.Rows.NONE,
              RowEntitlementMaintainer.Rows.NONE,
              RowEntitlementMaintainer.Rows.NONE,
              List.of()),
          "CREATE VIEW x",
          "DROP VIEW x",
          List.of(),
          List.of(),
          "signature");

  /** A clock the test moves by hand. */
  private static final class Hand extends Clock {
    private Instant now = Instant.parse("2026-09-24T03:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  @DisplayName("a review is returned once, with what was shown")
  void singleUse() {
    ReviewedPlans plans = new ReviewedPlans();
    ReviewedPlans.Reviewed held = plans.hold("a.b.c.d", "admin", DRY, "CREATE VIEW x");

    assertThat(plans.take(held.id(), "a.b.c.d")).contains(held);
    assertThat(plans.take(held.id(), "a.b.c.d")).isEmpty();
  }

  @Test
  @DisplayName("a review for another asset is refused and left for its owner")
  void otherAssetLeavesItInPlace() {
    ReviewedPlans plans = new ReviewedPlans();
    ReviewedPlans.Reviewed held = plans.hold("a.b.c.d", "admin", DRY, "CREATE VIEW x");

    assertThat(plans.take(held.id(), "a.b.c.other")).isEmpty();
    assertThat(plans.take(held.id(), "a.b.c.d")).isPresent();
  }

  @Test
  @DisplayName("a review expires after its time to live")
  void expires() {
    Hand clock = new Hand();
    ReviewedPlans plans = new ReviewedPlans(clock, Duration.ofMinutes(30));
    ReviewedPlans.Reviewed held = plans.hold("a.b.c.d", "admin", DRY, "CREATE VIEW x");

    clock.advance(Duration.ofMinutes(30));

    assertThat(plans.take(held.id(), "a.b.c.d")).isEmpty();
    assertThat(plans.size()).isZero();
  }

  @Test
  @DisplayName("a review taken just before expiry still works")
  void stillValidJustBefore() {
    Hand clock = new Hand();
    ReviewedPlans plans = new ReviewedPlans(clock, Duration.ofMinutes(30));
    ReviewedPlans.Reviewed held = plans.hold("a.b.c.d", "admin", DRY, "CREATE VIEW x");

    clock.advance(Duration.ofMinutes(29));

    assertThat(plans.take(held.id(), "a.b.c.d")).isPresent();
  }

  @Test
  @DisplayName("an unknown or missing id finds nothing")
  void unknownId() {
    ReviewedPlans plans = new ReviewedPlans();
    plans.hold("a.b.c.d", "admin", DRY, "CREATE VIEW x");

    assertThat(plans.take(UUID.randomUUID(), "a.b.c.d")).isEmpty();
    assertThat(plans.take(null, "a.b.c.d")).isEmpty();
    assertThat(plans.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("beyond capacity the oldest review is forgotten first")
  void boundedByCapacity() {
    ReviewedPlans plans = new ReviewedPlans();
    ReviewedPlans.Reviewed first = plans.hold("t0", "admin", DRY, "x");
    ReviewedPlans.Reviewed second = plans.hold("t1", "admin", DRY, "x");
    for (int i = 2; i <= ReviewedPlans.CAPACITY; i++) {
      plans.hold("t" + i, "admin", DRY, "x");
    }

    assertThat(plans.size()).isEqualTo(ReviewedPlans.CAPACITY);
    assertThat(plans.take(first.id(), "t0")).isEmpty();
    assertThat(plans.take(second.id(), "t1")).isPresent();
  }
}
