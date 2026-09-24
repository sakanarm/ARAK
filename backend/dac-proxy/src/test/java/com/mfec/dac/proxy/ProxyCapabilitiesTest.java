package com.mfec.dac.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.common.engine.SourceEngine;
import com.mfec.dac.common.engine.SourceEngines;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The fail-closed rule of mode 5.2, tested from the direction that matters.
 *
 * <p>The interesting assertions here are the refusals. An engine that cannot
 * express a mask and runs the query anyway hands back the column in the clear,
 * and nobody reports that as a bug because the query succeeded — so the test
 * that earns its place is the one proving the query does not run.
 */
class ProxyCapabilitiesTest {

  private static final String FQN = "demo-pg.salesdb.sales.customer";

  /** An engine that can express nothing, which is the shape of a newly added one. */
  private static final SourceEngine INCAPABLE = new StubEngine(Set.of());

  static List<String> engineIds() {
    return List.copyOf(SourceEngines.ids());
  }

  // ------------------------------------------------------------- fixtures

  private static PolicyDecision allowed() {
    return new PolicyDecision()
        .withPrincipal("analyst_a")
        .withAssetFqn(FQN)
        .withAllowed(true)
        .withRowPredicates(List.of())
        .withColumnMasks(List.of())
        .withHiddenColumns(List.of());
  }

  private static ResolvedColumnMask partialMask() {
    return new ResolvedColumnMask()
        .withColumn("citizen_id")
        .withMasking(
            new MaskingSpec().withFunction(MaskingSpec.MaskingFunction.PARTIAL).withShowLast(4));
  }

  // ------------------------------------------------------ what is required

  @Test
  void anUntreatedDecisionNeedsNothing() {
    assertThat(ProxyCapabilities.required(allowed())).isEmpty();
  }

  @Test
  void aDenialNeedsNothingBecauseNothingIsSentToTheSource() {
    PolicyDecision denied = allowed().withAllowed(false).withHiddenColumns(List.of("salary"));
    assertThat(ProxyCapabilities.required(denied)).isEmpty();
  }

  @Test
  void aNullDecisionNeedsNothing() {
    assertThat(ProxyCapabilities.required(null)).isEmpty();
  }

  @Test
  void aRowFilterNeedsRowFiltering() {
    PolicyDecision decision =
        allowed()
            .withRowPredicates(
                List.of(
                    new ResolvedRowPredicate()
                        .withKind(ResolvedRowPredicate.Kind.IN_LIST)
                        .withColumn("branch_code")
                        .withValues(List.<Object>of("BKK-01"))));
    assertThat(ProxyCapabilities.required(decision))
        .containsExactly(SourceEngine.Capability.ROW_FILTER);
  }

  @Test
  void aHiddenColumnNeedsColumnHiding() {
    assertThat(ProxyCapabilities.required(allowed().withHiddenColumns(List.of("salary"))))
        .containsExactly(SourceEngine.Capability.COLUMN_HIDE);
  }

  @Test
  void anUnconditionalMaskNeedsColumnMasking() {
    assertThat(ProxyCapabilities.required(allowed().withColumnMasks(List.of(partialMask()))))
        .containsExactly(SourceEngine.Capability.COLUMN_MASK);
  }

  @Test
  void aConditionalMaskNeedsCellMaskingInstead() {
    // The distinction is the whole reason the two are separate capabilities:
    // showing a value on one row and hiding it on the next is a strictly
    // harder thing to emit than blanking a column outright, and an engine can
    // manage one without the other.
    PolicyDecision decision =
        allowed()
            .withColumnMasks(
                List.of(partialMask().withCondition("c.department <> 'FINANCE'")));
    assertThat(ProxyCapabilities.required(decision))
        .containsExactly(SourceEngine.Capability.CELL_MASK);
  }

  @Test
  void aBlankConditionIsNoConditionAtAll() {
    PolicyDecision decision =
        allowed().withColumnMasks(List.of(partialMask().withCondition("   ")));
    assertThat(ProxyCapabilities.required(decision))
        .containsExactly(SourceEngine.Capability.COLUMN_MASK);
  }

