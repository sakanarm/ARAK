package com.mfec.dac.resources;

import com.mfec.dac.auth.Secured;
import com.mfec.dac.catalog.SearchQuery;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The one search box in the header.
 *
 * <p>Separate from {@code /v1/catalog/assets?search=} on purpose: that one
 * filters a table of assets and is paged, this one answers "where is the thing
 * called X" across every kind of object at once and is capped short. Folding
 * them together would make the catalog listing pay for six extra UNION branches
 * on every page change.
 */
@Path("/v1/search")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class SearchResource {

  private final SearchQuery search;

  public SearchResource(SearchQuery search) {
    this.search = search;
  }

  @GET
  public SearchQuery.Results search(
      @QueryParam("q") String query, @DefaultValue("20") @QueryParam("limit") int limit) {
    return search.search(query, limit);
  }
}
