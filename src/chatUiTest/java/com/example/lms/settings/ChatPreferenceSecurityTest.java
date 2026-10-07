package com.example.lms.settings;

import com.example.lms.config.AppSecurityConfig;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.service.AdminDetailsServiceImpl;
import com.example.lms.web.OwnerKeyBootstrapFilter;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import jakarta.servlet.Filter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class ChatPreferenceSecurityTest {
    AnnotationConfigWebApplicationContext context; MockMvc mvc;
    static final String A = "22222222-2222-4222-8222-222222222222", B = "33333333-3333-4333-8333-333333333333";
    static final java.util.Map<String, com.example.lms.service.ChatPreferenceService.State> states = new java.util.HashMap<>();
    @Configuration @EnableWebSecurity @EnableWebMvc @Import({AppSecurityConfig.class, com.example.lms.config.AdminTokenGuardWebMvcConfig.class,
            com.example.lms.api.ChatPreferencesController.class})
    static class Fixture {

        @Bean com.example.lms.web.ClientOwnerKeyResolver owner(jakarta.servlet.http.HttpServletRequest request) {
            return new com.example.lms.web.ClientOwnerKeyResolver(request);
        }
        @Bean com.example.lms.config.ChatDefaultsProperties defaults() {
            var p = new com.example.lms.config.ChatDefaultsProperties(); p.setModel("fixture-model"); p.setModelSelectionMode("preferred");
            p.setTemperature(0.2); p.setTopP(1.0); p.setFrequencyPenalty(0.0); p.setPresencePenalty(0.0); p.setMaxTokens(2048);
            p.setUseRag(true); p.setUseWebSearch(false); p.setSearchMode("OFF"); p.setRagAnswerPolicy("adaptive"); p.setDefaultsVersion("1"); return p;
        }
        @Bean com.example.lms.service.SettingsService settings() {
            var service = mock(com.example.lms.service.SettingsService.class);
            when(service.getChatAdminOverrides()).thenReturn(java.util.Map.of()); return service;
        }
        @Bean com.example.lms.service.ChatPreferenceService preferences() {
            var service = mock(com.example.lms.service.ChatPreferenceService.class);
            when(service.read(anyString())).thenAnswer(call -> states.getOrDefault(call.getArgument(0),
                    new com.example.lms.service.ChatPreferenceService.State(java.util.Map.of(), 0, null)));
            when(service.patch(anyString(), anyMap(), anyList(), anyLong(), nullable(String.class))).thenAnswer(call -> {
                String owner = call.getArgument(0);
                var clean = com.example.lms.service.ChatPreferenceService.validate(call.getArgument(1));
                var state = new com.example.lms.service.ChatPreferenceService.State(clean, 1, "a".repeat(64));
                states.put(owner, state); return state;
            }); return service;
        }
        @Bean jakarta.persistence.EntityManagerFactory syntheticPersistence() {
            var factory = mock(jakarta.persistence.EntityManagerFactory.class);
            when(factory.getProperties()).thenReturn(java.util.Map.of());
            return factory;
        }
        @Bean AdministratorRepository admins() { return mock(AdministratorRepository.class); }
        @Bean AdminDetailsServiceImpl details() { return mock(AdminDetailsServiceImpl.class); }
        @Bean AdminTokenGuardInterceptor guard() {
            var guard = new AdminTokenGuardInterceptor();
            ReflectionTestUtils.setField(guard, "tokenRequired", true);
            ReflectionTestUtils.setField(guard, "expectedToken", "synthetic-fixture-capability");
            return guard;
        }
    }
    @BeforeEach void open() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        states.clear(); context.register(Fixture.class); context.refresh();
        ReflectionTestUtils.setField(context.getBean(AdminTokenGuardInterceptor.class), "tokenRequired", true);
        ReflectionTestUtils.setField(context.getBean(AdminTokenGuardInterceptor.class), "expectedToken", "synthetic-fixture-capability");
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new OwnerKeyBootstrapFilter(),
                context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @AfterEach void close() { context.close(); }

    @Test void anonymousOwnerPatchSucceedsWithOnlyWhitelistedValues() throws Exception {
        var response = mvc.perform(patch("/api/settings/preferences").cookie(new jakarta.servlet.http.Cookie("ownerKey", A))
                .contentType("application/json").content("{\"expectedRevision\":0,\"set\":{\"temperature\":0,\"useRag\":false}}"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var state = states.get(com.example.lms.service.AttachmentOwnerIdentity.forAnonymous(A).hash());
        assertEquals(java.util.Map.of("temperature", 0.0, "useRag", false), state.overrides());
    }
    @Test void anotherOwnerCannotBeSelectedByBodyQueryOrHeader() throws Exception {
        String victim = com.example.lms.service.AttachmentOwnerIdentity.forAnonymous(B).hash();
        var original = new com.example.lms.service.ChatPreferenceService.State(java.util.Map.of("topP", 0.42), 7, "b".repeat(64));
        states.put(victim, original);
        var response = mvc.perform(patch("/api/settings/preferences").param("owner", B).header("X-Owner-Key", B)
                .cookie(new jakarta.servlet.http.Cookie("ownerKey", A)).contentType("application/json")
                .content("{\"owner\":\"" + B + "\",\"expectedRevision\":0,\"set\":{\"useRag\":false}}")).andReturn().getResponse();
        assertEquals(200, response.getStatus()); // Spoofed owner input is ignored; only the caller's own row can change.
        assertEquals(original, states.get(victim));
        assertEquals(java.util.Map.of("useRag", false), states.get(com.example.lms.service.AttachmentOwnerIdentity.forAnonymous(A).hash()).overrides());
    }
    @Test void crossOriginPatchIsRejected() throws Exception {
        assertEquals(403, mvc.perform(patch("/api/settings/preferences").header("Origin", "https://other.example")
                .contentType("application/json").content("{\"expectedRevision\":0,\"set\":{}}")).andReturn().getResponse().getStatus());
        assertTrue(states.isEmpty());
    }
    @Test void traceSaveCannotCrossAnOwnerChangeWithIdenticalRevisionAndHash() throws Exception {
        String expectedScope = com.example.lms.service.AttachmentOwnerIdentity.forAnonymous(A).hash();
        var response = mvc.perform(patch("/api/settings/preferences")
                .cookie(new jakarta.servlet.http.Cookie("ownerKey", B)).contentType("application/json")
                .content("{\"expectedRevision\":0,\"expectedHash\":null,\"expectedOwnerScopeId\":\"" + expectedScope
                        + "\",\"set\":{\"chatTraceEnabled\":false}}"))
                .andReturn().getResponse();
        assertEquals(409, response.getStatus());
        assertTrue(states.isEmpty());
    }
    @Test void traceSaveAcceptsItsVerifiedOwnerWithoutChangingProtectedAccess() throws Exception {
        String scope = com.example.lms.service.AttachmentOwnerIdentity.forAnonymous(A).hash();
        assertEquals(200, mvc.perform(patch("/api/settings/preferences")
                .cookie(new jakarta.servlet.http.Cookie("ownerKey", A)).contentType("application/json")
                .content("{\"expectedRevision\":0,\"expectedHash\":null,\"expectedOwnerScopeId\":\"" + scope
                        + "\",\"set\":{\"chatTraceEnabled\":false}}"))
                .andReturn().getResponse().getStatus());
        assertEquals(false, states.get(scope).overrides().get("chatTraceEnabled"));
    }
    @Test void globalAndAdminApisRetainProtectedStatus() throws Exception {
        assertEquals(403, mvc.perform(post("/api/settings").contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(get("/admin/pipeline-status")).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(patch("/api/settings/routing").contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(post("/api/settings/preferences").contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
    }
}
