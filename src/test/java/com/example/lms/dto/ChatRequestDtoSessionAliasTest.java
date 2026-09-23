package com.example.lms.dto;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestDtoSessionAliasTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void mapsSessionAliasesToSessionId() throws Exception {
        assertEquals(42L, read("{\"sessionId\":42}").getSessionId());
        assertEquals(42L, read("{\"session_id\":\"42\"}").getSessionId());
        assertEquals(42L, read("{\"conversationId\":\"42\"}").getSessionId());
        assertEquals(42L, read("{\"conversation_id\":\"42\"}").getSessionId());
        assertEquals(123L, read("{\"conversationId\":\"chat-123\"}").getSessionId());
    }

    @Test
    void malformedSessionAliasFallbackLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/dto/ChatRequestDto.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"chatRequest.sessionId\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"chat.request.suppressed.\" + safeStage, true);"));
    }

    @Test
    void overflowingNumericSessionAliasUsesStableReasonCodeWithoutRawValue() throws Exception {
        String raw = "999999999999999999999999999999999999999999";

        assertNull(read("{\"sessionId\":\"" + raw + "\"}").getSessionId());

        assertEquals("chatRequest.sessionId", TraceStore.get("chat.request.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("chat.request.suppressed.errorType"));
        assertEquals("invalid_number", TraceStore.get("chat.request.suppressed.chatRequest.sessionId.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
    }

    private ChatRequestDto read(String json) throws Exception {
        return mapper.readValue(json, ChatRequestDto.class);
    }
}
