package com.mfec.dac.resources;

import com.mfec.dac.auth.Secured;
import com.mfec.dac.catalog.CatalogQuery;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reading the metadata cache (FR-1.2, FR-3.1.5).
 *
 * <p>Open to any authenticated caller. What is here is the shape of the data —
 * names, types, tags, owners — not the data itself, and a policy author who
 * cannot see which tables carry PII cannot write a policy about them. The rows
 * behind these names stay behind the enforcement modes.
 *
 * <p>Read-only, including for platform admins. OpenMetadata owns this content
 * and the crawl owns these tables; an edit here would be overwritten by the
 * next sync without telling anyone. Writing back is a separate, explicit
 * action (FR-1.7) against OpenMetadata itself.
 */
@Path("/v1/catalog")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class CatalogResource {

  private final CatalogQuery catalog;

  public CatalogResource(CatalogQuery catalog) {
    this.catalog = catalog;
  }

  /**
   * A page of assets.
   *
   * <p>{@code facet} repeats, each one {@code <type>:<fqn>} — for example
   * {@code facet=tags:PII.Sensitive&facet=domains:Finance}. They are AND-ed, and
   * because ancestors are expanded at write time {@code domains:Finance} also
   * finds the assets sitting in {@code Finance.Risk.Credit} (FR-2A.2).
   *
   * <p>A malformed facet is ignored rather than rejected: the alternative is a
   * 400 on a filter the caller can see they typed, and silently returning
   * everything is worse than returning the page the rest of the filters select.
   */
  @GET
  @Path("/assets")
  public CatalogQuery.AssetPage assets(
      @QueryParam("q") String search,
      @QueryParam("type") String assetType,
      @QueryParam("facet") List<String> facets,
      @QueryParam("owner") String owner,
      @QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit,
      @QueryParam("offset") @jakarta.ws.rs.DefaultValue("0") int offset) {

    List<CatalogQuery.FacetFilter> parsed = new ArrayList<>();
    if (facets != null) {
      for (String facet : facets) {
        CatalogQuery.FacetFilter.parse(facet).ifPresent(parsed::add);
      }
    }
    return catalog.assets(search, assetType, parsed, owner, limit, offset);
  }

  /**
   * One asset with its columns, facets and owners.
   *
   * <p>The FQN is dotted and can be long; it goes in the path because it is the
   * identity of the thing, and {@code .+} so that a service whose name contains
   * a slash does not become two path segments.
   */
  @GET
  @Path("/assets/{fqn: .+}")
  public CatalogQuery.AssetDetail asset(@PathParam("fqn") String fqn) {
    return catalog
        .asset(fqn)
        .orElseThrow(
            () ->
                // 404 means "not in the cache", which is not the same as "not in
                // OpenMetadata": a crawl that has never run leaves every FQN
                // here. The sync status screen is what tells them apart.
                new NotFoundException("No current asset is cached for " + fqn));
  }

  /** The facet values assets actually carry, for the filter menus. */
  @GET
  @Path("/facets")
  public Map<String, Object> facets(
      @QueryParam("type") String facetType,
      @QueryParam("limit") @jakarta.ws.rs.DefaultValue("100") int limit) {
    return Map.of("values", catalog.facetValues(facetType, limit));
  }

  /** Totals across the whole cache. */
  @GET
  @Path("/summary")
  public CatalogQuery.CatalogSummary summary() {
    return catalog.summary();
  }
}
