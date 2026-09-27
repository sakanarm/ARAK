package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueryAdmissionTest {

  private static final UUID SALES = UUID.fromString("3f2b1c9e-0000-4000-8000-000000000001");
  private static final UUID HR = UUID.fromString("3f2b1c9e-0000-4000-8000-000000000002");

  @Test
  @DisplayName("one person may hold only their own share of the slots")
  void perCaller() {
    QueryAdmission admission = new QueryAdmission(true, 16, 4, 2, Duration.ZERO);
    admission.admit(SALES, "analyst_a");
    admission.admit(HR, "analyst_a");

    assertThatThrownBy(() -> admission.admit(SALES, "analyst_a"))
        .isInstanceOfSatisfying(
            QueryAdmission.BusyException.class,
            e -> {
              assertThat(e.scope()).isEqualTo(QueryAdmission.Scope.CALLER);
              assertThat(e.limit()).isEqualTo(2);
            });
    // Somebody else is not held up by it.
    admission.admit(SALES, "analyst_b");
  }

  @Test
  @DisplayName("the caller is counted without regard to case")
  void callerIgnoresCase() {
    QueryAdmission admission = new QueryAdmission(true, 16, 4, 1, Duration.ZERO);
    admission.admit(SALES, "Analyst_A");

    assertThatThrownBy(() -> admission.admit(SALES, "analyst_a"))
        .isInstanceOf(QueryAdmission.BusyException.class);
  }

  @Test
  @DisplayName("one source takes no more than its ceiling, whoever is asking")
  void perSource() {
    QueryAdmission admission = new QueryAdmission(true, 16, 2, 2, Duration.ZERO);
    admission.admit(SALES, "a");
    admission.admit(SALES, "b");

    assertThatThrownBy(() -> admission.admit(SALES, "c"))
        .isInstanceOfSatisfying(
            QueryAdmission.BusyException.class,
            e -> assertThat(e.scope()).isEqualTo(QueryAdmission.Scope.SOURCE));
    admission.admit(HR, "c");
  }

  @Test
  @DisplayName("the service as a whole stops at its own ceiling")
  void service() {
    QueryAdmission admission = new QueryAdmission(true, 2, 2, 2, Duration.ZERO);
    admission.admit(SALES, "a");
    admission.admit(HR, "b");

    assertThatThrownBy(() -> admission.admit(UUID.randomUUID(), "c"))
        .isInstanceOfSatisfying(
            QueryAdmission.BusyException.class,
            e -> {
              assertThat(e.scope()).isEqualTo(QueryAdmission.Scope.SERVICE);
              assertThat(e.limit()).isEqualTo(2);
            });
  }

  @Test
  @DisplayName("a closed slot is free again, and closing it twice frees it once")
  void releaseOnce() {
    QueryAdmission admission = new QueryAdmission(true, 16, 1, 2, Duration.ZERO);
    QueryAdmission.Permit first = admission.admit(SALES, "a");
    first.close();
    first.close();

    QueryAdmission.Permit second = admission.admit(SALES, "b");
    assertThatThrownBy(() -> admission.admit(SALES, "c"))
        .isInstanceOf(QueryAdmission.BusyException.class);
    second.close();
    assertThat(admission.stats().running()).isZero();
  }

  @Test
  @DisplayName("a statement that finds no room waits for a slot to be given back")
  void waitsForRoom() throws Exception {
    QueryAdmission admission = new QueryAdmission(true, 16, 1, 2, Duration.ofSeconds(5));
    QueryAdmission.Permit holding = admission.admit(SALES, "a");

    CompletableFuture<QueryAdmission.Permit> waiting =
        CompletableFuture.supplyAsync(() -> admission.admit(SALES, "b"));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (admission.stats().waiting() == 0 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(admission.stats().waiting()).isEqualTo(1);
    holding.close();

    waiting.get(5, TimeUnit.SECONDS).close();
    QueryAdmission.Stats stats = admission.stats();
    assertThat(stats.admitted()).isEqualTo(2);
    assertThat(stats.queued()).isEqualTo(1);
    assertThat(stats.waiting()).isZero();
    assertThat(stats.throttled()).isZero();
  }

  @Test
  @DisplayName("a wait that runs out is refused, with a retry no longer than the wait")
  void waitRunsOut() {
    QueryAdmission admission = new QueryAdmission(true, 16, 1, 2, Duration.ofMillis(150));
    admission.admit(SALES, "a");

    long started = System.nanoTime();
    assertThatThrownBy(() -> admission.admit(SALES, "b"))
        .isInstanceOfSatisfying(
            QueryAdmission.BusyException.class,
            e -> assertThat(e.retryAfterSeconds()).isEqualTo(1));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(140));

    QueryAdmission.Stats stats = admission.stats();
    assertThat(stats.throttled()).isEqualTo(1);
    assertThat(stats.queued()).isEqualTo(1);
    assertThat(stats.waiting()).isZero();
    assertThat(stats.throttledBy()).containsEntry(QueryAdmission.Scope.SOURCE, 1L);
  }

  @Test
  @DisplayName("switched off, it lets every statement through and counts nothing")
  void unlimited() {
    QueryAdmission admission = QueryAdmission.unlimited();
    for (int i = 0; i < 50; i++) {
      admission.admit(SALES, "a");
    }
    assertThat(admission.stats().running()).isZero();
    assertThat(admission.stats().enabled()).isFalse();
  }
}
