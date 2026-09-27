package com.mfec.dac.enforcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.TableScope;
import com.mfec.dac.source.jdbc.CredentialResolver;
import com.mfec.dac.source.jdbc.DirectAccessReader;
import com.mfec.dac.source.jdbc.SourceProbe;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DirectAccessServiceTest {

  static final String FQN = "pg.salesdb.sales.customer";
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T08:00:00Z"), ZoneOffset.UTC);

  final SecureViewService views = mock(SecureViewService.class);
  final EnforcementStateStore states = mock(EnforcementStateStore.class);
  final List<SourceProbe.Target> asked = new ArrayList<>();
  DirectAccessReader.Report report;
  Exception failure;

  final DirectAccessService.Reader reader =
      (target, ref, schema, table) -> {
        asked.add(target);
        if (failure instanceof SQLException e) {
          throw e;
        }
        if (failure instanceof CredentialResolver.UnresolvableCredentialException e) {
          throw e;
        }
        return report;
      };

  final DirectAccessService service = new DirectAccessService(views, reader, states, CLOCK);

  static DataSourceStore.Source source(DataSourceStore.EnforcementMode mode) {
    return new DataSourceStore.Source(
        UUID.randomUUID(), "salesdb", DataSourceStore.Engine.POSTGRES, null, "db.example.test",
        5432, "salesdb", "env:SRC", mode, null, "sec", "{table}", TableScope.EVERYTHING, true,
        Instant.now(), Instant.now(), 0);
  }

  void at(DataSourceStore.EnforcementMode mode) {
    when(views.locate(FQN))
        .thenReturn(
            new SecureViewService.Located(UUID.randomUUID(), FQN, source(mode), "sales", "customer"));
    when(views.state(FQN)).thenReturn(Optional.empty());
  }

  static DirectAccessReader.Holder holder(String name, boolean self, String... via) {
    return new DirectAccessReader.Holder(name, true, List.of(via), List.of(), 0, self);
  }

  static DirectAccessReader.Report found(DirectAccessReader.Holder... holders) {
    return new DirectAccessReader.Report(true, "arak_reader", List.of(holders), 3);
  }

  EnforcementStateStore.Audit audited() {
    ArgumentCaptor<EnforcementStateStore.Audit> captor =
        ArgumentCaptor.forClass(EnforcementStateStore.Audit.class);
    verify(states).audit(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("under the proxy, anybody but the platform holding the table is a way around it")
  void proxyWithOthersIsExposed() {
    at(DataSourceStore.EnforcementMode.PROXY);
    report = found(holder("analyst", false, "GRANT"), holder("arak_reader", true, "GRANT"));

    DirectAccessService.Check check = service.check(FQN, "owner", "192.0.2.10");

    assertThat(check.verdict()).isEqualTo(DirectAccessService.Verdict.EXPOSED);
    assertThat(check.guarded()).isTrue();
    assertThat(check.bypass()).isEqualTo(1);
    assertThat(check.checkedAt()).isEqualTo(CLOCK.instant());
    assertThat(asked).containsExactly(new SourceProbe.Target("POSTGRES", "db.example.test", 5432, "salesdb"));
    EnforcementStateStore.Audit audit = audited();
    assertThat(audit.action()).isEqualTo("DIRECT_ACCESS_CHECK");
    assertThat(audit.outcome()).isEqualTo("CHECKED");
    assertThat(audit.mode()).isEqualTo("PROXY");
    assertThat(audit.detail()).startsWith("EXPOSED");
  }

  @Test
  @DisplayName("under the proxy, only the platform holding it is closed")
  void proxyAloneIsClosed() {
    at(DataSourceStore.EnforcementMode.PROXY);
    report = found(holder("arak_reader", true, "GRANT"));

    assertThat(service.check(FQN, "owner", null).verdict())
        .isEqualTo(DirectAccessService.Verdict.CLOSED);
  }

  @Test
  @DisplayName("an installed secure view guards the table whatever the source's mode says")
  void installedViewGuards() {
    at(DataSourceStore.EnforcementMode.NONE);
    EnforcementStateStore.State installed =
        new EnforcementStateStore.State(
            UUID.randomUUID(), UUID.randomUUID(), FQN, UUID.randomUUID(), "SECURE_VIEW",
            "APPLIED", null, null, null, Instant.now(), "admin", null, "sec", "customer",
            Instant.now());
    when(views.state(FQN)).thenReturn(Optional.of(installed));
    report = found(holder("analyst", false, "GRANT"));

    DirectAccessService.Check check = service.check(FQN, "owner", null);

    assertThat(check.secureViewInstalled()).isTrue();
    assertThat(check.verdict()).isEqualTo(DirectAccessService.Verdict.EXPOSED);
  }

  @Test
  @DisplayName("where people are meant to read the table directly, the list is information")
  void unguardedIsOpen() {
    at(DataSourceStore.EnforcementMode.NATIVE_CONFIG);
    report = found(holder("analyst", false, "GRANT"));

    DirectAccessService.Check check = service.check(FQN, "owner", null);

    assertThat(check.verdict()).isEqualTo(DirectAccessService.Verdict.OPEN);
    assertThat(check.bypass()).isEqualTo(1);
  }

  @Test
  @DisplayName("a table the source does not have is said to be missing, not closed")
  void missingIsNotClosed() {
    at(DataSourceStore.EnforcementMode.PROXY);
    report = new DirectAccessReader.Report(false, "arak_reader", List.of(), 1);

    assertThat(service.check(FQN, "owner", null).verdict())
        .isEqualTo(DirectAccessService.Verdict.NOT_FOUND);
  }

  @Test
  @DisplayName("a source that refuses is a failure on the trail, not a result")
  void sourceFailure() {
    at(DataSourceStore.EnforcementMode.PROXY);
    failure = new SQLException("connection refused");

    assertThatThrownBy(() -> service.check(FQN, "owner", null))
        .isInstanceOf(SecureViewService.SourceFailureException.class);
    assertThat(audited().outcome()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("a credential that cannot be resolved is a refusal on the trail")
  void unresolvableCredential() {
    at(DataSourceStore.EnforcementMode.PROXY);
    failure = new CredentialResolver.UnresolvableCredentialException("not set");

    assertThatThrownBy(() -> service.check(FQN, "owner", null))
        .isInstanceOf(SecureViewService.RefusedException.class);
    assertThat(audited().outcome()).isEqualTo("REFUSED");
  }

  @Test
  @DisplayName("an FQN the catalogue cannot place never reaches a source")
  void unknownTable() {
    when(views.locate(any())).thenThrow(new SecureViewService.NotFoundException("no"));

    assertThatThrownBy(() -> service.check("nope", "owner", null))
        .isInstanceOf(SecureViewService.NotFoundException.class);
    assertThat(asked).isEmpty();
    verify(states, never()).audit(any());
  }
}
