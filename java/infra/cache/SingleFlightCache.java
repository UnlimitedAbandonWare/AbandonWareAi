package infra.cache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;

public class SingleFlightCache<K,V> {
  private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

  public V getOrCompute(K key, Callable<V> loader) {
    CompletableFuture<V> fut = inFlight.computeIfAbsent(key, k -> new CompletableFuture<>());
    if (!fut.isDone()) {
      try { fut.complete(loader.call()); }
      catch (Exception e){ fut.completeExceptionally(e); }
      finally { inFlight.remove(key); }
    }
    try { return fut.get(); } catch(Exception e){ throw new RuntimeException(e); }
  }
}