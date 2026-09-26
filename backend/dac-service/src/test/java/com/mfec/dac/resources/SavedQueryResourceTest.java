package com.mfec.dac.resources;

import static com.mfec.dac.resources.StewardshipGuardsTest.ADMIN;
import static com.mfec.dac.resources.StewardshipGuardsTest.REQUESTER;
import static com.mfec.dac.resources.StewardshipGuardsTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mfec.dac.query.SavedQueryStore;
import com.mfec.dac.query.SavedQueryStore.Draft;
import com.mfec.dac.query.SavedQueryStore.Saved;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The saved query endpoints: checked drafts, a taken name, and whose query is whose. */
class SavedQueryResourceTest {

  final SavedQueryStore store = mock(SavedQueryStore.class);
  final SavedQueryResource resource = new SavedQueryResource(store);

  static Saved saved(String owner, boolean shared) {
    return new Saved(UUID.randomUUID(), owner, "Monthly", null, null, "SELECT 1", shared, Instant.now(), Instant.now());
  }

  @Test
  @DisplayName("a draft is checked before anything is stored")
  void validation() {
    for (Draft bad :
        List.of(
            new Draft(" ", null, null, "SELECT 1", false),
            new Draft("x".repeat(SavedQueryStore.MAX_NAME + 1), null, null, "SELECT 1", false),
            new Draft("Named", null, null, "  ", false),
            new Draft("Named", null, "not-a-uuid", "SELECT 1", false))) {
      assertThatThrownBy(() -> resource.create(bad, as(REQUESTER))).isInstanceOf(BadRequestException.class);
    }
    verify(store, never()).create(any(), anyString());
  }

  @Test
  @DisplayName("a taken name is a 409 that names the query holding it")
  void nameTaken() {
    UUID existing = UUID.randomUUID();
    when(store.create(any(), eq("analyst")))
        .thenThrow(new SavedQueryStore.NameTakenException("Monthly", existing));
    assertThatThrownBy(() -> resource.create(new Draft("Monthly", null, null, "SELECT 1", false), as(REQUESTER)))
        .isInstanceOfSatisfying(
            WebApplicationException.class,
            e -> {
              assertThat(e.getResponse().getStatus()).isEqualTo(409);
              assertThat(((Map<?, ?>) e.getResponse().getEntity()).get("existingId")).isEqualTo(existing);
            });
  }

  @Test
  @DisplayName("each row says whether it is the caller's; another's private query is simply not found")
  void mine() {
    when(store.list("analyst")).thenReturn(List.of(saved("analyst", false), saved("someone_else", true)));
    assertThat(resource.list(as(REQUESTER))).extracting(SavedQueryResource.Row::mine).containsExactly(true, false);

    UUID hidden = UUID.randomUUID();
    when(store.find(hidden, "admin")).thenReturn(Optional.empty());
    // Administrators too: a query is its owner's notes, not a governed object.
    assertThatThrownBy(() -> resource.one(hidden, as(ADMIN))).isInstanceOf(NotFoundException.class);
    doThrow(new SavedQueryStore.NoSuchQueryException(hidden)).when(store).delete(hidden, "admin");
    assertThatThrownBy(() -> resource.delete(hidden, as(ADMIN))).isInstanceOf(NotFoundException.class);
  }
}
