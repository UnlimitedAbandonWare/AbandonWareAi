package com.example.lms.infra.resilience;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultMaskingLayerMonitorRedactionContractTest {

    @Test
    void logNoteUsesSafeMessageLikeTraceAndDebugPayloads() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/infra/resilience/FaultMaskingLayerMonitor.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("last, note);"));
        assertTrue(source.contains("last, safeMessage(note, 180));"));
        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "FaultMaskingLayerMonitor fail-soft paths need fixed-stage breadcrumbs instead of exact empty catches");
    }

    @Test
    void failSoftTelemetryPathsLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/infra/resilience/FaultMaskingLayerMonitor.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"faultMask.recordTrace\", traceError);"));
        assertTrue(source.contains("traceSuppressed(\"faultMask.guardContext\", tGet);"));
        assertTrue(source.contains("traceSuppressed(\"faultMask.guardContextEmit\", failure);"));
        assertTrue(source.contains("traceSuppressed(\"faultMask.stagePolicy\", policyError);"));
        assertTrue(source.contains("traceSuppressed(\"faultMask.debugEventEmit\", failure);"));
        assertTrue(source.contains("traceSuppressed(\"faultMask.debugEventFailureTrace\", traceError);"));
        assertTrue(source.contains("TraceStore.put(\"faultmask.suppressed.\" + safeStage, true);"));
    }

    @Test
    void telemetrySkippedDebugLogUsesSafeStageLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/infra/resilience/FaultMaskingLayerMonitor.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private static void traceFaultMaskSkipped");
        int end = source.indexOf("private static String messageOf", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, \"unknown\");"));
        assertTrue(method.contains("safeStage,\n                com.example.lms.trace.SafeRedactor.hashValue(messageOf(error))"));
        assertFalse(method.contains("stage,\n                com.example.lms.trace.SafeRedactor.hashValue(messageOf(error))"));
    }
}
