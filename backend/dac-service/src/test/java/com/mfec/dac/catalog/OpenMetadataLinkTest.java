package com.mfec.dac.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OpenMetadataLinkTest {

  private static final String BASE = "https://catalog.example.com";

  private final OpenMetadataLink links = new OpenMetadataLink(BASE);

  @Nested
  @DisplayName("the entity paths the 2.0.1 console actually routes")
  class Paths {

    @Test
    void aTableLandsOnTheTablePage() {
      assertThat(links.forAsset("TABLE", "pg.sales.public.customer", true))
          .isEqualTo(BASE + "/table/pg.sales.public.customer");
    }

    @Test
    void aViewIsATableEntityToo() {
      // Only tableType differs over there; the route is the same one.
      assertThat(links.forAsset("VIEW", "pg.sales.public.v_customer", true))
          .isEqualTo(BASE + "/table/pg.sales.public.v_customer");
    }

    @Test
    void aSchemaLandsOnTheSchemaPage() {
      assertThat(links.forAsset("SCHEMA", "pg.sales.public", true))
          .isEqualTo(BASE + "/databaseSchema/pg.sales.public");
    }

    @Test
    void aDatabaseLandsOnTheDatabasePage() {
      assertThat(links.forAsset("DATABASE", "pg.sales", true))
          .isEqualTo(BASE + "/database/pg.sales");
    }

    @Test
    void aServiceIsRoutedByItsCategory() {
      assertThat(links.forAsset("SERVICE", "pg", true))
          .isEqualTo(BASE + "/service/databaseServices/pg");
    }

    @Test
    void anAssetTypeThisDoesNotKnowGetsNoLink() {
      // Better no link than one that lands on a 404 and reads as ARAK being
      // broken rather than as this type having no page over there.
      assertThat(links.forAsset("DASHBOARD", "bi.sales", true)).isNull();
      assertThat(links.forAsset(null, "pg.sales", true)).isNull();
    }
  }

  @Nested
  @DisplayName("what does not get a link")
  class Absent {

    @Test
    void anAssetTheCrawlDidNotBringIn() {
      // Discovered over JDBC: a perfectly good FQN with nothing behind it.
      assertThat(links.forAsset("TABLE", "pg.sales.public.customer", false)).isNull();
    }

    @Test
    void aDeploymentWithNoConsoleConfigured() {
      assertThat(new OpenMetadataLink(null).forAsset("TABLE", "a.b.c.d", true)).isNull();
      assertThat(new OpenMetadataLink("   ").forAsset("TABLE", "a.b.c.d", true)).isNull();
    }

    @Test
    void anAssetWithNoName() {
      assertThat(links.forAsset("TABLE", null, true)).isNull();
      assertThat(links.forAsset("TABLE", "  ", true)).isNull();
    }
  }

  @Nested
  @DisplayName("the URL itself")
  class Shape {

    @Test
    void aTrailingSlashOnTheBaseDoesNotDoubleUp() {
      assertThat(new OpenMetadataLink(BASE + "///").forAsset("TABLE", "a.b.c.d", true))
          .isEqualTo(BASE + "/table/a.b.c.d");
    }

    @Test
    void aSpaceInTheNameIsEncodedAsAPathSegmentNotAFormField() {
      // URLEncoder is form encoding, so this is the one substitution that
      // matters: a path reads '+' literally, and the link would miss.
      assertThat(links.forAsset("DATABASE", "pg.Sales DB", true))
          .isEqualTo(BASE + "/database/pg.Sales%20DB");
    }

    @Test
    void dotsSurviveSoTheFqnStaysReadableInTheAddressBar() {
      assertThat(links.forAsset("TABLE", "a.b.c.d", true)).doesNotContain("%2E");
    }
  }
}
