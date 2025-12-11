package com.example.lms.infra.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * A helper class for single‑flight execution.  Only one concurrent
 * invocation per key is allowed at a time across the cluster.  Other
 * callers will await the result of the in‑flight computation instead of
 * performing duplicate work.  Once the computation completes the
 * result is cached in memory until the next call.
 */
@Component
@RequiredArgsConstructor
public class SingleFlightExecutor {
    private final StringRedisTemplate redis;
    /** Tracks in‑flight computations keyed by the single‑flight key. */
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> local = new ConcurrentHashMap<>();

    /**
     * Execute or await a computation keyed by {@code key}.  The
     * computation is executed only when no other thread or process is
     * currently computing the same key.  Remote callers coordinate
     * using a Redis set‑nx lock to ensure cluster‑wide mutual exclusion.
     * Once the computation finishes the lock is released and the
     * cached result is returned to all waiting callers.
     *
     * @param key the single‑flight key
     * @param call the supplier performing the work
     * @param ttl the maximum time to hold the distributed lock
     * @param ser the serializer used to encode/decode the result
     * @return the computed result
     */
    public <T> T run(String key, Supplier<T> call, Duration ttl, Serializer<T> ser) {
        CompletableFuture<byte[]> cf = local.computeIfAbsent(key, k -> {
            String lockKey = "sf:" + k;
            Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", ttl);
            if (Boolean.TRUE.equals(locked)) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return ser.serialize(call.get());
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }).whenComplete((r,e) -> {
                    redis.delete(lockKey);
                    local.remove(k);
                });
            }
            // if another process holds the lock, wait on the local future
            return local.get(k);
        });
        try {
            byte[] bytes = cf.get();
            return ser.deserialize(bytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Simple serializer abstraction.  Allows callers to define
     * custom (de)serialisation logic for their result types.
     */
    public interface Serializer<T> {
        byte[] serialize(T v);
        T deserialize(byte[] bytes);
    }
}