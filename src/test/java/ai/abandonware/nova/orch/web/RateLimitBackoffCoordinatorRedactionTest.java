package ai.abandonware.nova.orch.web;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitBackoffCoordinatorRedactionTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void arbitraryProviderNameDoesNotBecomeRawTraceKeySegment() {
        String rawProvider = "ownertoken-" + "sk-" + "12345678901234567890";
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(new MockEnvironment());

        backoff.recordRateLimited(rawProvider, 1_000L, "rate_limit");

        String rendered = String.valueOf(TraceStore.getAll()).toLowerCase(Locale.ROOT);
        assertFalse(rendered.contains(rawProvider.toLowerCase(Locale.ROOT)), rendered);
        assertFalse(rendered.contains("ownertoken"), rendered);
        assertFalse(rendered.contains("sk-" + "12345678901234567890"), rendered);
        assertTrue(rendered.contains("web.failsoft.ratelimitbackoff.hash_"), rendered);
    }

    @Test
    void decisionReasonDoesNotExposeFreeFormBackoffReason() {
        String privateReason = "private student query timeout retry";
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(new MockEnvironment());

        backoff.recordRateLimited(RateLimitBackoffCoordinator.PROVIDER_NAVER, 1_000L, privateReason);
        RateLimitBackoffCoordinator.Decision decision = backoff.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_NAVER);

        assertTrue(decision.shouldSkip());
        assertFalse(decision.reason().contains(privateReason), decision.reason());
        assertFalse(decision.reason().contains("private student"), decision.reason());
        assertFalse(decision.reason().contains("timeout retry"), decision.reason());
        assertTrue(decision.reason().startsWith("hash:"), decision.reason());
    }

    @Test
    void canonicalWebProviderConstantsCoverAllRuntimeProviders() {
        assertEquals("naver", RateLimitBackoffCoordinator.PROVIDER_NAVER);
        assertEquals("brave", RateLimitBackoffCoordinator.PROVIDER_BRAVE);
        assertEquals("serpapi", RateLimitBackoffCoordinator.PROVIDER_SERPAPI);
        assertEquals("tavily", RateLimitBackoffCoordinator.PROVIDER_TAVILY);
    }

    @Test
    void invalidNumericBackoffValueUsesStableReasonCodeWithoutRawValue() throws Exception {
        Method method = RateLimitBackoffCoordinator.class.getDeclaredMethod("toLong", Object.class);
        method.setAccessible(true);

        long value = (Long) method.invoke(null, "ownerToken-not-a-long");

        assertEquals(0L, value);
        assertEquals("toLong", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken-not-a-long"));
        assertFalse(trace.contains("NumberFormatException"));
    }

    @Test
    void nonFiniteNumericBackoffValueUsesStableReasonCode() throws Exception {
        Method method = RateLimitBackoffCoordinator.class.getDeclaredMethod("toLong", Object.class);
        method.setAccessible(true);

        long value = (Long) method.invoke(null, Double.POSITIVE_INFINITY);

        assertEquals(0L, value);
        assertEquals("toLong", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(String.valueOf(Long.MAX_VALUE)), trace);
        assertFalse(trace.contains("Infinity"), trace);
    }

    @Test
    void nonFiniteDoubleConfigFallsBackWithStableReasonCode() throws Exception {
        MockEnvironment env = new MockEnvironment()
                .withProperty("nova.orch.web.failsoft.ratelimit-backoff.jitter-ratio", "NaN");
        RateLimitBackoffCoordinator backoff = new RateLimitBackoffCoordinator(env);
        Method method = RateLimitBackoffCoordinator.class.getDeclaredMethod(
                "getDouble", double.class, String[].class);
        method.setAccessible(true);

        double value = (Double) method.invoke(backoff, 0.20d,
                new String[] { "nova.orch.web.failsoft.ratelimit-backoff.jitter-ratio" });

        assertEquals(0.20d, value);
        assertEquals("getDouble", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("web.failsoft.rateLimitBackoff.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NaN"));
    }

    @Test
    void retryAfterParseFailuresUseStableReasonCodes() throws Exception {
        Method method = RateLimitBackoffCoordinator.class.getDeclaredMethod(
                "errorType", String.class, Throwable.class);
        method.setAccessible(true);

        assertEquals("invalid_number", method.invoke(null,
                "parseRetryAfterMs.delta", new NumberFormatException("private-token")));
        assertEquals("invalid_date", method.invoke(null,
                "parseRetryAfterMs.rfc1123",
                new java.time.format.DateTimeParseException("private-token", "private-token", 0)));
        assertEquals("invalid_date", method.invoke(null,
                "parseRetryAfterMs.zoned",
                new java.time.DateTimeException("private-token")));
    }

    @Test
    void retryAfterDeltaSecondsCapsBeforeMultiplicationOverflow() {
        long parsedMillis = RateLimitBackoffCoordinator.parseRetryAfterMs(
                Long.toString(Long.MAX_VALUE),
                0L);

        assertEquals(5 * 60_000L, parsedMillis);
    }

    @Test
    void longParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/web/RateLimitBackoffCoordinator.java"))
                .replace("\r\n", "\n");

        assertFalse(source.contains("catch (Exception ignore) {\n            return 0L;\n        }"));
        assertFalse(source.contains("catch (Throwable ignore)"));
        assertFalse(source.contains("catch (Throwable ignore2)"));
        assertFalse(source.contains("catch (NumberFormatException ignore)"));
        assertFalse(source.contains("catch (DateTimeParseException ignore)"));
        assertTrue(source.contains("traceSuppressed(\"recordRateLimited.trace\", e);"));
        assertTrue(source.contains("traceSuppressed(\"recordLocalRateLimit.trace\", e);"));
        assertTrue(source.contains("traceSuppressed(\"recordFailure.awaitDisabledTrace\", e);"));
        assertTrue(source.contains("traceSuppressed(\"recordFailure.trace\", e);"));
        assertTrue(source.contains("traceSuppressed(\"hardCap.zero100Trace\", e);"));
        assertTrue(source.contains("traceSuppressed(\"toLong\", e);"));
        assertTrue(source.contains("traceSuppressed(\"extractTimeoutHintMs\", e);"));
        assertTrue(source.contains("traceSuppressed(\"parseRetryAfterMs.delta\", e);"));
        assertTrue(source.contains("traceSuppressed(\"parseRetryAfterMs.rfc1123\", e);"));
        assertTrue(source.contains("traceSuppressed(\"parseRetryAfterMs.zoned\", e2);"));
        assertTrue(source.contains("traceSuppressed(\"getLong\", e);"));
        assertTrue(source.contains("traceSuppressed(\"getDouble\", e);"));
        assertTrue(source.contains("MDC.put(\"web.failsoft.rateLimitBackoff.suppressed.stage\", stage);"));
        assertFalse(source.contains("e.getMessage()"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "authored,2500,10000,2500", "short,200,10000,200",
            "masked_authored,2500,200,200", "masked_short,200,200,200"})
    void shippedZero100BackoffCapChangesActualCooldownAdmission(
            String control, long rawCap, long staticCap, long expectedDelay) throws Throwable {
        TraceStore.clear();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/zero100.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        String key = "search.zero100.backoffHardCapMs";
        assertEquals(2500L, original.path("params").path(key).asLong());
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put(key, rawCap);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                .set(key, original.path("params").path(key));
        assertEquals(original, restored, "only the raw backoff cap changes within each static-cap pair");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/zero100.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "zero100.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var ctx = new com.example.lms.service.guard.GuardContext();
        applier.applyToGuardContext(applier.load("zero100.v1"), ctx);
        assertEquals(rawCap, ctx.planLong(key, -1L));
        var props = new ai.abandonware.nova.config.Zero100EngineProperties();
        props.setEngineEnabled(true);
        var registry = new ai.abandonware.nova.orch.zero100.Zero100SessionRegistry(props);
        var env = new MockEnvironment()
                .withProperty("nova.orch.web.failsoft.ratelimit-backoff.min-ms", "200")
                .withProperty("nova.orch.web.failsoft.ratelimit-backoff.max-ms", "10000")
                .withProperty("nova.orch.web.failsoft.ratelimit-backoff.hard-cap-ms", Long.toString(staticCap))
                .withProperty("nova.orch.web.failsoft.ratelimit-backoff.jitter-ratio", "0");
        var aspect = new ai.abandonware.nova.orch.aop.Zero100SessionAspect(props, registry, env);
        var coordinator = new RateLimitBackoffCoordinator(env);
        String provider = RateLimitBackoffCoordinator.PROVIDER_NAVER;
        var pjp = org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[]{"bounded backoff fixture"});
        org.mockito.Mockito.when(pjp.proceed()).thenAnswer(invocation -> {
            assertEquals(Boolean.TRUE, TraceStore.get("zero100.enabled"));
            assertEquals("CALIBRATE", TraceStore.get("zero100.phase"));
            assertEquals(rawCap, TraceStore.get("zero100.backoff.hardCapMs"));
            var before = coordinator.shouldSkip(provider);
            assertFalse(before.shouldSkip());
            assertEquals(0L, before.remainingMs());
            long startedMs = System.currentTimeMillis();
            coordinator.recordRateLimited(provider, 5000L, "rate_limit");
            var decision = coordinator.shouldSkip(provider);
            long finishedMs = System.currentTimeMillis();
            long observationWindowMs = finishedMs - startedMs;
            assertTrue(observationWindowMs >= 0L && observationWindowMs < 100L,
                    "observe immediate admission before the shortest cooldown can expire");
            assertTrue(decision.shouldSkip());
            assertTrue(decision.remainingMs() >= expectedDelay - observationWindowMs
                    && decision.remainingMs() <= expectedDelay, "actual remaining cooldown is bounded by the measured window");
            String prefix = "web.failsoft.rateLimitBackoff.naver.last.";
            assertEquals(expectedDelay, TraceStore.get(prefix + "capMs"));
            assertEquals(expectedDelay, TraceStore.get(prefix + "delayMs"));
            assertEquals(0L, TraceStore.get(prefix + "jitterMs"));
            assertEquals(1, TraceStore.get(prefix + "streak"));
            assertFalse(coordinator.shouldSkip(RateLimitBackoffCoordinator.PROVIDER_BRAVE).shouldSkip(),
                    "a fresh provider must retain independent admission");
            System.out.printf("TBL07_BACKOFF control=%s rawCap=%d staticCap=%d chosenDelay=%d remainingMs=%d observationWindowMs=%d beforeSkip=false afterSkip=%s otherProviderSkip=false externalRequests=0%n",
                    control, rawCap, staticCap, expectedDelay, decision.remainingMs(), observationWindowMs, decision.shouldSkip());
            return decision;
        });
        com.example.lms.service.guard.GuardContextHolder.set(ctx);
        try {
            var result = (RateLimitBackoffCoordinator.Decision) aspect.aroundChatEntry(pjp);
            assertTrue(result.shouldSkip());
            org.mockito.Mockito.verify(pjp, org.mockito.Mockito.times(1)).proceed();
        } finally {
            com.example.lms.service.guard.GuardContextHolder.clear();
            TraceStore.clear();
        }
    }
}
