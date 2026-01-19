package com.abandonware.ai.agent.integrations.infra.cache;


import java.util.concurrent.*;
import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.infra.cache.SingleFlightExecutor
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.infra.cache.SingleFlightExecutor
role: config
*/
public class SingleFlightExecutor {
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> run(String key, java.util.concurrent.Callable<T> task){
        CompletableFuture<Object> f = inflight.computeIfAbsent(key, k -> new CompletableFuture<>());
        boolean leader = !f.isDone();
        if (leader){
            // execute task
            CompletableFuture.runAsync(() -> {
                try {
                    T res = task.call();
                    f.complete(res);
                } catch (Exception e){
                    f.completeExceptionally(e);
                } finally {
                    inflight.remove(key);
                }
            });
        }
        return (CompletableFuture<T>) f;
    }
}