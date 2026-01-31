package com.abandonware.ai.normalization.infra.concurrency;

import java.util.concurrent.*;
import java.util.*;
import java.util.function.Supplier;

/** Single-flight execution to collapse concurrent duplicate work. */
public class SingleFlightExecutor<T> {
    private final ConcurrentHashMap<String, CompletableFuture<T>> inflight = new ConcurrentHashMap<>();
    public CompletableFuture<T> run(String key, Supplier<T> supplier) {
        return inflight.computeIfAbsent(key, k ->
            CompletableFuture.supplyAsync(() -> supplier.get())
                .whenComplete((r, ex) -> inflight.remove(k))
        );
    }
}