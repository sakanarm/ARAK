package com.mfec.dac.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.catalog.OmConnectionStore.InvalidConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The address an administrator is allowed to point the platform at.
 *
 * <p>This is the one field on the connection screen that can be wrong in a way
 * that looks right. Every refusal here exists because the alternative is a
 * saved connection whose only symptom is a crawl that returns nothing, hours
 * later, with a 404 in a log nobody is reading.
 */
class OmConnectionStoreTest {

  @Test
  @DisplayName("takes an ordinary console address")
  void takesAConsoleAddress() {
    assertThat(OmConnectionStore.requireUrl("https://openmetadata.example.com"))
        .isEqualTo("https://openmetadata.example.com");
    assertThat(OmConnectionStore.requireUrl("http://openmetadata.example.com:8585"))
        .isEqualTo("http://openmetadata.example.com:8585");
  }

  @Test
  @DisplayName("takes a private address — this is the platform's own catalog, not a user-named host")
  void takesAPrivateAddress() {
    assertThat(OmConnectionStore.requireUrl("http://192.0.2.10:8585"))
        .isEqualTo("http://192.0.2.10:8585");
    assertThat(OmConnectionStore.requireUrl("http://localhost:8585"))
        .isEqualTo("http://localhost:8585");
  }

  @Test
  @DisplayName("takes the trailing slash off, so links do not come out doubled")
  void trimsTrailingSlashes() {
    assertThat(OmConnectionStore.requireUrl("  https://om.example.com///  "))
        .isEqualTo("https://om.example.com");
  }

  @Test
  @DisplayName("refuses the API root, which is the likeliest thing to be pasted")
  void refusesTheApiRoot() {
    assertThatThrownBy(() -> OmConnectionStore.requireUrl("https://om.example.com/api"))
        .isInstanceOf(InvalidConnectionException.class)
        .hasMessageContaining("without /api on the end");
    // Trailing slash first, so the check has to run after the trim rather than
    // before it.
    assertThatThrownBy(() -> OmConnectionStore.requireUrl("https://om.example.com/api/"))
        .isInstanceOf(InvalidConnectionException.class)
        .hasMessageContaining("without /api on the end");
  }

  @Test
  @DisplayName("refuses a scheme that is not http or https")
  void refusesAnotherScheme() {
    assertThatThrownBy(() -> OmConnectionStore.requireUrl("ftp://om.example.com"))
        .isInstanceOf(InvalidConnectionException.class)
        .hasMessageContaining("http://");
    assertThatThrownBy(() -> OmConnectionStore.requireUrl("om.example.com"))
        .isInstanceOf(InvalidConnectionException.class);
  }

  @Test
  @DisplayName("refuses an address with no host in it")
  void refusesAHostlessAddress() {
    assertThatThrownBy(() -> OmConnectionStore.requireUrl("https://"))
        .isInstanceOf(InvalidConnectionException.class);
  }

  @Test
  @DisplayName("refuses a query or a fragment, which would be a copied browser URL")
  void refusesQueryAndFragment() {
    assertThatThrownBy(() -> OmConnectionStore.requireUrl("https://om.example.com?tab=tables"))
        .isInstanceOf(InvalidConnectionException.class)
        .hasMessageContaining("console root only");
    assertThatThrownBy(() -> OmConnectionStore.requireUrl("https://om.example.com#/explore"))
        .isInstanceOf(InvalidConnectionException.class)
        .hasMessageContaining("console root only");
  }

  @Test
  @DisplayName("refuses nothing at all, rather than storing a blank")
  void refusesBlank() {
    assertThatThrownBy(() -> OmConnectionStore.requireUrl("   "))
        .isInstanceOf(InvalidConnectionException.class)
        .hasMessageContaining("required");
    assertThatThrownBy(() -> OmConnectionStore.requireUrl(null))
        .isInstanceOf(InvalidConnectionException.class);
  }
}
