package com.lucho.promisechain;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class PromiseChainTest {

  @Test public void testEnqueuing() throws Exception {
    final PromiseChain<Integer> defaultPromiseChain = PromiseChain.create();

    final CompletableFuture<String> settableFuture1 = new CompletableFuture<>();
    final Supplier<CompletionStage<String>> supplier1 = () -> settableFuture1;

    final CompletableFuture<String> settableFuture2 = new CompletableFuture<>();
    final Supplier<CompletionStage<String>> supplier2 = () -> settableFuture2;

    final CompletableFuture<String> settableFuture3 = new CompletableFuture<>();
    final Supplier<CompletionStage<String>> supplier3 = () -> settableFuture3;

    final CompletableFuture<String> result1 = defaultPromiseChain.enqueueOn(1, PromiseChain.Type.EXCLUSIVE, supplier1);
    final CompletableFuture<String> result2 = defaultPromiseChain.enqueueOn(1, PromiseChain.Type.EXCLUSIVE, supplier2);
    final CompletableFuture<String> result3 = defaultPromiseChain.enqueueOn(1, PromiseChain.Type.EXCLUSIVE, supplier3);

    // no one is finished yet.
    Assert.assertFalse(result1.isDone());
    Assert.assertFalse(result2.isDone());
    Assert.assertFalse(result3.isDone());

    settableFuture1.complete("first");

    // first is finished. Second and third not yet.
    Assert.assertTrue(result1.isDone());
    Assert.assertEquals("first", result1.get());
    Assert.assertFalse(result2.isDone());
    Assert.assertFalse(result3.isDone());

    settableFuture3.complete("third");

    // third is "finished" but the block should not be called yet.
    Assert.assertFalse(result2.isDone());
    Assert.assertFalse(result3.isDone());

    settableFuture2.complete("second");

    // now all are finished.
    Assert.assertTrue(result2.isDone());
    Assert.assertTrue(result3.isDone());
    Assert.assertEquals("second", result2.get());
    Assert.assertEquals("third", result3.get());
  }

  private static <T> CompletableFuture<List<T>> sequence(final List<CompletableFuture<T>> futures) {
    final CompletableFuture<Void> allDoneFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));
    return allDoneFuture.thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.<T>toList()));
  }

  private <T> CompletableFuture<T> submit(final Callable<T> value, final Executor executor) {
    final CompletableFuture<T> result = new CompletableFuture<>();
    executor.execute(() -> {
      try {
        result.complete(value.call());
      } catch (final Exception e) {
        result.completeExceptionally(e);
      }
    });
    return result;
  }

  private Callable<Boolean> someExclusiveWork(final ConcurrentMap<String, Boolean> map, final String key) {
    return () -> {
      final Boolean oldValue = map.put(key, Boolean.TRUE);
      if (oldValue == null) {
        Thread.sleep(500);
        map.remove(key);
        return Boolean.TRUE;
      } else {
        return Boolean.FALSE;
      }
    };
  }

  @Test public void testExclusion() throws Exception {
    final ExecutorService executorService = java.util.concurrent.Executors.newWorkStealingPool();
    final ConcurrentMap<String, Boolean> map = new ConcurrentHashMap<>();
    final List<CompletableFuture<Boolean>> completionStages = new ArrayList<>();
    final PromiseChain<String> chain = PromiseChain.create();
    try {
      for (int i = 0; i < 30; i++) {
        final String key = Integer.toString(i % 3);
        completionStages.add(chain.enqueueOn(key, PromiseChain.Type.EXCLUSIVE, () -> submit(someExclusiveWork(map, key), executorService)));
      }
      Assert.assertTrue(sequence(completionStages).get().stream().reduce(true, (accum, b) -> accum |= b));
    } finally {
      executorService.shutdown();
    }
  }

  private <K, V> Callable<V> getValue(final ConcurrentMap<K, V> map, final K key) {
    return () -> map.get(key);
  }

  private <K, V> Callable<V> putValue(final ConcurrentMap<K, V> map, final K key, final Supplier<V> value) {
    return () -> map.put(key, value.get());
  }

  private <A> List<A> streamToList(final Stream<A> stream) {
    final List<A> list = new LinkedList<>();
    stream.forEach(list::add);
    return list;
  }

  @Test public void testSharing() throws Exception {
    final ExecutorService executorService = java.util.concurrent.Executors.newWorkStealingPool();
    final ConcurrentMap<String, Integer> map = new ConcurrentHashMap<>();
    final PromiseChain<String> chain = PromiseChain.create();
    final String key = "key";
    try {
      final CompletableFuture<Integer> firstValue = chain.enqueueOn(key, PromiseChain.Type.EXCLUSIVE, () -> submit(putValue(map, key, () -> 1), executorService));
      final CompletableFuture<List<Integer>> firstShared = sequence(streamToList(IntStream.range(1, 10).mapToObj(i -> chain.enqueueOn(key, PromiseChain.Type.SHARED, () -> submit(getValue(map, key), executorService)))));
      final CompletableFuture<Integer> secondValue = chain.enqueueOn(key, PromiseChain.Type.EXCLUSIVE, () -> submit(putValue(map, key, () -> 2), executorService));
      final CompletableFuture<List<Integer>> secondShared = sequence(streamToList(IntStream.range(1, 10).mapToObj(i -> chain.enqueueOn(key, PromiseChain.Type.SHARED, () -> submit(getValue(map, key), executorService)))));
      final CompletableFuture<Integer> thirdValue = chain.enqueueOn(key, PromiseChain.Type.EXCLUSIVE, () -> submit(putValue(map, key, () -> 3), executorService));

      Thread.sleep(1000);

      Assert.assertNull(firstValue.get());
      Assert.assertEquals(Integer.valueOf(1), secondValue.get());
      Assert.assertEquals(Integer.valueOf(2), thirdValue.get());

      firstShared.get().stream().forEach(i -> Assert.assertEquals(Integer.valueOf(1), i));
      secondShared.get().stream().forEach(i -> Assert.assertEquals(Integer.valueOf(2), i));

    } finally {
      executorService.shutdown();
    }
  }

}
