package com.mfec.dac.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.llm.LlmFeatureStore.Feature;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LlmFeatureStore — which roles are offered which job")
class LlmFeatureStoreTest {

  private static AuthenticatedUser with(String... roles) {
    return new AuthenticatedUser(
        UUID.randomUUID(),
        "someone",
        "someone@example.test",
        "Someone",
        "local",
        Set.of(roles),
        List.of());
  }

  @Test
  void everyoneLetsAnyAccountIn() {
    assertThat(LlmFeatureStore.admits(List.of("EVERYONE"), with())).isTrue();
  }

  @Test
  void aRoleListLetsInOnlyThoseRoles() {
    List<String> roles = List.of("POLICY_AUTHOR", "AUDITOR");
    assertThat(LlmFeatureStore.admits(roles, with("AUDITOR"))).isTrue();
    assertThat(LlmFeatureStore.admits(roles, with("REQUESTER"))).isFalse();
    assertThat(LlmFeatureStore.admits(List.of(), with("PLATFORM_ADMIN"))).isFalse();
    assertThat(LlmFeatureStore.admits(null, with("PLATFORM_ADMIN"))).isFalse();
    assertThat(LlmFeatureStore.admits(roles, null)).isFalse();
  }

  @Test
  void rolesAreCheckedAndPutInOrder() {
    assertThat(LlmFeatureStore.normalise(Arrays.asList(" auditor", "platform_admin", "", null, "AUDITOR")))
        .containsExactly("PLATFORM_ADMIN", "AUDITOR");
    assertThat(LlmFeatureStore.normalise(List.of("AUDITOR", "everyone"))).containsExactly("EVERYONE");
    assertThat(LlmFeatureStore.normalise(List.of())).isEmpty();
  }

  @Test
  void anUnknownRoleIsRefused() {
    assertThatThrownBy(() -> LlmFeatureStore.normalise(List.of("SUPERUSER")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown role SUPERUSER");
    assertThatThrownBy(() -> LlmFeatureStore.normalise(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void featuresParseByName() {
    assertThat(LlmFeatureStore.parse(" write_sql ")).isEqualTo(Feature.WRITE_SQL);
    assertThat(LlmFeatureStore.parse("nope")).isNull();
    assertThat(LlmFeatureStore.parse(null)).isNull();
  }
}
