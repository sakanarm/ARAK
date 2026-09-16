package com.mfec.dac.om.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.mfec.dac.om.client.model.Column;
import com.mfec.dac.om.client.model.EntityReference;
import com.mfec.dac.om.client.model.Table;
import com.mfec.dac.om.client.model.TagLabel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AssetMapperTest {

  private static final String TABLE_FQN = "prod-mssql.SalesDB.dbo.customer";

  private static TagLabel tag(String fqn) {
    return new TagLabel()
        .tagFQN(fqn)
        .source(TagLabel.SourceEnum.CLASSIFICATION)
        .labelType(TagLabel.LabelTypeEnum.MANUAL)
        .state(TagLabel.StateEnum.CONFIRMED);
  }

  private static EntityReference owner(String name, String type) {
    return new EntityReference().name(name).fullyQualifiedName(name).type(type);
  }

  private static Column column(String name, Column.DataTypeEnum type) {
    return new Column().name(name).fullyQualifiedName(TABLE_FQN + "." + name).dataType(type);
  }

  @Nested
  @DisplayName("assets")
  class Assets {

    @Test
    @DisplayName("record a view as a view, because a view cannot be wrapped in another one")
    void viewsAreNotTables() {
      CrawledAsset.AssetRow view =
          AssetMapper.table(
              new Table()
                  .name("customer_v")
                  .fullyQualifiedName("prod-mssql.SalesDB.dbo.customer_v")
                  .tableType(Table.TableTypeEnum.VIEW));

      // The secure-view compiler has to know: rewriting a view into
      // `<name>_secure` and revoking the base object is a different operation
      // when the base object is itself a view (FR-6.1.1).
      assertThat(view.assetType()).isEqualTo("VIEW");
      assertThat(AssetMapper.table(new Table().name("c").fullyQualifiedName(TABLE_FQN)).assetType())
          .isEqualTo("TABLE");
    }

    @Test
    @DisplayName("lift tier and certification onto the asset itself")
    void denormalisesTier() {
      CrawledAsset.AssetRow row =
          AssetMapper.table(
              new Table()
                  .name("customer")
                  .fullyQualifiedName(TABLE_FQN)
                  .tags(List.of(tag("Tier.Tier1"))));

      assertThat(row.tier()).isEqualTo("Tier1");
      assertThat(row.parentFqn()).isEqualTo("prod-mssql.SalesDB.dbo");
    }

    @Test
    @DisplayName("keep the raw extension object, including properties nobody has taught us yet")
    void keepsExtension() {
      CrawledAsset.AssetRow row =
          AssetMapper.table(
              new Table()
                  .name("customer")
                  .fullyQualifiedName(TABLE_FQN)
                  .extension(Map.of("dataResidency", "TH", "somethingNew", 7)));

      assertThat(row.customProperties())
          .containsEntry("dataResidency", "TH")
          .containsEntry("somethingNew", 7);
    }

    @Test
    @DisplayName("skip an entity with no fully-qualified name rather than caching a blank key")
    void skipsUnusable() {
      assertThat(AssetMapper.table(new Table().name("customer"))).isNull();
      assertThat(AssetMapper.database(null)).isNull();
    }
  }

  @Nested
  @DisplayName("columns")
  class Columns {

    @Test
    @DisplayName("flatten a struct's fields into columns of their own")
    void flattensNestedFields() {
      Column address =
          column("address", Column.DataTypeEnum.STRUCT)
              .children(
                  List.of(
                      new Column()
                          .name("postcode")
                          .fullyQualifiedName(TABLE_FQN + ".address.postcode")
                          .dataType(Column.DataTypeEnum.VARCHAR)
                          .tags(List.of(tag("PII.Sensitive")))));

      Table table = new Table().name("customer").fullyQualifiedName(TABLE_FQN).columns(
          List.of(column("id", Column.DataTypeEnum.BIGINT), address));

      // Masking a whole struct to hide one field inside it would take away data
      // nobody asked to restrict, so the field has to be addressable on its own.
      assertThat(AssetMapper.columns(table))
          .extracting(CrawledAsset.ColumnRow::fqn)
          .containsExactly(
              TABLE_FQN + ".id", TABLE_FQN + ".address", TABLE_FQN + ".address.postcode");

      assertThat(AssetMapper.columnFacets(table))
          .extracting(f -> f.targetFqn() + " " + f.facetFqn())
          .contains(TABLE_FQN + ".address.postcode PII.Sensitive");
    }

    @Test
    @DisplayName("tell 'no constraint recorded' apart from 'nullable'")
    void nullabilityIsThreeValued() {
      Table table =
          new Table()
              .name("customer")
              .fullyQualifiedName(TABLE_FQN)
              .columns(
                  List.of(
                      column("id", Column.DataTypeEnum.BIGINT)
                          .constraint(Column.ConstraintEnum.PRIMARY_KEY),
                      column("email", Column.DataTypeEnum.VARCHAR)
                          .constraint(Column.ConstraintEnum.NULL),
                      column("phone", Column.DataTypeEnum.VARCHAR)));

      // NULLIFY on a NOT NULL column fails at the source. Guessing "nullable"
      // where the catalogue says nothing would turn that into an apply-time
      // error instead of a warning in the capability check (FR-6.0b).
      assertThat(AssetMapper.columns(table))
          .extracting(CrawledAsset.ColumnRow::name, CrawledAsset.ColumnRow::nullable)
          .containsExactly(tuple("id", false), tuple("email", true), tuple("phone", null));
    }

    @Test
    @DisplayName("carry the declared type, which decides what may be masked how")
    void keepsDataType() {
      Table table =
          new Table()
              .name("customer")
              .fullyQualifiedName(TABLE_FQN)
              .columns(List.of(column("email", Column.DataTypeEnum.VARCHAR).dataLength(255)));

      assertThat(AssetMapper.columns(table))
          .extracting(CrawledAsset.ColumnRow::dataType, CrawledAsset.ColumnRow::dataLength)
          .containsExactly(tuple("VARCHAR", 255));
    }
  }

  @Nested
  @DisplayName("owners")
  class Owners {

    @Test
    @DisplayName("tell a team apart from a user of the same name")
    void keepsTheType() {
      List<CrawledAsset.OwnerRow> rows =
          AssetMapper.owners(TABLE_FQN, List.of(owner("Finance", "team"), owner("Finance", "user")));

      assertThat(rows)
          .extracting(CrawledAsset.OwnerRow::ownerType, CrawledAsset.OwnerRow::ownerName)
          .containsExactly(tuple("team", "Finance"), tuple("user", "Finance"));
    }

    @Test
    @DisplayName("add the owners of enclosing levels rather than replacing them")
    void ownershipAccumulates() {
      List<CrawledAsset.OwnerRow> own =
          AssetMapper.owners(TABLE_FQN, List.of(owner("alice", "user")));

      List<CrawledAsset.OwnerRow> effective =
          AssetMapper.effectiveOwners(
              TABLE_FQN,
              own,
              List.of(
                  new AssetMapper.OwnerLevel(
                      "prod-mssql.SalesDB.dbo",
                      AssetMapper.owners("prod-mssql.SalesDB.dbo", List.of(owner("bob", "user")))),
                  new AssetMapper.OwnerLevel(
                      "prod-mssql.SalesDB",
                      AssetMapper.owners(
                          "prod-mssql.SalesDB", List.of(owner("Finance", "team"))))));

      // A schema's owner is answerable for the tables inside it — that is what
      // lets them write a local policy there (FR-3.1.2). Each row says where the
      // authority came from so the UI can still show the table's own owner.
      assertThat(effective)
          .extracting(
              CrawledAsset.OwnerRow::ownerName,
              CrawledAsset.OwnerRow::direct,
              CrawledAsset.OwnerRow::inheritedFrom)
          .containsExactly(
              tuple("alice", true, null),
              tuple("bob", false, "prod-mssql.SalesDB.dbo"),
              tuple("Finance", false, "prod-mssql.SalesDB"));

      assertThat(effective)
          .extracting(CrawledAsset.OwnerRow::targetFqn)
          .containsOnly(TABLE_FQN);
    }

    @Test
    @DisplayName("do not list the same owner twice when a level repeats it")
    void deduplicates() {
      List<CrawledAsset.OwnerRow> effective =
          AssetMapper.effectiveOwners(
              TABLE_FQN,
              AssetMapper.owners(TABLE_FQN, List.of(owner("alice", "user"))),
              List.of(
                  new AssetMapper.OwnerLevel(
                      "prod-mssql.SalesDB.dbo",
                      AssetMapper.owners(
                          "prod-mssql.SalesDB.dbo", List.of(owner("alice", "user"))))));

      assertThat(effective).hasSize(1);
      assertThat(effective.get(0).direct()).isTrue();
    }

    @Test
    @DisplayName("record what OpenMetadata propagated as inherited, not as set here")
    void propagatedOwnersAreNotDirect() {
      EntityReference propagated = owner("Finance", "team").inherited(true);
      assertThat(AssetMapper.owners(TABLE_FQN, List.of(propagated)))
          .extracting(CrawledAsset.OwnerRow::direct)
          .containsExactly(false);
    }

    @Test
    @DisplayName("keep the OpenMetadata id, so a rename does not orphan the grant")
    void keepsTheId() {
      UUID id = UUID.randomUUID();
      assertThat(AssetMapper.owners(TABLE_FQN, List.of(owner("alice", "user").id(id))))
          .extracting(CrawledAsset.OwnerRow::ownerOmId)
          .containsExactly(id);
    }
  }

  @Nested
  @DisplayName("physical mapping")
  class Physical {

    @Test
    @DisplayName("split a table FQN into the object the DDL will name")
    void splitsFourSegments() {
      AssetMapper.Physical physical = AssetMapper.physicalOf(TABLE_FQN, "TABLE");

      assertThat(physical)
          .isEqualTo(
              new AssetMapper.Physical("prod-mssql", "SalesDB", "dbo", "customer", "TABLE"));
    }

    @Test
    @DisplayName("keep a dotted object name whole")
    void quotedSegmentsStayWhole() {
      AssetMapper.Physical physical =
          AssetMapper.physicalOf("prod-mssql.SalesDB.dbo.\"customer.archive\"", "TABLE");

      // Cutting at the raw dot would point the DDL at a table that does not
      // exist, or worse, at a different one that does.
      assertThat(physical.objectName()).isEqualTo("customer.archive");
    }

    @Test
    @DisplayName("refuse to guess when the FQN is not a table's")
    void wrongDepthIsNull() {
      assertThat(AssetMapper.physicalOf("prod-mssql.SalesDB.dbo", "TABLE")).isNull();
      assertThat(AssetMapper.physicalOf(TABLE_FQN + ".email", "TABLE")).isNull();
    }
  }
}
