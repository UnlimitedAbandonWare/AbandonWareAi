package com.abandonware.ai.service.infra.cache;

import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;

public class SingleFlight<K,V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    public CompletableFuture<V> computeIfAbsent(K key, Supplier<CompletableFuture<V>> loader) {
        return inFlight.computeIfAbsent(key, k -> loader.get())
            .whenComplete((v, e) -> inFlight.remove(key));
    }
}