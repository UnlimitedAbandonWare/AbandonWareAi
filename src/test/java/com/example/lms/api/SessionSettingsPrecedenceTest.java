package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세션 복원값이 effective DTO에 실제로 반영되는지 검증한다.
 * 계약: request(명시값) > session(sessionMeta) > global(설정) > default.
 * 컨트롤러는 session-meta merge -> global-settings merge 순서로 호출해야 하며,
 * 이 테스트는 그 조합 자체를 단위로 확인한다.
 */
class SessionSettingsPrecedenceTest {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(SessionSettingsPrecedenceTest.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatSession sessionWithMeta(String json) {
        ChatSession session = new ChatSession("t");
        session.setSessionMeta(json);
        return session;
    }

    @Test
    void sessionValuesRestoreWhenRequestOmitsThem() {
        ChatSession session = sessionWithMeta(
                "{\"model\":\"session-model\",\"useRag\":false,\"useWebSearch\":true,\"precisionSearch\":true}");
        ChatRequestDto req = ChatRequestDto.builder().message("q").build();

        ChatSessionMetaMerger.merge(objectMapper, session, req, LOG);
        ChatRequestDto effective = ChatRequestSettingsMerger.merge(
                req, Map.of(SettingsService.KEY_OPENAI_MODEL, "global-model"), true, LOG);

        assertEquals("session-model", effective.getModel());
        assertFalse(effective.getUseRag());       // session false beats defaultUseRag=true
        assertTrue(effective.getUseWebSearch());  // session true beats chat.defaults.useWebSearch default false
        assertEquals(Boolean.TRUE, effective.getPrecisionSearch());
    }

    @Test
    void explicitRequestValuesBeatSessionMeta() {
        ChatSession session = sessionWithMeta(
                "{\"model\":\"session-model\",\"useRag\":false,\"useWebSearch\":true}");
        ChatRequestDto req = ChatRequestDto.builder()
                .message("q")
                .model("req-model")
                .useRag(true)
                .useWebSearch(false)
                .build();

        ChatSessionMetaMerger.merge(objectMapper, session, req, LOG);
        ChatRequestDto effective = ChatRequestSettingsMerger.merge(
                req, Map.of(SettingsService.KEY_OPENAI_MODEL, "global-model"), false, LOG);

        assertEquals("req-model", effective.getModel());
        assertTrue(effective.getUseRag());
        assertFalse(effective.getUseWebSearch());
    }

    @Test
    void globalAndDefaultFillWhatSessionAndRequestLack() {
        ChatSession session = sessionWithMeta("{}");
        ChatRequestDto req = ChatRequestDto.builder().message("q").build();

        ChatSessionMetaMerger.merge(objectMapper, session, req, LOG);
        ChatRequestDto effective = ChatRequestSettingsMerger.merge(
                req,
                Map.of(SettingsService.KEY_OPENAI_MODEL, "global-model",
                        "chat.defaults.useWebSearch", "true"),
                true,
                LOG);

        assertEquals("global-model", effective.getModel());
        assertTrue(effective.getUseWebSearch()); // global key
        assertTrue(effective.getUseRag());       // defaultUseRag fallback
    }

    @Test
    void malformedSessionMetaFailsSoftAndRequestStillWorks() {
        ChatSession session = sessionWithMeta("{not-json");
        ChatRequestDto req = ChatRequestDto.builder().message("q").useRag(true).build();

        Map<String, Object> meta = ChatSessionMetaMerger.merge(objectMapper, session, req, LOG);
        ChatRequestDto effective = ChatRequestSettingsMerger.merge(req, Map.of(), false, LOG);

        assertTrue(effective.getUseRag());
        assertEquals(1, meta.get("schemaVersion"));
    }

    @Test
    void persistedMetaCapturesExplicitRequestValues() {
        ChatSession session = sessionWithMeta("{\"legacyKey\":\"keep-me\"}");
        ChatRequestDto req = ChatRequestDto.builder()
                .message("q")
                .useRag(false)
                .useWebSearch(true)
                .build();

        Map<String, Object> meta = ChatSessionMetaMerger.merge(objectMapper, session, req, LOG);

        assertEquals(false, meta.get("useRag"));
        assertEquals(true, meta.get("useWebSearch"));
        assertEquals("keep-me", meta.get("legacyKey"));
        assertEquals(1, meta.get("schemaVersion"));
    }
}
