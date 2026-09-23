package com.example.lms.image;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedImagePromptBuilderTraceContractTest {

    @Test
    void failSoftContextExtractionLeavesFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/image/GroundedImagePromptBuilder.java"));

        assertTrue(source.contains("traceSuppressed(\"imagePrompt.webSegment\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"imagePrompt.franchiseResolve\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"image.prompt.suppressed.\" + safeStage, true);"));
    }
}
