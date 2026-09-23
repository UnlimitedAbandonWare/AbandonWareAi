package com.abandonware.ai.infra.upstash;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
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