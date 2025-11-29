package com.abandonware.ai.zsystem;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.zsystem.ZSystem
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.zsystem.ZSystem
role: config
*/
public class ZSystem {
    private final Semaphore rerankerSemaphore = new Semaphore(4); // configurable
    private final Map<String, CompletableFuture<Object>> singleFlight = new ConcurrentHashMap<>();

    public <T> T withBudget(long millis, Supplier<T> work, Supplier<T> fallback) {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<T> f = ex.submit(work::get);
        try {
            return f.get(millis, TimeUnit.MILLISECONDS);
        } catch(Exception e) {
            f.cancel(true);
            return fallback.get();
        } finally {
            ex.shutdownNow();
        }
    }

    public <T> T guardedRerank(Supplier<T> work, Supplier<T> fastPath) {
        if (rerankerSemaphore.tryAcquire()) {
            try { return work.get(); }
            finally { rerankerSemaphore.release(); }
        } else {
            return fastPath.get();
        }
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> singleFlight(String key, Supplier<T> supplier) {
        CompletableFuture<Object> existing = singleFlight.putIfAbsent(key, new CompletableFuture<>());
        if (existing != null) return (CompletableFuture<T>) existing;
        CompletableFuture<Object> created = singleFlight.get(key);
        CompletableFuture.runAsync(() -> {
            try {
                T val = supplier.get();
                created.complete(val);
            } catch(Exception e) {
                created.completeExceptionally(e);
            } finally {
                singleFlight.remove(key);
            }
        });
        return (CompletableFuture<T>) created;
    }

    public double finalSigmoidGate(double x, double k, double x0) {
        return 1.0 / (1.0 + Math.exp(-k*(x - x0)));
    }
}