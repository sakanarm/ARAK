package com.mfec.dac.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.om.crawl.GovernanceSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes one governance crawl into the cache (FR-1.2).
 *
 * <p>Unlike the asset crawl this arrives whole: an organisation has hundreds of
 * tags and domains, not hundreds of thousands, so the snapshot fits in memory
 * and the write can be one transaction. Either the whole vocabulary a policy is
 * written against updates or none of it does — half a classification tree is a
 * state in which {@code classifications contains 'PII'} silently means something
 * different from what its author intended.
 *
 * <p>Rows we created ourselves are never touched. A tag marked {@code local}
 * exists because OpenMetadata did not have one and somebody needed it (FR-1.7);
 * a sync that removed it would delete governance the platform is the source of
 * truth for.
 */
public class GovernanceStore {

  private static final Logger LOG = LoggerFactory.getLogger(GovernanceStore.class);

  private final Jdbi jdbi;
  private final ObjectMapper json;

  public GovernanceStore(Jdbi jdbi, ObjectMapper json) {
    this.jdbi = jdbi;
    this.json = json;
  }

  /**
   * Replaces the OpenMetadata-provenance governance rows with this snapshot.
   *
   * <p>An empty snapshot writes nothing. The tags and domains here are what
   * every policy selector is written against, and an OpenMetadata that answers
   * an empty list — a bot token that lost its scope, most likely — would
   * otherwise unbind every policy in the platform at once.
   */
  public void store(GovernanceSnapshot snapshot) {
    if (snapshot.classifications().isEmpty()
        && snapshot.glossaries().isEmpty()
        && snapshot.domains().isEmpty()) {
      LOG.warn("Governance snapshot has no classifications, glossaries or domains; not storing it");
      return;
    }
    jdbi.useTransaction(
        handle -> {
          classifications(handle, snapshot.classifications());
          tags(handle, snapshot.tags());
          glossaries(handle, snapshot.glossaries());
          terms(handle, snapshot.terms());
          domains(handle, snapshot.domains());
          dataProducts(handle, snapshot.dataProducts());
          customProperties(handle, snapshot.customProperties());
        });
    LOG.info(
        "Governance stored: {} classifications, {} tags, {} glossaries, {} terms, {} domains,"
            + " {} data products, {} custom property definitions",
        snapshot.classifications().size(),
        snapshot.tags().size(),
        snapshot.glossaries().size(),
        snapshot.terms().size(),
        snapshot.domains().size(),
        snapshot.dataProducts().size(),
        snapshot.customProperties().size());
  }

  private void classifications(Handle handle, List<GovernanceSnapshot.ClassificationRow> rows) {
    upsert(
        handle,
        "classification",
        rows,
        """
        INSERT INTO classification (om_id, fqn, name, description, mutually_exclusive,
                                    provider, disabled, provenance, updated_at)
        VALUES (CAST(:omId AS uuid), :fqn, :name, CAST(:description AS text), :mutuallyExclusive,
                :provider, :disabled, 'openmetadata', now())
        ON CONFLICT (fqn) DO UPDATE SET
            om_id = EXCLUDED.om_id, name = EXCLUDED.name, description = EXCLUDED.description,
            mutually_exclusive = EXCLUDED.mutually_exclusive, provider = EXCLUDED.provider,
            disabled = EXCLUDED.disabled, updated_at = now()
        WHERE classification.provenance = 'openmetadata'
        """,
        (batch, row) ->
            batch
                .bind("omId", row.omId())
                .bind("fqn", row.fqn())
                .bind("name", row.name())
                .bind("description", row.description())
                .bind("mutuallyExclusive", row.mutuallyExclusive())
                .bind("provider", row.provider())
                .bind("disabled", row.disabled()),
        GovernanceSnapshot.ClassificationRow::fqn);
  }

