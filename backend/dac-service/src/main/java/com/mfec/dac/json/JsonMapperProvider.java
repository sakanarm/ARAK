package com.mfec.dac.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;

/**
 * Hands Jersey the ObjectMapper this application configured.
 *
 * <p>Needed because configuring {@code environment.getObjectMapper()} is not
 * enough on its own: the message body writer resolves its mapper through a
 * {@link ContextResolver} first, and without one it falls back to a default
 * instance that nothing here has touched. The visible symptom was an {@code
 * Instant} going out as {@code 1789832026.308722} — epoch seconds with a
 * fraction — which the console read as milliseconds and reported as a crawl
 * that happened in 1970.
 *
 * <p>A timestamp that crosses an HTTP boundary should say what it means, so the
 * mapper this resolves to writes ISO-8601 and every endpoint inherits that
 * rather than each one formatting its own dates.
 */
@Provider
public class JsonMapperProvider implements ContextResolver<ObjectMapper> {

  private final ObjectMapper mapper;

  public JsonMapperProvider(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public ObjectMapper getContext(Class<?> type) {
    return mapper;
  }
}
