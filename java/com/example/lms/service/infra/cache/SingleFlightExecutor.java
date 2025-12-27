package com.example.lms.service.infra.cache;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Collapses concurrent calls with the same key into a single in-flight call (single-flight).
 * Thread-safe and GC-friendly: entries are removed when the underlying future completes.
 */
public class SingleFlightExecutor {

    private final ConcurrentHashMap<String, CompletableFuture<?>> flights = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> run(String key, Supplier<CompletableFuture<T>> supplier) {
        return (CompletableFuture<T>) flights.computeIfAbsent(key, k -> {
            CompletableFuture<T> f = supplier.get();
            f.whenComplete((r, e) -> flights.remove(k));
            return f;
        });
    }
}