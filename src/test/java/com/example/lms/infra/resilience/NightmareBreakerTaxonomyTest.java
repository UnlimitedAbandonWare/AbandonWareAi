package com.example.lms.infra.resilience;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightmareBreakerTaxonomyTest {

    @AfterEach
    void tearDown() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void classifySeparatesRateLimitTimeoutCancellationAndConfig() {
        assertEquals(NightmareBreaker.FailureKind.RATE_LIMIT,
                NightmareBreaker.classify(new RuntimeException("provider rate limit exceeded")));
        assertEquals(NightmareBreaker.FailureKind.TIMEOUT,
                NightmareBreaker.classify(new TimeoutException("request timed out")));
        assertEquals(NightmareBreaker.FailureKind.INTERRUPTED,
                NightmareBreaker.classify(new CancellationException("cancelled by caller")));
        assertEquals(NightmareBreaker.FailureKind.INTERRUPTED,
                NightmareBreaker.classify(new InterruptedException("interrupted")));
        assertEquals(NightmareBreaker.FailureKind.CONFIG,
                NightmareBreaker.classify(new RuntimeException("model is required")));
    }

    @Test
    void auxBlockTrackerRejectsNonFiniteBreakerTimestamps() throws Exception {
        Method openUntil = AuxBlockTracker.class.getDeclaredMethod("resolveBreakerOpenUntilMs", String.class);
        Method openAt = AuxBlockTracker.class.getDeclaredMethod("resolveBreakerOpenAtMs", String.class);
        openUntil.setAccessible(true);
        openAt.setAccessible(true);

        TraceStore.put(NightmareBreaker.TRACE_OPEN_UNTIL_MS_KEY, Double.POSITIVE_INFINITY);
        assertEquals(null, openUntil.invoke(null, "web:naver:search"));

        TraceStore.put(NightmareBreaker.TRACE_OPEN_AT_MS_KEY,
                Map.of("web:naver:search", Double.POSITIVE_INFINITY));
        assertEquals(null, openAt.invoke(null, "web:naver:search"));
    }

    @Test
    void interruptionDoesNotOpenBreakerWhenTripOnInterruptIsDisabled() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setTripOnInterrupt(false);
        props.setInterruptThreshold(1);
        NightmareBreaker breaker = new NightmareBreaker(props);

        breaker.recordFailure("web:naver:search",
                NightmareBreaker.FailureKind.INTERRUPTED,
                new CancellationException("cancelled"),
                "cancelled request");

        NightmareBreaker.StateView state = breaker.inspect("web:naver:search");
        assertFalse(breaker.isOpen("web:naver:search"));
        assertEquals(NightmareBreaker.FailureKind.INTERRUPTED, state.lastKind);
        assertEquals(1, state.consecutiveInterrupts);
        assertEquals(0, state.consecutiveFailures);
    }

    @Test
    void rateLimitOpensWithRateLimitKindWithoutGenericFailureCount() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setRateLimitThreshold(1);
        props.setRateLimitCountsAsFailure(false);
        NightmareBreaker breaker = new NightmareBreaker(props);

        breaker.recordRateLimit("web:naver:search", "HTTP 429", "rate-limit", 2_000L);

        NightmareBreaker.StateView state = breaker.inspect("web:naver:search");
        assertTrue(breaker.isOpen("web:naver:search"));
        assertEquals(NightmareBreaker.FailureKind.RATE_LIMIT, state.lastKind);
        assertEquals(1, state.consecutiveRateLimits);
        assertEquals(0, state.consecutiveFailures);
    }

    @Test
    void webClientHttp429ClassifiesAsRateLimitWithoutGenericFailureCount() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setRateLimitThreshold(1);
        props.setRateLimitCountsAsFailure(false);
        NightmareBreaker breaker = new NightmareBreaker(props);
        WebClientResponseException http429 = WebClientResponseException.create(
                429,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);

        assertEquals(NightmareBreaker.FailureKind.RATE_LIMIT, NightmareBreaker.classify(http429));

        breaker.recordFailure("web:brave:search",
                NightmareBreaker.FailureKind.UNKNOWN,
                http429,
                "private query");

        NightmareBreaker.StateView state = breaker.inspect("web:brave:search");
        assertTrue(breaker.isOpen("web:brave:search"));
        assertEquals(NightmareBreaker.FailureKind.RATE_LIMIT, state.lastKind);
        assertEquals(1, state.consecutiveRateLimits);
        assertEquals(0, state.consecutiveFailures);
    }

    @Test
    void requestPlanCanFailSoftBypassOpenBreakerWithRedactedTrace() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setTimeoutThreshold(1);
        props.setTimeoutCountsAsFailure(false);
        NightmareBreaker breaker = new NightmareBreaker(props);
        String rawSecret = "sk-" + "nightmarefailsoftbypass1234567890";
        String rawKey = "web search Authorization Bearer " + rawSecret;

        breaker.recordFailure(rawKey,
                NightmareBreaker.FailureKind.TIMEOUT,
                new TimeoutException("timed out"),
                "query transform");

        GuardContext ctx = GuardContext.defaultContext();
        ctx.putPlanOverride("breaker.failSoft", true);
        GuardContextHolder.set(ctx);
        breaker.checkOpenOrThrow(rawKey);

        String trace = String.valueOf(TraceStore.getAll());
        assertEquals(Boolean.TRUE, TraceStore.get("nightmare.breaker.failSoft.bypassed"));
        assertTrue(trace.contains("nightmare.breaker.failSoft.key"), trace);
        assertTrue(trace.contains("hash:"), trace);
        assertFalse(trace.contains(rawKey), trace);
        assertFalse(trace.contains(rawSecret), trace);
    }

    @Test
    void timeoutUsesTimeoutCounterWithoutRateLimitCounter() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setTimeoutThreshold(1);
        props.setTimeoutCountsAsFailure(false);
        NightmareBreaker breaker = new NightmareBreaker(props);

        breaker.recordFailure("qtx:rewrite",
                NightmareBreaker.FailureKind.TIMEOUT,
                new TimeoutException("timed out"),
                "query transform");

        NightmareBreaker.StateView state = breaker.inspect("qtx:rewrite");
        assertTrue(breaker.isOpen("qtx:rewrite"));
        assertEquals(NightmareBreaker.FailureKind.TIMEOUT, state.lastKind);
        assertEquals(1, state.consecutiveTimeouts);
        assertEquals(0, state.consecutiveRateLimits);
        assertEquals(0, state.consecutiveFailures);
    }

    @Test
    void openBreakerErrorTraceUsesHashLabel() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setTimeoutThreshold(1);
        props.setTimeoutCountsAsFailure(false);
        NightmareBreaker breaker = new NightmareBreaker(props);
        String testSecret = "sk-" + "12345678901234567890";
        String raw = "timeout for private query owner_token=" + testSecret;

        breaker.recordFailure("qtx:rewrite",
                NightmareBreaker.FailureKind.TIMEOUT,
                new TimeoutException(raw),
                "query transform");

        Object msgObj = TraceStore.get(NightmareBreaker.TRACE_OPEN_ERRMSG_KEY);
        assertTrue(msgObj instanceof Map<?, ?>);
        String rendered = String.valueOf(msgObj);
        Map<?, ?> messages = (Map<?, ?>) msgObj;
        assertEquals(1, messages.size(), rendered);
        String diagnosticKey = String.valueOf(messages.keySet().iterator().next());
        String error = String.valueOf(messages.values().iterator().next());

        assertTrue(diagnosticKey.startsWith("hash:"), rendered);
        assertTrue(error.startsWith("hash:"), rendered);
        assertFalse(rendered.contains(raw));
        assertFalse(rendered.contains("private query"));
        assertFalse(rendered.contains(testSecret));
    }

    @Test
    void nightmareBreakExceptionMessageUsesCauseHashOnly() {
        String rawSecret = "sk-" + "nightmarebreakmessage1234567890";
        String rawCause = "timeout for private query owner_token=" + rawSecret;

        NightmareBreaker.NightmareBreakException ex = new NightmareBreaker.NightmareBreakException(
                NightmareBreaker.FailureKind.TIMEOUT,
                new RuntimeException(rawCause));

        String message = ex.getMessage();
        assertTrue(message.contains("NightmareBreak: TIMEOUT"), message);
        assertTrue(message.contains("errorHash="), message);
        assertTrue(message.contains("errorLength="), message);
        assertFalse(message.contains(rawCause), message);
        assertFalse(message.contains(rawSecret), message);
        assertFalse(message.contains("RuntimeException"), message);
    }

    @Test
    void openBreakerTraceKeysDoNotExposeRawBreakerKey() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setTimeoutThreshold(1);
        props.setTimeoutCountsAsFailure(false);
        NightmareBreaker breaker = new NightmareBreaker(props);
        String rawSecret = "sk-" + "nightmarebreakerrawkey1234567890";
        String rawKey = "web search Authorization Bearer " + rawSecret;

        breaker.recordFailure(rawKey,
                NightmareBreaker.FailureKind.TIMEOUT,
                new TimeoutException("timed out"),
                "query transform");

        assertTrue(breaker.isOpen(rawKey));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawKey), trace);
        assertFalse(trace.contains(rawSecret), trace);
        assertTrue(trace.contains("hash:"), trace);
    }

    @Test
    void openBreakerDebugEventsDoNotExposeRawBreakerKey() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setTimeoutThreshold(1);
        props.setTimeoutCountsAsFailure(false);
        NightmareBreaker breaker = new NightmareBreaker(props);
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", 6L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 15_000L);
        ReflectionTestUtils.setField(store, "ndjsonEnabled", false);
        ReflectionTestUtils.setField(breaker, "debugEventStore", store);
        String rawKey = "web search token=test-secret-nightmare-debug";

        breaker.recordFailure(rawKey,
                NightmareBreaker.FailureKind.TIMEOUT,
                new TimeoutException("timed out"),
                "query transform");
        NightmareBreaker.OpenCircuitException open = null;
        try {
            breaker.checkOpenOrThrow(rawKey);
        } catch (NightmareBreaker.OpenCircuitException expected) {
            TraceStore.put("test.nightmareBreaker.openCircuitCaptured", true);
            open = expected;
        }

        String debug = String.valueOf(store.list(10));
        assertFalse(debug.contains(rawKey), debug);
        assertFalse(debug.contains("test-secret-nightmare-debug"), debug);
        assertTrue(debug.contains("hash:"), debug);
        assertTrue(open != null);
        assertFalse(open.getMessage().contains(rawKey), open.getMessage());
        assertFalse(open.getMessage().contains("test-secret-nightmare-debug"), open.getMessage());
    }

    @Test
    void debugEventStoreFailureLeavesRedactedTraceBreadcrumb() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setTimeoutThreshold(1);
        props.setTimeoutCountsAsFailure(false);
        NightmareBreaker breaker = new NightmareBreaker(props);
        DebugEventStore throwingStore = new DebugEventStore() {
            @Override
            public void emit(DebugProbeType probe,
                    DebugEventLevel level,
                    String fingerprint,
                    String message,
                    String where,
                    Map<String, Object> data,
                    Throwable error) {
                throw new IllegalStateException("debug sink failed owner_token=secret-debug-event");
            }
        };
        ReflectionTestUtils.setField(breaker, "debugEventStore", throwingStore);
        String rawSecret = "sk-" + "nightmaredebugeventfailure1234567890";
        String rawKey = "debug event Authorization Bearer " + rawSecret;

        breaker.recordFailure(rawKey,
                NightmareBreaker.FailureKind.TIMEOUT,
                new TimeoutException("timed out"),
                "private query");

        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(trace.contains("nightmare.debugEvent.emit.failed"), trace);
        assertTrue(trace.contains("nightmare_debug_event_emit_failed"), trace);
        assertFalse(trace.contains("IllegalStateException"), trace);
        assertFalse(trace.contains(rawKey), trace);
        assertFalse(trace.contains(rawSecret), trace);
        assertFalse(trace.contains("secret-debug-event"), trace);
    }

    @Test
    void halfOpenInterruptTraceKeyDoesNotExposeRawBreakerKey() throws Exception {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setTimeoutThreshold(1);
        props.setTimeoutCountsAsFailure(false);
        props.setTimeoutOpenDuration(Duration.ofMillis(1));
        props.setTripOnInterrupt(false);
        NightmareBreaker breaker = new NightmareBreaker(props);
        String rawKey = "half-open token=test-secret-halfopen";

        breaker.recordFailure(rawKey,
                NightmareBreaker.FailureKind.TIMEOUT,
                new TimeoutException("timed out"),
                "query transform");
        Thread.sleep(5L);
        breaker.checkOpenOrThrow(rawKey);
        breaker.recordFailure(rawKey,
                NightmareBreaker.FailureKind.INTERRUPTED,
                new CancellationException("cancelled"),
                "cancelled request");

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawKey), trace);
        assertFalse(trace.contains("test-secret-halfopen"), trace);
        assertTrue(trace.contains("hash:"), trace);
    }

    @Test
    void silentFailureTraceKeysDoNotExposeRawBreakerKey() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setSilentFailureThreshold(2);
        NightmareBreaker breaker = new NightmareBreaker(props);
        String rawSecret = "sk-" + "nightmaresilentrawkey1234567890";
        String rawKey = "silent stage owner_token=" + rawSecret;
        String rawContext = "private context marker=nmsilentctx-48271";
        String rawReason = "private reason marker=nmsilentreason-48272";

        breaker.recordSilentFailure(rawKey, rawContext, rawReason);

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawKey), trace);
        assertFalse(trace.contains(rawSecret), trace);
        assertFalse(trace.contains(rawContext), trace);
        assertFalse(trace.contains(rawReason), trace);
        assertTrue(trace.contains("hash:"), trace);
    }

    @Test
    void rateLimitCooldownTraceKeysDoNotExposeRawBreakerKey() {
        NightmareBreaker breaker = new NightmareBreaker(new NightmareBreakerProperties());
        String rawSecret = "sk-" + "nightmareratelimitcooldownkey1234567890";
        String rawKey = "web rate Authorization Bearer " + rawSecret;

        breaker.recordRateLimit(rawKey, "private query", "provider cooldown active", 1_000L);

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawKey), trace);
        assertFalse(trace.contains(rawSecret), trace);
        assertTrue(trace.contains("hash:"), trace);
    }

    @Test
    void rateLimitDuplicateTraceKeysDoNotExposeRawBreakerKey() {
        NightmareBreakerProperties props = new NightmareBreakerProperties();
        props.setRateLimitThreshold(3);
        NightmareBreaker breaker = new NightmareBreaker(props);
        String rawSecret = "sk-" + "nightmareratelimitduplicatekey1234567890";
        String rawKey = "web duplicate owner_token=" + rawSecret;

        breaker.recordRateLimit(rawKey, "private query", "HTTP 429", 1_000L);
        breaker.recordRateLimit(rawKey, "private query", "HTTP 429 duplicate", 1_000L);

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawKey), trace);
        assertFalse(trace.contains(rawSecret), trace);
        assertTrue(trace.contains("hash:"), trace);
    }

    @Test
    void prefixScanFailurePathLeavesTraceBreadcrumbContract() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/infra/resilience/NightmareBreaker.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("tracePrefixScanFailure(prefix, e);"));
        assertTrue(source.contains("TraceStore.put(\"nightmare.prefixScan.failed\", true);"));
    }

    @Test
    void failSoftTelemetryPathsLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/infra/resilience/NightmareBreaker.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"nightmare.failSoftBypass\", ignored);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.prefixScan\", e);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.rateLimitCooldownTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.rateLimitOnceTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.rateLimitDuplicateTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.permitDuplicateTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.externalSignalTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.permitOpenEvent\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.stateSignal\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.legacySuccessTrace\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"nightmare.suppressed.\" + safeStage, true);"));
    }

    @Test
    void tailFailSoftTelemetryPathsLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/infra/resilience/NightmareBreaker.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"nightmare.safeToMs\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.backoffPow\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.executeOpenCircuitFallback\", oce);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.executeFailureFallback\", t);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.executeRateLimitReason\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.openAtTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.openMetaTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.classifyClassName\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.retryAfterExtract\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.retryAfterSeconds\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"nightmare.retryAfterDate\", ignore);"));
    }

    @Test
    void diagnosticsDoNotWriteRawContextOrThrowableMessages() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/infra/resilience/NightmareBreaker.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("context={}"));
        assertFalse(source.contains("\" context=\" + clip(context, cfg.maxContextChars())"));
        assertFalse(source.contains("String.valueOf(error.getMessage())"));
        assertFalse(source.contains("String.valueOf(s.lastError.getMessage())"));
        assertFalse(source.contains("\": \" + cause.getMessage()"));
        assertFalse(source.contains("TraceStore.putIfAbsent(\"nightmare.rateLimit.cooldown.reason.\" + k, reason);"));
        assertFalse(source.contains("TraceStore.put(\"nightmare.rateLimit.dup.lastReason.\" + k, reason);"));
        assertFalse(source.contains("\"nightmare.rateLimit.cooldown.\" + k"));
        assertFalse(source.contains("\"nightmare.rateLimit.once.\" + k"));
        assertFalse(source.contains("\"nightmare.rateLimit.dup.\" + k"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(error), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(s.lastError), 180)"));

        assertTrue(source.contains("event.put(\"ctxLen\", context == null ? 0 : context.length());"));
        assertTrue(source.contains("next.lastErrorSummary = redactedErrorSummary(error);"));
        assertTrue(source.contains("return error.getClass().getSimpleName() + \":\" + SafeRedactor.hashValue(error.getMessage());"));
        assertTrue(source.contains("recordOpenMetaForTrace(base, gateState.lastKind, gateState.lastErrorSummary);"));
        assertTrue(source.contains("String traceKey = safeBreakerKey(k);"));
        assertFalse(source.contains("TraceStore.putIfAbsent(\"nightmare.rateLimit.cooldown.reason.\" + traceKey, SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(source.contains("TraceStore.put(\"nightmare.rateLimit.dup.lastReason.\" + traceKey, SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains("TraceStore.putIfAbsent(\"nightmare.rateLimit.cooldown.reason.\" + traceKey, SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(source.contains("TraceStore.put(\"nightmare.rateLimit.dup.lastReason.\" + traceKey, SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertFalse(source.contains("ev.put(\"reason\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(source.contains("dd.put(\"reason\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(source.contains("\"silent-failure reason=\" + SafeRedactor.safeMessage(reason, 120)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(reason, 120),"));
        assertFalse(source.contains("String safeReason = SafeRedactor.safeMessage(reason, 120);"));
        assertTrue(source.contains("private static String safeExternalSignalReason(String reason)"));
        assertTrue(source.contains("default -> SafeRedactor.hashValue(value);"));
        assertTrue(source.contains("event.put(\"reason\", safeExternalSignalReason(reason));"));
    }
}
