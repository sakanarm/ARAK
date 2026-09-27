package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueryCostGuardTest {

  @Test
  @DisplayName("each engine has its own ceiling, and one at zero is not priced")
  void ceilings() {
    QueryCostGuard guard =
        new QueryCostGuard(true, Map.of("postgres", 1_000d, "SQLSERVER", 0d));

    assertThat(guard.ceilingFor("POSTGRES")).isEqualTo(1_000d);
    assertThat(guard.ceilingFor("SQLSERVER")).isZero();
    assertThat(guard.ceilingFor("MYSQL")).isZero();
    assertThat(guard.ceilingFor(null)).isZero();
    assertThat(guard.stats().ceilings()).containsOnlyKeys("POSTGRES");
  }

  @Test
  @DisplayName("switched off, nothing is priced whatever the ceilings say")
  void off() {
    assertThat(new QueryCostGuard(false, Map.of("POSTGRES", 1_000d)).ceilingFor("POSTGRES"))
        .isZero();
    assertThat(QueryCostGuard.off().ceilingFor("POSTGRES")).isZero();
  }

  @Test
  @DisplayName("a null ceiling is ignored rather than a crash")
  void nullCeiling() {
    Map<String, Double> ceilings = new HashMap<>();
    ceilings.put("POSTGRES", null);
    assertThat(new QueryCostGuard(true, ceilings).ceilingFor("POSTGRES")).isZero();
  }

  @Test
  @DisplayName("it counts what it priced, let through and refused")
  void counts() {
    QueryCostGuard guard = new QueryCostGuard(true, Map.of("POSTGRES", 1_000d));
    guard.admitted(12.5, null);
    guard.admitted(400d, null);
    guard.refused(5_000d);

    QueryCostGuard.Stats stats = guard.stats();
    assertThat(stats.priced()).isEqualTo(3);
    assertThat(stats.refused()).isEqualTo(1);
    assertThat(stats.unpriced()).isZero();
    assertThat(stats.highestAdmitted()).isEqualTo(400d);
    assertThat(stats.lastRefusedEstimate()).isEqualTo(5_000d);
  }

  @Test
  @DisplayName("why a statement went unpriced is reported once per new reason")
  void unpricedReasonOnce() {
    QueryCostGuard guard = new QueryCostGuard(true, Map.of("SQLSERVER", 50d));

    assertThat(guard.admitted(null, "SHOWPLAN permission denied")).isTrue();
    assertThat(guard.admitted(null, "SHOWPLAN permission denied")).isFalse();
    assertThat(guard.admitted(null, "timeout")).isTrue();
    assertThat(guard.admitted(null, null)).isFalse();
    assertThat(guard.stats().unpriced()).isEqualTo(4);
    assertThat(guard.stats().priced()).isZero();
  }

  @Test
  @DisplayName("a cost prints as a whole number when big and to two places when small")
  void format() {
    assertThat(QueryCostGuard.format(48_210_555.4)).isEqualTo("48,210,555");
    assertThat(QueryCostGuard.format(0.0132)).isEqualTo("0.01");
    assertThat(QueryCostGuard.format(99.5)).isEqualTo("99.50");
  }
}
