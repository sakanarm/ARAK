package com.mfec.dac.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SandboxCatalogTest {

  private static final String SHAPE = "CREATE TABLE \"sales\".\"customer\" (\"id\" integer);\n";

  @Test
  @DisplayName("The same shape always gets the same database; a different one gets another")
  void shapeNameIsStable() {
    String name = SandboxCatalog.shapeName("demo-pg", SHAPE);
    assertThat(SandboxCatalog.shapeName("demo-pg", SHAPE)).isEqualTo(name);
    assertThat(SandboxCatalog.shapeName("demo-pg", SHAPE + "-- more\n")).isNotEqualTo(name);
    assertThat(name).startsWith("demo-pg~").hasSize("demo-pg~".length() + 16);
  }

  @Test
  @DisplayName("A long source name is cut so the shape's name fits in 63 bytes")
  void longSourceNameFits() {
    String ascii = "s".repeat(80);
    String thai = "ข".repeat(40);
    assertThat(SandboxCatalog.shapeName(ascii, SHAPE).getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(63);
    assertThat(SandboxCatalog.shapeName(thai, SHAPE).getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(63);
  }

  @Test
  @DisplayName("Only this source's shapes are taken for its own when tidying")
  void shapeOfItsSourceOnly() {
    String prefix = SandboxCatalog.shapePrefix("demo-pg");
    assertThat(SandboxCatalog.isShapeOf(SandboxCatalog.shapeName("demo-pg", SHAPE), prefix)).isTrue();
    assertThat(SandboxCatalog.isShapeOf(SandboxCatalog.shapeName("demo-pg~x", SHAPE), prefix)).isFalse();
    assertThat(SandboxCatalog.isShapeOf(SandboxCatalog.shapeName("demo-pg-proxy", SHAPE), prefix)).isFalse();
    assertThat(SandboxCatalog.isShapeOf("demo-pg", prefix)).isFalse();
    assertThat(SandboxCatalog.isShapeOf("demo-pg~not-a-hash-at-all", prefix)).isFalse();
  }
}
