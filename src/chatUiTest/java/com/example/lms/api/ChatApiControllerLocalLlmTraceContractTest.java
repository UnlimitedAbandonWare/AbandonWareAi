package com.example.lms.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatApiControllerLocalLlmTraceContractTest {

    @Test
    void syncFinalTraceRefreshesDebugCopilotBeforeSnapshotMeta() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatApiController.java"),
                StandardCharsets.UTF_8);
        int syncStart = source.lastIndexOf("ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);");
        int finalMeta = source.indexOf("StageBoundaryBreadcrumbs.recordFromCurrentTrace(\"final\");", syncStart);
        assertTrue(syncStart >= 0 && finalMeta > syncStart, "sync final trace block not found");
        String between = source.substring(syncStart, finalMeta);
        assertTrue(between.contains("debugCopilotService.maybeEnrichTrace()"),
                "sync final trace must refresh local LLM operator action before snapshot meta");
    }
}
