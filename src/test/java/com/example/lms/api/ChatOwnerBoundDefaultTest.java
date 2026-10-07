package com.example.lms.api;

import com.example.lms.config.ChatDefaultsProperties;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.llm.ChatGptOAuthRegistration;
import com.example.lms.llm.gateway.CloudModelRouteClassifier;
import com.example.lms.service.*;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatOwnerBoundDefaultTest {
    @Test void preferenceDescriptorBindsDraftToServerOwnerWithoutClientAuthority() {
        var settings = mock(SettingsService.class); when(settings.getChatAdminOverrides()).thenReturn(Map.of());
        var service = mock(ChatPreferenceService.class);
        var a = new ChatPreferenceService.State(Map.of("googleSearchRescueEnabled", true), 1, "a".repeat(64));
        var b = new ChatPreferenceService.State(Map.of("googleSearchRescueEnabled", false), 2, "b".repeat(64));
        var owner = mock(ClientOwnerKeyResolver.class); when(owner.ownerKey()).thenReturn(SYNTHETIC_OWNER_KEY);
        when(service.read(OWNER)).thenReturn(a);
        var controller = new ChatPreferencesController(service, settings, factory(), owner);
        var first = (Map<?, ?>) controller.get().getBody();
        assertEquals(OWNER, first.get("ownerScopeId"));
        assertEquals(true, ((Map<?, ?>) first.get("overrides")).get("googleSearchRescueEnabled"));
        assertEquals(first.get("ownerScopeId"), ((Map<?, ?>) controller.get().getBody()).get("ownerScopeId"));
        assertFalse(first.toString().contains(SYNTHETIC_OWNER_KEY));
        String secondKey = "00000000-0000-0000-0000-000000000002";
        String secondOwner = AttachmentOwnerIdentity.forAnonymous(secondKey).hash();
        when(owner.ownerKey()).thenReturn(secondKey); when(service.read(secondOwner)).thenReturn(b);
        var second = (Map<?, ?>) controller.get().getBody();
        assertEquals(secondOwner, second.get("ownerScopeId"));
        assertNotEquals(first.get("ownerScopeId"), second.get("ownerScopeId"));
        assertEquals(false, ((Map<?, ?>) second.get("overrides")).get("googleSearchRescueEnabled"));
        var servlet = new org.springframework.mock.web.MockHttpServletRequest();
        when(service.patch(eq(secondOwner), anyMap(), anyList(), eq(2L), eq(b.hash()))).thenReturn(b);
        controller.patch(Map.of("expectedRevision", 2, "expectedHash", b.hash(), "owner", OWNER,
                "ownerKey", SYNTHETIC_OWNER_KEY, "set", Map.of("googleSearchRescueEnabled", false)), servlet);
        verify(service).patch(eq(secondOwner), eq(Map.of("googleSearchRescueEnabled", false)), eq(List.of()), eq(2L), eq(b.hash()));
        verify(service, never()).patch(eq(OWNER), anyMap(), anyList(), anyLong(), any());
    }
    private static final String SYNTHETIC_OWNER_KEY = "00000000-0000-0000-0000-000000000001";
    private static final String OWNER = AttachmentOwnerIdentity.forAnonymous(SYNTHETIC_OWNER_KEY).hash();
    private static final String LUNA = "chatgpt-oauth:gpt-5.6-luna";

    private ChatDefaultsProperties factory() {
        var f = new ChatDefaultsProperties();
        f.setModel("chatgpt-oauth:gpt-5.5"); f.setModelSelectionMode("preferred");
        f.setTemperature(0.3); f.setTopP(1.0); f.setFrequencyPenalty(0.0); f.setPresencePenalty(0.0);
        f.setMaxTokens(512); f.setUseRag(true); f.setUseWebSearch(false); f.setSearchMode("OFF");
        f.setRagAnswerPolicy("adaptive"); f.setDefaultsVersion("fixture");
        return f;
    }

    @Test void googleSearchRescueRequestSessionUserAndFactoryPrecedencePreservesFalse() {
        var f = factory();
        var log = LoggerFactory.getLogger(getClass());
        var user = Map.<String, Object>of("googleSearchRescueEnabled", true);
        var omitted = ChatRequestDto.builder().build();
        assertEquals(false, ChatRequestSettingsMerger.resolve(omitted, Map.of(), Map.of(), f, log)
                .effective().get("googleSearchRescueEnabled"));
        assertEquals(true, ChatRequestSettingsMerger.resolve(omitted, user, Map.of(), f, log)
                .effective().get("googleSearchRescueEnabled"));
        assertEquals("USER", ChatRequestSettingsMerger.resolve(omitted, user, Map.of(), f, log)
                .sources().get("googleSearchRescueEnabled"));
        var session = ChatRequestDto.builder().googleSearchRescueEnabled(false).build();
        session.bindChatSettingsSnapshot(new ChatRequestDto.ChatSettingsSnapshot(user, Map.of(), Map.of(), f.values()));
        var restored = ChatRequestSettingsMerger.resolve(session, user, Map.of(), f, log);
        assertEquals(false, restored.effective().get("googleSearchRescueEnabled"));
        assertEquals("SESSION", restored.sources().get("googleSearchRescueEnabled"));
        var request = ChatRequestDto.builder().googleSearchRescueEnabled(false).build();
        request.bindChatSettingsSnapshot(new ChatRequestDto.ChatSettingsSnapshot(user, Map.of(),
                ChatRequestSettingsMerger.requestValues(request), f.values()));
        var explicit = ChatRequestSettingsMerger.resolve(request, user, Map.of(), f, log);
        assertEquals(false, explicit.effective().get("googleSearchRescueEnabled"));
        assertEquals("REQUEST", explicit.sources().get("googleSearchRescueEnabled"));
        assertFalse(explicit.request().isGoogleSearchRescueEnabled());
        f.setGoogleSearchRescueEnabled(true);
        assertTrue(ChatRequestSettingsMerger.resolve(omitted, Map.of(), Map.of(), f, log).request().isGoogleSearchRescueEnabled());
    }

    @Test void proposalRequiresExactRegisteredOwnerMembershipAndNeverProbesInventory() {
        var cloud = mock(CloudModelRouteClassifier.class);
        var registration = mock(ChatGptOAuthRegistration.class);
        var catalog = new ChatModelCatalogService(cloud, null, new RestTemplateBuilder(), "https://fixture.invalid", false);
        ReflectionTestUtils.setField(catalog, "chatGptOAuth", registration);
        var f = factory(); var original = f.values();
        when(registration.isRegisteredOwner(OWNER)).thenReturn(true);
        when(registration.models(OWNER)).thenReturn(List.of("gpt-5.6-luna"));
        var proposal = catalog.firstSessionDefaults(original, OWNER);
        assertEquals(LUNA, proposal.get("model"));
        assertEquals("OFF", proposal.get("searchMode"));
        assertEquals(original, f.values());
        assertEquals(original, catalog.firstSessionDefaults(original, "b".repeat(64)));
        assertEquals(original, catalog.firstSessionDefaults(original, null));
        for (var models : List.of(List.<String>of(), List.of("gpt-5.6-luna-other"), List.of("gpt-5.5"))) {
            when(registration.models(OWNER)).thenReturn(models);
            assertEquals(original, catalog.firstSessionDefaults(original, OWNER));
        }
        when(registration.models(OWNER)).thenReturn(List.of("gpt-5.6-luna"));
        var pinned = new java.util.LinkedHashMap<>(original); pinned.put("modelSelectionMode", "strict");
        assertEquals(pinned, catalog.firstSessionDefaults(pinned, OWNER));
        verifyNoInteractions(cloud);
        verify(registration, never()).modelFor(anyString(), anyLong());
    }

    @Test void preferenceDescriptorProposesLunaButSavedPinnedAndOffSettingsWin() {
        var service = mock(ChatPreferenceService.class); var settings = mock(SettingsService.class);
        var owners = mock(ClientOwnerKeyResolver.class); when(owners.ownerKey()).thenReturn(SYNTHETIC_OWNER_KEY);
        when(settings.getChatAdminOverrides()).thenReturn(Map.of());
        var f = factory(); var catalog = mock(ChatModelCatalogService.class);
        var proposal = new java.util.LinkedHashMap<>(f.values()); proposal.put("model", LUNA);
        when(catalog.firstSessionDefaults(anyMap(), eq(OWNER))).thenReturn(Map.copyOf(proposal));
        var controller = new ChatPreferencesController(service, settings, f, owners);
        ReflectionTestUtils.setField(controller, "modelCatalog", catalog);
        when(service.read(anyString())).thenReturn(new ChatPreferenceService.State(Map.of(), 0, null));
        var empty = new ObjectMapper().valueToTree(controller.get().getBody());
        assertEquals(LUNA, empty.path("effective").path("model").asText());
        assertEquals("FACTORY", empty.path("sources").path("model").asText());
        var saved = Map.<String,Object>of("model", "chatgpt-oauth:gpt-5.5", "modelSelectionMode", "strict",
                "searchMode", "OFF", "useRag", false);
        when(service.read(anyString())).thenReturn(new ChatPreferenceService.State(saved, 7, "a".repeat(64)));
        var stored = new ObjectMapper().valueToTree(controller.get().getBody());
        assertEquals("chatgpt-oauth:gpt-5.5", stored.path("effective").path("model").asText());
        assertEquals("strict", stored.path("effective").path("modelSelectionMode").asText());
        assertEquals("USER", stored.path("sources").path("model").asText());
        assertEquals("OFF", stored.path("effective").path("searchMode").asText());
        assertFalse(stored.path("effective").path("useRag").asBoolean());
        verify(service, never()).patch(anyString(), anyMap(), anyList(), anyLong(), any());
        verify(catalog, never()).choices(anyString());
        assertEquals("chatgpt-oauth:gpt-5.5", f.getModel());
    }

    @Test void submissionFreezesFactoryProposalAndExplicitRequestStillWins() {
        var settings = mock(SettingsService.class); when(settings.getChatAdminOverrides()).thenReturn(Map.of());
        var preferences = mock(ChatPreferenceService.class);
        when(preferences.read(anyString())).thenReturn(new ChatPreferenceService.State(Map.of(), 0, null));
        var catalog = mock(ChatModelCatalogService.class); var f = factory();
        var proposal = new java.util.LinkedHashMap<>(f.values()); proposal.put("model", LUNA);
        when(catalog.firstSessionDefaults(anyMap(), eq(OWNER))).thenReturn(proposal);
        var controller = new ChatApiController(null, null, null, settings, null, null, null, null,
                null, null, null, null, null, null, null, null, new ObjectMapper(), null, null, null);
        ReflectionTestUtils.setField(controller, "chatPreferenceService", preferences);
        ReflectionTestUtils.setField(controller, "chatDefaultsProperties", f);
        ReflectionTestUtils.setField(controller, "modelCatalog", catalog);
        var request = ChatRequestDto.builder().message("fixture").build();
        ReflectionTestUtils.invokeMethod(controller, "captureChatSettings", request, SYNTHETIC_OWNER_KEY);
        proposal.put("model", "changed-after-submit"); f.setModel("changed-factory");
        var resolved = ChatRequestSettingsMerger.resolve(request, Map.of(), Map.of(), f, LoggerFactory.getLogger(getClass()));
        assertEquals(LUNA, resolved.request().getModel());
        assertEquals("FACTORY", resolved.sources().get("model"));
        assertThrows(UnsupportedOperationException.class, () -> request.getChatSettingsSnapshot().factory().put("model", "mutable"));
        request.setModel("chatgpt-oauth:gpt-5.5"); request.setModelSelectionMode("strict");
        // An explicitly captured request must remain above the owner proposal.
        var explicit = ChatRequestDto.builder().message("fixture").model("chatgpt-oauth:gpt-5.5")
                .modelSelectionMode("strict").useWebSearch(false).useRag(false).build();
        var snapshot = request.getChatSettingsSnapshot();
        explicit.bindChatSettingsSnapshot(new ChatRequestDto.ChatSettingsSnapshot(Map.of(), Map.of(),
                ChatRequestSettingsMerger.requestValues(explicit), snapshot.factory()));
        var chosen = ChatRequestSettingsMerger.resolve(explicit, Map.of(), Map.of(), f, LoggerFactory.getLogger(getClass()));
        assertEquals("chatgpt-oauth:gpt-5.5", chosen.request().getModel());
        assertEquals("REQUEST", chosen.sources().get("model"));
        assertTrue(chosen.request().isStrictModelSelection());
        assertFalse(chosen.request().getUseWebSearch()); assertFalse(chosen.request().getUseRag());
    }
}