  private void tags(Handle handle, List<GovernanceSnapshot.TagRow> rows) {
    upsert(
        handle,
        "tag",
        rows,
        """
        INSERT INTO tag (om_id, classification_fqn, fqn, parent_fqn, name, description,
                         disabled, provenance, updated_at)
        VALUES (CAST(:omId AS uuid), :classificationFqn, :fqn, CAST(:parentFqn AS text), :name,
                CAST(:description AS text), :disabled, 'openmetadata', now())
        ON CONFLICT (fqn) DO UPDATE SET
            om_id = EXCLUDED.om_id, classification_fqn = EXCLUDED.classification_fqn,
            parent_fqn = EXCLUDED.parent_fqn, name = EXCLUDED.name,
            description = EXCLUDED.description, disabled = EXCLUDED.disabled, updated_at = now()
        WHERE tag.provenance = 'openmetadata'
        """,
        (batch, row) ->
            batch
                .bind("omId", row.omId())
                .bind("classificationFqn", row.classificationFqn())
                .bind("fqn", row.fqn())
                .bind("parentFqn", row.parentFqn())
                .bind("name", row.name())
                .bind("description", row.description())
                .bind("disabled", row.disabled()),
        GovernanceSnapshot.TagRow::fqn);
  }

  private void glossaries(Handle handle, List<GovernanceSnapshot.GlossaryRow> rows) {
    upsert(
        handle,
        "glossary",
        rows,
        """
        INSERT INTO glossary (om_id, fqn, name, description, provenance, updated_at)
        VALUES (CAST(:omId AS uuid), :fqn, :name, CAST(:description AS text),
                'openmetadata', now())
        ON CONFLICT (fqn) DO UPDATE SET
            om_id = EXCLUDED.om_id, name = EXCLUDED.name, description = EXCLUDED.description,
            updated_at = now()
        WHERE glossary.provenance = 'openmetadata'
        """,
        (batch, row) ->
            batch
                .bind("omId", row.omId())
                .bind("fqn", row.fqn())
                .bind("name", row.name())
                .bind("description", row.description()),
        GovernanceSnapshot.GlossaryRow::fqn);
  }

  private void terms(Handle handle, List<GovernanceSnapshot.GlossaryTermRow> rows) {
    upsert(
        handle,
        "glossary_term",
        rows,
        """
        INSERT INTO glossary_term (om_id, glossary_fqn, fqn, parent_fqn, name, description,
                                   synonyms, related_terms, provenance, updated_at)
        VALUES (CAST(:omId AS uuid), :glossaryFqn, :fqn, CAST(:parentFqn AS text), :name,
                CAST(:description AS text), CAST(:synonyms AS jsonb),
                CAST(:relatedTerms AS jsonb), 'openmetadata', now())
        ON CONFLICT (fqn) DO UPDATE SET
            om_id = EXCLUDED.om_id, glossary_fqn = EXCLUDED.glossary_fqn,
            parent_fqn = EXCLUDED.parent_fqn, name = EXCLUDED.name,
            description = EXCLUDED.description, synonyms = EXCLUDED.synonyms,
            related_terms = EXCLUDED.related_terms, updated_at = now()
        WHERE glossary_term.provenance = 'openmetadata'
        """,
        (batch, row) ->
            batch
                .bind("omId", row.omId())
                .bind("glossaryFqn", row.glossaryFqn())
                .bind("fqn", row.fqn())
                .bind("parentFqn", row.parentFqn())
                .bind("name", row.name())
                .bind("description", row.description())
                .bind("synonyms", jsonOf(row.synonyms()))
                .bind("relatedTerms", jsonOf(row.relatedTerms())),
        GovernanceSnapshot.GlossaryTermRow::fqn);
  }

  private void domains(Handle handle, List<GovernanceSnapshot.DomainRow> rows) {
    upsert(
        handle,
        "domain",
        rows,
        """
        INSERT INTO domain (om_id, fqn, parent_fqn, depth, name, description, domain_type,
                            provenance, updated_at)
        VALUES (CAST(:omId AS uuid), :fqn, CAST(:parentFqn AS text), :depth, :name,
                CAST(:description AS text), CAST(:domainType AS text), 'openmetadata', now())
        ON CONFLICT (fqn) DO UPDATE SET
            om_id = EXCLUDED.om_id, parent_fqn = EXCLUDED.parent_fqn, depth = EXCLUDED.depth,
            name = EXCLUDED.name, description = EXCLUDED.description,
            domain_type = EXCLUDED.domain_type, updated_at = now()
        WHERE domain.provenance = 'openmetadata'
        """,
        (batch, row) ->
            batch
                .bind("omId", row.omId())
                .bind("fqn", row.fqn())
                .bind("parentFqn", row.parentFqn())
                .bind("depth", row.depth())
                .bind("name", row.name())
                .bind("description", row.description())
                .bind("domainType", row.domainType()),
        GovernanceSnapshot.DomainRow::fqn);
  }

