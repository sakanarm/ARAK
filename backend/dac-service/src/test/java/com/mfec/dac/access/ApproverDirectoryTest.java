package com.mfec.dac.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How a data steward or custodian custom property names people, in every shape OpenMetadata sends. */
class ApproverDirectoryTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static List<String> refs(String json) throws Exception {
    JsonNode node = json == null ? null : JSON.readTree(json);
    return ApproverDirectory.references(node).stream().map(r -> r[0] + ":" + r[1]).toList();
  }

  @Test
  void aPlainStringIsBareNamesSplitOnCommas() throws Exception {
    assertThat(refs("\" ann , Finance,, \"")).containsExactly("null:ann", "null:Finance");
  }

  @Test
  void anEntityReferenceKeepsItsType() throws Exception {
    assertThat(refs("{\"type\":\"User\",\"name\":\"ann\"}")).containsExactly("user:ann");
    assertThat(refs("{\"type\":\"team\",\"name\":\"Finance\"}")).containsExactly("team:Finance");
    // Anything else is a bare name, looked up as a person first.
    assertThat(refs("{\"type\":\"domain\",\"name\":\"Sales\"}")).containsExactly("null:Sales");
  }

  @Test
  void theFullNameStandsInForAMissingName() throws Exception {
    assertThat(refs("{\"type\":\"user\",\"fullyQualifiedName\":\"ann\"}")).containsExactly("user:ann");
    assertThat(refs("{\"type\":\"user\"}")).isEmpty();
  }

  @Test
  void aListMixesShapes() throws Exception {
    assertThat(refs("[\"ann\", {\"type\":\"team\",\"name\":\"Finance\"}, [\"bob\"], null, 7]"))
        .containsExactly("null:ann", "team:Finance", "null:bob");
  }

  @Test
  void nothingNamesNobody() throws Exception {
    assertThat(refs(null)).isEmpty();
    assertThat(refs("null")).isEmpty();
    assertThat(refs("\"  \"")).isEmpty();
    assertThat(refs("42")).isEmpty();
  }
}
