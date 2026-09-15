package com.mfec.dac.health;

import com.codahale.metrics.health.HealthCheck;
import org.jdbi.v3.core.Jdbi;

/**
 * The app DB is not optional: policy decisions are served from it, and a
 * degraded instance must be taken out of the load balancer rather than start
 * failing open.
 */
public class AppDatabaseHealthCheck extends HealthCheck {

  private final Jdbi jdbi;

  public AppDatabaseHealthCheck(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  @Override
  protected Result check() {
    Integer one = jdbi.withHandle(h -> h.createQuery("SELECT 1").mapTo(Integer.class).one());
    return one != null && one == 1
        ? Result.healthy()
        : Result.unhealthy("app database did not answer SELECT 1");
  }
}
