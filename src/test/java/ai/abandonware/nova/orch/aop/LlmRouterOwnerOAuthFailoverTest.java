package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.router.LocalModelAdmission;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.*;
import com.example.lms.llm.gateway.*;
import com.example.lms.routing.RoutingProfile;
import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmRouterOwnerOAuthFailoverTest {
    private static final String OWNER = "a".repeat(64);
    private static final String OAUTH = "chatgpt-oauth:owner-model";
    private static final String LOCAL = "synthetic-local";
    private static final String REASON = "local_unavailable_owner_oauth";

    @AfterEach void clear() { TraceStore.clear(); }

    @Test void T0_mainDecisionDoesNotExistAtPreferredConstructionBoundary() throws Exception {
        try (Fixture f = new Fixture()) {
            RequestedModelSelection.begin(null, OWNER);
            var router = spy(new com.example.lms.service.routing.PolicyBasedModelRouter(f.local, null, null,
                    mock(com.example.lms.service.routing.RouterPolicy.class), null));
            AtomicInteger constructions = new AtomicInteger();
            doAnswer(invocation -> {
                constructions.incrementAndGet();
                assertNull(RequestedModelSelection.mainDecision());
                assertNull(RequestedModelSelection.mainRole());
                return f.local;
            }).when(router).route(anyString(), anyString(), anyString(), anyInt(), eq(LOCAL));
            router.routeMain("qa", "low", "brief", 128, LOCAL, "synthetic", false);
            assertEquals(1, constructions.get());
            assertNotNull(RequestedModelSelection.mainDecision());
        }
    }

    @Test void T1_ownerPreferredPreselectionUsesOneOAuthAndNoLocalDispatch() throws Exception {
        try (Fixture f = new Fixture()) {
            f.main(OWNER, false); f.saturate();
            ChatResponse answer = f.routed().chat(List.of(UserMessage.from("synthetic")));
            assertEquals("OAUTH_OK", answer.aiMessage().text());
            assertEquals(0, f.localCalls.get()); assertEquals(0, f.cloudCalls.get());
            assertEquals(1, f.oauthCalls.get());
            assertEquals(REASON, TraceStore.get("llm.gateway.fallbackReason"));
            assertEquals(OAUTH, answer.metadata().modelName());
            verify(f.registration, times(1)).modelFor(eq(OAUTH), anyLong());
        }
    }

    @Test void T9_initialMainConstructionUsesOAuthAndRestoresMarker() throws Exception {
        try (Fixture f = new Fixture()) {
            RequestedModelSelection.begin(null, OWNER); f.saturate();
            var router = spy(new com.example.lms.service.routing.PolicyBasedModelRouter(f.local, null, null,
                    mock(com.example.lms.service.routing.RouterPolicy.class), null));
            doAnswer(invocation -> {
                assertNull(RequestedModelSelection.mainDecision());
                var pjp = mock(org.aspectj.lang.ProceedingJoinPoint.class);
                when(pjp.getArgs()).thenReturn(new Object[]{LOCAL, 0.0, null, null, null, 32, 5, 0});
                return f.aspect.aroundLcWithTimeout(pjp);
            }).when(router).route(anyString(), anyString(), anyString(), anyInt(), eq(LOCAL));
            ChatModel selected = router.routeMain("qa", "low", "brief", 128, LOCAL, "synthetic", false);
            assertNull(TraceStore.get("chat.internal.exactModelSelection.mainConstruction"));
            assertEquals(OAUTH, RequestedModelSelection.mainDecision().selectedKey());
            assertEquals("OAUTH_OK", selected.chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());
            assertEquals(1, f.oauthCalls.get()); assertEquals(0, f.cloudCalls.get());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void T10_invokeTimeAdmissionRaceUsesOneOAuth(boolean apiFirst) throws Exception {
        try (Fixture f = new Fixture()) {
            f.env.setProperty("llmrouter.api-first.enabled", String.valueOf(apiFirst));
            f.main(OWNER, false); f.lateAdmission();
            assertEquals("OAUTH_OK", f.routed().chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());
            assertEquals(1, f.oauthCalls.get()); assertEquals(0, f.cloudCalls.get());
            assertEquals(0, f.localCalls.get());
        }
    }

    @ParameterizedTest @ValueSource(ints = {401, 403, 429})
    void T11_lazyOAuthHttpFailureDoesNotTryApiKeyRoute(int status) throws Exception {
        try (Fixture f = new Fixture()) {
            f.env.setProperty("llmrouter.api-first.enabled", "false");
            f.main(OWNER, false); f.lateAdmission();
            f.oauthFailure = new dev.langchain4j.exception.HttpException(status, "synthetic failure");
            assertThrows(RuntimeException.class, () -> f.routed().chat(List.of(UserMessage.from("synthetic"))));
            assertEquals(1, f.oauthCalls.get()); assertEquals(0, f.cloudCalls.get());
        }
    }

    @Test void T12_preselectedOAuthCannotLeakThroughRequestedModelCache() throws Exception {
        try (Fixture f = new Fixture()) {
            var factory = mock(DynamicChatModelFactory.class);
            when(factory.canServe(LOCAL)).thenReturn(true);
            when(factory.lcWithTimeout(eq(LOCAL), anyDouble(), isNull(), anyInt(), anyInt())).thenAnswer(i -> {
                var pjp = mock(org.aspectj.lang.ProceedingJoinPoint.class);
                when(pjp.getArgs()).thenReturn(new Object[]{LOCAL, 0.0, null, null, null, 32, 5, 0});
                return f.aspect.aroundLcWithTimeout(pjp);
            });
            var router = spy(new com.example.lms.service.routing.PolicyBasedModelRouter(f.local, null, null,
                    mock(com.example.lms.service.routing.RouterPolicy.class), factory));
            doReturn(f.local).when(router).route(anyString(), anyString(), anyString(), anyInt());
            RequestedModelSelection.begin(null, OWNER); f.saturate();
            var ownerModel = router.routeMain("qa", "low", "brief", 128, LOCAL, "synthetic", false);
            assertEquals("OAUTH_OK", ownerModel.chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());
            RequestedModelSelection.begin(null, "b".repeat(64));
            var otherModel = router.routeMain("qa", "low", "brief", 128, LOCAL, "synthetic", false);
            assertNotSame(ownerModel, otherModel);
            assertEquals("CLOUD_OK", otherModel.chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());
            assertEquals(1, f.oauthCalls.get());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void T13_sameModelAssistCannotUseRequestWideMainDecision(boolean late) throws Exception {
        try (Fixture f = new Fixture()) {
            f.main(OWNER, false); if (late) f.lateAdmission(); else f.saturate();
            var assist = f.aspect.routeLocalInference(f.local, "http://127.0.0.1:11434/v1", LOCAL, 5000);
            assertEquals("CLOUD_OK", assist.chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());
            f.noOAuth(); assertEquals(0, f.localCalls.get()); assertEquals(1, f.cloudCalls.get());
        }
    }

    @Test void T14_sameOwnerReevaluatesRefusedLocalConstruction() throws Exception {
        try (Fixture f = new Fixture()) {
            var factory = mock(DynamicChatModelFactory.class); when(factory.canServe(LOCAL)).thenReturn(true);
            when(factory.lcWithTimeout(eq(LOCAL), anyDouble(), isNull(), anyInt(), anyInt())).thenAnswer(i -> {
                var pjp = mock(org.aspectj.lang.ProceedingJoinPoint.class);
                when(pjp.getArgs()).thenReturn(new Object[]{LOCAL, 0.0, null, null, null, 32, 5, 0});
                return f.aspect.aroundLcWithTimeout(pjp);
            });
            var router = spy(new com.example.lms.service.routing.PolicyBasedModelRouter(f.local, null, null,
                    mock(com.example.lms.service.routing.RouterPolicy.class), factory));
            doReturn(f.local).when(router).route(anyString(), anyString(), anyString(), anyInt()); f.saturate();
            RequestedModelSelection.begin(null, OWNER);
            var first = router.routeMain("qa", "low", "brief", 128, LOCAL, "synthetic", false);
            RequestedModelSelection.begin(null, OWNER);
            var second = router.routeMain("qa", "low", "brief", 128, LOCAL, "synthetic", false);
            verify(factory, times(2)).lcWithTimeout(eq(LOCAL), anyDouble(), isNull(), anyInt(), anyInt());
            assertSame(first, second); // Registration reuses its stub; construction still re-runs.
        }
    }

    @Test void T2_strictPreservesUnavailable() throws Exception {
        try (Fixture f = new Fixture()) {
            f.main(OWNER, true); f.saturate();
            var failure = assertThrows(ModelSelectionException.class,
                    () -> f.routed().chat(List.of(UserMessage.from("synthetic"))));
            assertTrue(failure.getMessage().contains("model_unavailable")); f.noOAuth();
            assertEquals(0, f.localCalls.get()); assertEquals(0, f.cloudCalls.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"other", "anonymous"})
    void T3_nonOwnerKeepsApiKeyFallback(String kind) throws Exception {
        try (Fixture f = new Fixture()) {
            f.main(kind.equals("other") ? "b".repeat(64) : null, false); f.saturate();
            f.cloudAnswer(); f.noOAuth(); assertEquals(0, f.localCalls.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"usage_limit_recheck_required", "chatgpt_oauth_scope_missing", "chatgpt_oauth_owner_mismatch"})
    void T4_registrationRejectionKeepsApiKeyFallback(String reason) throws Exception {
        try (Fixture f = new Fixture()) {
            f.main(OWNER, false); f.saturate();
            doThrow(ChatGptOAuthRegistration.unavailable(reason)).when(f.registration).modelFor(anyString(), anyLong());
            f.cloudAnswer(); assertEquals(0, f.oauthCalls.get());
            verify(f.registration, never()).modelFor(argThat(route -> !OAUTH.equals(route)), anyLong());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"401", "403", "429", "terminal"})
    void T5_existingLoopStopsOnOAuthTerminalWithoutAnotherRoute(String reason) {
        AtomicInteger oauthCalls = new AtomicInteger(), resolutions = new AtomicInteger();
        var terminal = new LlmResponseTerminalException(reason, LlmFailureClass.AUTH_MISSING,
                "", null, "failed", null, reason);
        ChatModel primary = new ChatModel() { public ChatResponse doChat(ChatRequest request) { throw new LlmGatewayException("refused", LlmFailureClass.SOFT_CIRCUIT_OPEN, "local_contended"); } };
        ChatModel oauth = new ChatModel() { public ChatResponse doChat(ChatRequest request) { oauthCalls.incrementAndGet(); throw terminal; } };
        var model = new FallbackAwareChatModel(primary, (failure, used) -> {
            resolutions.incrementAndGet(); return new FallbackAwareChatModel.ResolvedFallback(oauth, OAUTH, null);
        }, new LlmGatewayFailureClassifier(), null, "primary", null, null, null, 2).withApiFirstPolicy();
        assertSame(terminal, assertThrows(LlmResponseTerminalException.class,
                () -> model.chat(List.of(UserMessage.from("synthetic")))));
        assertEquals(1, oauthCalls.get()); assertEquals(1, resolutions.get());
    }

    @Test void T6_alreadyDispatchedLocalTimeoutDoesNotAddOAuth() throws Exception {
        try (Fixture f = new Fixture()) {
            f.main(OWNER, false);
            f.localFailure = new LlmGatewayException("timeout", LlmFailureClass.TIMEOUT_SOFT, "backend_timeout");
            f.cloudAnswer(); f.noOAuth(); assertEquals(1, f.localCalls.get());
        }
    }

    @ParameterizedTest @ValueSource(ints = {200, 400})
    void T6c_nativeHttpCircuitErrorAfterDispatchDoesNotAddOAuth(int status) throws Exception {
        try (Fixture f = new Fixture()) {
            f.cloud.createContext("/api/chat", exchange -> {
                exchange.getRequestBody().readAllBytes(); f.localCalls.incrementAndGet();
                byte[] body = "{\"error\":\"circuit open\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            String baseUrl = "http://127.0.0.1:" + f.cloud.getAddress().getPort() + "/v1";
            ChatModel nativeLocal = new OllamaNativeChatModel(baseUrl, LOCAL, java.time.Duration.ofSeconds(5),
                    128, 0.0, null, new ModelRuntimeHealthTracker(), false);
            f.main(OWNER, false);
            ChatResponse answer = f.routed(nativeLocal, baseUrl).chat(List.of(UserMessage.from("synthetic")));
            assertEquals(1, f.localCalls.get()); f.noOAuth();
            assertEquals("CLOUD_OK", answer.aiMessage().text()); assertEquals(1, f.cloudCalls.get());
        }
    }

    @Test void T6d_concurrentTraceBusyAfterNativeDispatchDoesNotAddOAuth() throws Exception {
        try (Fixture f = new Fixture()) {
            f.main(OWNER, false);
            Map<String, Object> sharedTrace = TraceStore.context();
            f.cloud.createContext("/api/chat", exchange -> {
                exchange.getRequestBody().readAllBytes(); f.localCalls.incrementAndGet();
                sharedTrace.put("llm.localEndpoint.selectionDecision", "local_backend_busy");
                byte[] body = "{\"error\":\"rate limit\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            String baseUrl = "http://127.0.0.1:" + f.cloud.getAddress().getPort() + "/v1";
            ChatModel nativeLocal = new OllamaNativeChatModel(baseUrl, LOCAL, java.time.Duration.ofSeconds(5),
                    128, 0.0, null, new ModelRuntimeHealthTracker(), false);
            ChatResponse answer = f.routed(nativeLocal, baseUrl).chat(List.of(UserMessage.from("synthetic")));
            assertEquals(1, f.localCalls.get()); f.noOAuth();
            assertEquals("CLOUD_OK", answer.aiMessage().text()); assertEquals(1, f.cloudCalls.get());
        }
    }

    @Test void T12_gatewayCircuitRefusalBeforeDelegateStillUsesOwnerOAuth() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.gateway.guardLocalModel(any(), anyString(), anyString(), any())).thenAnswer(i -> new ChatModel() {
                public ChatResponse doChat(ChatRequest request) {
                    TraceStore.put("llm.localEndpoint.selectionDecision", "open_blocked");
                    throw new LlmGatewayException("Local circuit open before delegate", LlmFailureClass.GPU_DEVICE_LOST, "local_endpoint_quarantine");
                }
            });
            f.main(OWNER, false);
            assertEquals("OAUTH_OK", f.routed().chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());
            assertEquals(0, f.localCalls.get()); assertEquals(1, f.oauthCalls.get()); assertEquals(0, f.cloudCalls.get());
        }
    }

    @Test void T6b_apiFirstOffDispatchedTimeoutRemainsTerminal() throws Exception {
        try (Fixture f = new Fixture()) {
            f.env.setProperty("llmrouter.api-first.enabled", "false"); f.main(OWNER, false);
            f.localFailure = new LlmGatewayException("timeout", LlmFailureClass.TIMEOUT_SOFT, "backend_timeout");
            assertThrows(RuntimeException.class, () -> f.routed().chat(List.of(UserMessage.from("synthetic"))));
            f.noOAuth(); assertEquals(1, f.localCalls.get()); assertEquals(0, f.cloudCalls.get());
        }
    }

    @Test void T7_blankMainModelKeepsApiKeyFallback() throws Exception {
        try (Fixture f = new Fixture()) {
            f.main(OWNER, false); f.env.setProperty("chatgpt.oauth.main-model", ""); f.saturate();
            f.cloudAnswer(); f.noOAuth();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "assist", "different-main"})
    void T8_nonMainCallKeepsApiKeyFallback(String kind) throws Exception {
        try (Fixture f = new Fixture()) {
            RequestedModelSelection.begin(null, OWNER);
            if (!kind.equals("absent")) {
                RequestedModelSelection.rememberMainDecision(new LlmRouteDecision("other", "other", null, null, false, "synthetic"));
                RequestedModelSelection.rememberMainRole(kind.equals("assist") ? RoutingProfile.Role.SELFASK_BQ : RoutingProfile.Role.MAIN_DEFAULT);
            }
            f.saturate(); f.cloudAnswer(); f.noOAuth();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger localCalls = new AtomicInteger(), cloudCalls = new AtomicInteger(), oauthCalls = new AtomicInteger();
        final HttpServer cloud;
        final MockEnvironment env = new MockEnvironment().withProperty("llm.api-key", "ollama")
                .withProperty("llmrouter.api-first.enabled", "true").withProperty("llmrouter.api-first.route-order", "cloud").withProperty("chatgpt.oauth.main-model", "owner-model");
        final ChatGptOAuthRegistration registration = spy(new ChatGptOAuthRegistration(false,
                Path.of("unused-synthetic-credentials"), Path.of("unused-synthetic-catalogue"), 0, Clock.systemUTC(), timeout -> false));
        final LlmRouterAspect aspect;
        final HybridLlmGatewayProbeService gateway;
        RuntimeException localFailure;
        RuntimeException oauthFailure;
        final ChatModel local = new NamedChatModel() {
            public String resolvedModelName() { return LOCAL; }
            public ChatResponse doChat(ChatRequest request) {
                localCalls.incrementAndGet(); if (localFailure != null) throw localFailure;
                return response("LOCAL_OK", LOCAL);
            }
        };
        Fixture() throws Exception {
            cloud = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            cloud.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes(); cloudCalls.incrementAndGet();
                byte[] body = "{\"model\":\"cloud-model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"CLOUD_OK\"}}]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            }); cloud.start();
            var props = new LlmRouterProperties(); props.setEnabled(true);
            var primary = route("local", LOCAL, "http://127.0.0.1:11434/v1"); primary.setFallbackKey("cloud");
            props.setModels(Map.of("primary", primary, "cloud", route("openai", "cloud-model", "http://127.0.0.1:" + cloud.getAddress().getPort() + "/v1")));
            gateway = mock(HybridLlmGatewayProbeService.class);
            when(gateway.localFailoverEnabled()).thenReturn(true); when(gateway.cloudFallbackEnabled()).thenReturn(true);
            when(gateway.evaluate(anyString(), any(), anyString())).thenAnswer(invocation -> {
                LlmRouterProperties.ModelConfig cfg = invocation.getArgument(1);
                return RoutingEligibility.eligible(invocation.getArgument(0), cfg.getProvider(), cfg.getName(), "chat", 100, false, Map.of());
            });
            when(gateway.guardLocalModel(any(), anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(0));
            var guard = new NovaModelGuardProperties(); guard.setEnabled(false);
            @SuppressWarnings("unchecked") ObjectProvider<KeyResolver> keys = mock(ObjectProvider.class);
            var resolver = mock(KeyResolver.class);
            when(resolver.resolveOpenAiCredential()).thenReturn(new com.example.lms.guard.ProviderCredentialResolver.Resolution(
                    "openai", java.util.UUID.randomUUID().toString(), true, true, "synthetic", 1, false, ""));
            when(keys.getIfAvailable()).thenReturn(resolver);
            aspect = new LlmRouterAspect(env, props, new LlmRouterBandit(props), guard, keys, gateway, null,
                    new LlmGatewayFailureClassifier(), new ModelRuntimeHealthTracker(), null);
            ReflectionTestUtils.setField(registration, "registeredOwnerHash", OWNER);
            ReflectionTestUtils.setField(aspect, "chatGptOAuth", registration);
            doReturn(true).when(registration).available(OAUTH);
            ChatModel oauth = new ChatModel() { public ChatResponse doChat(ChatRequest request) {
                oauthCalls.incrementAndGet(); if (oauthFailure != null) throw oauthFailure; return response("OAUTH_OK", OAUTH);
            } };
            doReturn(oauth).when(registration).modelFor(eq(OAUTH), anyLong());
        }
        void main(String owner, boolean strict) {
            RequestedModelSelection.begin(strict ? LOCAL : null, owner);
            RequestedModelSelection.rememberMainDecision(new LlmRouteDecision(LOCAL, LOCAL, null, null, false, "synthetic"));
            RequestedModelSelection.rememberMainRole(RoutingProfile.Role.MAIN_DEFAULT);
        }
        void saturate() {
            LocalModelAdmission admission = (LocalModelAdmission) ReflectionTestUtils.getField(aspect, "localModelAdmission");
            assertTrue(admission.tryAcquire(LocalModelAdmission.slotKey("http://127.0.0.1:11434/v1", LOCAL), 1));
        }
        void lateAdmission() {
            when(gateway.guardLocalModel(any(), anyString(), anyString(), any())).thenAnswer(i -> { saturate(); return i.getArgument(0); });
        }
        ChatModel routed() {
            return routed(local, "http://127.0.0.1:11434/v1");
        }
        ChatModel routed(ChatModel delegate, String baseUrl) {
            String previous = RequestedModelSelection.mainConstructionModel();
            var decision = RequestedModelSelection.mainDecision();
            RequestedModelSelection.mainConstructionModel(decision == null ? null : decision.selectedKey());
            try { return aspect.routeLocalInference(delegate, baseUrl, LOCAL, 5000); }
            finally { RequestedModelSelection.mainConstructionModel(previous); }
        }
        void cloudAnswer() {
            assertEquals("CLOUD_OK", routed().chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());
            assertEquals(1, cloudCalls.get());
        }
        void noOAuth() { assertEquals(0, oauthCalls.get()); verify(registration, never()).modelFor(anyString(), anyLong()); }
        public void close() { cloud.stop(0); }
    }
    private static ChatResponse response(String text, String model) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).modelName(model).build();
    }
    private static LlmRouterProperties.ModelConfig route(String provider, String model, String url) {
        var cfg = new LlmRouterProperties.ModelConfig(); cfg.setEnabled(true); cfg.setProvider(provider);
        cfg.setName(model); cfg.setBaseUrl(url); cfg.setStage("chat"); return cfg;
    }
}
