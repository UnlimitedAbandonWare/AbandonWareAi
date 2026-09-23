package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridWebSearchInterruptHygieneAspectTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
    }

    @Test
    void awaitRootDetailStoresDiagnosticSummaryOnly() throws Exception {
        String rawDetail = "await failed for private await root query must not leak";
        TraceStore.put("web.await.events", List.of(Map.of(
                "ok", false,
                "seq", 1,
                "engine", "Naver",
                "stage", "await",
                "step", "provider",
                "cause", "await_timeout",
                "waitedMs", 0,
                "detail", rawDetail)));

        Method method = HybridWebSearchInterruptHygieneAspect.class.getDeclaredMethod("observeWebAwaitRootCause");
        method.setAccessible(true);
        method.invoke(null);

        String rootDetail = String.valueOf(TraceStore.get("web.await.root.detail"));
        assertFalse(rootDetail.contains(rawDetail), rootDetail);
        assertFalse(rootDetail.contains("private await root query"), rootDetail);
        assertTrue(rootDetail.contains("hash12"));
    }

    @Test
    void awaitResidualAndRootLabelsDoNotRetainRawEventText() throws Exception {
        String tokenMarker = "owner" + "Token";
        String fakeKey = "sk-" + "12345678901234567890";
        String rawEngine = "Private Engine " + tokenMarker + "=raw-secret";
        String rawStage = "Private Stage query text";
        String rawStep = "Private Step api_key=" + fakeKey;
        TraceStore.put("web.await.events", List.of(Map.of(
                "ok", false,
                "seq", 1,
                "engine", rawEngine,
                "stage", rawStage,
                "step", rawStep,
                "cause", "intentional_cancel",
                "waitedMs", 0,
                "detail", "private await residual detail")));

        Method residual = HybridWebSearchInterruptHygieneAspect.class
                .getDeclaredMethod("observeWaitedMs0InterruptResidual");
        residual.setAccessible(true);
        residual.invoke(null);
        Method root = HybridWebSearchInterruptHygieneAspect.class.getDeclaredMethod("observeWebAwaitRootCause");
        root.setAccessible(true);
        root.invoke(null);

        String derived = String.join(" | ",
                String.valueOf(TraceStore.get("web.await.interruptResidual.waitedMs0.engines")),
                String.valueOf(TraceStore.get("web.await.interruptResidual.waitedMs0.steps")),
                String.valueOf(TraceStore.get("web.await.interruptResidual.waitedMs0.digest")),
                String.valueOf(TraceStore.get("web.await.root.engine")),
                String.valueOf(TraceStore.get("web.await.root.stage")),
                String.valueOf(TraceStore.get("web.await.root.step")),
                String.valueOf(TraceStore.get("web.await.root.cause")));
        assertFalse(derived.contains(rawEngine), derived);
        assertFalse(derived.contains(rawStage), derived);
        assertFalse(derived.contains(rawStep), derived);
        assertFalse(derived.contains("raw-secret"), derived);
        assertFalse(derived.contains(fakeKey), derived);
        assertTrue(String.valueOf(TraceStore.get("web.await.interruptResidual.waitedMs0.digest")).contains("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.await.root.engine")).startsWith("hash:"));
    }

    @Test
    void awaitRootDetailHashDoesNotAliasSupplementaryBoundaryToQuestionMark() throws Exception {
        String prefix = "x".repeat(199);
        Map<String, Object> supplementary = observeRootDetail(prefix + "\uD83D\uDE00" + "tail");
        Map<String, Object> replacement = observeRootDetail(prefix + "?" + "tail");

        assertAll(
                () -> assertEquals(199, ((Number) supplementary.get("len")).intValue()),
                () -> assertNotEquals(replacement.get("hash12"), supplementary.get("hash12")));
    }

    @Test
    void awaitRootDetailKeepsBmpAndCompletePairControls() throws Exception {
        String bmp = "y".repeat(200);
        Map<String, Object> bmpSummary = observeRootDetail(bmp + "tail");
        assertEquals(200, ((Number) bmpSummary.get("len")).intValue());
        assertEquals(SafeRedactor.hash12(bmp), bmpSummary.get("hash12"));

        String completePair = "q".repeat(198) + "\uD83D\uDE00";
        Map<String, Object> pairSummary = observeRootDetail(completePair + "tail");
        assertEquals(200, ((Number) pairSummary.get("len")).intValue());
        assertEquals(SafeRedactor.hash12(completePair), pairSummary.get("hash12"));
    }

    @Test
    void swallowedInterruptLogUsesHashAndLengthInsteadOfRawMessage() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/HybridWebSearchInterruptHygieneAspect.java"));

        assertFalse(source.contains("t.toString())"));
        assertFalse(source.contains("SafeRedactor.safeMessage(t.getMessage(), 180)"));
        assertTrue(source.contains("errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(t.getMessage())"));
    }

    @Test
    void interruptHygieneAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/HybridWebSearchInterruptHygieneAspect.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "interrupt hygiene fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void utilityFallbackCatchesUseInterruptHygieneBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/HybridWebSearchInterruptHygieneAspect.java"));

        assertTrue(source.contains("traceSuppressed(\"stackDigest\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"toLong\", ignore);"));
    }

    @Test
    void toLongDropsNonFiniteNumbersWithBreadcrumb() throws Exception {
        Method method = HybridWebSearchInterruptHygieneAspect.class.getDeclaredMethod("toLong", Object.class);
        method.setAccessible(true);

        assertEquals(-1L, method.invoke(null, Double.POSITIVE_INFINITY));
        assertEquals("toLong", TraceStore.get("web.interruptHygiene.suppressed.stage"));
        assertEquals("NumberFormatException", TraceStore.get("web.interruptHygiene.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.interruptHygiene.suppressed.toLong"));
        assertEquals("NumberFormatException", TraceStore.get("web.interruptHygiene.suppressed.toLong.errorType"));

        TraceStore.clear();

        assertEquals(-1L, method.invoke(null, Double.NaN));
        assertEquals("toLong", TraceStore.get("web.interruptHygiene.suppressed.stage"));
        assertEquals("NumberFormatException", TraceStore.get("web.interruptHygiene.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.interruptHygiene.suppressed.toLong"));
        assertEquals("NumberFormatException", TraceStore.get("web.interruptHygiene.suppressed.toLong.errorType"));
    }

    @Test
    void swallowedInterruptedFailureUsesOperationalTraceLabel() throws Throwable {
        String tokenMarker = "owner" + "Token";
        HybridWebSearchInterruptHygieneAspect aspect = new HybridWebSearchInterruptHygieneAspect();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenThrow(new InterruptedException("interrupted " + tokenMarker + "=secret"));

        try {
            Object result = aspect.aroundSearch(pjp);

            assertEquals(List.of(), result);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1L, TraceStore.getLong("web.interruptHygiene.preserved.throw.count"));
            assertEquals("interrupted", TraceStore.get("web.interruptHygiene.preserved.throw.error"));
            assertEquals(null, TraceStore.get("interrupt.cleaned"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("InterruptedException"), trace);
            assertFalse(trace.contains(tokenMarker), trace);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preInterruptedRequestSkipsProceedAndPreservesFlag() throws Throwable {
        HybridWebSearchInterruptHygieneAspect aspect = new HybridWebSearchInterruptHygieneAspect();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(List.of("must-not-run"));

        Thread.currentThread().interrupt();
        try {
            Object result = aspect.aroundSearch(pjp);

            assertEquals(List.of(), result);
            verify(pjp, never()).proceed();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1L, TraceStore.getLong("web.interruptHygiene.preserved.entry.count"));
            assertEquals(null, TraceStore.get("interrupt.cleaned"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void residualInterruptFromNormalProceedKeepsValueAndFlag() throws Throwable {
        HybridWebSearchInterruptHygieneAspect aspect = new HybridWebSearchInterruptHygieneAspect();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return List.of("kept");
        });

        try {
            Object result = aspect.aroundSearch(pjp);

            assertEquals(List.of("kept"), result);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1L, TraceStore.getLong("web.interruptHygiene.preserved.exit.count"));
            assertEquals(null, TraceStore.get("interrupt.cleaned"));
        } finally {
            Thread.interrupted();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> observeRootDetail(String detail) throws Exception {
        TraceStore.put("web.await.events", List.of(Map.of(
                "ok", false,
                "seq", 1,
                "engine", "Naver",
                "stage", "await",
                "step", "provider",
                "cause", "await_timeout",
                "waitedMs", 0,
                "detail", detail)));
        Method method = HybridWebSearchInterruptHygieneAspect.class.getDeclaredMethod("observeWebAwaitRootCause");
        method.setAccessible(true);
        method.invoke(null);
        return (Map<String, Object>) TraceStore.get("web.await.root.detail");
    }
}
