package infra.cache;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;

public class SingleFlightCache<K, V> {
  private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

  public V getOrCompute(K key, Callable<V> loader) {
    CompletableFuture<V> candidate = new CompletableFuture<>();
    CompletableFuture<V> existing = inFlight.putIfAbsent(key, candidate);
    CompletableFuture<V> fut = existing == null ? candidate : existing;
    if (existing == null) {
      try {
        fut.complete(loader.call());
      } catch (Exception e) {
        traceSuppressed("loader", key, e);
        fut.completeExceptionally(e);
      } finally {
        inFlight.remove(key, candidate);
      }
    }
    try {
      return fut.get();
    } catch (Exception e) {
      traceSuppressedIfAbsent("await", key, e);
      throw new RuntimeException(e);
    }
  }

  private static void traceSuppressed(String stage, Object key, Throwable failure) {
    String safeStage = stage == null || stage.isBlank() ? "unknown" : stage;
    String keyText = String.valueOf(key);
    TraceStore.put("infra.singleFlight.suppressed.stage", safeStage);
    TraceStore.put("infra.singleFlight.suppressed.errorType",
        failure == null ? "unknown" : failure.getClass().getSimpleName());
    TraceStore.put("infra.singleFlight.suppressed." + safeStage, true);
    TraceStore.put("infra.singleFlight.suppressed." + safeStage + ".errorType",
        failure == null ? "unknown" : failure.getClass().getSimpleName());
    TraceStore.put("infra.singleFlight.suppressed.keyLength", keyText.length());
    TraceStore.put("infra.singleFlight.suppressed.keyHash", SafeRedactor.hashValue(keyText));
  }

  private static void traceSuppressedIfAbsent(String stage, Object key, Throwable failure) {
    if (TraceStore.get("infra.singleFlight.suppressed.stage") == null) {
      traceSuppressed(stage, key, failure);
    }
  }
}
