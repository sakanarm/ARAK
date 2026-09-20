package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The audit row must survive whatever the container calls the client.
 *
 * <p>This is the regression that lost every audit row on a loopback request:
 * the insert threw on a bracketed IPv6 address, the catch logged it, and the
 * query went through unrecorded.
 */
class QueryServiceInetTest {

  @Test
  void stripsTheBracketsJettyPutsAroundAnIpv6Address() {
    assertThat(QueryService.inet("[0:0:0:0:0:0:0:1]")).isEqualTo("0:0:0:0:0:0:0:1");
  }

  @Test
  void leavesAnIpv4AddressAlone() {
    // TEST-NET-1 (RFC 5737). This repository is public, so an address that
    // exists on somebody's network does not belong in a fixture.
    assertThat(QueryService.inet("192.0.2.10")).isEqualTo("192.0.2.10");
  }

  @Test
  void dropsTheZoneIndexBecauseInetDoesNotStoreIt() {
    assertThat(QueryService.inet("fe80::1%eth0")).isEqualTo("fe80:0:0:0:0:0:0:1");
  }

  @Test
  void treatsAMissingAddressAsMissingRatherThanAsAFailure() {
    assertThat(QueryService.inet(null)).isNull();
    assertThat(QueryService.inet("  ")).isNull();
  }

  @Test
  void refusesAHostnameRatherThanAskingDnsAboutItDuringAnAuditWrite() {
    assertThat(QueryService.inet("db.internal.example.com")).isNull();
  }

  @Test
  void dropsRubbishInsteadOfLosingTheRowItBelongsTo() {
    assertThat(QueryService.inet("not-an-address")).isNull();
    assertThat(QueryService.inet("999.999.999.999")).isNull();
  }
}
