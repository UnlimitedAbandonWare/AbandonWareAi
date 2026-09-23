package com.example.lms.trace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DiagnosticPayloadThrowableRedactionContractTest {

    @Test
    void structuredDiagnosticPayloadsDoNotRenderThrowableToString() throws Exception {
        String onnxHealth = Files.readString(
                Path.of("main/java/com/example/lms/health/OnnxRerankerHealthIndicator.java"),
                StandardCharsets.UTF_8);
        String vectorQuarantine = Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorQuarantineDlqService.java"),
                StandardCharsets.UTF_8);
        String traceAttribution = Files.readString(
                Path.of("main/java/com/example/lms/trace/attribution/TraceAblationAttributionService.java"),
                StandardCharsets.UTF_8);

        assertFalse(onnxHealth.contains("validation.put(\"error\", SafeRedactor.safeMessage(String.valueOf(t), 180));"));
        assertFalse(onnxHealth.contains("validation.put(\"error\", SafeRedactor.safeMessage(t.getMessage(), 180));"));
        assertFalse(vectorQuarantine.contains(
                "out.put(\"error\", com.example.lms.trace.SafeRedactor.safeMessage(String.valueOf(e), 180));"));
        assertFalse(vectorQuarantine.contains(
                "out.put(\"error\", com.example.lms.trace.SafeRedactor.safeMessage(e.getMessage(), 180));"));
        assertFalse(traceAttribution.contains(
                "Map.of(\"error\", com.example.lms.trace.SafeRedactor.safeMessage(String.valueOf(e), 180))"));
        assertFalse(traceAttribution.contains(
                "String message = e == null ? null : com.example.lms.trace.SafeRedactor.safeMessage(e.getMessage(), 180);"));

        assertTrue(onnxHealth.contains("validation.put(\"error\", SafeRedactor.traceLabelOrFallback(t.getMessage(), \"\"));"));
        assertTrue(vectorQuarantine.contains(
                "out.put(\"error\", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\"));"));
        assertTrue(traceAttribution.contains("\"error\", String.valueOf(NightmareBreaker.classify(e))"));
        assertTrue(traceAttribution.contains("\"errorType\", SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
    }
}
