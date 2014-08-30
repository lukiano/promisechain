package com.lucho.promisechain;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class PromiseChain<K> {
  public enum Type { SHARED, EXCLUSIVE }

  public static <K> PromiseChain<K> create() {
    return new DefaultPromiseChain<>(new ConcurrentHashMap<>());
  }

  @ParametersAreNonnullByDefault
  public abstract <A> CompletableFuture<A> enqueueOn(K key, Type type, Supplier<CompletionStage<A>> block);

  public final <A, B> Function<A, CompletableFuture<B>> enqueue(@Nonnull final K key,
                                                      @Nonnull final Type type,
                                                      @Nonnull final Function<A, CompletionStage<B>> f) {
    return a -> enqueueOn(key, type, () -> f.apply(a));
  }
}
