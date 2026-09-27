package com.mfec.dac.resources;

import com.mfec.dac.auth.Secured;
import com.mfec.dac.config.DacConfiguration;
import com.mfec.dac.policy.DecisionCache;
import com.mfec.dac.policy.QueryResultCache;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/** Mirrors OpenMetadata's /api/v1/system/version so our own probes look the same. */
@Path("/v1/system")
@Produces(MediaType.APPLICATION_JSON)
public class SystemResource {

  private final DacConfiguration config;
  private final DecisionCache decisionCache;
  private final QueryResultCache resultCache;

  public SystemResource(DacConfiguration config, DecisionCache decisionCache) {
    this(config, decisionCache, QueryResultCache.disabled());
  }

  public SystemResource(
      DacConfiguration config, DecisionCache decisionCache, QueryResultCache resultCache) {
    this.config = config;
    this.decisionCache = decisionCache;
    this.resultCache = resultCache;
  }

  @GET
  @Path("/version")
  public Map<String, String> version() {
    String version = getClass().getPackage().getImplementationVersion();
    return Map.of(
        "version", version == null ? "0.1.0-SNAPSHOT" : version,
        "openMetadataBaseUrl", config.getOpenMetadata().getBaseUrl(),
        "openMetadataExpectedVersion", config.getOpenMetadata().getExpectedVersion());
  }

  /**
   * What the decision cache is holding and how well it is holding it (FR-5.5).
   *
   * <p>NFR-2 asks for a decision under 10ms warm and under 100ms cold. Whether
   * that is being met depends entirely on how often "warm" happens, and a hit
   * rate is not something a deployment should have to take on trust from a
   * design document. The invalidation counter and the reason for the last one
   * are here for the other question an operator asks, which is why a decision
   * they expected to be instant was not.
   *
   * <p>Behind the auth filter, unlike the version above: the last invalidation
   * reason names a policy or a person.
   */
  @GET
  @Secured
  @Path("/decision-cache")
  public DecisionCache.Stats decisionCache() {
    return decisionCache.stats();
  }

  /**
   * What the Query API's result cache is holding (FR-6.3): how often a
   * statement was answered without asking the source, and how much memory
   * that is costing, in cells. Behind the auth filter for the same reason as
   * the decision cache.
   */
  @GET
  @Secured
  @Path("/result-cache")
  public QueryResultCache.Stats resultCache() {
    return resultCache.stats();
  }
}
