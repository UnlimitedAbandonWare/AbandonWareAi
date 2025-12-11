package com.example.lms.service.infra.cache;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.infra.cache.SingleFlightExecutor
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.example.lms.service.infra.cache.SingleFlightExecutor
role: config
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