package com.abandonware.ai.addons.cache;

import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.Logger;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.addons.cache.SingleFlightRegistry
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.addons.cache.SingleFlightRegistry
role: config
*/
public class SingleFlightRegistry {
    private static final Logger log = Logger.getLogger(SingleFlightRegistry.class.getName());
    private final ConcurrentHashMap<String, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "single-flight-evict");
        t.setDaemon(true); return t;
    });

    public <T> CompletableFuture<T> run(String key, Supplier<CompletableFuture<T>> supplier, long ttlMs) {
        @SuppressWarnings("unchecked")
        CompletableFuture<T> cf = (CompletableFuture<T>) inFlight.computeIfAbsent(key, k -> {
            CompletableFuture<T> created = supplier.get();
            created.whenComplete((r, ex) -> scheduleEvict(k, ttlMs));
            return created;
        });
        return cf;
    }

    private void scheduleEvict(String key, long ttlMs) {
        scheduler.schedule(() -> {
            inFlight.remove(key);
            log.fine(() -> "single-flight evicted: " + key);
        }, Math.max(1, ttlMs), TimeUnit.MILLISECONDS);
    }
}