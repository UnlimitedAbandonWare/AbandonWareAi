package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowPublicSystemPromptContractTest {

    @Test
    void publicSystemPromptUsesAssetIdResolverAndRedactedRejectionTrace() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains("promptAssetService.resolveSystemPromptText(requestedSystemPrompt)"));
        assertTrue(source.contains("traceRejectedPublicSystemPrompt(requestedSystemPrompt)"));
        assertTrue(source.contains("\"literal_public_rejected\""));
        assertTrue(source.contains("\"prompt.systemPrompt.rejectedHash12\""));
        assertFalse(source.contains("TraceStore.put(\"prompt.systemPrompt.raw\""));
    }

    @Test
    void trustedPromptResolverIsLimitedToPlanOwnedFinalAnswerConfig() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains("promptAssetService.resolveTrustedSystemPromptText(cfg.systemPrompt())"));
        assertFalse(source.contains("resolveTrustedSystemPromptText(stepReq != null ? stepReq.getSystemPrompt()"));
        assertFalse(source.contains("resolveTrustedSystemPromptText(llmReq.getSystemPrompt()"));
    }
}
