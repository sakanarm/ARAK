package com.mfec.dac.om.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The mapping from OpenMetadata's vocabulary to the two questions this platform
 * asks of a change: what level of the tree moved, and did it survive.
 *
 * <p>Both defaults are deliberate and both are the safe direction. An entity
 * type nobody has taught us about produces no change rather than a change at a
 * guessed level, and an event type nobody has taught us about produces no change
 * rather than a deletion.
 */
class CatalogChangeTest {

  @Test
  void mapsTheAssetLevels() {
    assertThat(CatalogChange.subjectOf("databaseService"))
        .contains(CatalogChange.Subject.SERVICE);
    assertThat(CatalogChange.subjectOf("database")).contains(CatalogChange.Subject.DATABASE);
    assertThat(CatalogChange.subjectOf("databaseSchema")).contains(CatalogChange.Subject.SCHEMA);
    assertThat(CatalogChange.subjectOf("table")).contains(CatalogChange.Subject.TABLE);
  }

  @Test
  void mapsEveryGovernanceObjectToTheSameSubject() {
    // They collapse together because the response to any of them is the same
    // whole re-read: there is no targeted refresh of a classification, and a
    // glossary term's parent moving changes what every asset under it inherits.
    for (String type : CatalogChange.GOVERNANCE_ENTITY_TYPES.split(",")) {
      assertThat(CatalogChange.subjectOf(type))
          .as(type)
          .contains(CatalogChange.Subject.GOVERNANCE);
    }
  }

  @Test
  void ignoresEntityTypesThisPlatformDoesNotCache() {
    assertThat(CatalogChange.subjectOf("dashboard")).isEmpty();
    assertThat(CatalogChange.subjectOf(null)).isEmpty();
  }

  @Test
  void treatsEveryFormOfEditAsAnUpsert() {
    assertThat(CatalogChange.kindOf("entityCreated")).contains(CatalogChange.Kind.UPSERTED);
    assertThat(CatalogChange.kindOf("entityUpdated")).contains(CatalogChange.Kind.UPSERTED);
    assertThat(CatalogChange.kindOf("entityFieldsChanged")).contains(CatalogChange.Kind.UPSERTED);
    // Restored is an upsert too: the entity is back, and the refresh re-reads
    // it rather than trying to undo the retirement.
    assertThat(CatalogChange.kindOf("entityRestored")).contains(CatalogChange.Kind.UPSERTED);
  }

  @Test
  void treatsBothFormsOfDeleteAsRemoval() {
    assertThat(CatalogChange.kindOf("entityDeleted")).contains(CatalogChange.Kind.REMOVED);
    // Soft delete included: OpenMetadata still holds the row, but the crawl
    // reads with include=non-deleted, so a soft-deleted asset that stayed
    // current in the cache would keep matching policy selectors.
    assertThat(CatalogChange.kindOf("entitySoftDeleted")).contains(CatalogChange.Kind.REMOVED);
  }

  @Test
  void ignoresEventsThatAreNotAboutTheEntity() {
    assertThat(CatalogChange.kindOf("threadCreated")).isEmpty();
    assertThat(CatalogChange.kindOf("userLogin")).isEmpty();
    assertThat(CatalogChange.kindOf("suggestionCreated")).isEmpty();
    assertThat(CatalogChange.kindOf("entityNoChange")).isEmpty();
    assertThat(CatalogChange.kindOf(null)).isEmpty();
  }

  @Test
  void keysAssetsByLevelAndName() {
    CatalogChange table = change(CatalogChange.Subject.TABLE, "table", "s.db.dbo.customer");
    CatalogChange schema = change(CatalogChange.Subject.SCHEMA, "databaseSchema", "s.db.dbo");

    assertThat(table.key()).isNotEqualTo(schema.key());
    assertThat(table.key()).isEqualTo(
        change(CatalogChange.Subject.TABLE, "table", "s.db.dbo.customer").key());
  }

  @Test
  void keysEveryGovernanceChangeTogether() {
    CatalogChange tag = change(CatalogChange.Subject.GOVERNANCE, "tag", "PII.Sensitive");
    CatalogChange domain = change(CatalogChange.Subject.GOVERNANCE, "domain", "Finance.Risk");

    // So that fifty tag edits in one poll window cost one governance re-read.
    assertThat(tag.key()).isEqualTo(domain.key());
  }

  private static CatalogChange change(CatalogChange.Subject subject, String type, String fqn) {
    return new CatalogChange(subject, CatalogChange.Kind.UPSERTED, type, UUID.randomUUID(), fqn, 1L);
  }
}
