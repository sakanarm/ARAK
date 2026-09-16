package com.mfec.dac.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mfec.dac.om.events.CatalogChange;
import com.mfec.dac.om.events.ChangeEventReader;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the poller does with its cursor, which is the only piece of state it owns
 * and the only way it can lose a change.
 *
 * <p>The cursor says "everything before this is in the cache". Moving it past a
 * change that did not apply makes that a lie, and nothing notices until the
 * nightly reconcile — which on a PII tag is a night of an unmasked column.
 */
class ChangeEventPollerTest {

  private static final long HIGH_WATER = 1_758_000_000_000L;

  private ChangeEventReader reader;
  private CatalogChangeApplier applier;
  private SyncStateDao syncState;
  private ChangeEventPoller poller;

  @BeforeEach
  void setUp() throws Exception {
    reader = mock(ChangeEventReader.class);
    applier = mock(CatalogChangeApplier.class);
    syncState = mock(SyncStateDao.class);

    when(syncState.find(anyString())).thenReturn(Optional.empty());
    when(reader.read(anyLong()))
        .thenReturn(new ChangeEventReader.Batch(List.of(change()), HIGH_WATER, 1, 0));

    poller = new ChangeEventPoller(reader, applier, syncState, Duration.ofSeconds(60));
  }

  @Test
  void advancesTheCursorWhenEverythingApplied() throws Exception {
    when(applier.apply(any())).thenReturn(new CatalogChangeApplier.Outcome(1, 0, 0, 0));

    poller.pollOnce();

    verify(syncState).eventCursor(CatalogSyncService.SOURCE, HIGH_WATER);
  }

  @Test
  void holdsTheCursorWhileAChangeIsFailing() throws Exception {
    // OpenMetadata restarting, or the app database briefly gone. The next tick
    // reads the same window again, and re-reading an entity by FQN is free.
    when(applier.apply(any())).thenReturn(new CatalogChangeApplier.Outcome(0, 0, 0, 1));

    poller.pollOnce();

    verify(syncState, never()).eventCursor(anyString(), anyLong());
  }

  @Test
  void givesUpHoldingRatherThanRereadingAGrowingWindowForever() throws Exception {
    // A change that will never apply must not wedge the cursor: the window it
    // re-reads grows on every tick. After the hold is spent the cursor moves and
    // the nightly reconcile owns what was left behind.
    when(applier.apply(any())).thenReturn(new CatalogChangeApplier.Outcome(0, 0, 0, 1));

    for (int poll = 0; poll < 11; poll++) {
      poller.pollOnce();
    }

    verify(syncState, times(1)).eventCursor(CatalogSyncService.SOURCE, HIGH_WATER);
  }

  @Test
  void skipsThePollWhileAFullCrawlIsRunning() throws Exception {
    // The crawl's closing sweep retires anything not carrying its stamp, and a
    // refresh underneath it writes a different one.
    when(syncState.find(anyString()))
        .thenReturn(
            Optional.of(
                new SyncStateDao.SyncState(
                    CatalogSyncService.SOURCE, null, null, null, SyncStateDao.RUNNING, null, null)));

    assertEmpty(poller.pollOnce());
    verify(reader, never()).read(anyLong());
  }

  @Test
  void doesNothingWhenTheFeedIsQuiet() throws Exception {
    when(reader.read(anyLong())).thenReturn(new ChangeEventReader.Batch(List.of(), 0L, 0, 0));

    assertEmpty(poller.pollOnce());
    verify(applier, never()).apply(any());
    verify(syncState, never()).eventCursor(anyString(), anyLong());
  }

  @Test
  void resumesAdvancingOnceTheFailureClears() throws Exception {
    when(applier.apply(any()))
        .thenReturn(new CatalogChangeApplier.Outcome(0, 0, 0, 1))
        .thenReturn(new CatalogChangeApplier.Outcome(1, 0, 0, 0));

    poller.pollOnce();
    poller.pollOnce();

    verify(syncState).eventCursor(eq(CatalogSyncService.SOURCE), eq(HIGH_WATER));
  }

  private static void assertEmpty(Optional<CatalogChangeApplier.Outcome> outcome) {
    org.assertj.core.api.Assertions.assertThat(outcome).isEmpty();
  }

  private static CatalogChange change() {
    return new CatalogChange(
        CatalogChange.Subject.TABLE,
        CatalogChange.Kind.UPSERTED,
        "table",
        UUID.randomUUID(),
        "prod-mssql.SalesDB.dbo.customer",
        HIGH_WATER);
  }
}
