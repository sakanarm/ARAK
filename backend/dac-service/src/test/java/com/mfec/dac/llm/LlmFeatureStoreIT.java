package com.mfec.dac.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.llm.LlmFeatureStore.Access;
import com.mfec.dac.llm.LlmFeatureStore.Feature;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Who is offered which assistant job, as V29 stores it (M28). */
@Testcontainers
class LlmFeatureStoreIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static Jdbi jdbi;
  private LlmFeatureStore store;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbi.installPlugins();
  }

  @BeforeEach
  void clean() {
    jdbi.useHandle(handle -> handle.execute("TRUNCATE llm_feature_access"));
    store = new LlmFeatureStore(jdbi);
  }

  private static AuthenticatedUser with(String... roles) {
    return new AuthenticatedUser(
        UUID.randomUUID(), "someone", null, "Someone", "local", Set.of(roles), List.of());
  }

  @Test
  void nothingConfiguredOffersEveryJobToEveryone() {
    assertThat(store.all())
        .extracting(Access::feature)
        .containsExactly(Feature.values());
    assertThat(store.all()).allSatisfy(a -> {
      assertThat(a.roles()).containsExactly("EVERYONE");
      assertThat(a.configured()).isFalse();
    });
    assertThat(store.allowedFor(with("REQUESTER"))).isEqualTo(EnumSet.allOf(Feature.class));
  }

  @Test
  void aSavedListNarrowsThatJobOnly() {
    List<String> saved = store.save(Feature.INSIGHTS, List.of("auditor", "PLATFORM_ADMIN"), "admin");

    assertThat(saved).containsExactly("PLATFORM_ADMIN", "AUDITOR");
    assertThat(store.allowedFor(with("REQUESTER"))).doesNotContain(Feature.INSIGHTS).contains(Feature.CHAT);
    assertThat(store.allowedFor(with("AUDITOR"))).contains(Feature.INSIGHTS);
    Access insights =
        store.all().stream().filter(a -> a.feature() == Feature.INSIGHTS).findFirst().orElseThrow();
    assertThat(insights.configured()).isTrue();
    assertThat(insights.updatedBy()).isEqualTo("admin");
    assertThat(insights.updatedAt()).isNotNull();
  }

  @Test
  void savingAgainReplacesTheList() {
    store.save(Feature.CHAT, List.of("AUDITOR"), "admin");
    store.save(Feature.CHAT, List.of(), "admin2");

    assertThat(store.allows(Feature.CHAT, with("AUDITOR", "PLATFORM_ADMIN"))).isFalse();
    assertThat(store.all().get(0).updatedBy()).isEqualTo("admin2");

    store.save(Feature.CHAT, List.of("EVERYONE", "AUDITOR"), "admin");
    assertThat(store.allows(Feature.CHAT, with())).isTrue();
  }

  @Test
  void aRowForAFeatureThatNoLongerExistsIsIgnored() {
    jdbi.useHandle(
        handle ->
            handle.execute(
                "INSERT INTO llm_feature_access (feature, roles, updated_at, updated_by)"
                    + " VALUES ('RETIRED_JOB', ARRAY['EVERYONE'], now(), 'admin')"));

    assertThat(store.all()).hasSize(Feature.values().length);
  }
}
