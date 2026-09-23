package com.example.lms.orchestration;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestrationSignalsExecutionPlanTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void computeAppliesExecutionPlanToGuardContext() {
        GuardContext ctx = new GuardContext();
        ctx.setHighRiskQuery(true);
        TraceStore.put("outCount", 0);

        OrchestrationSignals.compute("RAG evidence starvation debug", null, ctx);

        assertEquals("EXTREMEZ", ctx.getPlanOverride("executionPlan.primaryMode"));
        assertEquals(Boolean.TRUE, ctx.getPlanOverride("extremeZ.enabled"));
        assertEquals(Boolean.FALSE, ctx.getPlanOverride("hypernova.enabled"));
        assertEquals("EXTREMEZ", TraceStore.get("routing.executionPlan.applied.primaryMode"));
    }

    @Test
    void executionPlanFailureTraceUsesStableReasonAndHashOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/orchestration/OrchestrationSignals.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertTrue(source.contains("TraceStore.put(\"routing.executionPlan.apply.reason\", \"execution_plan_apply_failed\");"));
        assertTrue(source.contains("TraceStore.put(\"routing.executionPlan.apply.errorHash\", SafeRedactor.hashValue(messageOf(t)));"));
        assertTrue(source.contains("TraceStore.put(\"routing.executionPlan.apply.errorLength\", messageLength(t));"));
    }

    @Test
    void orchestrationSignalsDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/orchestration/OrchestrationSignals.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "Orchestration signal debug traces need fixed-stage breadcrumbs instead of exact empty catch bodies");
        assertTrue(source.contains("traceSkipped(\"aux_optional_trace\", ignore);"));
        assertTrue(source.contains("traceSkipped(\"web_rate_limited_trace\", ignore);"));
        assertTrue(source.contains("traceSkipped(\"execution_plan_apply_trace\", ignore);"));
        assertTrue(source.contains("traceSkipped(\"as_double\", ignore);"));
    }

    @Test
    void noiseGateProbabilityPropertiesUseBoundedParser() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/orchestration/OrchestrationSignals.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("Double.parseDouble(System.getProperty("));
        assertTrue(source.contains("boundedDoubleProperty("));
        assertTrue(source.contains("\"orch.noiseGate.orch.bypassSilentFailure.escapeP.max\", 0.28d"));
        assertTrue(source.contains("\"orch.noiseGate.orch.bypassSilentFailure.escapeP.min\", 0.06d"));
        assertTrue(source.contains("traceSkipped(stage, ignore);"));
    }

    @Test
    void numericFallbackParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/orchestration/OrchestrationSignals.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private static double asDouble");
        int parse = source.indexOf("Double.parseDouble", start);
        int end = source.indexOf("\n        }", parse);
        assertTrue(start >= 0 && parse > start && end > parse, "asDouble parser should be locatable");
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception");
        assertTrue(helper.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException");
    }

    @Test
    void numericFallbackClassifierUsesStableInvalidNumberLabel() throws Exception {
        Method errorType = OrchestrationSignals.class.getDeclaredMethod("errorType", Throwable.class);
        errorType.setAccessible(true);

        String label = (String) errorType.invoke(null, new NumberFormatException("ownerToken=raw-secret"));

        assertEquals("invalid_number", label);
        assertFalse(label.contains("NumberFormatException"));
        assertFalse(label.contains("ownerToken"));
    }

    @Test
    void asDoubleRejectsNonFiniteNumbers() throws Exception {
        Method asDouble = OrchestrationSignals.class.getDeclaredMethod("asDouble", Object.class);
        asDouble.setAccessible(true);

        assertEquals(0.0d, (Double) asDouble.invoke(null, Double.POSITIVE_INFINITY), 0.0001d);
        assertEquals(0.0d, (Double) asDouble.invoke(null, "Infinity"), 0.0001d);
    }
}
