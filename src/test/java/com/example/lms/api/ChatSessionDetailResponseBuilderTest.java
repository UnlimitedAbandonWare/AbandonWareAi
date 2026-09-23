package com.example.lms.api;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionDetailResponseBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsDetailWithoutLeakingModelMetaAndRestoresTraceSnapshotCard() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 12, 15, 0);
        ChatSession session = ChatSession.builder()
                .id(99L)
                .title("saved chat")
                .createdAt(now)
                .sessionMeta("{\"profile\":\"saved-profile\"}")
                .messages(List.of(
                        message(1L, "system", "?MODEL?OpenAiChatModel", now),
                        message(2L, "system", "?TRACESNAP?snap-1", now.plusSeconds(1)),
                        message(3L, "user", "hello", now.plusSeconds(2)),
                        message(4L, "assistant", "answer", now.plusSeconds(3))))
                .build();

        ResponseEntity<ChatApiController.SessionDetail> response = ChatSessionDetailResponseBuilder.build(
                session,
                "guest",
                objectMapper,
                Map.of(SettingsService.KEY_OPENAI_MODEL, "configured-model"),
                true,
                LoggerFactory.getLogger(ChatSessionDetailResponseBuilderTest.class));

        ChatApiController.SessionDetail body = response.getBody();
        assertNotNull(body);
        assertEquals("configured-model", body.modelUsed());
        assertEquals("saved-profile", body.settings().get("profile"));
        assertEquals("configured-model", response.getHeaders().getFirst("X-Model-Used"));
        assertEquals("guest", response.getHeaders().getFirst("X-Session-Owner"));
        assertEquals(3, body.messages().size());
        assertEquals("system", body.messages().get(0).role());
        assertTrue(body.messages().get(0).content().contains("data-trace-snapshot-id=\"snap-1\""));
        assertEquals("user", body.messages().get(1).role());
        assertEquals("assistant", body.messages().get(2).role());
    }

    @Test
    void concreteModelWinsAndLegacyTraceMetaStaysHiddenWhenTraceExposureIsOff() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 12, 15, 5);
        ChatSession session = ChatSession.builder()
                .id(100L)
                .title("saved chat")
                .createdAt(now)
                .messages(List.of(
                        message(1L, "system", "?MODEL?real-model", now),
                        message(2L, "system", "?TRACE?<div>raw</div>", now.plusSeconds(1)),
                        message(3L, "user", "hello", now.plusSeconds(2))))
                .build();

        ResponseEntity<ChatApiController.SessionDetail> response = ChatSessionDetailResponseBuilder.build(
                session,
                null,
                objectMapper,
                Map.of(SettingsService.KEY_OPENAI_MODEL, "configured-model"),
                false,
                LoggerFactory.getLogger(ChatSessionDetailResponseBuilderTest.class));

        ChatApiController.SessionDetail body = response.getBody();
        assertNotNull(body);
        assertEquals("real-model", body.modelUsed());
        assertEquals("anonymousUser", response.getHeaders().getFirst("X-Session-Owner"));
        assertEquals(1, body.messages().size());
        assertEquals("hello", body.messages().get(0).content());
    }

    @Test
    void sessionMetaParseFailureLogsHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatSessionDetailResponseBuilder.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("errorHash={}") && source.contains("errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(e.getMessage())"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
    }

    private static ChatMessage message(Long id, String role, String content, LocalDateTime createdAt) {
        return ChatMessage.builder()
                .id(id)
                .role(role)
                .content(content)
                .createdAt(createdAt)
                .build();
    }
}
