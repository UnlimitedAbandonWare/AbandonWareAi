package com.abandonware.patch.infra.cache;

import com.example.lms.infra.exec.ContextPropagation;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class SingleFlightExecutor {
    private final Map<String, CompletableFuture<?>> inflight = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T run(String key, Supplier<T> supplier) {
        CompletableFuture<?> f = inflight.computeIfAbsent(key,
                k -> CompletableFuture.supplyAsync(ContextPropagation.wrapSupplier(supplier)));
        try {
            return (T) f.get();
        } catch (Exception e) {
            throw new CompletionException(e);
        } finally {
            inflight.remove(key);
        }
    }
}