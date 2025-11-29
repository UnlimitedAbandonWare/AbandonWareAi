package strategy.infra.upstash;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** Minimal single-flight registry to deduplicate concurrent loads per key. */
public class SingleFlightRegistry<T> {
  private final ConcurrentHashMap<String, CompletableFuture<T>> inflight = new ConcurrentHashMap<>();
  public T getOrLoad(String key, Supplier<T> loader) {
    CompletableFuture<T> fut = inflight.computeIfAbsent(key, k ->
      CompletableFuture.supplyAsync(loader)
    );
    try { return fut.get(); }
    catch (Exception e) { throw new RuntimeException(e); }
    finally { inflight.remove(key); }
  }
}