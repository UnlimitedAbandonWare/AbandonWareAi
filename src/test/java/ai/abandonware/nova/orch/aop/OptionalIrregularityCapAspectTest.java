package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OptionalIrregularityCapAspectTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void optionalCapReasonsUseTraceLabels() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/OptionalIrregularityCapAspect.java"));

        assertFalse(source.contains("TraceStore.put(\"irregularity.optional.cap.skippedHighRisk.reason\", r);"));
        assertFalse(source.contains("TraceStore.put(\"irregularity.optional.cap.skippedReason\", r);"));
        assertTrue(source.contains(
                "SafeRedactor.traceLabelOrFallback(r, \"\"));"));
        assertTrue(source.contains(
                "TraceStore.put(\"irregularity.optional.cap.skippedReason\", SafeRedactor.traceLabelOrFallback(r, \"\"));"));
        assertTrue(source.contains(
                "\"reason\", SafeRedactor.traceLabelOrFallback(r, \"\"),"));
        assertFalse(source.contains("catch (Exception ignore) { return 0.0; }"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"optionalIrregularityCap.highRiskCheck\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"optionalIrregularityCap.counterUpdate\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"optionalIrregularityCap.parseNumber\", ignore);"));
    }

    @Test
    void optionalCapReasonsUseTraceLabelsForDynamicSuffixes() throws Throwable {
        String rawReason = "query_transformer_private student detail near river";
        OptionalIrregularityCapAspect aspect = new OptionalIrregularityCapAspect(true, 0.25d, 1.0d, 1);
        GuardContext ctx = new GuardContext();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{ctx, 0.75d, rawReason});
        when(pjp.proceed(any(Object[].class))).thenReturn(null);

        aspect.aroundBump(pjp);
        aspect.aroundBump(pjp);

        Map<?, ?> last = assertInstanceOf(Map.class, TraceStore.get("irregularity.optional.cap.last"));
        assertTrue(String.valueOf(last.get("reason")).startsWith("hash:"), String.valueOf(last));
        assertTrue(String.valueOf(TraceStore.get("irregularity.optional.cap.skippedReason")).startsWith("hash:"),
                String.valueOf(TraceStore.get("irregularity.optional.cap.skippedReason")));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawReason), trace);
        assertFalse(trace.contains("student detail"), trace);
    }

    @Test
    void nonFiniteStoredCountersDoNotTripOptionalCapCeiling() throws Throwable {
        TraceStore.put("irregularity.optional.sum", "1.0e309");
        TraceStore.put("irregularity.optional.events", Double.NaN);

        OptionalIrregularityCapAspect aspect = new OptionalIrregularityCapAspect(true, 0.25d, 1.0d, 2);
        GuardContext ctx = new GuardContext();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{ctx, 0.20d, "query_transformer_timeout"});
        when(pjp.proceed(any(Object[].class))).thenReturn("ok");

        Object out = aspect.aroundBump(pjp);

        assertEquals("ok", out);
        Map<?, ?> last = assertInstanceOf(Map.class, TraceStore.get("irregularity.optional.cap.last"));
        assertEquals(0.0d, ((Number) last.get("sumBefore")).doubleValue(), 0.0001d);
        assertEquals(0.20d, ((Number) last.get("sumAfter")).doubleValue(), 0.0001d);
    }

    @Test
    void nonFiniteConstructorCapsDoNotPropagateNaNDelta() throws Throwable {
        OptionalIrregularityCapAspect aspect = new OptionalIrregularityCapAspect(true, Double.NaN, Double.NaN, 2);
        GuardContext ctx = new GuardContext();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{ctx, 0.20d, "query_transformer_timeout"});
        when(pjp.proceed(any(Object[].class))).thenReturn("ok");

        Object out = aspect.aroundBump(pjp);

        assertEquals(null, out);
        assertFalse(Boolean.TRUE.equals(TraceStore.get("irregularity.optional.cap.hit")));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NaN"));
    }
}
