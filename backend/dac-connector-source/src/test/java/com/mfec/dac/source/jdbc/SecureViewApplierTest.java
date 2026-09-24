package com.mfec.dac.source.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.compiler.sql.PostgresDialect;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.ColumnGrant;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.Entitlement;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.Maintenance;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.Rows;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.Subscription;
import com.mfec.dac.compiler.sql.ViewCompiler;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of the applier that decide things, separated from the part that
 * talks to a database.
 *
 * <p>Almost all of this class's behaviour needs Postgres and lives in
 * {@code SecureViewApplierIT}. What is here is the signature, which is the only
 * thing standing between a reviewed change and a different one being applied in
 * its place. It has two ways to be wrong and they fail in opposite directions:
 * if it is unstable, every apply is refused as stale and the feature is unusable;
 * if two different diffs share one, an apply carries out a change nobody read.
 */
class SecureViewApplierTest {

  private static final String ASSET = "sales.customer";

  @Test
  @DisplayName("the same change signs the same however the sets were built")
  void theSignatureDoesNotDependOnInsertionOrder() {
    Maintenance one =
        maintenance(
            rows(
                Set.of(new Subscription("analyst_a", ASSET), new Subscription("analyst_b", ASSET)),
                Set.of(
                    new Entitlement("analyst_a", ASSET, "branch_code", "BKK-01"),
                    new Entitlement("analyst_a", ASSET, "branch_code", "CNX-01")),
                Set.of()),
            Rows.NONE);

    LinkedHashSet<Subscription> reversed = new LinkedHashSet<>();
    reversed.add(new Subscription("analyst_b", ASSET));
    reversed.add(new Subscription("analyst_a", ASSET));
    LinkedHashSet<Entitlement> reversedValues = new LinkedHashSet<>();
    reversedValues.add(new Entitlement("analyst_a", ASSET, "branch_code", "CNX-01"));
    reversedValues.add(new Entitlement("analyst_a", ASSET, "branch_code", "BKK-01"));

    Maintenance other = maintenance(rows(reversed, reversedValues, Set.of()), Rows.NONE);

    assertThat(SecureViewApplier.signature(other))
        .isEqualTo(SecureViewApplier.signature(one));
  }

  @Test
  @DisplayName("granting a row and revoking it do not sign the same")
  void insertAndDeleteAreNotInterchangeable() {
    Rows row =
        rows(Set.of(new Subscription("analyst_a", ASSET)), Set.of(), Set.of());

    assertThat(SecureViewApplier.signature(maintenance(row, Rows.NONE)))
        .isNotEqualTo(SecureViewApplier.signature(maintenance(Rows.NONE, row)));
  }

  @Test
  @DisplayName("a changed treatment of one column signs differently from the old one")
  void aTreatmentChangeIsVisibleInTheSignature() {
    Rows before =
        rows(
            Set.of(),
            Set.of(),
            Set.of(new ColumnGrant("analyst_a", ASSET, "citizen_id", "PARTIAL:4")));
    Rows after =
        rows(
            Set.of(),
            Set.of(),
            Set.of(new ColumnGrant("analyst_a", ASSET, "citizen_id", "NULLIFY")));

    assertThat(SecureViewApplier.signature(maintenance(after, Rows.NONE)))
        .isNotEqualTo(SecureViewApplier.signature(maintenance(before, Rows.NONE)));
  }

  @Test
  @DisplayName("nothing to do signs as nothing")
  void anEmptyDiffSignsEmpty() {
    assertThat(SecureViewApplier.signature(maintenance(Rows.NONE, Rows.NONE))).isEmpty();
  }

  @Test
  @DisplayName("a request with no entitlement source reads no entitlements rather than failing")
  void theEntitlementSourceDefaultsToNone() {
    SecureViewApplier.Request request =
        new SecureViewApplier.Request(
            new SourceProbe.Target("POSTGRES", "example.test", 5432, "salesdb"),
            new CredentialResolver.Credential("arak", "not-a-real-password"),
            new PostgresDialect(),
            target(),
            plan(),
            List.of(decision()),
            null);

    assertThat(request.entitlements())
        .isSameAs(RowEntitlementMaintainer.EntitlementSource.NONE);
  }

  @Test
  @DisplayName("a request without decisions is refused rather than read as revoking everybody")
  void decisionsAreRequired() {
    assertThatThrownBy(
            () ->
                new SecureViewApplier.Request(
                    new SourceProbe.Target("POSTGRES", "example.test", 5432, "salesdb"),
                    new CredentialResolver.Credential("arak", "not-a-real-password"),
                    new PostgresDialect(),
                    target(),
                    plan(),
                    null,
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("decisions");
  }

  // --------------------------------------------------------------- fixtures

  private static Maintenance maintenance(Rows insert, Rows delete) {
    return new Maintenance(Rows.NONE, insert, delete, List.of());
  }

  private static Rows rows(
      Set<Subscription> subscriptions, Set<Entitlement> entitlements, Set<ColumnGrant> grants) {
    return new Rows(subscriptions, entitlements, grants);
  }

  private static ViewCompiler.Target target() {
    return new ViewCompiler.Target(
        "sales",
        "customer",
        "sec",
        "customer",
        "acl",
        ASSET,
        List.of("id", "email"),
        ViewCompiler.IdentitySource.DB_PRINCIPAL,
        null);
  }

  private static ViewCompiler.Plan plan() {
    return new ViewCompiler(new PostgresDialect()).compile(List.of(decision()), target());
  }

  private static PolicyDecision decision() {
    return new PolicyDecision()
        .withPrincipal("analyst_a")
        .withAssetFqn(ASSET)
        .withAllowed(true)
        .withColumnMasks(
            List.of(
                new ResolvedColumnMask()
                    .withColumn("email")
                    .withMasking(
                        new MaskingSpec().withFunction(MaskingSpec.MaskingFunction.NULLIFY))));
  }
}
