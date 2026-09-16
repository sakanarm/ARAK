package com.mfec.dac.om.crawl;

import com.mfec.dac.om.OmPager;
import com.mfec.dac.om.OpenMetadataClient;
import com.mfec.dac.om.client.ApiException;
import com.mfec.dac.om.client.model.ClassificationList;
import com.mfec.dac.om.client.model.CustomProperty;
import com.mfec.dac.om.client.model.DataProductList;
import com.mfec.dac.om.client.model.DomainList;
import com.mfec.dac.om.client.model.GlossaryList;
import com.mfec.dac.om.client.model.GlossaryTermList;
import com.mfec.dac.om.client.model.TagList;
import com.mfec.dac.om.client.model.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads every governance object OpenMetadata holds (FR-1.2).
 *
 * <p>Runs before the asset crawl, because an asset's facets are meaningless
 * without the vocabulary they refer to: which classifications are mutually
 * exclusive decides how tags inherit, and what type {@code dataResidency} has
 * decides whether a comparison against it is a string or a date.
 *
 * <p>Deleted entities are excluded. A tag that was removed in OpenMetadata must
 * stop selecting assets here too — keeping it would leave policies quietly
 * enforcing against a vocabulary nobody can see any more. History is preserved
 * by the SCD2 asset tables, not by retaining dead vocabulary.
 */
public class GovernanceCrawler {

  private static final Logger LOG = LoggerFactory.getLogger(GovernanceCrawler.class);

  /** The entity types whose custom properties can appear in a policy (FR-1.9). */
  private static final List<String> CUSTOM_PROPERTY_ENTITY_TYPES =
      List.of("table", "databaseSchema", "database", "databaseService");

  private final OpenMetadataClient client;

  public GovernanceCrawler(OpenMetadataClient client) {
    this.client = client;
  }

  public GovernanceSnapshot crawl() throws ApiException {
    GovernanceSnapshot snapshot =
        new GovernanceSnapshot(
            classifications(),
            tags(),
            glossaries(),
            terms(),
            domains(),
            dataProducts(),
            customProperties());

    LOG.info(
        "Governance crawl of {}: {} classifications, {} tags, {} glossaries, {} terms, "
            + "{} domains, {} data products, {} custom properties.",
        client.baseUrl(),
        snapshot.classifications().size(),
        snapshot.tags().size(),
        snapshot.glossaries().size(),
        snapshot.terms().size(),
        snapshot.domains().size(),
        snapshot.dataProducts().size(),
        snapshot.customProperties().size());
    return snapshot;
  }

  List<GovernanceSnapshot.ClassificationRow> classifications() throws ApiException {
    // disabled = "false" would hide classifications that are switched off, but
    // an asset may still carry their tags and the UI has to be able to say so.
    // They are cached and excluded from enforcement instead (FR-2A.3a).
    return map(
        OmPager.collect(
            (limit, after) ->
                client.classifications().listClassifications(null, null, limit, null, after, "non-deleted"),
            ClassificationList::getData,
            ClassificationList::getPaging),
        GovernanceMapper::classification);
  }

  List<GovernanceSnapshot.TagRow> tags() throws ApiException {
    return map(
        OmPager.collect(
            (limit, after) ->
                client.classifications().listTags(null, null, null, limit, null, after, "non-deleted"),
            TagList::getData,
            TagList::getPaging),
        GovernanceMapper::tag);
  }

  List<GovernanceSnapshot.GlossaryRow> glossaries() throws ApiException {
    return map(
        OmPager.collect(
            (limit, after) -> client.glossaries().listGlossaries(null, limit, null, after, "non-deleted"),
            GlossaryList::getData,
            GlossaryList::getPaging),
        GovernanceMapper::glossary);
  }

  List<GovernanceSnapshot.GlossaryTermRow> terms() throws ApiException {
    return map(
        OmPager.collect(
            (limit, after) ->
                client
                    .glossaries()
                    .listGlossaryTerm(
                        null, null, "relatedTerms", limit, null, after, "non-deleted", null, null),
            GlossaryTermList::getData,
            GlossaryTermList::getPaging),
        GovernanceMapper::glossaryTerm);
  }

  List<GovernanceSnapshot.DomainRow> domains() throws ApiException {
    return map(
        OmPager.collect(
            (limit, after) -> client.domains().listDomains(null, limit, null, after),
            DomainList::getData,
            DomainList::getPaging),
        GovernanceMapper::domain);
  }

  List<GovernanceSnapshot.DataProductRow> dataProducts() throws ApiException {
    return map(
        OmPager.collect(
            (limit, after) -> client.domains().listDataProducts("domains", null, limit, null, after),
            DataProductList::getData,
            DataProductList::getPaging),
        GovernanceMapper::dataProduct);
  }

  /**
   * Custom property definitions, read one entity type at a time.
   *
   * <p>A missing or unreadable type is skipped rather than fatal: an instance
   * that has never had a custom property on {@code databaseService} is normal,
   * and losing the whole crawl over it would be a poor trade.
   */
  List<GovernanceSnapshot.CustomPropertyRow> customProperties() {
    List<GovernanceSnapshot.CustomPropertyRow> out = new ArrayList<>();
    for (String entityType : CUSTOM_PROPERTY_ENTITY_TYPES) {
      try {
        Type type = client.metadata().getTypeByName(entityType, "customProperties", "non-deleted");
        List<CustomProperty> properties = type == null ? null : type.getCustomProperties();
        if (properties == null) {
          continue;
        }
        for (CustomProperty property : properties) {
          GovernanceSnapshot.CustomPropertyRow row =
              GovernanceMapper.customProperty(entityType, property);
          if (row != null) {
            out.add(row);
          }
        }
      } catch (ApiException e) {
        LOG.warn(
            "Could not read custom properties of entity type {} ({}): {}",
            entityType, e.getCode(), e.getMessage());
      }
    }
    return out;
  }

  private static <S, T> List<T> map(List<S> source, Function<S, T> mapper) {
    List<T> out = new ArrayList<>(source.size());
    for (S each : source) {
      T mapped = mapper.apply(each);
      if (mapped != null) {
        out.add(mapped);
      }
    }
    return out;
  }
}
