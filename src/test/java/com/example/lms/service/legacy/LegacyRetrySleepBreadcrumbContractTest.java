package com.example.lms.service.legacy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRetrySleepBreadcrumbContractTest {

    @Test
    void legacyChatRetrySleepsLeaveBreadcrumbsWhenInterrupted() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/legacy/ChatServiceLegacy.java"));

        assertTrue(source.contains("traceLegacySuppressed(\"llm.retry.sleep\", ie);"));
        assertTrue(source.contains("traceLegacySuppressed(\"llm.connect.retry.sleep\", ie);"));
    }

    @Test
    void patchChatRetrySleepsLeaveBreadcrumbsWhenInterrupted() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/patch/ChatOrchestratorPatch.java"));

        assertTrue(source.contains("tracePatchSuppressed(\"llm.retry.sleep\", ie);"));
        assertTrue(source.contains("tracePatchSuppressed(\"llm.connect.retry.sleep\", ie);"));
    }

    @Test
    void legacyPatchChatRetrySleepsLeaveBreadcrumbsWhenInterrupted() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/patch/ChatServiceLegacyPatch.java"));

        assertTrue(source.contains("traceLegacyPatchSuppressed(\"llm.retry.sleep\", ie);"));
        assertTrue(source.contains("traceLegacyPatchSuppressed(\"llm.connect.retry.sleep\", ie);"));
    }
}
