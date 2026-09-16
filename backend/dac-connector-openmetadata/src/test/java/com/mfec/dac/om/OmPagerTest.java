package com.mfec.dac.om;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.om.client.ApiException;
import com.mfec.dac.om.client.model.Paging;
import com.mfec.dac.om.client.model.Table;
import com.mfec.dac.om.client.model.TableList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OmPagerTest {

  private static TableList page(String after, String... names) {
    List<Table> rows = new ArrayList<>();
    for (String name : names) {
      rows.add(new Table().name(name));
    }
    return new TableList().data(rows).paging(new Paging().after(after));
  }

  private static List<String> namesIn(List<Table> tables) {
    return tables.stream().map(Table::getName).toList();
  }

  @Test
  @DisplayName("follows the cursor until the server stops handing one back")
  void followsTheCursor() throws ApiException {
    List<String> collected = new ArrayList<>();
    List<String> cursorsSent = new ArrayList<>();

    int seen =
        OmPager.forEachPage(
            (limit, after) -> {
              cursorsSent.add(after);
              return switch (after == null ? "" : after) {
                case "" -> page("c1", "a", "b");
                case "c1" -> page("c2", "c");
                default -> page(null, "d");
              };
            },
            TableList::getData,
            TableList::getPaging,
            2,
            rows -> collected.addAll(namesIn(rows)));

    assertThat(collected).containsExactly("a", "b", "c", "d");
    assertThat(cursorsSent).containsExactly(null, "c1", "c2");
    assertThat(seen).isEqualTo(4);
  }

  @Test
  @DisplayName("stops rather than spinning when the cursor repeats")
  void stopsOnARepeatedCursor() throws ApiException {
    List<String> collected = new ArrayList<>();

    // Without the guard this request pages for ever, and a stuck crawl looks
    // exactly like a slow one until someone reads the row count.
    int seen =
        OmPager.forEachPage(
            (limit, after) -> page("stuck", "a"),
            TableList::getData,
            TableList::getPaging,
            2,
            rows -> collected.addAll(namesIn(rows)));

    assertThat(collected).containsExactly("a", "a");
    assertThat(seen).isEqualTo(2);
  }

  @Test
  @DisplayName("treats a blank cursor as the end, not as another page")
  void blankCursorEnds() throws ApiException {
    List<String> collected = new ArrayList<>();

    OmPager.forEachPage(
        (limit, after) -> page("", "only"),
        TableList::getData,
        TableList::getPaging,
        2,
        rows -> collected.addAll(namesIn(rows)));

    assertThat(collected).containsExactly("only");
  }

  @Test
  @DisplayName("survives an empty collection")
  void handlesNoRows() throws ApiException {
    int seen =
        OmPager.forEachPage(
            (limit, after) -> page(null),
            TableList::getData,
            TableList::getPaging,
            2,
            rows -> {
              throw new AssertionError("should not be called for an empty page");
            });

    assertThat(seen).isZero();
  }

  @Test
  @DisplayName("collect gathers every page for the small governance collections")
  void collectsEverything() throws ApiException {
    List<Table> all =
        OmPager.collect(
            (limit, after) -> after == null ? page("c1", "a") : page(null, "b"),
            TableList::getData,
            TableList::getPaging);

    assertThat(namesIn(all)).containsExactly("a", "b");
  }
}
