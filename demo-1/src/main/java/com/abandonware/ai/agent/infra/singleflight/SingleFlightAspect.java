package com.abandonware.ai.agent.infra.singleflight;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;

@Aspect
@Component
@RequiredArgsConstructor
public class SingleFlightAspect {

    private final ConcurrentMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    @Around("@annotation(sf)")
    public Object around(ProceedingJoinPoint pjp, SingleFlight sf) throws Throwable {
        String key = sf.value();
        CompletableFuture<Object> existing = inflight.putIfAbsent(key, new CompletableFuture<>());
        if (existing != null) {
            try {
                return existing.get(sf.ttlMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        CompletableFuture<Object> current = inflight.get(key);
        try {
            Object result = pjp.proceed();
            current.complete(result);
            return result;
        } catch (Throwable t) {
            current.completeExceptionally(t);
            throw t;
        } finally {
            inflight.remove(key);
        }
    }
}