package com.mfec.dac.om;

import com.mfec.dac.om.client.ApiException;
import com.mfec.dac.om.client.model.Paging;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks an OpenMetadata list endpoint to the end of its cursor.
 *
 * <p>Every {@code /api/v1/*} collection pages the same way — {@code limit} plus
 * an opaque {@code after} taken from the previous response — but the generated
 * client gives each one its own unrelated list type. So the shape is supplied
 * here by two accessors rather than by an interface nothing implements.
 *
 * <p>Pages are handed to the caller one at a time. A full crawl of a hundred
 * thousand tables must not be assembled in memory first, and {@link #collect}
 * exists for the governance collections, which are small enough to hold and
 * awkward to use any other way.
 */
public final class OmPager {

  private static final Logger LOG = LoggerFactory.getLogger(OmPager.class);

  /** OpenMetadata's own cap is 1000; this leaves room without risking a reject. */
  public static final int DEFAULT_PAGE_SIZE = 500;

  private OmPager() {}

  /** One call to a list endpoint. */
  @FunctionalInterface
  public interface PageRequest<R> {
    R fetch(int limit, String after) throws ApiException;
  }

  /**
   * Reads every page, handing each to {@code consumer}.
   *
   * @param request  the list call, already bound to its own filters
   * @param data     pulls the page's rows out of the response
   * @param paging   pulls the cursor out of the response
   * @param pageSize rows per call
   * @return how many rows were seen in total
   */
  public static <R, T> int forEachPage(
      PageRequest<R> request,
      Function<R, List<T>> data,
      Function<R, Paging> paging,
      int pageSize,
      Consumer<List<T>> consumer)
      throws ApiException {

    int seen = 0;
    String after = null;
    // A server that echoes the cursor back unchanged would otherwise spin here
    // forever, re-reading one page and looking, from the outside, like a slow
    // crawl rather than a stuck one.
    String previousCursor = null;

    while (true) {
      R response = request.fetch(pageSize, after);
      if (response == null) {
        return seen;
      }
      List<T> rows = data.apply(response);
      if (rows != null && !rows.isEmpty()) {
        seen += rows.size();
        consumer.accept(rows);
      }
      Paging page = paging.apply(response);
      after = page == null ? null : page.getAfter();
      if (after == null || after.isBlank()) {
        return seen;
      }
      if (after.equals(previousCursor)) {
        LOG.warn("OpenMetadata returned the same page cursor twice; stopping after {} rows.", seen);
        return seen;
      }
      previousCursor = after;
    }
  }

  /** As {@link #forEachPage}, with the default page size. */
  public static <R, T> int forEachPage(
      PageRequest<R> request,
      Function<R, List<T>> data,
      Function<R, Paging> paging,
      Consumer<List<T>> consumer)
      throws ApiException {
    return forEachPage(request, data, paging, DEFAULT_PAGE_SIZE, consumer);
  }

  /**
   * Reads every page into one list.
   *
   * <p>For the governance collections — classifications, glossaries, domains —
   * which number in the hundreds. Do not use it for tables.
   */
  public static <R, T> List<T> collect(
      PageRequest<R> request, Function<R, List<T>> data, Function<R, Paging> paging)
      throws ApiException {
    List<T> all = new ArrayList<>();
    forEachPage(request, data, paging, DEFAULT_PAGE_SIZE, all::addAll);
    return all;
  }
}
