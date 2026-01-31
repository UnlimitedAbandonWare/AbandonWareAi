package com.abandonware.ai.addons.cache;

import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Single-flight registry: ensures only one concurrent computation per key.
 */
public class SingleFlightRegistry {
    private static final Logger log = Logger.getLogger(SingleFlightRegistry.class.getName());
    private final ConcurrentHashMap<String, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T getOrCompute(String key, Supplier<T> loader, long timeoutMs) {
        CompletableFuture<T> cf = (CompletableFuture<T>) inFlight.computeIfAbsent(key, k ->
            CompletableFuture.supplyAsync(loader)
        );
        try {
            return cf.get(timeoutMs <= 0 ? 0 : timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warning("single-flight timeout key=" + key);
            return null;
        } catch (Exception e) {
            log.warning("single-flight error key=" + key + " : " + e.getMessage());
            return null;
        } finally {
            inFlight.remove(key);
        }
    }
}