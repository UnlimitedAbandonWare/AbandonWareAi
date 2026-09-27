package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.plan.PlanHints;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import com.example.lms.service.SettingsService;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Request-path proof for the X-Brave-Mode bridge: the header must reach
 * GuardContext.planId on the real controller path (not only the resolver
 * helper), and the resolved "brave" lane must load the real brave.v1 plan
 * through PlanHintApplier. External search/LLM are stubbed at ChatService.
 */
class ChatApiControllerBraveHeaderRequestPathTest {

    @Test
    void braveModeHeaderReachesGuardContextOnSyncChat() {
        try (Fixture f = new Fixture()) {
            AtomicReference<String> planSeen = new AtomicReference<>();
            AtomicReference<String> modeSeen = new AtomicReference<>();
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                GuardContext ctx = GuardContextHolder.get();
                planSeen.set(ctx == null ? null : ctx.getPlanId());
                modeSeen.set(ctx == null ? null : ctx.getMode());
                return ChatResult.of("generated answer", "mock-model", false);
            });
            MockHttpServletRequest http = new MockHttpServletRequest();
            http.addHeader("X-Brave-Mode", "on");

            var response = f.controller.chat(f.dto(), null, http).block(Duration.ofSeconds(10));

            assertEquals(200, response.getStatusCode().value());
            assertEquals("brave", planSeen.get());
            assertEquals("brave", modeSeen.get());
        }
    }

    @Test
    void explicitJamminiModeWinsOverBraveHeaderOnRequestPath() {
        try (Fixture f = new Fixture()) {
            AtomicReference<String> planSeen = new AtomicReference<>();
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                GuardContext ctx = GuardContextHolder.get();
                planSeen.set(ctx == null ? null : ctx.getPlanId());
                return ChatResult.of("generated answer", "mock-model", false);
            });
            MockHttpServletRequest http = new MockHttpServletRequest();
            http.addHeader("X-Brave-Mode", "on");
            http.addHeader("X-Jammini-Mode", "s1");

            f.controller.chat(f.dto(), null, http).block(Duration.ofSeconds(10));

            assertEquals("s1", planSeen.get());
        }
    }

    @Test
    void nonOnBraveHeaderKeepsDefaultSafeLaneOnRequestPath() {
        try (Fixture f = new Fixture()) {
            AtomicReference<String> planSeen = new AtomicReference<>();
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                GuardContext ctx = GuardContextHolder.get();
                planSeen.set(ctx == null ? null : ctx.getPlanId());
                return ChatResult.of("generated answer", "mock-model", false);
            });
            for (String value : new String[]{"off", "1", "true"}) {
                MockHttpServletRequest http = new MockHttpServletRequest();
                http.addHeader("X-Brave-Mode", value);
                f.controller.chat(f.dto(), null, http).block(Duration.ofSeconds(10));
                // GuardContext.defaultContext() seeds planId="safe"; an inert
                // X-Brave-Mode value must not move the request off it.
                assertEquals("safe", planSeen.get(),
                        "X-Brave-Mode=" + value + " must stay inert");
            }
        }
    }

    @Test
    void sequentialRequestsDoNotBleedBraveContext() {
        try (Fixture f = new Fixture()) {
            java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                GuardContext ctx = GuardContextHolder.get();
                seen.add(ctx == null ? null : ctx.getPlanId());
                return ChatResult.of("generated answer", "mock-model", false);
            });
            MockHttpServletRequest brave = new MockHttpServletRequest();
            brave.addHeader("X-Brave-Mode", "on");
            f.controller.chat(f.dto(), null, brave).block(Duration.ofSeconds(10));
            f.controller.chat(f.dto(), null, new MockHttpServletRequest()).block(Duration.ofSeconds(10));

            assertEquals(java.util.List.of("brave", "safe"), seen);
        }
    }

    @Test
    void braveLaneResolvesToRealBraveV1PlanFile() {
        // The resolved lane string must load the actual plans/brave.v1.yaml
        // content through the live PlanHintApplier normalization path.
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());
        PlanHints hints = applier.load("brave");
        assertEquals(3, hints.minCitations(), "brave.v1 gate.citation.min");
        assertEquals(12, hints.queryBurstCount(), "brave.v1 expand.queryBurst.count");
    }

    @Test
    void braveModeHeaderReachesPlanLoaderOnStream() {
        try (Fixture f = new Fixture()) {
            PlanHintApplier spyApplier = spy(new PlanHintApplier(new DefaultResourceLoader()));
            ReflectionTestUtils.setField(f.controller, "planHintApplier", spyApplier);
            MockHttpServletRequest http = new MockHttpServletRequest();
            http.addHeader("X-Brave-Mode", "on");

            f.controller.chatStream(f.dto(), false, false, null, http).subscribe();

            // The stream path resolves the plan twice on purpose: once at
            // admission (validateProjectedBudgetBeforeStream) and once inside
            // the flux before prefetch/search.
            verify(spyApplier, timeout(10_000).atLeastOnce()).load("brave");
            ArgumentCaptor<PlanHints> hints = ArgumentCaptor.forClass(PlanHints.class);
            verify(spyApplier, timeout(10_000).atLeastOnce()).applyToGuardContext(hints.capture(), any());
            assertEquals(3, hints.getValue().minCitations());
            assertEquals(12, hints.getValue().queryBurstCount());
        }
    }

    @Test
    void streamWithoutBraveHeaderLoadsDefaultPlanNotBrave() {
        try (Fixture f = new Fixture()) {
            PlanHintApplier spyApplier = spy(new PlanHintApplier(new DefaultResourceLoader()));
            ReflectionTestUtils.setField(f.controller, "planHintApplier", spyApplier);

            f.controller.chatStream(f.dto(), false, false, null, new MockHttpServletRequest())
                    .subscribe();

            // No header -> default "safe" lane; the brave plan must never load.
            verify(spyApplier, timeout(10_000).atLeastOnce()).load("safe");
            verify(spyApplier, after(1_000).never()).load("brave");
        }
    }

    @Test
    void syncChatAppliesBraveHeaderToPlanLane() {
        // /api/chat/sync is the live path used by display-core.js. It must
        // honor the same X-Brave-Mode plan policy as the reactive/SSE entries.
        try (Fixture f = new Fixture()) {
            AtomicReference<String> planSeen = new AtomicReference<>();
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                GuardContext ctx = GuardContextHolder.get();
                planSeen.set(ctx == null ? null : ctx.getPlanId());
                return ChatResult.of("generated answer", "mock-model", false);
            });
            MockHttpServletRequest http = new MockHttpServletRequest();
            http.addHeader("X-Brave-Mode", "on");

            var response = f.controller.chatSync(f.dto(), null, http);

            assertEquals(200, response.getStatusCode().value());
            assertEquals("brave", planSeen.get());
        }
    }

    @Test
    void syncChatWithoutBraveHeaderKeepsDefaultSafeLane() {
        try (Fixture f = new Fixture()) {
            AtomicReference<String> planSeen = new AtomicReference<>();
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                GuardContext ctx = GuardContextHolder.get();
                planSeen.set(ctx == null ? null : ctx.getPlanId());
                return ChatResult.of("generated answer", "mock-model", false);
            });

            f.controller.chatSync(f.dto(), null, new MockHttpServletRequest());

            assertEquals("safe", planSeen.get());
        }
    }

    @Test
    void syncEndpointMapsBraveHeaderThroughDispatcherServlet() throws Exception {
        // Full MVC dispatch (URL mapping, @RequestBody binding, HttpServletRequest
        // injection) via standalone MockMvc — not a direct method call.
        try (Fixture f = new Fixture()) {
            AtomicReference<String> planSeen = new AtomicReference<>();
            when(f.chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(invocation -> {
                GuardContext ctx = GuardContextHolder.get();
                planSeen.set(ctx == null ? null : ctx.getPlanId());
                return ChatResult.of("generated answer", "mock-model", false);
            });
            MockMvc mvc = MockMvcBuilders.standaloneSetup(f.controller)
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                    .build();

            mvc.perform(post("/api/chat/sync")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Brave-Mode", "on")
                            .content("{\"message\":\"ordinary question\",\"sessionId\":42,"
                                    + "\"useRag\":false,\"useWebSearch\":false}"))
                    .andExpect(status().isOk());

            assertEquals("brave", planSeen.get());
        }
    }

    @Test
    void loadedBravePlanMutatesTheRequestGuardContext() {
        // The resolved plan must not only load — a representative setting
        // (gate.citation.min = 3) must land on the request's GuardContext.
        try (Fixture f = new Fixture()) {
            PlanHintApplier spyApplier = spy(new PlanHintApplier(new DefaultResourceLoader()));
            ReflectionTestUtils.setField(f.controller, "planHintApplier", spyApplier);
            MockHttpServletRequest http = new MockHttpServletRequest();
            http.addHeader("X-Brave-Mode", "on");

            f.controller.chatStream(f.dto(), false, false, null, http).subscribe();

            ArgumentCaptor<GuardContext> contexts = ArgumentCaptor.forClass(GuardContext.class);
            verify(spyApplier, timeout(10_000).atLeastOnce())
                    .applyToGuardContext(any(), contexts.capture());
            assertTrue(contexts.getAllValues().stream()
                    .anyMatch(c -> Integer.valueOf(3).equals(c.getMinCitations())),
                    "brave.v1 gate.citation.min must reach the GuardContext");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ChatHistoryService history = mock(ChatHistoryService.class);
        final ChatService chat = mock(ChatService.class);
        final ChatRunRegistry registry = spy(new ChatRunRegistry());
        final ChatApiController controller;

        Fixture() {
            ReflectionTestUtils.setField(registry, "replayCapacity", 32);
            ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
            SettingsService settings = mock(SettingsService.class);
            ClientOwnerKeyResolver owners = mock(ClientOwnerKeyResolver.class);
            when(settings.getAllSettings()).thenReturn(Map.of());
            when(owners.ownerKey()).thenReturn("owner-a");
            controller = new ChatApiController(history, chat, null, settings, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    new com.fasterxml.jackson.databind.ObjectMapper(), null, registry, owners);
            ChatSession session = new ChatSession("brave-path", "owner-a", "ANON");
            session.setId(42L);
            when(history.getSessionWithMessages(42L)).thenReturn(session);
            when(history.getSessionWithMessages(42L, 1)).thenReturn(session);
            when(history.startNewSession(any(), any(), any(), any(), any()))
                    .thenReturn(java.util.Optional.of(session));
            when(chat.continueChat(any(ChatRequestDto.class), any()))
                    .thenReturn(ChatResult.of("generated answer", "mock-model", false));
        }

        ChatRequestDto dto() {
            return ChatRequestDto.builder().message("ordinary question")
                    .sessionId(42L).useRag(false).useWebSearch(false).build();
        }

        @Override
        public void close() {
            ReflectionTestUtils.invokeMethod(registry, "shutdown");
        }
    }
}
