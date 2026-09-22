package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.entity.policy.Exemption;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import com.mfec.dac.schema.entity.policy.TimeRule;
import com.mfec.dac.schema.entity.policy.TimeWindow;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How long a decision may be held (FR-5.5).
 *
 * <p>Every one of these is a question about safety rather than about speed: a
 * window that returns too late is a decision served after it stopped being
 * true, and an exemption that returns a minute late is a minute of access
 * somebody wrote a date to end.
 */
class DecisionValidityTest {

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

  private static Instant bangkok(String dateTime) {
    return LocalDateTime.parse(dateTime).atZone(BANGKOK).toInstant();
  }

  private static Policy policy() {
    return new Policy().withName("p").withVersion(1);
  }

  private static Policy timeWindowed() {
    return policy()
        .withSubject(
            new SubjectRule()
                .withTime(
                    new TimeRule()
                        .withWindows(
                            List.of(
                                new TimeWindow()
                                    .withDays(List.of("MON-FRI"))
                                    .withFrom("08:00")
                                    .withTo("18:00")
                                    .withTimezone("Asia/Bangkok")))));
  }

  private static Optional<Instant> until(Instant at, Policy... policies) {
    return DecisionValidity.until(Arrays.asList(policies), at);
  }

  @Nested
  @DisplayName("nothing to expire")
  class NoBoundary {

    @Test
    @DisplayName("a stack with no clock in it never expires on its own")
    void noTimeTerms() {
      assertThat(until(bangkok("2026-09-15T09:30"), policy(), policy())).isEmpty();
    }

    @Test
    @DisplayName("an empty stack has nothing to expire")
    void empty() {
      assertThat(DecisionValidity.until(List.of(), Instant.now())).isEmpty();
      assertThat(DecisionValidity.until(null, Instant.now())).isEmpty();
    }

    @Test
    @DisplayName("boundaries already behind us cannot change the answer again")
    void pastBoundariesIgnored() {
      Policy expired =
          policy()
              .withValidFrom(bangkok("2026-01-01T00:00"))
              .withValidUntil(bangkok("2026-06-30T00:00"));
      assertThat(until(bangkok("2026-09-15T09:30"), expired)).isEmpty();
    }
  }

  @Nested
  @DisplayName("time-windowed policies")
  class Windows {

    @Test
    @DisplayName("a time rule ends the decision at the next minute, matching the engine")
    void nextMinute() {
      Instant at = bangkok("2026-09-15T09:30").plusSeconds(17);
      assertThat(until(at, timeWindowed()))
          .contains(bangkok("2026-09-15T09:31"));
    }

    @Test
    @DisplayName("exactly on a minute, the decision still has a whole minute to live")
    void onTheMinute() {
      Instant at = bangkok("2026-09-15T09:30");
      assertThat(until(at, timeWindowed())).contains(bangkok("2026-09-15T09:31"));
    }

    @Test
    @DisplayName("the granularity is the engine's own, not a second opinion about it")
    void matchesEngineGranularity() {
      // PolicyEngine.cacheKey truncates to the minute for a time-dependent
      // stack. If this ever disagrees, the cache would be reusing decisions
      // across a boundary the engine considers significant.
      Instant at = bangkok("2026-09-15T09:30").plusSeconds(59);
      Instant expiry = until(at, timeWindowed()).orElseThrow();
      assertThat(expiry).isEqualTo(at.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60));
      assertThat(expiry).isAfter(at);
    }

    @Test
    @DisplayName("a policy that does not apply today still ends the decision when it starts")
    void futureValidFromCounts() {
      // The whole stack is handed in, not only what matched, precisely for
      // this: tomorrow's policy is silent today and decisive at midnight.
      Policy tomorrow = policy().withValidFrom(bangkok("2026-09-16T00:00"));
      assertThat(until(bangkok("2026-09-15T09:30"), policy(), tomorrow))
          .contains(bangkok("2026-09-16T00:00"));
    }
  }

  @Nested
  @DisplayName("fixed instants are taken exactly")
  class FixedInstants {

    @Test
    @DisplayName("a grant written to end at 17:00:00 ends at 17:00:00")
    void validUntilToTheSecond() {
      Policy ends = policy().withValidUntil(bangkok("2026-09-15T17:00"));
      assertThat(until(bangkok("2026-09-15T09:30"), ends))
          .contains(bangkok("2026-09-15T17:00"));
    }

    @Test
    @DisplayName("an exemption expiry ends the decision")
    void exemptionExpiry() {
      Policy exempted =
          policy()
              .withExemptions(
                  List.of(
                      new Exemption()
                          .withPrincipal("analyst_a")
                          .withReason("fraud investigation")
                          .withExpiresAt(bangkok("2026-09-15T12:00"))));
      assertThat(until(bangkok("2026-09-15T09:30"), exempted))
          .contains(bangkok("2026-09-15T12:00"));
    }

    @Test
    @DisplayName("the earliest boundary anywhere in the stack wins")
    void earliestWins() {
      Policy late = policy().withValidUntil(bangkok("2026-12-31T00:00"));
      Policy early = policy().withValidUntil(bangkok("2026-09-15T10:00"));
      assertThat(until(bangkok("2026-09-15T09:30"), late, early, timeWindowed()))
          // The minute bucket is earlier than either fixed instant.
          .contains(bangkok("2026-09-15T09:31"));
      assertThat(until(bangkok("2026-09-15T09:30"), late, early))
          .contains(bangkok("2026-09-15T10:00"));
    }
  }
}
