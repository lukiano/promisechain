package com.lucho.promisechain;


import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Main access class. Holds the queue in a map.
 *
 * @param <K> Key type.
 */
@ThreadSafe final class DefaultPromiseChain<K> extends PromiseChain<K> {

  DefaultPromiseChain(@Nonnull final ConcurrentMap<K, Entry<K, ?>> map) {
    this.map = map;
  }

  private final ConcurrentMap<K, Entry<K, ?>> map;

  /**
   * Enqueue the promise on the key queue to be executed after the last promise is done,
   * or now if there are no current processes.
   *
   * @param key   the key to lock in.
   * @param block a block of code that returns a promise of a result.
   * @param <A>   The type of the result that the promise will return.
   * @return a new promise that will be fulfilled some time in the future with the result of the block of code.
   */
  @Override public <A> CompletableFuture<A> enqueueOn(@Nonnull final K key,
                                            @Nonnull final Type type,
                                            @Nonnull final Supplier<CompletionStage<A>> block) {
    // Remove from the map after the promise completes if it is the last one.
    final Entry<K, A> entry = new Entry<>(key, type, Optional.of(map));
    // Atomically set this callback as the latest one on the queue.
    final Entry<K, ?> previousEntry = map.put(key, entry);
    if (previousEntry == null) {
      // No one is running anything on this key. Run this block and then update the handler with the result.
      block.get().whenComplete(entry.callback);
      return entry.future;
    } else
      if (previousEntry.type == Type.SHARED && entry.type == Type.SHARED) {
        final Entry<K, A> barrier = new Entry<>(key, type, Optional.empty());
        previousEntry.future.runAfterBoth(barrier.future, () -> entry.future.complete(null));
        block.get().whenComplete(barrier.callback);
        return barrier.future;
      } else {
        // Enqueue this block to be run after the current block is done.
        // Run it on the same thread and then update the handler with the result.
        previousEntry.future.thenRun(() -> block.get().whenComplete(entry.callback));
        return entry.future;
      }
  }

  private static final class Entry<K, A> {
    public final Type type;
    public final CompletableFuture<A> future = new CompletableFuture<>();
    public final BiConsumer<A, Throwable> callback = (a, t) -> {
      if (t == null) {
        future.complete(a);
      } else {
        future.completeExceptionally(t);
      }
    };
    public Entry(final K key, final Type type, final Optional<ConcurrentMap<K, Entry<K, ?>>> map) {
      this.type = type;
      map.ifPresent( m -> this.future.thenRun(() -> m.remove(key, this)) );
    }


  }

}

