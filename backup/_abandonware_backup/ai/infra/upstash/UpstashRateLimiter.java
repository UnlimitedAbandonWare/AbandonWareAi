package com.abandonware.ai.infra.upstash;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.infra.upstash.UpstashRateLimiter
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.infra.upstash.UpstashRateLimiter
role: config
*/
public class UpstashRateLimiter {
    @Value("${naver.search.rate.per-second:5}")
    private int permitsPerSecond;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public synchronized boolean tryAcquire(String key) {
        long sec = Instant.now().getEpochSecond();
        Window w = windows.computeIfAbsent(key, k -> new Window(sec, 0));
        if (w.second != sec) { w.second = sec; w.count = 0; }
        if (w.count < permitsPerSecond) { w.count++; return true; }
        return false;
    }

    static class Window { long second; int count; Window(long s, int c){{second=s;count=c;}} }
}