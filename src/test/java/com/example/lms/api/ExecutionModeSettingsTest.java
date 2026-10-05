package com.example.lms.api;

import com.example.lms.config.ChatDefaultsProperties;
import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.ChatPreferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionModeSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ChatRequestDto request(String mode) throws Exception {
        return mapper.readValue(mode == null ? "{}" : "{\"executionMode\":\"" + mode + "\"}", ChatRequestDto.class);
    }
    private ChatDefaultsProperties factory() {
        var f = new ChatDefaultsProperties();
        f.setModel("fixture:model"); f.setModelSelectionMode("preferred");
        f.setTemperature(0.3); f.setTopP(1.0); f.setFrequencyPenalty(0.0); f.setPresencePenalty(0.0);
        f.setMaxTokens(512); f.setUseRag(false); f.setUseWebSearch(false);
        f.setSearchMode("OFF"); f.setRagAnswerPolicy("adaptive"); f.setDefaultsVersion("fixture");
        return f;
    }
    private ResolvedChatSettings resolve(ChatRequestDto r, Map<String,Object> user, Map<String,Object> admin) {
        return ChatRequestSettingsMerger.resolve(r, user, admin, factory(), LoggerFactory.getLogger(getClass()));
    }
    @Test void factoryAdminUserAndExplicitAutoKeepIndependentPrecedence() throws Exception {
        assertEquals("AUTO", resolve(request(null), Map.of(), Map.of()).effective().get("executionMode"));
        var admin = Map.<String,Object>of("executionMode", "SELF_ASK");
        var user = Map.<String,Object>of("executionMode", "STRIKE");
        assertEquals("SELF_ASK", resolve(request(null), Map.of(), admin).effective().get("executionMode"));
        assertEquals("STRIKE", resolve(request(null), user, admin).effective().get("executionMode"));
        var explicit = resolve(request("AUTO"), user, admin);
        assertEquals("AUTO", explicit.effective().get("executionMode"));
        assertEquals("REQUEST", explicit.sources().get("executionMode"));
    }
    @Test void sessionRestoresButExplicitSelectionWinsAndUnknownLegacyKeysSurvive() throws Exception {
        var session = new ChatSession("synthetic");
        session.setSessionMeta("{\"executionMode\":\"STRIKE\",\"legacyFixture\":true}");
        var r = request(null);
        r.bindChatSettingsSnapshot(new ChatRequestDto.ChatSettingsSnapshot(Map.of(), Map.of(), Map.of()));
        var meta = ChatSessionMetaMerger.merge(mapper, session, r, LoggerFactory.getLogger(getClass()));
        assertEquals(true, meta.get("legacyFixture"));
        var resolved = resolve(r, Map.of(), Map.of("executionMode", "SELF_ASK"));
        assertEquals("STRIKE", resolved.effective().get("executionMode"));
        assertEquals("SESSION", resolved.sources().get("executionMode"));
        var explicit = request("AUTO");
        ChatSessionMetaMerger.merge(mapper, session, explicit, LoggerFactory.getLogger(getClass()));
        assertEquals("AUTO", resolve(explicit, Map.of(), Map.of()).effective().get("executionMode"));
    }
    @Test void preferenceModeIsSparseValidatedAndNeverChangesSamplingOrModelMode() {
        assertEquals(Map.of(), ChatPreferenceService.validate(Map.of()));
        for (String mode : new String[]{"AUTO", "STRIKE", "SELF_ASK"})
            assertEquals(Map.of("executionMode", mode), ChatPreferenceService.validate(Map.of("executionMode", mode)));
        assertThrows(IllegalArgumentException.class, () -> ChatPreferenceService.validate(Map.of("executionMode", "BYPASS")));
        assertThrows(IllegalArgumentException.class, () -> ChatPreferenceService.validate(Map.of("temperature", Double.NaN)));
        assertEquals(0.0, ChatPreferenceService.validate(Map.of("temperature", 0)).get("temperature"));
    }
    @Test void dtoRejectsUnknownModesAndAdministratorProjectionIsValidated() throws Exception {
        assertThrows(com.fasterxml.jackson.databind.exc.InvalidFormatException.class, () -> request("BYPASS"));
        var repo = org.mockito.Mockito.mock(com.example.lms.repository.ConfigurationSettingRepository.class);
        var service = new com.example.lms.service.SettingsService(repo);
        org.mockito.Mockito.when(repo.findAll()).thenReturn(java.util.List.of(
                new com.example.lms.domain.ConfigurationSetting("chat.defaults.executionMode", "SELF_ASK")));
        assertEquals(Map.of("executionMode", "SELF_ASK"), service.getChatAdminOverrides());
        org.mockito.Mockito.when(repo.findAll()).thenReturn(java.util.List.of(
                new com.example.lms.domain.ConfigurationSetting("chat.defaults.executionMode", "BYPASS")));
        assertEquals(Map.of(), service.getChatAdminOverrides());
    }
    @Test void sharedSettingsEndpointAllowsOnlyKnownExecutionModes() {
        var service = org.mockito.Mockito.mock(com.example.lms.service.SettingsService.class);
        var controller = new SettingsController(service);
        var value = Map.of("chat.defaults.executionMode", "STRIKE");
        assertEquals(200, controller.saveAllSettings(value).getStatusCode().value());
        org.mockito.Mockito.verify(service).saveAllSettings(value);
        assertEquals(400, controller.saveAllSettings(Map.of("chat.defaults.executionMode", "BYPASS"))
                .getStatusCode().value());
        org.mockito.Mockito.verifyNoMoreInteractions(service);
        org.mockito.Mockito.when(service.getAllSettings()).thenReturn(value);
        assertEquals(value, controller.getAllSettings().getBody());
    }
}