  // ------------------------------------------------------------- refusals

  @Test
  void refusesWhenTheEngineCannotMask() {
    assertThatThrownBy(
            () ->
                ProxyCapabilities.require(
                    INCAPABLE, FQN, allowed().withColumnMasks(List.of(partialMask()))))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining(FQN)
        .hasMessageContaining("a column mask")
        // A refusal that does not say what to do instead is a refusal somebody
        // works around by turning the policy off.
        .hasMessageContaining("secure view");
  }

  @Test
  void namesEveryTreatmentItCannotExpressRatherThanTheFirst() {
    PolicyDecision decision =
        allowed()
            .withRowPredicates(
                List.of(
                    new ResolvedRowPredicate()
                        .withKind(ResolvedRowPredicate.Kind.ALWAYS_FALSE)))
            .withColumnMasks(List.of(partialMask()))
            .withHiddenColumns(List.of("salary"));
    assertThat(ProxyCapabilities.missing(INCAPABLE, decision))
        .containsExactlyInAnyOrder(
            SourceEngine.Capability.ROW_FILTER,
            SourceEngine.Capability.COLUMN_MASK,
            SourceEngine.Capability.COLUMN_HIDE);
  }

  @Test
  void lettingAnUngovernedQueryThroughNeedsNoCapabilityAtAll() {
    assertThatCode(() -> ProxyCapabilities.require(INCAPABLE, FQN, allowed()))
        .doesNotThrowAnyException();
  }

  @Test
  void anEngineDeclaringOnlyRowFilteringStillRunsRowFilteredQueries() {
    SourceEngine rowsOnly = new StubEngine(Set.of(SourceEngine.Capability.ROW_FILTER));
    PolicyDecision decision =
        allowed()
            .withRowPredicates(
                List.of(
                    new ResolvedRowPredicate()
                        .withKind(ResolvedRowPredicate.Kind.ALWAYS_FALSE)));
    assertThatCode(() -> ProxyCapabilities.require(rowsOnly, FQN, decision))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                ProxyCapabilities.require(
                    rowsOnly, FQN, decision.withColumnMasks(List.of(partialMask()))))
        .isInstanceOf(QueryRewriter.RefusedException.class);
  }

  // -------------------------------------------------- the shipped engines

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void everyShippedEngineCanExpressEverythingTheRewriterEmits(String id) {
    // Both Phase 1 engines are rewritten by the same CASE/WHERE construction,
    // so a gap here would mean a capability was dropped from an engine rather
    // than that an engine genuinely cannot do it.
    PolicyDecision everything =
        allowed()
            .withRowPredicates(
                List.of(
                    new ResolvedRowPredicate()
                        .withKind(ResolvedRowPredicate.Kind.ALWAYS_FALSE)))
            .withColumnMasks(
                List.of(partialMask(), partialMask().withCondition("c.dept <> 'FIN'")))
            .withHiddenColumns(List.of("salary"));
    assertThat(ProxyCapabilities.missing(SourceEngines.of(id), everything)).isEmpty();
  }

  /** An engine that exists only to declare a capability set. */
  private record StubEngine(Set<String> capabilities) implements SourceEngine {
    @Override
    public String id() {
      return "STUB";
    }

    @Override
    public String displayName() {
      return "Stub";
    }

    @Override
    public int defaultPort() {
      return 1234;
    }

    @Override
    public boolean supportsSchemas() {
      return true;
    }

    @Override
    public String driverClassName() {
      return "com.example.StubDriver";
    }

    @Override
    public String jdbcUrl(JdbcCoordinates coordinates) {
      return "jdbc:stub://" + coordinates.host() + ":" + coordinates.port();
    }

    @Override
    public String dialectId() {
      return "STUB";
    }

    @Override
    public Set<String> proxyCapabilities() {
      return capabilities;
    }
  }
}
