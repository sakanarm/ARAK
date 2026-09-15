package com.mfec.dac.engine;

import java.time.Instant;

/**
 * The circumstances of one request: when, from where, and what for.
 *
 * <p>Passing the clock in rather than reading it inside the engine is what makes
 * a time-based policy testable, and it is what lets the simulator answer "what
 * would this user see at 20:00" without changing the machine clock.
 *
 * @param at      the instant to evaluate against; never null
 * @param ip      the caller's address, or null when it is not known
 * @param purpose the declared purpose of access, or null when none was declared
 */
public record RequestContext(Instant at, String ip, String purpose) {

  public RequestContext {
    if (at == null) {
      throw new IllegalArgumentException("RequestContext.at is required: time-based policies "
          + "cannot be evaluated against an unknown instant");
    }
  }

  public static RequestContext at(Instant instant) {
    return new RequestContext(instant, null, null);
  }

  public RequestContext fromIp(String value) {
    return new RequestContext(at, value, purpose);
  }

  public RequestContext forPurpose(String value) {
    return new RequestContext(at, ip, value);
  }
}
