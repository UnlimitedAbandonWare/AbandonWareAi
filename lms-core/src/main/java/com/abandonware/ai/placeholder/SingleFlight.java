package com.abandonware.ai.placeholder;

import java.util.Map;
import java.util.concurrent.*;

/** Minimal single-flight cache (in-memory) for demo/testing. */
public final class SingleFlight<K,V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> inflight = new ConcurrentHashMap<>();
    public V run(K key, Callable<V> task, long timeoutMs) throws Exception {
        CompletableFuture<V> f = inflight.computeIfAbsent(key, k ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        try {
                            return task.call();
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    } finally {
                        inflight.remove(k);
                    }
                }));
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            f.completeExceptionally(te);
            inflight.remove(key);
            throw te;
        }
    }
}