
package com.example.lms.resilience;

import java.util.Map;
import java.util.concurrent.*;



public class SingleFlightManager {
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    public <T> T run(String key, Callable<T> task) throws Exception {
        CompletableFuture<Object> fut = inflight.computeIfAbsent(key, k -> {
            CompletableFuture<Object> f = new CompletableFuture<>();
            Executors.newSingleThreadExecutor().submit(() -> {
                try {
                    f.complete(task.call());
                } catch (Exception e) {
                    f.completeExceptionally(e);
                } finally {
                    inflight.remove(k);
                }
            });
            return f;
        });
        try {
            @SuppressWarnings("unchecked")
            T t = (T) fut.get();
            return t;
        } catch (ExecutionException ee) {
            throw new Exception(ee.getCause());
        }
    }
}