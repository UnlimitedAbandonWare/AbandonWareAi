package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatOrchestratorTraceContractTest {

    @Test
    void retrySleepInterruptionsLeaveRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatOrchestrator.java"));

        assertTrue(source.contains("traceChatSuppressed(\"llm.retry.sleep\", ie);"));
        assertTrue(source.contains("traceChatSuppressed(\"llm.connect.retry.sleep\", ie);"));
    }
}
