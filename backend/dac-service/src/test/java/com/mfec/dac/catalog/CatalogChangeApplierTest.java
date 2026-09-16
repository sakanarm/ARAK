package com.mfec.dac.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.om.events.CatalogChange;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two decisions the applier makes before it touches anything.
 *
 * <p>Collapsing is what makes both transports safe to run at once: the poller
 * deliberately overlaps its cursor window and the webhook can redeliver, so
 * duplicates are the normal case rather than the exception. Escaping is what
 * stops a deletion taking its neighbours with it.
 */
class CatalogChangeApplierTest {

  private static final String TABLE = "prod-mssql.SalesDB.dbo.customer";

  @Test
  void collapsesRepeatedChangesToOneEntity() {
    // OpenMetadata emits an event per field touched, so one editing session on
    // one table is several events and should be one read.
    Collection<CatalogChange> collapsed =
        CatalogChangeApplier.collapse(
            List.of(
                upserted(CatalogChange.Subject.TABLE, "table", TABLE, 1L),
                upserted(CatalogChange.Subject.TABLE, "table", TABLE, 2L),
                upserted(CatalogChange.Subject.TABLE, "table", TABLE, 3L)));

    assertThat(collapsed).singleElement()
        .satisfies(change -> assertThat(change.timestamp()).isEqualTo(3L));
  }

  @Test
  void keepsChangesToDifferentEntitiesApart() {
    Collection<CatalogChange> collapsed =
        CatalogChangeApplier.collapse(
            List.of(
                upserted(CatalogChange.Subject.TABLE, "table", TABLE, 1L),
                upserted(CatalogChange.Subject.TABLE, "table", "prod-mssql.SalesDB.dbo.order", 1L),
                upserted(CatalogChange.Subject.SCHEMA, "databaseSchema", "prod-mssql.SalesDB.dbo",
                    1L)));

    assertThat(collapsed).hasSize(3);
  }

  @Test
  void collapsesEveryGovernanceChangeIntoOne() {
    Collection<CatalogChange> collapsed =
        CatalogChangeApplier.collapse(
            List.of(
                upserted(CatalogChange.Subject.GOVERNANCE, "tag", "PII.Sensitive", 1L),
                upserted(CatalogChange.Subject.GOVERNANCE, "tag", "PII.Financial", 2L),
                upserted(CatalogChange.Subject.GOVERNANCE, "domain", "Finance.Risk", 3L)));

    assertThat(collapsed).hasSize(1);
  }

  @Test
  void takesTheLatestEventByTimeRatherThanByArrival() {
    // A webhook retry can deliver an old event after a new one. Taking the last
    // arrival would let a stale "updated" undo a "deleted", leaving an asset
    // current in the cache that no longer exists — and still matching policy
    // selectors.
    Collection<CatalogChange> collapsed =
        CatalogChangeApplier.collapse(
            List.of(
                new CatalogChange(
                    CatalogChange.Subject.TABLE, CatalogChange.Kind.REMOVED, "table",
                    UUID.randomUUID(), TABLE, 20L),
                upserted(CatalogChange.Subject.TABLE, "table", TABLE, 10L)));

    assertThat(collapsed).singleElement()
        .satisfies(change -> assertThat(change.kind()).isEqualTo(CatalogChange.Kind.REMOVED));
  }

  @Test
  void escapesTheWildcardsAnFqnCanContain() {
    // Underscores are ordinary in database names and are a single-character
    // wildcard in LIKE. Unescaped, retiring `cust_data` would also retire
    // `custXdata`, and the assets under it would stop being governed.
    assertThat(CatalogChangeApplier.escapeLike("s.db.cust_data"))
        .isEqualTo("s.db.cust\\_data");
    assertThat(CatalogChangeApplier.escapeLike("s.db.10%")).isEqualTo("s.db.10\\%");
    assertThat(CatalogChangeApplier.escapeLike("s.db.a\\b")).isEqualTo("s.db.a\\\\b");
  }

  @Test
  void leavesAnOrdinaryFqnAlone() {
    assertThat(CatalogChangeApplier.escapeLike(TABLE)).isEqualTo(TABLE);
  }

  private static CatalogChange upserted(
      CatalogChange.Subject subject, String entityType, String fqn, long timestamp) {
    return new CatalogChange(
        subject, CatalogChange.Kind.UPSERTED, entityType, UUID.randomUUID(), fqn, timestamp);
  }
}
