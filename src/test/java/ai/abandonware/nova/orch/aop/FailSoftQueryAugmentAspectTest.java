package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaWebFailSoftProperties;
import ai.abandonware.nova.orch.web.RuleBasedQueryAugmenter;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FailSoftQueryAugmentAspectTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void queryAugmentTraceStoresSampleHashesOnly() throws Throwable {
        String rawQuery = "Gemini API quota private-codename-raw-owner-token";
        GuardContext context = new GuardContext();
        context.setAuxDegraded(true);
        GuardContextHolder.set(context);

        FailSoftQueryAugmentAspect aspect = new FailSoftQueryAugmentAspect(
                new MockEnvironment(),
                new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties()));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{List.of(rawQuery)});
        when(pjp.proceed(any(Object[].class))).thenReturn("ok");

        Object result = aspect.aroundRetrieveAll(pjp);

        assertEquals("ok", result);
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("private-codename-raw-owner-token"), trace);
        assertTrue(String.valueOf(TraceStore.get("orch.failsoft.queryAugment.sampleHashes")).contains("hash:"));
        assertEquals(3, TraceStore.get("orch.failsoft.queryAugment.sampleCount"));
    }

    @Test
    void qtxBypassTraceActivatesQueryAugmentWithoutRawTrace() throws Throwable {
        String rawQuery = "private qtx bypass query ownerToken=raw-secret";
        TraceStore.put("qtx.bypass", true);

        FailSoftQueryAugmentAspect aspect = new FailSoftQueryAugmentAspect(
                new MockEnvironment(),
                new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties()));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{List.of(rawQuery)});
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            Object[] newArgs = invocation.getArgument(0);
            List<?> queries = (List<?>) newArgs[0];
            assertTrue(queries.size() > 1);
            return "ok";
        });

        Object result = aspect.aroundRetrieveAll(pjp);

        assertEquals("ok", result);
        assertEquals(Boolean.TRUE, TraceStore.get("orch.failsoft.queryAugment.used"));
        assertEquals("qt_degraded", TraceStore.get("orch.failsoft.queryAugment.reason"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("raw-secret"), trace);
        assertTrue(String.valueOf(TraceStore.get("orch.failsoft.queryAugment.sampleHashes")).contains("hash:"));
    }

    @Test
    void canonicalQueryTransformerBypassedTraceActivatesQueryAugmentWithoutRawTrace() throws Throwable {
        String rawQuery = "private canonical qtx bypass query ownerToken=raw-secret";
        TraceStore.put("queryTransformer.bypassed", true);

        FailSoftQueryAugmentAspect aspect = new FailSoftQueryAugmentAspect(
                new MockEnvironment(),
                new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties()));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{List.of(rawQuery)});
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            Object[] newArgs = invocation.getArgument(0);
            List<?> queries = (List<?>) newArgs[0];
            assertTrue(queries.size() > 1);
            return "ok";
        });

        Object result = aspect.aroundRetrieveAll(pjp);

        assertEquals("ok", result);
        assertEquals(Boolean.TRUE, TraceStore.get("orch.failsoft.queryAugment.used"));
        assertEquals("qt_degraded", TraceStore.get("orch.failsoft.queryAugment.reason"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("raw-secret"), trace);
        assertTrue(String.valueOf(TraceStore.get("orch.failsoft.queryAugment.sampleHashes")).contains("hash:"));
    }

    @Test
    void nonFiniteQueryTransformerTraceDoesNotActivateQueryAugment() throws Throwable {
        String rawQuery = "private nonfinite qtx bypass query ownerToken=raw-secret";
        TraceStore.put("queryTransformer.bypassed", Double.POSITIVE_INFINITY);

        FailSoftQueryAugmentAspect aspect = new FailSoftQueryAugmentAspect(
                new MockEnvironment(),
                new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties()));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{List.of(rawQuery)});
        when(pjp.proceed()).thenReturn("ok");
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            throw new AssertionError("non-finite trace metadata must not activate query augmentation");
        });

        Object result = aspect.aroundRetrieveAll(pjp);

        assertEquals("ok", result);
        assertFalse(Boolean.TRUE.equals(TraceStore.get("orch.failsoft.queryAugment.used")));
    }

    @Test
    void traceStoreRateLimitSkipsQueryAugmentWithoutGuardContext() throws Throwable {
        String rawQuery = "private rate limited qtx bypass query ownerToken=raw-secret";
        TraceStore.put("queryTransformer.bypassed", true);
        TraceStore.put("web.rateLimited", true);

        FailSoftQueryAugmentAspect aspect = new FailSoftQueryAugmentAspect(
                new MockEnvironment(),
                new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties()));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{List.of(rawQuery)});
        when(pjp.proceed()).thenReturn("ok");
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            throw new AssertionError("rate-limited path must not add more provider queries");
        });

        Object result = aspect.aroundRetrieveAll(pjp);

        assertEquals("ok", result);
        assertEquals("webRateLimited", TraceStore.get("orch.failsoft.queryAugment.skipped"));
        assertFalse(Boolean.TRUE.equals(TraceStore.get("orch.failsoft.queryAugment.used")));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("raw-secret"), trace);
    }

    @Test
    void queryAugmentReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/FailSoftQueryAugmentAspect.java"));

        assertFalse(source.contains("activateReason != null ? activateReason"));
        assertFalse(source.contains("String safeReason = SafeRedactor.safeMessage(reason, 120);"));
        assertTrue(source.contains("String safeReason = SafeRedactor.traceLabelOrFallback(reason, \"unknown\");"));
        assertTrue(source.contains("TraceStore.put(\"orch.failsoft.queryAugment.reason\", safeReason);"));
    }

    @Test
    void queryAugmentFailSoftLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/FailSoftQueryAugmentAspect.java"));

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("String.valueOf(e)"));
        assertTrue(source.contains("augment failed: errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e))"));
        assertTrue(source.contains("messageLength(e)"));
    }

    @Test
    void queryAugmentSuppressedFallbacksLeaveRedactedBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/FailSoftQueryAugmentAspect.java"));

        assertTrue(source.contains("traceSuppressed(\"guardContext.get\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"metaHints.cast\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"trace.write\", ignore);"));
        assertTrue(source.contains("[nova][failsoft-query-augment] suppressed stage={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
    }
}
