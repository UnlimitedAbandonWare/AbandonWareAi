package com.example.infra.cache;

import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;



@Component
public class SingleFlightExecutor {
    private final ConcurrentMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private SingleFlightProps props;
    public SingleFlightExecutor(){ this.props = new SingleFlightProps(true, 800); }

    @SuppressWarnings("unchecked")
    public <T> T run(String key, Callable<T> supplier) throws Exception {
        if (!props.enabled()) return supplier.call();
        CompletableFuture<Object> cf = inFlight.computeIfAbsent(key, k ->
            CompletableFuture.supplyAsync(() -> {
                try { return supplier.call(); }
                catch (Exception e){ throw new CompletionException(e); }
            })
        );
        try {
            Object v = cf.get(props.maxWaitMs(), TimeUnit.MILLISECONDS);
            return (T) v;
        } finally {
            inFlight.remove(key, cf);
        }
    }

    public record SingleFlightProps(boolean enabled, long maxWaitMs) { }
}