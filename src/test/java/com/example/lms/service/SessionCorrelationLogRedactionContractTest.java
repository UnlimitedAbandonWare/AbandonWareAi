package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class SessionCorrelationLogRedactionContractTest {

    @Test
    void shimHistoryAndStreamingLogsDoNotWriteRawSessionIds() throws Exception {
        String history = Files.readString(
                Path.of("main/java/com/example/lms/service/DefaultChatHistoryService.java"),
                StandardCharsets.UTF_8);
        String streaming = Files.readString(
                Path.of("main/java/com/example/lms/service/stream/StreamingCoordinator.java"),
                StandardCharsets.UTF_8);

        assertFalse(history.contains("append sid={}, role={}, len={}"));
        assertFalse(history.contains("addMessagesToSession id={}"));
        assertFalse(history.contains("sid={}, mode={}, traceTurnId={}"));
        assertFalse(history.contains("getSessionWithMessages sid={}"));
        assertFalse(history.contains("delete sid={}"));
        assertFalse(history.contains("sid={}, limit={} -> empty"));
        assertFalse(history.contains("getLastAssistantMessage sid={}"));
        assertFalse(history.contains("startNewSession user={}"));
        assertFalse(history.contains("getSessionsForUser user={}"));
        assertFalse(history.contains("(5-param) user={},"));
        assertFalse(streaming.contains("Cancellation requested for session {}\", sessionId"));

        assertTrue(history.contains("sessionHash"));
        assertTrue(history.contains("userHash"));
        assertTrue(streaming.contains("sessionHash"));
    }
}