  private void dataProducts(Handle handle, List<GovernanceSnapshot.DataProductRow> rows) {
    upsert(
        handle,
        "data_product",
        rows,
        """
        INSERT INTO data_product (om_id, fqn, name, description, domain_fqn,
                                  provenance, updated_at)
        VALUES (CAST(:omId AS uuid), :fqn, :name, CAST(:description AS text),
                CAST(:domainFqn AS text), 'openmetadata', now())
        ON CONFLICT (fqn) DO UPDATE SET
            om_id = EXCLUDED.om_id, name = EXCLUDED.name, description = EXCLUDED.description,
            domain_fqn = EXCLUDED.domain_fqn, updated_at = now()
        WHERE data_product.provenance = 'openmetadata'
        """,
        (batch, row) ->
            batch
                .bind("omId", row.omId())
                .bind("fqn", row.fqn())
                .bind("name", row.name())
                .bind("description", row.description())
                .bind("domainFqn", row.domainFqn()),
        GovernanceSnapshot.DataProductRow::fqn);
  }

  /**
   * Custom property definitions, keyed by entity type and name.
   *
   * <p>No provenance column and no local variant: these describe what
   * OpenMetadata's own type system says a property is, and inventing one here
   * would mean the builder offering a dropdown for values OpenMetadata will
   * refuse to store.
   */
  private void customProperties(Handle handle, List<GovernanceSnapshot.CustomPropertyRow> rows) {
    if (rows.isEmpty()) {
      return;
    }
    PreparedBatch batch =
        handle.prepareBatch(
            """
            INSERT INTO custom_property_def (entity_type, name, display_name, description,
                                             data_type, enum_values, multi_select, updated_at)
            VALUES (:entityType, :name, CAST(:displayName AS text), CAST(:description AS text),
                    :dataType, CAST(:enumValues AS jsonb), :multiSelect, now())
            ON CONFLICT (entity_type, name) DO UPDATE SET
                display_name = EXCLUDED.display_name, description = EXCLUDED.description,
                data_type = EXCLUDED.data_type, enum_values = EXCLUDED.enum_values,
                multi_select = EXCLUDED.multi_select, updated_at = now()
            """);
    List<String> keys = new ArrayList<>();
    for (GovernanceSnapshot.CustomPropertyRow row : rows) {
      batch
          .bind("entityType", row.entityType())
          .bind("name", row.name())
          .bind("displayName", row.displayName())
          .bind("description", row.description())
          .bind("dataType", row.dataType())
          .bind("enumValues", jsonOf(row.enumValues()))
          .bind("multiSelect", row.multiSelect())
          .add();
      keys.add(row.entityType() + '.' + row.name());
    }
    batch.execute();

    handle
        .createUpdate(
            "DELETE FROM custom_property_def WHERE entity_type || '.' || name NOT IN (<keys>)")
        .bindList("keys", keys)
        .execute();
  }

  /**
   * Upserts a snapshot of one table, then removes the rows it did not contain.
   *
   * <p>The delete is confined to {@code provenance = 'openmetadata'}: everything
   * in that set came from the snapshot's own source, so anything the snapshot
   * omits has genuinely gone from OpenMetadata. A locally defined tag was never
   * in the snapshot to begin with and must not be read as deleted.
   */
  private <T> void upsert(
      Handle handle,
      String table,
      List<T> rows,
      String sql,
      BiConsumer<PreparedBatch, T> bind,
      Function<T, String> key) {

    if (!rows.isEmpty()) {
      PreparedBatch batch = handle.prepareBatch(sql);
      for (T row : rows) {
        bind.accept(batch, row);
        batch.add();
      }
      batch.execute();
    }

    if (rows.isEmpty()) {
      handle
          .createUpdate("DELETE FROM " + table + " WHERE provenance = 'openmetadata'")
          .execute();
      return;
    }
    handle
        .createUpdate(
            "DELETE FROM " + table + " WHERE provenance = 'openmetadata' AND fqn NOT IN (<fqns>)")
        .bindList("fqns", rows.stream().map(key).toList())
        .execute();
  }

  private String jsonOf(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "[]";
    }
    try {
      return json.writeValueAsString(values);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialise " + values, e);
    }
  }
}
