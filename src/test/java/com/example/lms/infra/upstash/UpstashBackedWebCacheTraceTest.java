package com.example.lms.infra.upstash;

import com.example.lms.search.TraceStore;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpstashBackedWebCacheTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void localGetFailureFallsBackToRemoteWithTypeOnlyTrace() {
        Cache<String, String> local = mock(Cache.class);
        UpstashRedisClient upstash = mock(UpstashRedisClient.class);
        when(local.getIfPresent("cache-key")).thenThrow(new IllegalStateException("raw local token"));
        when(upstash.get("cache-key")).thenReturn(Mono.just("remote-json"));

        UpstashBackedWebCache cache = new UpstashBackedWebCache(local, upstash);

        Optional<String> value = cache.get("cache-key").block();

        assertEquals(Optional.of("remote-json"), value);
        assertEquals(1L, TraceStore.get("web.cache.local.getFallback.count"));
        assertEquals("IllegalStateException", TraceStore.get("web.cache.local.getFallback.errorType"));
        assertEquals("getFallback", TraceStore.get("web.cache.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("web.cache.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw local token"));
    }

    @Test
    void localPutFailureStillWritesRemoteWithTypeOnlyTrace() {
        Cache<String, String> local = mock(Cache.class);
        UpstashRedisClient upstash = mock(UpstashRedisClient.class);
        doThrow(new IllegalArgumentException("raw put token")).when(local).put(eq("cache-key"), eq("json"));
        when(upstash.setEx(eq("cache-key"), eq("json"), any(Duration.class))).thenReturn(Mono.just(true));

        UpstashBackedWebCache cache = new UpstashBackedWebCache(local, upstash);

        cache.put("cache-key", "json", Duration.ofSeconds(3)).block();

        assertEquals(1L, TraceStore.get("web.cache.local.putFallback.count"));
        assertEquals("IllegalArgumentException", TraceStore.get("web.cache.local.putFallback.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw put token"));
    }

    @Test
    void localNumericFailureUsesStableErrorType() {
        Cache<String, String> local = mock(Cache.class);
        UpstashRedisClient upstash = mock(UpstashRedisClient.class);
        when(local.getIfPresent("cache-key")).thenThrow(new NumberFormatException("raw numeric token"));
        when(upstash.get("cache-key")).thenReturn(Mono.just("remote-json"));

        UpstashBackedWebCache cache = new UpstashBackedWebCache(local, upstash);

        Optional<String> value = cache.get("cache-key").block();

        assertEquals(Optional.of("remote-json"), value);
        assertEquals(1L, TraceStore.get("web.cache.local.getFallback.count"));
        assertEquals("invalid_number", TraceStore.get("web.cache.local.getFallback.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw numeric token"));
    }

    @Test
    void localFallbackFailuresLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/infra/upstash/UpstashBackedWebCache.java"));

        assertTrue(source.contains("traceSuppressed(\"getFallback\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"fillFallback\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"putFallback\", ex);"));
        assertTrue(source.contains("TraceStore.put(\"web.cache.suppressed.\" + safeStage, true);"));
    }
}
