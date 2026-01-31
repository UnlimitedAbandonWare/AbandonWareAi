package com.abandonware.ai.agent.integrations.infra.cache;

import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
public final class SingleFlightExecutor<K,V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> inflight = new ConcurrentHashMap<>();
    public CompletableFuture<V> run(K key, Supplier<CompletableFuture<V>> task) {
        return inflight.computeIfAbsent(key, k -> task.get().whenComplete((v,t) -> inflight.remove(k)));
    }
}