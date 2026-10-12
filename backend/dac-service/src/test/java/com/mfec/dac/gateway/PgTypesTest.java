package com.mfec.dac.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PgTypesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {"0", "0.00", "1", "-1", "0.0001", "123.4500", "12345678.9", "-98765.4321", "100000", "NaN"})
  @DisplayName("A numeric survives the binary layout both ways")
  void numericRoundTrip(String value) throws PgError {
    assertThat(PgTypes.decodeBinary(PgTypes.NUMERIC, PgTypes.numeric(value))).isEqualTo(value);
  }

  @Test
  @DisplayName("A numeric written with an exponent comes back plain")
  void numericExponent() throws PgError {
    assertThat(PgTypes.decodeBinary(PgTypes.NUMERIC, PgTypes.numeric("1E+5"))).isEqualTo("100000");
  }

  @Test
  @DisplayName("Reads fixed-width binary parameters")
  void fixedWidth() throws PgError {
    assertThat(PgTypes.decodeBinary(PgTypes.INT4, ByteBuffer.allocate(4).putInt(-7).array())).isEqualTo("-7");
    assertThat(PgTypes.decodeBinary(PgTypes.INT8, ByteBuffer.allocate(8).putLong(1L << 40).array()))
        .isEqualTo(Long.toString(1L << 40));
    assertThat(PgTypes.decodeBinary(PgTypes.BOOL, new byte[] {1})).isEqualTo("true");
    assertThat(PgTypes.decodeBinary(PgTypes.DATE, ByteBuffer.allocate(4).putInt(0).array())).isEqualTo("2000-01-01");
  }

  @Test
  @DisplayName("A binary parameter too short for its type is a protocol error, not a crash")
  void tooShort() {
    assertThatThrownBy(() -> PgTypes.decodeBinary(PgTypes.INT8, new byte[] {1, 2}))
        .isInstanceOf(PgError.class)
        .extracting(e -> ((PgError) e).sqlState())
        .isEqualTo(PgError.PROTOCOL_VIOLATION);
  }

  @Test
  @DisplayName("A type with no binary reader here asks the client for text")
  void unsupportedBinary() {
    assertThatThrownBy(() -> PgTypes.decodeBinary(PgTypes.INTERVAL, new byte[16]))
        .isInstanceOf(PgError.class)
        .extracting(e -> ((PgError) e).sqlState())
        .isEqualTo(PgError.FEATURE_NOT_SUPPORTED);
  }
}
