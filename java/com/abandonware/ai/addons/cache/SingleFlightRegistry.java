package com.abandonware.ai.addons.cache;

import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.Logger;



/** 동일 키의 캐시 미스를 in-proc 단일 비행으로 병합 */
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