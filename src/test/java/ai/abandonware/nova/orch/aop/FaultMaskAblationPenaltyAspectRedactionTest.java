package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FaultMaskAblationPenaltyAspectRedactionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void rawStageDoesNotLeakIntoUawFaultmaskTraceKeysOrValues() throws Throwable {
        String rawStage = "websearch:private customer route stage";
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{rawStage, 1.0d, "note"});
        when(pjp.proceed()).thenReturn("ok");
        TraceStore.put("uaw.autolearn", true);

        Object out = new FaultMaskAblationPenaltyAspect(new MockEnvironment()).aroundFaultmaskRecord(pjp);

        assertTrue("ok".equals(out));
        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains(rawStage), rendered);
        assertFalse(rendered.contains("private customer"), rendered);
        assertFalse(rendered.contains("customer route"), rendered);
        assertTrue(String.valueOf(TraceStore.get("uaw.faultmask.lastStage")).startsWith("hash"), rendered);
    }

    @Test
    void failSoftLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/FaultMaskAblationPenaltyAspect.java"));

        assertTrue(source.contains("errorHash={} errorLength={}"), source);
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t))"), source);
        assertTrue(source.contains("messageLength(t)"), source);
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"), source);
        assertFalse(source.contains("String.valueOf(t)"), source);
    }

    @Test
    void numericPenaltyParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/FaultMaskAblationPenaltyAspect.java"))
                .replace("\r\n", "\n");
        String parserCall = "double parsed = Double.parseDouble(String.valueOf(v).trim());";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "FaultMask penalty parser should be locatable");
        int nextMethod = source.indexOf("\n    private static ", parser + parserCall.length());
        String window = source.substring(parser, nextMethod < 0 ? source.length() : nextMethod);

        assertFalse(window.contains("catch (Throwable"),
                "FaultMask numeric parser fallback must not swallow Throwable");
        assertFalse(window.contains("catch (Exception"),
                "FaultMask numeric parser fallback must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "FaultMask numeric parser fallback should catch only NumberFormatException");
    }

    @Test
    void numericPenaltyParserLeavesStableInvalidNumberBreadcrumb() throws Exception {
        Method method = FaultMaskAblationPenaltyAspect.class.getDeclaredMethod("readDouble", Object.class, double.class);
        method.setAccessible(true);

        assertEquals(0.37d, (double) method.invoke(null, "not-a-number", 0.37d));
        assertEquals("invalid_number", TraceStore.get("faultMask.delta.parse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("not-a-number"), trace);
        assertFalse(trace.contains("NumberFormatException"), trace);

        TraceStore.clear();
        assertEquals(0.37d, (double) method.invoke(null, Double.POSITIVE_INFINITY, 0.37d));
        assertEquals("invalid_number", TraceStore.get("faultMask.delta.parse.errorType"));

        TraceStore.clear();
        assertEquals(0.37d, (double) method.invoke(null, "1.0e309", 0.37d));
        assertEquals("invalid_number", TraceStore.get("faultMask.delta.parse.errorType"));
    }

    @Test
    void uawActivityAndNumericParseFallbacksLeaveRedactedBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/FaultMaskAblationPenaltyAspect.java"));

        assertTrue(source.contains("traceSuppressed(\"uaw.active\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"faultMask.delta.parse\", ignore);"));
        assertTrue(source.contains("[FaultMaskPenalty] suppressed stage={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
    }
}
