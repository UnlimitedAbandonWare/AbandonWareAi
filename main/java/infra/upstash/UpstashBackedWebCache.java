package infra.upstash;

import java.util.concurrent.*;

/**
 * Standalone single-flight guard usable outside Spring packages.
 */
public class UpstashBackedWebCache {

    private final ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    public String singleFlight(String key, java.util.concurrent.Callable<String> loader) {
        CompletableFuture<String> fut = inflight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
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
            return fut.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            inflight.remove(key);
            throw new RuntimeException(e);
        }
    }
}