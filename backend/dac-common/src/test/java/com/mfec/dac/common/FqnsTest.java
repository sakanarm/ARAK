package com.mfec.dac.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FQN matching is the foundation the whole selector layer stands on, so the
 * cases here are the ones that would otherwise hand a policy the wrong assets.
 */
class FqnsTest {

  @Test
  @DisplayName("a quoted segment containing a dot stays one segment")
  void quotedSegmentsAreNotSplit() {
    assertThat(Fqns.segments("prod-mssql.\"Sales.DB\".dbo"))
        .containsExactly("prod-mssql", "Sales.DB", "dbo");
    assertThat(Fqns.depth("prod-mssql.\"Sales.DB\".dbo")).isEqualTo(3);
  }

  @Test
  @DisplayName("quoted and unquoted spellings of the same name compare equal")
  void quotingDoesNotChangeIdentity() {
    assertThat(Fqns.equal("prod-mssql.\"SalesDB\".dbo", "prod-mssql.SalesDB.dbo")).isTrue();
    assertThat(Fqns.equal("Finance", null)).isFalse();
  }

  @Test
  @DisplayName("contains covers a domain and every sub-domain under it")
  void descendantOrSelf() {
    assertThat(Fqns.isDescendantOrSelf("Finance.Risk.Credit", "Finance")).isTrue();
    assertThat(Fqns.isDescendantOrSelf("Finance.Risk.Credit", "Finance.Risk")).isTrue();
    assertThat(Fqns.isDescendantOrSelf("Finance", "Finance")).isTrue();
    assertThat(Fqns.isDescendantOrSelf("Finance", "Finance.Risk")).isFalse();
  }

  @Test
  @DisplayName("a sibling whose name merely starts with the ancestor does not match")
  void siblingPrefixIsNotADescendant() {
    // The classic bug this class exists to prevent: LIKE 'Finance.%' would be
    // fine here, but LIKE 'Finance%' — or comparing raw strings — would bind a
    // Finance policy to every asset in Finance Ops.
    assertThat(Fqns.isDescendantOrSelf("Finance Ops.Risk", "Finance")).isFalse();
    assertThat(Fqns.isDescendantOrSelf("FinanceOps", "Finance")).isFalse();
  }

  @Test
  @DisplayName("leaf returns the last segment, honouring quoting")
  void leaf() {
    assertThat(Fqns.leaf("prod-mssql.SalesDB.dbo.customer")).isEqualTo("customer");
    assertThat(Fqns.leaf("prod-mssql.\"Sales.DB\"")).isEqualTo("Sales.DB");
    assertThat(Fqns.leaf(null)).isNull();
  }

  @Test
  @DisplayName("join quotes a segment that contains a dot, so the round trip survives")
  void joinQuotesWhereItMust() {
    String joined = Fqns.join("prod-mssql", "Sales.DB", "dbo");
    assertThat(Fqns.segments(joined)).containsExactly("prod-mssql", "Sales.DB", "dbo");
  }
}
