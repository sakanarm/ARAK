package com.mfec.dac.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.llm.HelpDocs.Section;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HelpDocs — the user guide the assistant answers from")
class HelpDocsTest {

  private static final String GUIDE =
      """
      # Example guide

      What this guide is for.

      ## Asking for access

      Open the table and press Request access. Say why and until when.

      ## Masking functions

      A masked column shows a partial value. The strictest mask wins.

      ```
      ## not a heading, inside a fence
      ```

      ### Hashing

      A hash keeps joins working.

      ## Query page

      Run a SELECT. Access is checked on every run.
      """;

  private static final String THAI =
      """
      # Policy ชนกัน แล้วใครชนะ?

      ## ตัวอย่างที่ 3 — DENY ตัวเดียวจบ

      DENY ชนะทุกอย่าง
      """;

  private static HelpDocs docs() {
    List<Section> all = new ArrayList<>(HelpDocs.parse("user-guide.md", GUIDE));
    all.addAll(HelpDocs.parse("policy-conflict-resolution.md", THAI));
    return new HelpDocs(all);
  }

  @Test
  void aFileIsCutAtItsSecondLevelHeadings() {
    List<Section> sections = HelpDocs.parse("user-guide.md", GUIDE);

    assertThat(sections)
        .extracting(Section::heading)
        .containsExactly("Example guide", "Asking for access", "Masking functions", "Query page");
    assertThat(sections.get(0).document()).isEqualTo("Example guide (user-guide.md)");
    assertThat(sections.get(0).text()).isEqualTo("What this guide is for.");
    // A deeper heading, and a heading inside a code fence, stay in their section.
    assertThat(sections.get(2).text())
        .contains("## not a heading, inside a fence")
        .contains("### Hashing")
        .contains("A hash keeps joins working.");
  }

  @Test
  void theSectionAboutTheQuestionComesFirst() {
    String answer = docs().search("how do I request access to a table?");

    assertThat(answer)
        .startsWith("From Example guide (user-guide.md), section \"Asking for access\":")
        .contains("Say why and until when.");
    // Only sections holding a keyword come back.
    assertThat(answer).doesNotContain("Masking functions");
  }

  @Test
  void aKeywordFindsTheWordsItIsPartOf() {
    assertThat(docs().search("mask")).contains("section \"Masking functions\"");
  }

  @Test
  void aThaiWordIsFoundInThaiTextWithoutSpaces() {
    assertThat(docs().search("ชนะ"))
        .contains("Policy ชนกัน แล้วใครชนะ? (policy-conflict-resolution.md)")
        .contains("DENY ชนะทุกอย่าง");
  }

  @Test
  void onlyStopWordsOrShortWordsAreRefused() {
    assertThat(docs().search("how do I")).startsWith("error:");
    assertThat(docs().search(null)).startsWith("error:");
    assertThat(HelpDocs.terms("How does ARAK mask a column?")).containsExactly("mask", "column");
    assertThat(HelpDocs.terms("masked columns approves access"))
        .containsExactly("mask", "column", "approv", "access");
    assertThat(HelpDocs.terms("change restore")).containsExactly("chang", "restor");
    assertThat(HelpDocs.terms("policies policy tags denied")).containsExactly("polic", "tag", "deni");
  }

  @Test
  void aRareKeywordOutweighsACommonOne() {
    List<Section> sections = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      sections.add(
          new Section("guide", "Policies " + i, "You can change it. Change it again. Change is audited."));
    }
    sections.add(new Section("guide", "Your account", "Change your password on the Profile page."));

    assertThat(new HelpDocs(sections).search("change password"))
        .startsWith("From guide, section \"Your account\"");
  }

  @Test
  void nothingFoundListsTheGuideHeadingsToTryInstead() {
    String answer = docs().search("firewall");

    assertThat(answer).startsWith("Nothing in the ARAK documentation matches firewall.");
    assertThat(answer).contains("- Asking for access").contains("- Query page");
    // The other documents' headings are not offered as the guide's.
    assertThat(answer).doesNotContain("DENY ตัวเดียวจบ");
  }

  @Test
  void noMoreThanThreeSectionsComeBack() {
    List<Section> many = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      many.add(new Section("guide", "Grants " + i, "A grant ends on its end date."));
    }

    assertThat(new HelpDocs(many).search("grant").split("From guide")).hasSize(1 + 3);
  }

  @Test
  void withoutTheDocumentationItSaysSo() {
    assertThat(new HelpDocs(List.of()).search("grant")).startsWith("error: the documentation");
  }

  @Test
  void theBuildPacksTheGuideAndBothPolicyDocuments() {
    List<Section> bundled = HelpDocs.bundled().sections();

    assertThat(bundled).extracting(Section::document).anyMatch(d -> d.endsWith("(user-guide.md)"));
    assertThat(bundled)
        .extracting(Section::document)
        .anyMatch(d -> d.endsWith("(policy-conflict-resolution.md)"));
    assertThat(bundled).extracting(Section::document).anyMatch(d -> d.endsWith("(policy-spec.md)"));
    assertThat(HelpDocs.bundled().search("column description"))
        .contains("section \"A table's page\"");
    assertThat(HelpDocs.bundled().search("new classification"))
        .contains("section \"Governance vocabulary and local tags\"");
  }
}
