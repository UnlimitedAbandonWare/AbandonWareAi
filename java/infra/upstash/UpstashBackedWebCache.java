package infra.upstash;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Single-Flight wrapper for remote web cache calls.
 * This is a minimal, self-contained addition to avoid build errors.
 */
public class UpstashBackedWebCache {

    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
    private final int ttlSeconds = 600;

    public String get(String key, Supplier<String> remoteFetcher) {
        // Coalesce identical keys
        CompletableFuture<String> fut = inFlight.computeIfAbsent(key, k ->
            CompletableFuture.supplyAsync(() -> {
                try {
                    return remoteFetcher.get();
                } finally {
                    inFlight.remove(k);
                }
            })
        );
        try {
            return fut.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            inFlight.remove(key);
            return remoteFetcher.get();
        }
    }

    public String cacheKey(String method, String url, String params, String headersSubset) {
        String base = method + "|" + url + "|" + (params == null ? "" : params) + "|" + (headersSubset == null ? "" : headersSubset);
        return Integer.toHexString(base.hashCode());
    }
}