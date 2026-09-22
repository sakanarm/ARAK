package com.mfec.dac.common;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A store saying out loud that it changed something.
 *
 * <p>This exists for one reason: the decision cache has to be emptied when the
 * inputs to a decision move, and the only places that know those inputs moved
 * are the stores that moved them. Wiring the cache into each store directly
 * would make four unrelated classes depend on a cache they have no business
 * knowing about; wiring the invalidation into the REST resources instead would
 * work until the first write arrived by some other road — a webhook, a poller,
 * a nightly reconcile — and then it would be wrong silently, which is the worst
 * way for an access-control cache to be wrong.
 *
 * <p>So the store publishes and does not care who listens. With nobody
 * listening, {@link #fire} is a no-op over an empty list, which is what every
 * test that builds a store on its own gets.
 *
 * <p>Delivery is synchronous and on the writing thread. That is deliberate: by
 * the time a write returns to its caller, the cache is already empty, so there
 * is no window in which the write has committed and a stale decision is still
 * being served.
 */
public final class ChangeNotifier {

  private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

  /** @param listener receives a short human-readable reason, for the log */
  public void listen(Consumer<String> listener) {
    if (listener != null) {
      listeners.add(listener);
    }
  }

  /**
   * Announces a change.
   *
   * <p>A listener that throws must not take the write down with it: the write
   * has already committed, and failing the request afterwards would tell the
   * caller their change did not happen when it did. The failure is swallowed
   * here and absorbed by the cache's own time-to-live, which is the backstop
   * for exactly this case.
   */
  public void fire(String reason) {
    for (Consumer<String> listener : listeners) {
      try {
        listener.accept(reason);
      } catch (RuntimeException ignored) {
        // See above. Nothing here may fail a committed write.
      }
    }
  }
}
