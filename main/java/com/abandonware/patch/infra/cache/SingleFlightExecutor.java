package com.abandonware.patch.infra.cache;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class SingleFlightExecutor {
    private final Map<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();
    @SuppressWarnings("unchecked")
    public <T> T run(String key, Supplier<T> supplier) {
        CompletableFuture<Object> f = inflight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(supplier::get));
        try { return (T) f.get(); }
        catch (Exception e) { throw new CompletionException(e); }
        finally { inflight.remove(key); }
    }
}