package com.mfec.dac.resources;

import com.mfec.dac.config.DacConfiguration;
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

  public SystemResource(DacConfiguration config) {
    this.config = config;
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
}
