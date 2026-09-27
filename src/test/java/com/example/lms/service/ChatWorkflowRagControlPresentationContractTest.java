package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowRagControlPresentationContractTest {

    @Test
    void workflowPublishesTypedRuntimeFactsButReturnsOnlySemanticContent() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("RagControlRuntimeAdapter.capturePresentationInput("));
        assertTrue(source.contains("RagControlRuntimeAdapter.RuntimeInput.evidenceNeeded("));
        assertTrue(source.contains("Boolean.TRUE.equals(req.getUseWebSearch())")
                && source.contains("Boolean.TRUE.equals(req.getUseRag())"));
        assertTrue(source.contains("new com.example.lms.orchestration.control.RagControlRuntimeAdapter.RuntimeInput("));
        assertTrue(source.contains("useWeb || useRag"));
        assertTrue(source.contains("return ChatResult.of(out, modelUsed, ragUsed,"));
        assertFalse(source.contains("private com.example.lms.orchestration.control.RagControlCoordinator ragControlCoordinator;"));
        assertFalse(source.contains("private com.example.lms.orchestration.control.RagControlProjectionRenderer ragControlProjectionRenderer;"));
        assertFalse(source.contains("userVisibleOut = controlRenderer.append"));
    }
}
