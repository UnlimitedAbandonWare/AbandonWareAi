package com.example.lms.infra.upstash;

import com.example.lms.search.TraceStore;
import com.example.lms.service.web.WebResultCache;
import com.example.lms.trace.SafeRedactor;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.github.benmanes.caffeine.cache.Cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Two-tier web cache: local Caffeine first, optional Upstash Redis second.
 * This implementation is small and self-contained to prevent compile issues.
 */
@Component
public class UpstashBackedWebCache implements WebResultCache {

    private final Cache<String, String> local;
    private final UpstashRedisClient upstash;

    @Value("${upstash.cache.ttl-seconds:600}")
    private int defaultTtlSeconds;

    @Value("${upstash.cache.timeout-ms:250}")
    private long timeoutMs = 250;

    @Value("${upstash.cache.remote-enabled:${UPSTASH_REDIS_ENABLED:true}}")
    private boolean remoteEnabled = true;

    @Autowired
    public UpstashBackedWebCache(Cache<String,String> webLocalCache, UpstashRedisClient upstash) {
        this.local = webLocalCache;
        this.upstash = upstash;
    }

    @Override
    public Mono<Optional<String>> get(String key) {
        com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
        var trace = TraceStore.context();
        long began = System.nanoTime();
        try {
            String v = local.getIfPresent(key);
            if (v != null) {
                traceCacheResult(trace, "local", true, began);
                return Mono.just(Optional.of(v));
            }
        } catch (RuntimeException ex) {
            traceSuppressed("getFallback", ex);
        }
        var budget = com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        if (!remoteEnabled) {
            traceCacheResult(trace, "disabled", false, began);
            return Mono.just(Optional.empty());
        }
        return Mono.defer(() -> {
            long wait = waitMillis(budget);
            if (wait <= 0) {
                traceRemoteFallback(trace, "get", "deadline_exhausted", began);
                return Mono.just(Optional.<String>empty());
            }
            return upstash.get(key).timeout(Duration.ofMillis(wait))
                .map(Optional::ofNullable)
                .defaultIfEmpty(Optional.empty())
                .doOnNext(opt -> traceCacheResult(trace, "redis", opt.isPresent(), began))
                .doOnNext(opt -> opt.ifPresent(val -> {
                    try { local.put(key, val); } catch (RuntimeException ex) { traceSuppressed("fillFallback", ex); }
                }))
                .onErrorResume(error -> {
                    if (com.example.lms.llm.gateway.LlmGatewayFailureClassifier.isCancellation(error)) return Mono.error(error);
                    traceRemoteFallback(trace, "get", error instanceof java.util.concurrent.TimeoutException ? "timeout" : "unavailable", began);
                    return Mono.just(Optional.empty());
                });
        });
    }

    @Override
    public Mono<Void> put(String key, String json, Duration ttl) {
        com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
        try { local.put(key, json); } catch (RuntimeException ex) { traceSuppressed("putFallback", ex); }
        if (!remoteEnabled) return Mono.empty();
        Duration useTtl = (ttl == null || ttl.isZero() || ttl.isNegative())
                ? Duration.ofSeconds(defaultTtlSeconds)
                : ttl;
        var trace = TraceStore.context();
        var budget = com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        long began = System.nanoTime();
        return Mono.defer(() -> {
            long wait = waitMillis(budget);
            if (wait <= 0) {
                traceRemoteFallback(trace, "put", "deadline_exhausted", began);
                return Mono.empty();
            }
            return upstash.setEx(key, json, useTtl).timeout(Duration.ofMillis(wait))
                .doOnNext(saved -> { if (!saved) traceRemoteFallback(trace, "put", "unavailable", began); })
                .then().onErrorResume(error -> {
                    if (com.example.lms.llm.gateway.LlmGatewayFailureClassifier.isCancellation(error)) return Mono.error(error);
                    traceRemoteFallback(trace, "put", error instanceof java.util.concurrent.TimeoutException ? "timeout" : "unavailable", began);
                    return Mono.empty();
                });
        });
    }

    private long waitMillis(com.abandonware.ai.addons.budget.TimeBudget budget) {
        long configured = Math.max(1, Math.min(2_000, timeoutMs));
        return budget == null ? configured : Math.min(configured, budget.remainingMillis());
    }

    private static void traceCacheResult(java.util.Map<String, Object> trace, String tier, boolean hit, long began) {
        trace.put("web.cache.tier", tier);
        trace.put("web.cache.result", hit ? "hit" : "miss");
        trace.put("web.cache.latencyMs", Math.max(0, (System.nanoTime() - began) / 1_000_000));
        trace.compute("web.cache." + (hit ? "hitCount" : "missCount"),
                (key, count) -> count instanceof Number n ? n.longValue() + 1 : 1L);
        org.slf4j.LoggerFactory.getLogger("rag.pipeline").debug(
                "[rag-pipeline] stage=web-cache tier={} result={} elapsedMs={}", tier, hit ? "hit" : "miss",
                trace.get("web.cache.latencyMs"));
    }

    private static void traceRemoteFallback(java.util.Map<String, Object> trace, String operation, String reason, long began) {
        trace.put("web.cache.selectedRoute", "get".equals(operation) ? "cache_miss" : "local_cache");
        trace.put("web.cache.failureReason", reason);
        trace.put("web.cache.operation", operation);
        trace.put("web.cache.latencyMs", Math.max(0, (System.nanoTime() - began) / 1_000_000));
        trace.compute("web.cache.fallbackCount", (key, count) -> count instanceof Number n ? n.longValue() + 1 : 1L);
        org.slf4j.LoggerFactory.getLogger(UpstashBackedWebCache.class).debug(
                "[web-cache] fallback operation={} reason={} retryCount=0", operation, reason);
    }

    private static void traceLocalFallback(String stage, RuntimeException ex) {
        String prefix = "web.cache.local." + stage;
        TraceStore.inc(prefix + ".count");
        TraceStore.put(prefix + ".errorType", errorType(ex));
    }

    private static String errorType(Throwable ex) {
        if (ex instanceof NumberFormatException) {
            return "invalid_number";
        }
        return ex == null ? "unknown" : SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown");
    }

    private static void traceSuppressed(String stage, RuntimeException ex) {
        traceLocalFallback(stage, ex);
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("web.cache.suppressed.stage", safeStage);
        TraceStore.put("web.cache.suppressed.errorType", errorType(ex));
        TraceStore.put("web.cache.suppressed." + safeStage, true);
    }
}
