package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.domain.ChatSession;
import com.example.lms.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ChatSettingsOmissionRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void omittedJsonSamplingInheritsServerValues() throws Exception {
        assertSampling(mapper.readValue("{\"message\":\"synthetic\"}", ChatRequestDto.class));
    }
    @Test void omittedBuilderSamplingInheritsServerValues() {
        assertSampling(ChatRequestDto.builder().message("synthetic").build());
    }
    private void assertSampling(ChatRequestDto request) {
        var merged = ChatRequestSettingsMerger.merge(request, Map.of(
                SettingsService.KEY_OPENAI_MODEL, "fixture-model",
                SettingsService.KEY_TOP_P, "0.42",
                SettingsService.KEY_FREQUENCY_PENALTY, "0.25",
                SettingsService.KEY_PRESENCE_PENALTY, "-0.25"), false,
                LoggerFactory.getLogger(getClass()));
        assertEquals(0.42, merged.getTopP());
        assertEquals(0.25, merged.getFrequencyPenalty());
        assertEquals(-0.25, merged.getPresencePenalty());
        assertEquals(2048, merged.getMaxTokens());
    }
    @Test void explicitZeroRemainsARequestOverride() {
        var request = ChatRequestDto.builder().topP(0.0).frequencyPenalty(0.0)
                .presencePenalty(0.0).temperature(0.0).maxTokens(100).build();
        var merged = ChatRequestSettingsMerger.merge(request,
                Map.of(SettingsService.KEY_TOP_P, "0.42"), false, LoggerFactory.getLogger(getClass()));
        assertEquals(0.0, merged.getTopP());
        assertEquals(0.0, merged.getFrequencyPenalty());
        assertEquals(100, merged.getMaxTokens());
    }
    @Test void rawMissingMaxTokensSurvivesJsonAndBuilder() throws Exception {
        assertNull(mapper.readValue("{}", ChatRequestDto.class).getMaxTokens());
        assertNull(ChatRequestDto.builder().build().getMaxTokens());
    }
    @Test void continuedConversationRestoresSamplingAndKeepsExplicitZero() {
        var session = ChatSession.builder().id(1L).sessionMeta(
                "{\"topP\":0.65,\"temperature\":0.4,\"frequencyPenalty\":0.3,"
                        + "\"presencePenalty\":-0.2,\"maxTokens\":1024,\"ragAnswerPolicy\":\"evidence_only\"}").build();
        var request = ChatRequestDto.builder().temperature(0.0).build();
        var meta = ChatSessionMetaMerger.merge(mapper, session, request, LoggerFactory.getLogger(getClass()));
        assertEquals(0.65, request.getTopP());
        assertEquals(0.0, request.getTemperature());
        assertEquals(0.3, request.getFrequencyPenalty());
        assertEquals(-0.2, request.getPresencePenalty());
        assertEquals(1024, request.getMaxTokens());
        assertEquals("evidence_only", request.getRagAnswerPolicy());
        assertEquals(0.0, meta.get("temperature"));
    }
}
