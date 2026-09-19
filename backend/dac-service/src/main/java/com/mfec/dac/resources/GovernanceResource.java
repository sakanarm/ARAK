package com.mfec.dac.resources;

import com.mfec.dac.auth.Secured;
import com.mfec.dac.catalog.GovernanceQuery;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

/**
 * The governance vocabulary, as a master list (FR-1.2, FR-2A).
 *
 * <p>Two audiences, one set of rows. A steward opens these screens to see what
 * values exist and what would break if one were retired; the Policy Builder
 * calls the same endpoints to fill its value pickers, so the list an author
 * chooses from is the list the engine will actually match against — not a
 * second copy of OpenMetadata's that can drift out of step with the crawl.
 */
@Path("/v1/governance")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class GovernanceResource {

  private final GovernanceQuery governance;

  public GovernanceResource(GovernanceQuery governance) {
    this.governance = governance;
  }

  /** Classifications with their tags nested underneath. */
  @GET
  @Path("/classifications")
  public List<GovernanceQuery.Value> classifications() {
    return governance.classifications();
  }

  /** Glossaries with their terms. */
  @GET
  @Path("/glossaries")
  public List<GovernanceQuery.Value> glossaries() {
    return governance.glossaries();
  }

  /** Domains as a tree, sub-domains nested to whatever depth OpenMetadata has. */
  @GET
  @Path("/domains")
  public List<GovernanceQuery.Value> domains() {
    return governance.domains();
  }

  @GET
  @Path("/data-products")
  public List<GovernanceQuery.Value> dataProducts() {
    return governance.dataProducts();
  }

  /** Custom property definitions, so the builder can offer the right editor (FR-1.9). */
  @GET
  @Path("/custom-properties")
  public List<GovernanceQuery.CustomProperty> customProperties() {
    return governance.customProperties();
  }

  /**
   * Everything at once, for the Policy Builder.
   *
   * <p>One round trip rather than five. The builder needs all of it before it
   * can render a single selector row, and five parallel requests would leave
   * the form half-populated for a moment — long enough for someone to pick a
   * facet type whose values have not arrived and see an empty list.
   */
  @GET
  @Path("/vocabulary")
  public Map<String, Object> vocabulary() {
    return Map.of(
        "classifications", governance.classifications(),
        "glossaries", governance.glossaries(),
        "domains", governance.domains(),
        "dataProducts", governance.dataProducts(),
        "customProperties", governance.customProperties());
  }
}
