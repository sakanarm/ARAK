package com.mfec.dac.purpose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.purpose.PurposeStore.Details;
import com.mfec.dac.purpose.SensitiveData.Change;
import com.mfec.dac.purpose.SensitiveData.Concern;
import com.mfec.dac.purpose.SensitiveData.Coverage;
import com.mfec.dac.purpose.SensitiveData.Kind;
import com.mfec.dac.purpose.SensitiveData.Label;
import com.mfec.dac.purpose.SensitiveData.Mode;
import com.mfec.dac.purpose.SensitiveData.Rule;
import com.mfec.dac.purpose.SensitiveData.Settings;
import io.dropwizard.jackson.Jackson;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What counts as sensitive data against a real PostgreSQL (FR-21, M31b): the
 * one row the rule lives in, the history kept of it, how far a rule reaches
 * across the catalog, and what it says of a purpose named on a table.
 */
@Testcontainers
class SensitiveDataIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String CUSTOMER = "demo-pg.salesdb.sales.customer";
  private static final String ORDERS = "demo-pg.salesdb.sales.orders";
  private static final String PAYROLL = "demo-pg.hrdb.hr.payroll";
  private static final String NOTES = "demo-pg.salesdb.sales.notes";

  private static final Label FINANCE = new Label(Kind.CLASSIFICATION, "Finance");

  private static Jdbi jdbi;
  private SensitiveData sensitive;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbi.installPlugins();
    jdbi.useHandle(SensitiveDataIT::catalog);
  }

  /**
   * customer: email confirmed PII.Sensitive; orders: a suggested PII tag only;
   * payroll: the table itself labelled Finance.Salary; notes: nothing.
   */
  private static void catalog(Handle h) {
    UUID customer = asset(h, CUSTOMER);
    column(h, customer, CUSTOMER + ".email", "tags", "PII", "Confirmed");
    column(h, customer, CUSTOMER + ".email", "tags", "PII.Sensitive", "Confirmed");
    column(h, customer, CUSTOMER + ".email", "classifications", "PII", "Confirmed");
    column(h, customer, CUSTOMER + ".country", "tags", "PII", "Confirmed");
    column(h, customer, CUSTOMER + ".country", "tags", "PII.NonSensitive", "Confirmed");
    UUID orders = asset(h, ORDERS);
    column(h, orders, ORDERS + ".phone", "tags", "PII.Sensitive", "Suggested");
    UUID payroll = asset(h, PAYROLL);
    h.createUpdate(
            "INSERT INTO asset_facet (asset_id, target_fqn, facet_type, facet_fqn, om_state)"
                + " VALUES (:a, :t, 'tags', :f, 'Confirmed')")
        .bind("a", payroll)
        .bind("t", PAYROLL)
        .bind("f", "Finance.Salary")
        .execute();
    asset(h, NOTES);
  }

  @BeforeEach
  void reset() {
    jdbi.useHandle(
        h -> {
          h.execute(
              "UPDATE sensitive_data_rule SET built_in = true, include = '[]', exclude = '[]',"
                  + " mode = 'WARN', updated_by = 'system'");
          h.execute("DELETE FROM audit_sensitive_data_rule WHERE actor <> 'system'");
          h.execute("DELETE FROM purpose WHERE created_by <> 'system'");
        });
    sensitive = new SensitiveData(jdbi, Jackson.newObjectMapper());
  }

  @Test
  @DisplayName("the migration starts with the built-in rule, warning, and one kept row saying so")
  void seeded() {
    Rule rule = sensitive.current();
    assertThat(rule.settings()).isEqualTo(Rule.BUILT_IN.settings());
    assertThat(rule.updatedBy()).isEqualTo("system");

    List<Change> history = sensitive.history();
    assertThat(history).hasSize(1);
    assertThat(history.get(0).actor()).isEqualTo("system");
    assertThat(history.get(0).before()).isNull();
    assertThat(history.get(0).after()).isEqualTo(Rule.BUILT_IN.settings());
  }

  @Test
  @DisplayName("a change is stored, seen at once, and kept with what it was, who made it and why")
  void updates() {
    Settings wanted =
        new Settings(false, List.of(FINANCE), List.of(new Label(Kind.TAG, "Finance.Public")), Mode.ENFORCE);

    Rule after = sensitive.update(wanted, "Audit finding 12", "author_a");

    assertThat(after.settings()).isEqualTo(wanted);
    assertThat(after.updatedBy()).isEqualTo("author_a");
    assertThat(after.updatedAt()).isNotNull();
    assertThat(sensitive.current().settings()).isEqualTo(wanted);
    // A second reader, with nothing held, reads the same row.
    assertThat(new SensitiveData(jdbi, Jackson.newObjectMapper()).current().settings()).isEqualTo(wanted);

    Change latest = sensitive.history().get(0);
    assertThat(latest.actor()).isEqualTo("author_a");
    assertThat(latest.reason()).isEqualTo("Audit finding 12");
    assertThat(latest.before()).isEqualTo(Rule.BUILT_IN.settings());
    assertThat(latest.after()).isEqualTo(wanted);
  }

  @Test
  @DisplayName("labels are trimmed and de-duplicated; one in both lists, one with no kind, or no change is refused")
  void refuses() {
    Rule after =
        sensitive.update(
            new Settings(
                true,
                List.of(new Label(Kind.CLASSIFICATION, " Finance "), new Label(Kind.CLASSIFICATION, "finance")),
                List.of(),
                Mode.WARN),
            "Finance counts",
            "author_a");
    assertThat(after.include()).containsExactly(FINANCE);

    assertThatThrownBy(
            () ->
                sensitive.update(
                    new Settings(true, List.of(FINANCE), List.of(new Label(Kind.CLASSIFICATION, "FINANCE")), Mode.WARN),
                    "both",
                    "author_a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("in both lists");
    assertThatThrownBy(
            () ->
                sensitive.update(
                    new Settings(true, List.of(new Label(null, "PII")), List.of(), Mode.WARN), "r", "author_a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs a kind");
    assertThatThrownBy(
            () -> sensitive.update(new Settings(true, List.of(FINANCE), List.of(), Mode.WARN), "again", "author_a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nothing to change");
    assertThat(sensitive.history()).hasSize(2);
  }

  @Test
  @DisplayName("a table is sensitive by its own labels or its columns', confirmed ones only")
  void labelsOn() {
    jdbi.useHandle(
        h -> {
          assertThat(SensitiveData.labelsOn(h, CUSTOMER, Rule.BUILT_IN)).containsExactly("PII.Sensitive");
          assertThat(SensitiveData.labelsOn(h, ORDERS, Rule.BUILT_IN)).isEmpty();
          assertThat(SensitiveData.labelsOn(h, NOTES, Rule.BUILT_IN)).isEmpty();
          assertThat(SensitiveData.labelsOn(h, PAYROLL, Rule.BUILT_IN)).isEmpty();
          Rule finance = new Rule(true, List.of(FINANCE), List.of(), Mode.WARN, null, null);
          assertThat(SensitiveData.labelsOn(h, PAYROLL, finance)).containsExactly("Finance.Salary");
        });
  }

  @Test
  @DisplayName("coverage counts the tables and columns a draft would reach, and the labels doing it")
  void coverage() {
    Coverage builtIn = sensitive.coverage(Rule.BUILT_IN);
    assertThat(builtIn.catalogTables()).isEqualTo(4);
    assertThat(builtIn.tables()).isEqualTo(1);
    assertThat(builtIn.columns()).isEqualTo(1);
    assertThat(builtIn.examples()).containsExactly(new SensitiveData.Covered(CUSTOMER, 1));
    assertThat(builtIn.labels()).containsExactly(new SensitiveData.LabelUse("PII.Sensitive", 1, 1));

    Coverage wider =
        sensitive.coverage(new Rule(true, List.of(FINANCE, new Label(Kind.CLASSIFICATION, "PII")), List.of(), Mode.WARN, null, null));
    assertThat(wider.tables()).isEqualTo(2);
    assertThat(wider.columns()).isEqualTo(2);
    assertThat(wider.examples())
        .containsExactly(new SensitiveData.Covered(CUSTOMER, 2), new SensitiveData.Covered(PAYROLL, 0));
  }

  @Test
  @DisplayName("a purpose that allows sensitive data passes; one that does not, or none, is a concern naming it")
  void judges() {
    PurposeStore purposes = new PurposeStore(jdbi);
    purposes.create("fraud-review", new Details("Fraud review", null, null, true, null, null), "author_a");

    jdbi.useHandle(
        h -> {
          assertThat(sensitive.judge(h, NOTES, "reporting")).isEqualTo(new SensitiveData.Judgement(false, null));
          assertThat(sensitive.judge(h, CUSTOMER, "fraud-review"))
              .isEqualTo(new SensitiveData.Judgement(true, null));

          Concern reporting = sensitive.judge(h, CUSTOMER, " Reporting ").concern();
          assertThat(reporting.mode()).isEqualTo(Mode.WARN);
          assertThat(reporting.refuses()).isFalse();
          assertThat(reporting.purpose()).isEqualTo("reporting");
          assertThat(reporting.purposeName()).isEqualTo("Reporting");
          assertThat(reporting.labels()).containsExactly("PII.Sensitive");
          assertThat(reporting.message()).startsWith(CUSTOMER + " holds sensitive data (PII.Sensitive), and Reporting");

          // A word the register lacks allows nothing sensitive, because nobody said it does.
          Concern unlisted = sensitive.judge(h, CUSTOMER, "month-end").concern();
          assertThat(unlisted.purpose()).isEqualTo("month-end");
          assertThat(unlisted.purposeName()).isEqualTo("month-end");

          Concern none = sensitive.judge(h, CUSTOMER, "  ").concern();
          assertThat(none.purpose()).isNull();
          assertThat(none.message()).contains("no purpose was named");
        });
    assertThat(sensitive.concern(CUSTOMER, "fraud-review")).isEmpty();
    assertThat(sensitive.concern(CUSTOMER, "reporting")).isPresent();
  }

  @Test
  @DisplayName("enforce refuses, and off judges nothing at all")
  void modes() {
    sensitive.update(new Settings(true, List.of(), List.of(), Mode.ENFORCE), "Enforce", "author_a");
    assertThat(sensitive.concern(CUSTOMER, "reporting").orElseThrow().refuses()).isTrue();

    sensitive.update(new Settings(true, List.of(), List.of(), Mode.OFF), "Pause", "author_a");
    assertThat(sensitive.concern(CUSTOMER, "reporting")).isEmpty();
    jdbi.useHandle(h -> assertThat(sensitive.judge(h, CUSTOMER, "reporting")).isNull());
  }

  private static UUID asset(Handle h, String fqn) {
    return h.createQuery("INSERT INTO asset (fqn, name, asset_type) VALUES (:f, :n, 'TABLE') RETURNING id")
        .bind("f", fqn)
        .bind("n", fqn.substring(fqn.lastIndexOf('.') + 1))
        .mapTo(UUID.class)
        .one();
  }

  private static void column(Handle h, UUID asset, String fqn, String type, String facet, String state) {
    UUID column =
        h.createQuery(
                """
                INSERT INTO asset_column (asset_id, fqn, name) VALUES (:a, :f, :n)
                ON CONFLICT DO NOTHING RETURNING id
                """)
            .bind("a", asset)
            .bind("f", fqn)
            .bind("n", fqn.substring(fqn.lastIndexOf('.') + 1))
            .mapTo(UUID.class)
            .findOne()
            .orElseGet(
                () ->
                    h.createQuery("SELECT id FROM asset_column WHERE fqn = :f")
                        .bind("f", fqn)
                        .mapTo(UUID.class)
                        .first());
    h.createUpdate(
            "INSERT INTO asset_facet (column_id, target_fqn, facet_type, facet_fqn, om_state)"
                + " VALUES (:c, :t, :ty, :f, :s)")
        .bind("c", column)
        .bind("t", fqn)
        .bind("ty", type)
        .bind("f", facet)
        .bind("s", state)
        .execute();
  }
}
