package com.mfec.dac.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.identity.PrincipalQuery.AttributeFilter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@code attr} parameter, which is the whole surface between a URL somebody
 * types and a query that reaches the identity cache.
 *
 * <p>Worth its own test because the parsing is where a filter goes quietly
 * wrong: a condition read as the wrong key, or dropped, changes who the
 * directory says a subject rule would match, and nobody checks a list of people
 * against the SQL that produced it.
 */
class AttributeFilterTest {

  @Nested
  @DisplayName("One condition")
  class One {

    @Test
    @DisplayName("a bare key asks who carries it at all")
    void bareKey() {
      assertThat(AttributeFilter.parse("clearance"))
          .isEqualTo(new AttributeFilter("clearance", null));
    }

    @Test
    @DisplayName("key=value asks for that exact value")
    void keyValue() {
      assertThat(AttributeFilter.parse("department=FINANCE"))
          .isEqualTo(new AttributeFilter("department", "FINANCE"));
    }

    @Test
    @DisplayName("surrounding space is not part of either half")
    void trims() {
      assertThat(AttributeFilter.parse("  department = FINANCE  "))
          .isEqualTo(new AttributeFilter("department", "FINANCE"));
    }

    @Test
    @DisplayName("a value may contain = itself; only the first one splits")
    void splitsOnce() {
      assertThat(AttributeFilter.parse("costCentre=a=b"))
          .isEqualTo(new AttributeFilter("costCentre", "a=b"));
    }

    @Test
    @DisplayName("a trailing = reads as the key alone, not as the empty value")
    void trailingEquals() {
      // The other reading would match nobody, silently, which looks the same as
      // "nobody carries this attribute" — the answer the author came for.
      assertThat(AttributeFilter.parse("clearance="))
          .isEqualTo(new AttributeFilter("clearance", null));
    }

    @Test
    @DisplayName("nothing to read gives nothing back")
    void unreadable() {
      assertThat(AttributeFilter.parse(null)).isNull();
      assertThat(AttributeFilter.parse("")).isNull();
      assertThat(AttributeFilter.parse("   ")).isNull();
      assertThat(AttributeFilter.parse("=FINANCE")).isNull();
    }
  }

  @Nested
  @DisplayName("The list of them")
  class Many {

    @Test
    @DisplayName("conditions keep the order they were given in")
    void keepsOrder() {
      assertThat(AttributeFilter.parseAll(List.of("department=FINANCE", "clearance=L2")))
          .containsExactly(
              new AttributeFilter("department", "FINANCE"),
              new AttributeFilter("clearance", "L2"));
    }

    @Test
    @DisplayName("nothing given is not an error, just no narrowing")
    void empty() {
      assertThat(AttributeFilter.parseAll(null)).isEmpty();
      assertThat(AttributeFilter.parseAll(List.of())).isEmpty();
    }

    @Test
    @DisplayName("an unreadable entry is skipped, not fatal")
    void skipsJunk() {
      assertThat(AttributeFilter.parseAll(Arrays.asList("department=FINANCE", "", "=x", null)))
          .containsExactly(new AttributeFilter("department", "FINANCE"));
    }

    @Test
    @DisplayName("the same condition twice is one condition")
    void deduplicates() {
      // Repeating it would AND a clause with itself — same answer, longer query.
      assertThat(AttributeFilter.parseAll(List.of("clearance=L2", "clearance=L2")))
          .containsExactly(new AttributeFilter("clearance", "L2"));
    }

    @Test
    @DisplayName("two values of one key stay two conditions")
    void sameKeyDifferentValues() {
      // Not a contradiction: an attribute is multi-valued, so this asks for
      // somebody holding both L1 and L2, which the EXISTS clauses answer.
      assertThat(AttributeFilter.parseAll(List.of("clearance=L1", "clearance=L2")))
          .containsExactly(
              new AttributeFilter("clearance", "L1"), new AttributeFilter("clearance", "L2"));
    }

    @Test
    @DisplayName("a URL cannot compose an unbounded number of joins")
    void capped() {
      List<String> tooMany =
          IntStream.range(0, AttributeFilter.MAX + 5).mapToObj(i -> "k" + i).toList();
      assertThat(AttributeFilter.parseAll(tooMany)).hasSize(AttributeFilter.MAX);
    }
  }
}
