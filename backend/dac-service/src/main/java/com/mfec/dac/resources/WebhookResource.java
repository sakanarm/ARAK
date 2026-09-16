package com.mfec.dac.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.catalog.CatalogChangeApplier;
import com.mfec.dac.om.events.CatalogChange;
import com.mfec.dac.om.events.EventSignature;
import com.mfec.dac.om.events.WebhookPayload;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where OpenMetadata pushes its change events (FR-1.5).
 *
 * <p>Deliberately not {@code @Secured}. This is the one endpoint on the platform
 * that cannot present a session token, because the caller is a server acting on
 * nobody's behalf. The shared secret takes its place: without one, anyone who
 * could reach the port could tell the platform that a column's PII tag had been
 * removed, and the next refresh would write exactly that into the cache every
 * policy decision is made from.
 *
 * <p>It therefore refuses to work unconfigured. An unset secret answers 503, not
 * "accept anything" — an unsigned-but-accepted webhook is a worse failure than a
 * webhook that does not run, since the poller covers the second and nothing
 * covers the first.
 *
 * <p>The status codes are chosen for what OpenMetadata does with them, which is
 * retry on 5xx and give up on 4xx. A payload we cannot parse is 400 because
 * sending it again will not help; a database that is down is 500 because it
 * will.
 */
@Path("/v1/webhooks/openmetadata")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WebhookResource {

  private static final Logger LOG = LoggerFactory.getLogger(WebhookResource.class);

  private final ObjectMapper json;
  private final CatalogChangeApplier applier;
  private final String secret;

  public WebhookResource(ObjectMapper json, CatalogChangeApplier applier, String secret) {
    this.json = json;
    this.applier = applier;
    this.secret = secret;
  }

  /**
   * One delivery.
   *
   * <p>The body is taken as a raw {@code String} rather than a bound object
   * because the signature is over the bytes OpenMetadata sent. Letting Jersey
   * deserialise it first and re-serialising to verify would compare a signature
   * against a document that merely means the same thing — different key order,
   * different whitespace, a dropped unknown field — and fail on every valid
   * delivery.
   */
  @POST
  public Response receive(
      @jakarta.ws.rs.HeaderParam(EventSignature.HEADER) String signature,
      @jakarta.ws.rs.HeaderParam(HttpHeaders.USER_AGENT) String userAgent,
      String body) {

    if (secret == null || secret.isBlank()) {
      LOG.warn(
          "Rejected a webhook delivery from {}: no webhook secret is configured, so no delivery "
              + "can be trusted. Set OM_WEBHOOK_SECRET to the value used on the subscription.",
          userAgent);
      return error(
          Response.Status.SERVICE_UNAVAILABLE,
          "The webhook receiver is not configured on this deployment");
    }

    if (!EventSignature.matches(secret, body, signature)) {
      // No detail in the response: the caller either holds the secret or is
      // guessing, and telling a guesser whether the header was missing, the
      // wrong encoding, or simply wrong is free help.
      LOG.warn("Rejected a webhook delivery from {}: signature did not verify", userAgent);
      return error(Response.Status.UNAUTHORIZED, "Signature did not verify");
    }

    List<CatalogChange> changes;
    try {
      changes = WebhookPayload.parse(json, body);
    } catch (WebhookPayload.MalformedPayloadException e) {
      LOG.warn("Rejected a webhook delivery: {}", e.getMessage());
      return error(Response.Status.BAD_REQUEST, e.getMessage());
    }

    if (changes.isEmpty()) {
      // Normal, and not an error: OpenMetadata sends a test payload when a
      // subscription is created, and events for entity types this platform
      // does not cache. Answering 2xx keeps the subscription marked healthy.
      return Response.ok(Map.of("applied", 0, "message", "Nothing to apply")).build();
    }

    try {
      CatalogChangeApplier.Outcome outcome = applier.apply(changes);
      Map<String, Object> applied =
          Map.of(
              "received", changes.size(),
              "refreshed", outcome.refreshed(),
              "retired", outcome.retired(),
              "governanceRuns", outcome.governanceRuns(),
              "failed", outcome.failed());
      if (outcome.failed() > 0) {
        // A partial failure is still 5xx, for the same reason as below: the
        // delivery is the only notice this platform gets that the change
        // happened, and answering 2xx to a change that did not apply spends it.
        // Redelivering the whole batch is free — the applier re-reads by FQN —
        // and if it keeps failing, OpenMetadata marks the subscription unhealthy,
        // which is how the operator finds out.
        LOG.warn(
            "Applied a webhook delivery with {} of {} change(s) failing; asking for a redelivery",
            outcome.failed(),
            changes.size());
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(applied).build();
      }
      return Response.ok(applied).build();
    } catch (RuntimeException e) {
      // 500 on purpose, so OpenMetadata redelivers. Applying a change twice is
      // free; dropping one leaves the cache wrong until the nightly reconcile.
      LOG.error("Could not apply a webhook delivery of {} change(s)", changes.size(), e);
      return error(Response.Status.INTERNAL_SERVER_ERROR, "Could not apply the change");
    }
  }

  private static Response error(Response.Status status, String message) {
    return Response.status(status).entity(Map.of("message", message)).build();
  }
}
