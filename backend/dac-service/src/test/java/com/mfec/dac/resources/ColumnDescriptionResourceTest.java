package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.catalog.ColumnDescriptionStore;
import com.mfec.dac.catalog.ColumnDescriptionStore.Entry;
import com.mfec.dac.catalog.ColumnDescriptionStore.Saved;
import com.mfec.dac.catalog.ColumnDescriptionStore.Written;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Column descriptions written in ARAK: who may write them, and what a save
 * refuses before it reaches the store.
 */
@DisplayName("ColumnDescriptionResource")
class ColumnDescriptionResourceTest {

  private static final String TABLE = "demo-pg.salesdb.sales.customer";
  private static final String EMAIL = TABLE + ".email";

  private final ColumnDescriptionStore store = mock(ColumnDescriptionStore.class);
  private final ColumnDescriptionResource resource = new ColumnDescriptionResource(store);

  private final AuthenticatedUser owner = user("owner_a", Set.of("DATA_OWNER"), List.of(TABLE));
  private final AuthenticatedUser otherOwner =
      user("owner_b", Set.of("DATA_OWNER"), List.of("demo-pg.salesdb.hr"));
  private final AuthenticatedUser analyst = user("analyst_a", Set.of("REQUESTER"), List.of());

  private final Written written =
      new Written(EMAIL, TABLE, "The customer's work email", false, "owner_a", Instant.EPOCH);

  @BeforeEach
  void wire() {
    when(store.isTable(TABLE)).thenReturn(true);
    when(store.forAsset(TABLE)).thenReturn(List.of(written));
  }

  @Test
  @DisplayName("everyone reads the descriptions; only whoever governs the table may change them")
  void lists() {
    assertThat(resource.list(TABLE, as(analyst)).canEdit()).isFalse();
    assertThat(resource.list(TABLE, as(analyst)).descriptions()).containsExactly(written);
    assertThat(resource.list(TABLE, as(owner)).canEdit()).isTrue();
    assertThat(resource.list(TABLE, as(otherOwner)).canEdit()).isFalse();
  }

  @Test
  @DisplayName("a table's owner saves, under their own name")
  void saves() {
    List<Entry> entries = List.of(new Entry(EMAIL, "The customer's work email", true));
    when(store.save(TABLE, entries, "owner_a")).thenReturn(new Saved(1, 0, 0));

    ColumnDescriptionResource.Result result =
        resource.save(new ColumnDescriptionResource.Change(TABLE, entries), as(owner));

    assertThat(result.set()).isEqualTo(1);
    assertThat(result.descriptions()).containsExactly(written);
  }

  @Test
  @DisplayName("somebody who does not govern the table is refused, and nothing is written")
  void refusesAnOutsider() {
    List<Entry> entries = List.of(new Entry(EMAIL, "Anything", false));

    assertThatThrownBy(
            () -> resource.save(new ColumnDescriptionResource.Change(TABLE, entries), as(analyst)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                resource.save(new ColumnDescriptionResource.Change(TABLE, entries), as(otherOwner)))
        .isInstanceOf(ForbiddenException.class);
    verify(store, never()).save(anyString(), anyList(), anyString());
  }

  @Test
  @DisplayName("a table that is not in the catalogue is a 404, not a write")
  void unknownTable() {
    String gone = "demo-pg.salesdb.sales.gone";
    assertThatThrownBy(
            () ->
                resource.save(
                    new ColumnDescriptionResource.Change(
                        gone, List.of(new Entry(gone + ".id", "Key", false))),
                    as(owner)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("the store's refusal, such as a column of another table, is a 400")
  void storeRefuses() {
    List<Entry> entries = List.of(new Entry("demo-pg.salesdb.hr.staff.salary", "Pay", false));
    when(store.save(eq(TABLE), any(), eq("owner_a")))
        .thenThrow(
            new ColumnDescriptionStore.Refused(
                "demo-pg.salesdb.hr.staff.salary is not a current column of " + TABLE));

    assertThatThrownBy(
            () -> resource.save(new ColumnDescriptionResource.Change(TABLE, entries), as(owner)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("not a current column");
  }

  @Test
  @DisplayName("an empty or oversized save is refused before the store")
  void validates() {
    assertThatThrownBy(() -> resource.list(" ", as(owner))).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () -> resource.save(new ColumnDescriptionResource.Change(TABLE, List.of()), as(owner)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                resource.save(
                    new ColumnDescriptionResource.Change(
                        null, List.of(new Entry(EMAIL, "x", false))),
                    as(owner)))
        .isInstanceOf(BadRequestException.class);
    List<Entry> tooMany =
        Collections.nCopies(
            ColumnDescriptionResource.MAX_ENTRIES + 1, new Entry(EMAIL, "x", false));
    assertThatThrownBy(
            () -> resource.save(new ColumnDescriptionResource.Change(TABLE, tooMany), as(owner)))
        .isInstanceOf(BadRequestException.class);
    verify(store, never()).save(anyString(), anyList(), anyString());
  }

  private static AuthenticatedUser user(String name, Set<String> roles, List<String> scopes) {
    return new AuthenticatedUser(
        UUID.randomUUID(), name, name + "@example.test", name, "local", roles, scopes);
  }

  private static SecurityContext as(Principal who) {
    return new SecurityContext() {
      @Override
      public Principal getUserPrincipal() {
        return who;
      }

      @Override
      public boolean isUserInRole(String role) {
        return false;
      }

      @Override
      public boolean isSecure() {
        return false;
      }

      @Override
      public String getAuthenticationScheme() {
        return "Bearer";
      }
    };
  }
}
