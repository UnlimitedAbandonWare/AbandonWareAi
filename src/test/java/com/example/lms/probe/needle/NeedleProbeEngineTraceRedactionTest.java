package com.example.lms.probe.needle;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeedleProbeEngineTraceRedactionTest {

    @Test
    void needleProbeEngineDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/probe/needle/NeedleProbeEngine.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "NeedleProbeEngine should keep fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void planAndTriggerReasonsUseTraceLabels() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/probe/needle/NeedleProbeEngine.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"needle.plan.reason\", plan.reason());"));
        assertFalse(source.contains("TraceStore.put(\"needle.trigger.reasons\", String.join(\",\", reasons));"));
        assertFalse(source.contains("TraceStore.put(\"needle.plan.reason\", SafeRedactor.safeMessage(plan.reason(), 120));"));
        assertTrue(source.contains("TraceStore.put(\"needle.plan.reason\", SafeRedactor.traceLabelOrFallback(plan.reason(), \"unknown\"));"));
        assertFalse(source.contains(
                "TraceStore.put(\"needle.trigger.reasons\", SafeRedactor.safeMessage(String.join(\",\", reasons), 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"needle.trigger.reasons\", SafeRedactor.traceLabelOrFallback(String.join(\",\", reasons), \"unknown\"));"));
        assertFalse(source.contains("TraceStore.put(\"needle.retrieve.error\", e.getClass().getSimpleName())"));
        assertTrue(source.contains("TraceStore.put(\"needle.retrieve.error\", \"needle_retrieve_failed\")"));
    }
}
