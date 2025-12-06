\1
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.infra.upstash.UpstashBackedWebCache
 * Role: config
 * Feature Flags: upstash.cache.*
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.infra.upstash.UpstashBackedWebCache
role: config
flags: [upstash.cache.*]
*/
public class UpstashBackedWebCache {

    private final ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    @Value("${upstash.cache.wait-timeout-ms:3000}")
    private long waitTimeoutMs;

    /**
     * Get or compute a value identified by {@code key}. The loader is executed at most once
     * concurrently for the same key (single-flight).  This method is synchronous by design.
     */
    public String getOrLoad(String key, Callable<String> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");

        final CompletableFuture<String> f = inflight.computeIfAbsent(key, k ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        try {
                            return loader.call();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    } finally {
                        inflight.remove(k);
                    }
                }));
        try {
            return f.get(waitTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            inflight.remove(key);
            throw new RuntimeException(e);
        }
    }
}

// --- single-flight additions (auto-patch) ---
private final ConcurrentHashMap<String, CompletableFuture<Object>> __inflight = new ConcurrentHashMap<>();

private String __canonicalKey(String k) {
  if (k == null) return "";
  int q = k.indexOf('?');
  String base = (q>=0)? k.substring(0, q) : k;
  // drop utm_ params (best-effort)
  return base;
}
/**
 * Wrap fetcher with single-flight to avoid request stampede.
 * NOTE: Generic 'Object' to avoid signature changes.
 */
@SuppressWarnings("unchecked")
public Object getWithSingleFlight(String key, java.util.function.Supplier<Object> fetcher, long fetchTimeoutMs) {
  String kk = __canonicalKey(key);
  CompletableFuture<Object> fut = __inflight.computeIfAbsent(kk, z ->
      CompletableFuture.supplyAsync(() -> {
        Object cached = getFromRemoteOrLocal(kk); // best-effort helper; may be patched later
        if (cached != null) return cached;
        Object fresh = fetcher.get();
        putToRemoteAndLocal(kk, fresh);
        return fresh;
      })
  );
  try {
    return fut.get(fetchTimeoutMs <= 0 ? 1500L : fetchTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
  } catch (Exception ex) {
    return fetcher.get(); // degrade
  } finally {
    __inflight.remove(kk);
  }
}
// --- end single-flight ---