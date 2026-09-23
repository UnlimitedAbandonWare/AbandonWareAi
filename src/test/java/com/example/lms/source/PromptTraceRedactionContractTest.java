package com.example.lms.source;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTraceRedactionContractTest {

    @Test
    void promptIntentAndDomainTraceUseDiagnosticSummaries() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"prompt.intent\", ctx.intent())"));
        assertFalse(source.contains("TraceStore.put(\"prompt.domain\", ctx.domain())"));
        assertFalse(source.contains("pev.put(\"intent\", ctx.intent())"));
        assertFalse(source.contains("pev.put(\"domain\", ctx.domain())"));
        assertFalse(source.contains("dd.put(\"intent\", ctx.intent())"));
        assertFalse(source.contains("dd.put(\"domain\", ctx.domain())"));

        assertTrue(source.contains("TraceStore.put(\"prompt.intent\", SafeRedactor.diagnosticValue(\"prompt.intent\", ctx.intent(), 160))"));
        assertTrue(source.contains("TraceStore.put(\"prompt.domain\", SafeRedactor.diagnosticValue(\"prompt.domain\", ctx.domain(), 160))"));
        assertTrue(source.contains("pev.put(\"intent\", SafeRedactor.diagnosticValue(\"intent\", ctx.intent(), 160))"));
        assertTrue(source.contains("pev.put(\"domain\", SafeRedactor.diagnosticValue(\"domain\", ctx.domain(), 160))"));
        assertTrue(source.contains("dd.put(\"intent\", SafeRedactor.diagnosticValue(\"intent\", ctx.intent(), 160))"));
        assertTrue(source.contains("dd.put(\"domain\", SafeRedactor.diagnosticValue(\"domain\", ctx.domain(), 160))"));
    }
}
