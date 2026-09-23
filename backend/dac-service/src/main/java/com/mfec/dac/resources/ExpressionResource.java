package com.mfec.dac.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.engine.ExpressionReference;
import com.mfec.dac.engine.PolicyExpressionEvaluator;
import com.mfec.dac.identity.PrincipalQuery;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The expression language, described and checked (FR-3.2).
 *
 * <p>Two endpoints for one problem. An author writing {@code user.country ==
 * asset.prop('dataResidency')} has, until now, had a one-line hint under the
 * field and a grammar that exists only in javadoc they cannot open. The
 * reference is served from the engine's own jar so the page cannot describe a
 * language this deployment is not running, and the check is the engine's own
 * parser so it cannot disagree with what happens on save.
 *
 * <p>Reading is open to any authenticated caller. Neither endpoint touches a
 * policy or reveals anything about one: the reference is static, and the check
 * is told an expression by the caller who already typed it.
 */
@Path("/v1/expressions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class ExpressionResource {

  private final PrincipalQuery principals;

  public ExpressionResource(PrincipalQuery principals) {
    this.principals = principals;
  }

  /** Roots, operators, worked examples and the expressions that are refused. */
  @GET
  @Path("/reference")
  public JsonNode reference() {
    return ExpressionReference.document();
  }

  /** What the author typed, and what the parser makes of it. */
  public record Check(String expression) {}

  /**
   * @param unknownAttributes {@code user.} names nobody in the directory
   *     carries. Not errors -- see {@link #validate} -- but the only place a
   *     typo can hide, so they are named rather than swallowed.
   */
  public record Verdict(
      boolean valid,
      String message,
      int position,
      boolean rowDependent,
      List<String> userAttributes,
      List<String> unknownAttributes) {}

  /**
   * Checks an expression before the policy carrying it is saved.
   *
   * <p>The interesting half is the warning rather than the error. {@code user.}
   * resolves anything to an attribute lookup on purpose, so the directory can
   * grow without a code change, which means {@code user.contry} parses
   * perfectly and then quietly makes the whole rule undecidable -- and an ALLOW
   * that cannot be decided grants nothing while still reading back exactly as
   * typed. The parser cannot catch that; the directory can. So the names that
   * fell through to an attribute lookup are compared against the attributes
   * principals actually carry, and anything nobody has is reported as a warning
   * the author can dismiss if they are onboarding the attribute tomorrow.
   */
  @POST
  @Path("/validate")
  public Verdict validate(Check body) {
    String expression = body == null ? null : body.expression();
    PolicyExpressionEvaluator.Validation result = PolicyExpressionEvaluator.validate(expression);
    return new Verdict(
        result.valid(),
        result.message(),
        result.position(),
        result.rowDependent(),
        result.userAttributes(),
        unknown(result.userAttributes()));
  }

  private List<String> unknown(List<String> referenced) {
    if (referenced.isEmpty()) {
      return List.of();
    }
    Set<String> known = new LinkedHashSet<>();
    // One value per key: this is a name check, and pulling every value of every
    // attribute in the directory to answer it would be a page-load-sized query.
    for (PrincipalQuery.AttributeKey key : principals.attributeKeys(1)) {
      known.add(key.key().toLowerCase(Locale.ROOT));
    }
    List<String> out = new ArrayList<>();
    for (String name : referenced) {
      if (!known.contains(name.toLowerCase(Locale.ROOT))) {
        out.add(name);
      }
    }
    return out;
  }
}
