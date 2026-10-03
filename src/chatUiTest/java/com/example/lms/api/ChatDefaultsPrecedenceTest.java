package com.example.lms.api;

import com.example.lms.config.ChatDefaultsProperties;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.domain.ChatSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class ChatDefaultsPrecedenceTest {
    ChatDefaultsProperties factory() {
        var p = new ChatDefaultsProperties(); p.setModel("fixture-model"); p.setModelSelectionMode("preferred");
        p.setTemperature(0.2); p.setTopP(1.0); p.setFrequencyPenalty(0.0); p.setPresencePenalty(0.0); p.setMaxTokens(2048);
        p.setUseRag(true); p.setUseWebSearch(false); p.setSearchMode("OFF"); p.setRagAnswerPolicy("adaptive"); p.setDefaultsVersion("1");
        return p;
    }
    ResolvedChatSettings resolve(ChatRequestDto req, Map<String, Object> user, Map<String, Object> admin) {
        return ChatRequestSettingsMerger.resolve(req, user, admin, factory(), LoggerFactory.getLogger(getClass()));
    }
    @Test void requestUserAdminFactoryAndZeroHaveCorrectPriority() {
        var r = resolve(ChatRequestDto.builder().temperature(0.0).build(), Map.of("topP", 0.42, "useRag", false),
                Map.of("topP", 0.8, "maxTokens", 1000));
        assertEquals(0.0, r.request().getTemperature()); assertEquals(0.42, r.request().getTopP());
        assertFalse(r.request().isUseRag()); assertEquals(1000, r.request().getMaxTokens());
        assertEquals("REQUEST", r.sources().get("temperature")); assertEquals("USER", r.sources().get("topP"));
        assertEquals("ADMIN_DB", r.sources().get("maxTokens")); assertEquals("FACTORY", r.sources().get("presencePenalty"));
    }
    @Test void existingSessionBeatsChangedPersonalDefaultsAndExplicitRequestBeatsSession() {
        var req = ChatRequestDto.builder().temperature(0.0).build();
        req.bindChatSettingsSnapshot(new ChatRequestDto.ChatSettingsSnapshot(Map.of("topP", 0.1), Map.of(), ChatRequestSettingsMerger.requestValues(req)));
        ChatSessionMetaMerger.merge(new ObjectMapper(), ChatSession.builder().sessionMeta("{\"topP\":0.65,\"temperature\":0.4}").build(), req, LoggerFactory.getLogger(getClass()));
        var r = resolve(req, req.getChatSettingsSnapshot().user(), Map.of());
        assertEquals(0.65, r.request().getTopP()); assertEquals("SESSION", r.sources().get("topP"));
        assertEquals(0.0, r.request().getTemperature());
    }
    @Test void searchAndModelPairsNormalizeAtTheirTierAndStrictNeverFallsBack() {
        var r = resolve(ChatRequestDto.builder().build(), Map.of("searchMode", "OFF", "useWebSearch", true, "model", "fixture-exact", "modelSelectionMode", "strict"), Map.of());
        assertFalse(r.request().isUseWebSearch()); assertTrue(r.request().isStrictModelSelection());
        assertEquals("fixture-exact", r.request().getModel());
        assertThrows(IllegalArgumentException.class, () -> resolve(ChatRequestDto.builder().model("llmrouter.auto").strictModelSelection(true).build(), Map.of(), Map.of()));
        var auto = resolve(ChatRequestDto.builder().build(), Map.of("modelSelectionMode", "auto"), Map.of());
        assertEquals("llmrouter.auto", auto.request().getModel());
    }
    @Test void explicitSearchPairBeatsRestoredSessionPair() {
        for (var request : java.util.List.of(ChatRequestDto.builder().searchMode(com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT).build(),
                ChatRequestDto.builder().useWebSearch(true).build())) {
            var original = ChatRequestSettingsMerger.requestValues(request);
            request.bindChatSettingsSnapshot(new ChatRequestDto.ChatSettingsSnapshot(Map.of(), Map.of(), original));
            ChatSessionMetaMerger.merge(new ObjectMapper(), ChatSession.builder().sessionMeta("{\"searchMode\":\"OFF\",\"useWebSearch\":false}").build(),
                    request, LoggerFactory.getLogger(getClass()));
            var resolved = resolve(request, Map.of(), Map.of());
            assertTrue(resolved.request().isUseWebSearch());
            assertEquals(original.containsKey("searchMode") ? com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT
                    : com.example.lms.gptsearch.dto.SearchMode.AUTO, resolved.request().getSearchMode());
            assertEquals("REQUEST", resolved.sources().get("searchMode"));
            assertEquals("REQUEST", resolved.sources().get("useWebSearch"));
        }
    }
    @Test void strictOmissionIsNullableForJsonAndBuilder() throws Exception {
        assertNull(new ObjectMapper().readValue("{}", ChatRequestDto.class).getStrictModelSelection());
        assertNull(ChatRequestDto.builder().build().getStrictModelSelection());
        assertFalse(new ObjectMapper().readValue("{\"strictModelSelection\":false}", ChatRequestDto.class).getStrictModelSelection());
    }
}
