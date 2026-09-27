package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.router.LlmRouterContext;
import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import com.example.lms.guard.KeyResolver;
import com.example.lms.infra.selection.SelectionCoordinate;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyException;
import com.example.lms.infra.selection.SelectionEntropyMode;
import com.example.lms.infra.selection.SelectionEntropyReason;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.SourceLocation;
import org.aspectj.runtime.internal.AroundClosure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmRouterRequestTimelineTest {

    @AfterEach
    void clearRequestState() {
        TraceStore.clear();
        LlmRouterContext.clear();
        GuardContextHolder.clear();
    }

    @Test
    void replayDerivationFailureStopsBeforeFactoryFallbackAndAttemptLedger() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(
                tracker, "selection-request", "selection-session", "llmrouter.auto");
        AtomicInteger weightReads = new AtomicInteger();
        LlmRouterProperties.ModelConfig weighted = new LlmRouterProperties.ModelConfig() {
            @Override
            public double getWeight() {
                return weightReads.incrementAndGet() == 2 ? Double.NaN : 1.0d;
            }
        };
        weighted.setEnabled(true);
        weighted.setName("weighted-model");
        weighted.setProvider("local");
        weighted.setBaseUrl("http://127.0.0.1:11435/v1");
        LlmRouterProperties props = new LlmRouterProperties();
        props.setEnabled(true);
        props.setModels(Map.of("weighted", weighted));
        LlmRouterBandit bandit = new LlmRouterBandit(props);
        bandit.recordOutcome("weighted", true, 25L);
        LlmRouterAspect aspect = aspectWithTracker(props, tracker, null, bandit);
        FakePjp pjp = new FakePjp(
                "factory-result", "llmrouter.auto", null, null, null, null, 64, 5);
        AtomicReference<Object> routed = new AtomicReference<>();
        AtomicInteger entropyCalls = new AtomicInteger();
        SelectionEntropy rejectingEntropy = new SelectionEntropy() {
            @Override
            public SelectionEntropyMode mode() {
                return SelectionEntropyMode.REPLAY;
            }

            @Override
            public String algorithmVersion() {
                return "v1";
            }

            @Override
            public double unitInterval(SelectionCoordinate coordinate) {
                entropyCalls.incrementAndGet();
                assertEquals("llm-router.weighted-exploration", coordinate.decisionKey());
                assertEquals("route:primary", coordinate.actorKey());
                assertEquals(0L, coordinate.attemptOrdinal());
                throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
            }

            @Override
            public int boundedIndex(SelectionCoordinate coordinate, int bound) {
                throw new AssertionError("weighted router must use unitInterval");
            }
        };
        GuardContext context = GuardContext.defaultContext();
        context.attachSelectionEntropy(
                rejectingEntropy,
                SelectionDecisionLedger.forReplay());
        GuardContextHolder.set(context);

        SelectionEntropyException failure = assertThrows(
                SelectionEntropyException.class,
                () -> routed.set(aspect.aroundLcWithTimeout(pjp)));

        assertEquals(SelectionEntropyReason.DERIVATION_INVALID, failure.reason());
        assertTrue(weightReads.get() >= 3, "test must reach the real weighted fallback");
        assertEquals(1, entropyCalls.get(), "missing-OpenAI fallback must not retry selection");
        assertEquals(0, pjp.proceedCount());
        assertNull(routed.get());
        assertTrue(tracker.redactedRequestAttemptLedger(timelineId).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"true,ollama_native", "false,openai_chat_completions"})
    void logicalRouteBypassesFactoryAndEnrichesTheSameTimelineWithSafeHostPort(
            boolean nativeEnabled, String expectedProtocol) throws Throwable {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "raw-router-request", "raw-router-session", "llmrouter.light");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, true);
        LlmRouterProperties props = props("light", "qwen3:8b", "http://127.0.0.1:11435/v1");
        MockEnvironment env = baseEnv().withProperty(
                "llm.ollama-native.think-false.enabled", Boolean.toString(nativeEnabled));

        FakePjp bypassProof = new FakePjp("factory-result", "llmrouter.light", null, null, null, null, 64, 5);
        LlmRouterAspect withoutTracker = new LlmRouterAspect(env, props, new LlmRouterBandit(props),
                disabledModelGuard(), emptyKeyResolverProvider(), null, null, new LlmGatewayFailureClassifier());
        assertInstanceOf(ChatModel.class, withoutTracker.aroundLcWithTimeout(bypassProof));
        assertEquals(0, bypassProof.proceedCount(), "llmrouter.* must reproduce the factory bypass");
        assertEquals("unknown", pendingRow(tracker, timelineId).get("endpointLabel"));

        FakePjp routedCall = new FakePjp("factory-result", "llmrouter.light", null, null, null, null, 64, 5);
        LlmRouterAspect withTracker = new LlmRouterAspect(env, props, new LlmRouterBandit(props),
                disabledModelGuard(), emptyKeyResolverProvider(), null, null, new LlmGatewayFailureClassifier(), tracker);
        assertInstanceOf(ChatModel.class, withTracker.aroundLcWithTimeout(routedCall));

        Map<String, Object> pending = pendingRow(tracker, timelineId);
        assertEquals("127.0.0.1:11435", pending.get("endpointLabel"));
        ModelRuntimeHealthTracker.RouteHealthKey healthRoute = tracker
                .requestRouteHealthKey(timelineId)
                .orElseThrow();
        assertEquals("local_openai_compatible", healthRoute.provider());
        assertEquals("qwen3:8b", healthRoute.model());
        assertTrue(healthRoute.endpointHash().matches("hash:[0-9a-f]{12}"));
        assertTrue(healthRoute.context().matches(
                "router:" + expectedProtocol + ":hash:[0-9a-f]{12}"));
        String serialized = String.valueOf(tracker.redactedRequestTimeline(timelineId));
        assertFalse(serialized.contains("http://127.0.0.1:11435/v1"));
        assertFalse(serialized.contains("raw-router-request"));
        assertFalse(serialized.contains("raw-router-session"));
        assertFalse(serialized.contains("qwen3:8b"));
    }

    @Test
    void auxiliaryLogicalRouteCannotClaimTheApplicationOwnedEndpoint() throws Throwable {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "primary-request", "primary-session", "gemma4:26b");
        LlmRouterProperties props = props("light", "qwen3:8b", "http://127.0.0.1:11435/v1");

        aspectWithTracker(props, tracker).aroundLcWithTimeout(
                new FakePjp("factory-result", "llmrouter.light", null, null, null, null, 64, 5));

        assertEquals("unknown", pendingRow(tracker, timelineId).get("endpointLabel"));
    }

    @Test
    void rejectedLogicalRouteNeverEnrichesTheApplicationOwnedEndpoint() throws Exception {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "rejected-request", "rejected-session", "llmrouter.light");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, true);
        LlmRouterProperties props = props("light", "qwen3:8b", "http://192.0.2.10:11435/v1");
        LlmRouterAspect aspect = aspectWithTracker(props, tracker);

        assertThrows(IllegalStateException.class, () -> aspect.aroundLcWithTimeout(
                new FakePjp("factory-result", "llmrouter.light", null, null, null, null, 64, 5)));

        assertEquals("unknown", pendingRow(tracker, timelineId).get("endpointLabel"));
    }

    @Test
    void expectedFailureRouteCannotClaimAnHttpAttempt() throws Throwable {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "guard-request", "guard-session", "llmrouter.light");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, true);
        LlmRouterProperties props = props("light", "gpt-5-pro", "https://api.openai.com/v1");
        NovaModelGuardProperties guard = new NovaModelGuardProperties();
        guard.setEnabled(true);
        guard.setMode(NovaModelGuardProperties.Mode.FAIL_FAST);

        LlmRouterAspect aspect = new LlmRouterAspect(
                baseEnv(), props, new LlmRouterBandit(props), guard, emptyKeyResolverProvider(),
                null, null, new LlmGatewayFailureClassifier(), tracker);

        ExpectedFailureChatModel model = assertInstanceOf(ExpectedFailureChatModel.class, aspect.aroundLcWithTimeout(
                new FakePjp("factory-result", "llmrouter.light", null, null, null, null, 64, 5)));
        assertEquals("unknown", pendingRow(tracker, timelineId).get("endpointLabel"));
        assertEquals(0, pendingRow(tracker, timelineId).get("attemptCount"));
        model.chat(List.of(UserMessage.from("disabled-fixture")));
        List<Map<String, Object>> attempts = tracker.redactedRequestAttemptLedger(timelineId);
        assertEquals(1, attempts.size(), "the concrete disabled model must emit one application-boundary row");
        Map<String, Object> attempt = attempts.get(0);
        assertEquals("disabled", attempt.get("failureClass"));
        assertEquals(Boolean.FALSE, attempt.get("modelAdapterAttemptObserved"));
        assertEquals(Boolean.FALSE, attempt.get("clientHttpExchangeObserved"));
        assertEquals(Boolean.FALSE, attempt.get("clientHttpResponseObserved"));
        assertEquals(Boolean.FALSE, attempt.get("providerAttemptObserved"));
        assertEquals(Boolean.FALSE, attempt.get("wireAttemptObserved"));
        assertEquals("hash:unknown", attempt.get("responseHash"));
        assertEquals(0, attempt.get("responseCharCount"));
        assertEquals(0, attempt.get("responseUtf8ByteCount"));
        assertEquals(Boolean.FALSE, attempt.get("responseObserved"));
        assertEquals(13, attempt.get("optionItemCount"));
    }

    @Test
    void ordinarySpringBeanChatModelAdviceRecordsOneBoundaryRowWithoutChangingDelegateCalls() throws Throwable {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "spring-bean-request", "spring-bean-session", "bean-model");
        AtomicInteger delegateCalls = new AtomicInteger();
        ChatModel beanModel = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                delegateCalls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("bean-result")).build();
            }
        };
        LlmRouterAspect aspect = aspectWithTracker(props("light", "qwen3:8b", "http://127.0.0.1:11435/v1"), tracker);
        Method advice;
        try {
            advice = LlmRouterAspect.class.getMethod("aroundSpringChatModelBean", ProceedingJoinPoint.class);
        } catch (NoSuchMethodException missing) {
            fail("RED: ordinary Spring ChatModel beans must pass through application-boundary proof");
            return;
        }

        ChatModel decorated = (ChatModel) advice.invoke(aspect, new FakePjp(beanModel));
        decorated.chat(List.of(UserMessage.from("bean-fixture")));

        assertEquals(1, delegateCalls.get());
        List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
        assertEquals(1, rows.size());
        assertEquals(13, rows.get(0).get("optionItemCount"));
    }

    @Test
    void configuredLazyFallbackMarksTheSelectedPrimaryAsRepairAmbiguous() throws Throwable {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "fallback-request", "fallback-session", "llmrouter.light");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY, true);
        LlmRouterProperties props = propsWithFallback();

        HybridLlmGatewayProbeService gateway = mock(HybridLlmGatewayProbeService.class);
        when(gateway.cloudFallbackEnabled()).thenReturn(true);
        assertInstanceOf(ChatModel.class, aspectWithTracker(props, tracker, gateway).aroundLcWithTimeout(
                new FakePjp("factory-result", "llmrouter.light", null, null, null, null, 64, 5)));

        Map<String, Object> pending = pendingRow(tracker, timelineId);
        assertEquals("127.0.0.1:11435", pending.get("endpointLabel"));
        assertEquals(true, pending.get("ambiguousRouteAttempts"));
        assertEquals("HOLD", tracker.endpointRepairReadiness(timelineId).status());
        assertEquals("ambiguous_route_attempts", tracker.endpointRepairReadiness(timelineId).reason());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"partial text", ""})
    void routedIncompleteResponsesKeepReceiptWithoutRewardOrReplay(String partialText) throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            var sent = new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody());
            assertEquals(64, sent.path("max_output_tokens").asInt());
            calls.incrementAndGet();
            byte[] out = ("{\"status\":\"incomplete\",\"model\":\"gpt-5-pro\",\"id\":\"resp_fixture\","
                    + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output_text\":\"" + partialText
                    + "\",\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length); exchange.getResponseBody().write(out); exchange.close();
        });
        server.start();
        try {
            var tracker = org.mockito.Mockito.spy(new ModelRuntimeHealthTracker());
            String timeline = pendingTimeline(tracker, "terminal-request", "terminal-session", "gpt-5-pro");
            var guard = new NovaModelGuardProperties(); guard.setOpenAiBaseOnly(false);
            guard.setMode(NovaModelGuardProperties.Mode.ROUTE_RESPONSES);
            var props = props("responses", "gpt-5-pro", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var bandit = org.mockito.Mockito.spy(new LlmRouterBandit(props));
            var aspect = new LlmRouterAspect(baseEnv().withProperty("llm.api-key-openai", "fixture-key"),
                    props, bandit, guard, emptyKeyResolverProvider(), null, null, new LlmGatewayFailureClassifier(), tracker);
            ChatModel model = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(
                    new FakePjp("factory-result", "llmrouter.responses", null, null, null, null, 64, 5)));
            var terminal = assertThrows(com.example.lms.llm.gateway.LlmResponseTerminalException.class,
                    () -> model.chat(List.of(UserMessage.from("fixture"))));
            assertEquals(partialText.isEmpty() ? null : partialText, terminal.partialText());
            assertEquals(5, terminal.metadata().tokenUsage().totalTokenCount());
            assertEquals(dev.langchain4j.model.output.FinishReason.LENGTH, terminal.metadata().finishReason());
            assertEquals(1, calls.get());
            var rows = tracker.redactedRequestAttemptLedger(timeline);
            assertEquals(1, rows.size());
            assertEquals("none", rows.get(0).get("failureClass"));
            assertEquals("output_limit_reached", rows.get(0).get("terminalClass"));
            assertEquals(partialText.isEmpty() ? "failed" : "partial", rows.get(0).get("outcome"));
            org.mockito.Mockito.verify(bandit, org.mockito.Mockito.never()).recordOutcome(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        } finally { server.stop(0); }
    }

    @Test
    void routedResponsesDirectOwnerWritesOneExactHttpRowAndSuppressesTheOuterDecorator() throws Throwable {
        AtomicInteger exchanges = new AtomicInteger();
        AtomicInteger sentCap = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            sentCap.set(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(exchange.getRequestBody()).path("max_output_tokens").asInt());
            exchanges.incrementAndGet();
            byte[] response = "{\"status\":\"completed\",\"output_text\":\"router-responses-private\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "router-responses-request-private",
                "router-responses-session-private", "gpt-5-pro-private");
        NovaModelGuardProperties guard = new NovaModelGuardProperties();
        guard.setEnabled(true);
        guard.setMode(NovaModelGuardProperties.Mode.ROUTE_RESPONSES);
        guard.setOpenAiBaseOnly(false);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        LlmRouterProperties props = props("responses", "gpt-5-pro", baseUrl);
        MockEnvironment env = baseEnv().withProperty("llm.api-key-openai", "loopback-test-key");
        LlmRouterAspect aspect = new LlmRouterAspect(
                env, props, new LlmRouterBandit(props), guard, emptyKeyResolverProvider(),
                null, null, new LlmGatewayFailureClassifier(), tracker);
        try {
            ChatModel model = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(
                    new FakePjp("factory-result", "llmrouter.responses", null, null, null, null, 64, 5)));
            model.chat(List.of(UserMessage.from("router-responses-prompt-private")));

            assertEquals(1, exchanges.get());
            assertEquals(64, sentCap.get(), "the resolved list-only cap must reach the HTTP body");
            List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, rows.size(), "one physical Responses exchange must produce one retained row");
            assertEquals("primary", rows.get(0).get("role"));
            assertEquals("openai_responses", rows.get(0).get("protocol"));
            assertEquals(Boolean.TRUE, rows.get(0).get("clientHttpExchangeObserved"));
            assertEquals(Boolean.TRUE, rows.get(0).get("clientHttpResponseObserved"));
            assertEquals(Boolean.FALSE, rows.get(0).get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, rows.get(0).get("wireAttemptObserved"));
            assertFalse((rows.toString() + TraceStore.getAll()).contains("router-responses-prompt-private"));
            assertFalse((rows.toString() + TraceStore.getAll()).contains("router-responses-private"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responsesPrimaryMalformed200FallsBackExactlyOnceAndRewardsFailure() throws Throwable {
        assertResponsesPrimaryFailureFallsBackExactlyOnce(
                200, "not-json-private", LlmFailureClass.PROVIDER_ERROR, "provider_error");
    }

    @Test
    void responsesPrimaryHttp503FallsBackExactlyOnceAndRewardsFailure() throws Throwable {
        assertResponsesPrimaryFailureFallsBackExactlyOnce(
                503, "{\"error\":\"primary-private\"}", LlmFailureClass.HEALTH_DOWN, "health_down");
    }

    @Test
    void routedResponsesFallbackTypedErrorKeepsOneFallbackDirectHttpRow() throws Throwable {
        HttpServer primaryServer = statusServer("/v1/chat/completions", 503,
                "{\"error\":\"primary-private\"}");
        HttpServer fallbackServer = statusServer("/v1/responses", 503,
                "{\"error\":\"fallback-private\"}");
        primaryServer.start();
        fallbackServer.start();
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "router-fallback-request-private",
                "router-fallback-session-private", "gpt-4-private");
        NovaModelGuardProperties guard = new NovaModelGuardProperties();
        guard.setEnabled(true);
        guard.setMode(NovaModelGuardProperties.Mode.ROUTE_RESPONSES);
        guard.setOpenAiBaseOnly(false);
        LlmRouterProperties props = responsesFallbackProps(
                "http://127.0.0.1:" + primaryServer.getAddress().getPort() + "/v1",
                "http://127.0.0.1:" + fallbackServer.getAddress().getPort() + "/v1");
        MockEnvironment env = baseEnv().withProperty("llm.api-key-openai", "loopback-test-key");
        HybridLlmGatewayProbeService gateway = mock(HybridLlmGatewayProbeService.class);
        when(gateway.cloudFallbackEnabled()).thenReturn(true);
        LlmRouterAspect aspect = new LlmRouterAspect(
                env, props, new LlmRouterBandit(props), guard, emptyKeyResolverProvider(),
                gateway, null, new LlmGatewayFailureClassifier(), tracker);
        try {
            ChatModel model = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(
                    new FakePjp("factory-result", "llmrouter.primary", null, null, null, null, 64, 5)));
            LlmGatewayException failure = assertThrows(LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("router-fallback-prompt-private"))));
            assertEquals(LlmFailureClass.HEALTH_DOWN, failure.failureClass());
            assertEquals("responses_http_503", failure.reasonCode());
            List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
            List<Map<String, Object>> fallbackRows = rows.stream()
                    .filter(row -> "openai_responses".equals(row.get("protocol")))
                    .toList();
            assertEquals(1, fallbackRows.size(), "fallback exchange must suppress its outer decorator row");
            Map<String, Object> fallback = fallbackRows.get(0);
            assertEquals("fallback", fallback.get("role"));
            assertEquals("failed", fallback.get("outcome"));
            assertEquals(Boolean.TRUE, fallback.get("clientHttpExchangeObserved"));
            assertEquals(Boolean.TRUE, fallback.get("clientHttpResponseObserved"));
            assertEquals(Boolean.FALSE, fallback.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, fallback.get("wireAttemptObserved"));
            assertFalse((rows.toString() + TraceStore.getAll()).contains("router-fallback-prompt-private"));
            assertFalse((rows.toString() + TraceStore.getAll()).contains("fallback-private"));
        } finally {
            primaryServer.stop(0);
            fallbackServer.stop(0);
        }
    }

    private static String pendingTimeline(
            ModelRuntimeHealthTracker tracker,
            String requestId,
            String sessionId,
            String modelId) {
        String timelineId = tracker.beginRequestTimeline(requestId, sessionId);
        tracker.recordRequestPhase(timelineId, "dispatch", modelId, null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        return timelineId;
    }

    private static Map<String, Object> pendingRow(ModelRuntimeHealthTracker tracker, String timelineId) {
        List<Map<String, Object>> rows = tracker.redactedRequestTimeline(timelineId);
        return rows.stream()
                .filter(row -> "pending".equals(row.get("phase")))
                .findFirst()
                .orElseThrow();
    }

    private static LlmRouterProperties props(String key, String modelName, String baseUrl) {
        LlmRouterProperties props = new LlmRouterProperties();
        props.setEnabled(true);
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setName(modelName);
        cfg.setBaseUrl(baseUrl);
        cfg.setWeight(1.0d);
        props.setModels(Map.of(key, cfg));
        return props;
    }

    private static LlmRouterProperties propsWithFallback() {
        LlmRouterProperties props = new LlmRouterProperties();
        props.setEnabled(true);
        LlmRouterProperties.ModelConfig primary = new LlmRouterProperties.ModelConfig();
        primary.setName("qwen3:8b");
        primary.setBaseUrl("http://127.0.0.1:11435/v1");
        primary.setWeight(1.0d);
        primary.setFallbackKey("fallback");
        LlmRouterProperties.ModelConfig fallback = new LlmRouterProperties.ModelConfig();
        fallback.setName("gemma3:4b");
        fallback.setBaseUrl("http://127.0.0.1:11434/v1");
        fallback.setWeight(1.0d);
        fallback.setFallbackOnly(true);
        props.setModels(Map.of("light", primary, "fallback", fallback));
        return props;
    }

    private static LlmRouterProperties responsesFallbackProps(String primaryBaseUrl, String fallbackBaseUrl) {
        LlmRouterProperties props = new LlmRouterProperties();
        props.setEnabled(true);
        LlmRouterProperties.ModelConfig primary = new LlmRouterProperties.ModelConfig();
        primary.setName("gpt-4-private");
        primary.setBaseUrl(primaryBaseUrl);
        primary.setWeight(1.0d);
        primary.setFallbackKey("responses-fallback");
        LlmRouterProperties.ModelConfig fallback = new LlmRouterProperties.ModelConfig();
        fallback.setName("gpt-5-pro");
        fallback.setBaseUrl(fallbackBaseUrl);
        fallback.setWeight(1.0d);
        fallback.setFallbackOnly(true);
        props.setModels(Map.of("primary", primary, "responses-fallback", fallback));
        return props;
    }

    private static LlmRouterProperties responsesPrimaryFallbackProps(
            String primaryBaseUrl,
            String fallbackBaseUrl) {
        LlmRouterProperties props = new LlmRouterProperties();
        props.setEnabled(true);
        LlmRouterProperties.ModelConfig primary = new LlmRouterProperties.ModelConfig();
        primary.setName("gpt-5-pro");
        primary.setBaseUrl(primaryBaseUrl);
        primary.setWeight(1.0d);
        primary.setFallbackKey("chat-fallback");
        LlmRouterProperties.ModelConfig fallback = new LlmRouterProperties.ModelConfig();
        fallback.setName("gpt-4o-mini");
        fallback.setBaseUrl(fallbackBaseUrl);
        fallback.setWeight(1.0d);
        fallback.setFallbackOnly(true);
        props.setModels(Map.of("responses-primary", primary, "chat-fallback", fallback));
        return props;
    }

    private static void assertResponsesPrimaryFailureFallsBackExactlyOnce(
            int primaryStatus,
            String primaryBody,
            LlmFailureClass expectedFailureClass,
            String expectedLedgerFailureClass) throws Throwable {
        AtomicInteger primaryExchanges = new AtomicInteger();
        AtomicInteger fallbackExchanges = new AtomicInteger();
        HttpServer primaryServer = countingStatusServer(
                "/v1/responses", primaryStatus, primaryBody, primaryExchanges);
        HttpServer fallbackServer = countingStatusServer(
                "/v1/chat/completions",
                200,
                "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":0,"
                        + "\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"message\":{"
                        + "\"role\":\"assistant\",\"content\":\"fallback-result-private\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
                        + "\"completion_tokens\":1,\"total_tokens\":2}}",
                fallbackExchanges);
        primaryServer.start();
        fallbackServer.start();
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(
                tracker,
                "responses-primary-request-private",
                "responses-primary-session-private",
                "gpt-5-pro-private");
        NovaModelGuardProperties guard = new NovaModelGuardProperties();
        guard.setEnabled(true);
        guard.setMode(NovaModelGuardProperties.Mode.ROUTE_RESPONSES);
        guard.setOpenAiBaseOnly(false);
        LlmRouterProperties props = responsesPrimaryFallbackProps(
                "http://127.0.0.1:" + primaryServer.getAddress().getPort() + "/v1",
                "http://127.0.0.1:" + fallbackServer.getAddress().getPort() + "/v1");
        OutcomeRecordingBandit bandit = new OutcomeRecordingBandit(props);
        MockEnvironment env = baseEnv().withProperty("llm.api-key-openai", "loopback-test-key");
        HybridLlmGatewayProbeService gateway = mock(HybridLlmGatewayProbeService.class);
        when(gateway.cloudFallbackEnabled()).thenReturn(true);
        LlmRouterAspect aspect = new LlmRouterAspect(
                env, props, bandit, guard, emptyKeyResolverProvider(),
                gateway, null, new LlmGatewayFailureClassifier(), tracker);
        try {
            ChatModel model = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(
                    new FakePjp("factory-result", "llmrouter.responses-primary",
                            null, null, null, null, 64, 5)));
            ChatResponse response = model.chat(List.of(UserMessage.from("responses-primary-prompt-private")));

            assertEquals("fallback-result-private", response.aiMessage().text());
            assertEquals(1, primaryExchanges.get(), "primary must perform exactly one physical exchange");
            assertEquals(1, fallbackExchanges.get(), "fallback must perform exactly one physical exchange");
            assertEquals(List.of(
                    new BanditOutcome("responses-primary", false, expectedFailureClass),
                    new BanditOutcome("chat-fallback", true, LlmFailureClass.NONE)),
                    bandit.outcomes(),
                    "the failed primary must receive zero reward before the successful fallback is rewarded");

            List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(2, rows.size(), "one primary and one fallback exchange must produce two rows only");
            Map<String, Object> primary = rows.stream()
                    .filter(row -> "primary".equals(row.get("role")))
                    .findFirst()
                    .orElseThrow();
            Map<String, Object> fallback = rows.stream()
                    .filter(row -> "fallback".equals(row.get("role")))
                    .findFirst()
                    .orElseThrow();
            assertEquals("openai_responses", primary.get("protocol"));
            assertEquals("failed", primary.get("outcome"));
            assertEquals(expectedLedgerFailureClass, primary.get("failureClass"));
            assertEquals(Boolean.TRUE, primary.get("clientHttpExchangeObserved"));
            assertEquals(Boolean.TRUE, primary.get("clientHttpResponseObserved"));
            assertEquals(Boolean.FALSE, primary.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, primary.get("wireAttemptObserved"));
            assertEquals("openai_chat_completions", fallback.get("protocol"));
            assertEquals("success", fallback.get("outcome"));
            assertEquals("none", fallback.get("failureClass"));
            assertEquals(Boolean.TRUE, fallback.get("responseObserved"));
            assertEquals(2, rows.stream()
                    .map(row -> row.get("logicalCallOrdinal") + ":" + row.get("attemptOrdinal"))
                    .distinct()
                    .count(), "each physical exchange must retain one unique logical-call/attempt tuple");
            String redacted = rows + "\n" + TraceStore.getAll();
            assertFalse(redacted.contains("responses-primary-prompt-private"));
            assertFalse(redacted.contains(primaryBody));
        } finally {
            primaryServer.stop(0);
            fallbackServer.stop(0);
        }
    }

    private static HttpServer statusServer(String path, int status, String body) throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        return server;
    }

    private static HttpServer countingStatusServer(
            String path,
            int status,
            String body,
            AtomicInteger exchanges) throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchanges.incrementAndGet();
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        return server;
    }

    private record BanditOutcome(String key, boolean success, LlmFailureClass failureClass) {
    }

    private static final class OutcomeRecordingBandit extends LlmRouterBandit {
        private final List<BanditOutcome> outcomes = new ArrayList<>();

        private OutcomeRecordingBandit(LlmRouterProperties props) {
            super(props);
        }

        @Override
        public void recordOutcome(
                String key,
                boolean success,
                long latencyMs,
                LlmFailureClass failureClass) {
            outcomes.add(new BanditOutcome(key, success, failureClass));
            super.recordOutcome(key, success, latencyMs, failureClass);
        }

        private List<BanditOutcome> outcomes() {
            return List.copyOf(outcomes);
        }
    }

    private static LlmRouterAspect aspectWithoutTracker(LlmRouterProperties props) {
        return new LlmRouterAspect(
                baseEnv(),
                props,
                new LlmRouterBandit(props),
                disabledModelGuard(),
                emptyKeyResolverProvider(),
                null,
                null,
                new LlmGatewayFailureClassifier());
    }

    private static LlmRouterAspect aspectWithTracker(
            LlmRouterProperties props,
            ModelRuntimeHealthTracker tracker) throws Exception {
        return aspectWithTracker(props, tracker, null);
    }

    private static LlmRouterAspect aspectWithTracker(
            LlmRouterProperties props,
            ModelRuntimeHealthTracker tracker,
            HybridLlmGatewayProbeService gatewayProbeService) throws Exception {
        return aspectWithTracker(
                props, tracker, gatewayProbeService, new LlmRouterBandit(props));
    }

    private static LlmRouterAspect aspectWithTracker(
            LlmRouterProperties props,
            ModelRuntimeHealthTracker tracker,
            HybridLlmGatewayProbeService gatewayProbeService,
            LlmRouterBandit bandit) throws Exception {
        Constructor<?> constructor = Arrays.stream(LlmRouterAspect.class.getConstructors())
                .filter(candidate -> {
                    Class<?>[] parameterTypes = candidate.getParameterTypes();
                    return parameterTypes.length == 9
                            && ModelRuntimeHealthTracker.class.equals(parameterTypes[8]);
                })
                .findFirst()
                .orElse(null);
        assertNotNull(constructor,
                "LlmRouterAspect must accept the shared ModelRuntimeHealthTracker for router-owned enrichment");
        return (LlmRouterAspect) constructor.newInstance(
                baseEnv(),
                props,
                bandit,
                disabledModelGuard(),
                emptyKeyResolverProvider(),
                gatewayProbeService,
                null,
                new LlmGatewayFailureClassifier(),
                tracker);
    }

    private static MockEnvironment baseEnv() {
        return new MockEnvironment()
                .withProperty("llm.chat-model", "gemma4:26b")
                .withProperty("llm.api-key", "ollama")
                .withProperty("llm.owner-token-header", "X-Owner-Token");
    }

    private static NovaModelGuardProperties disabledModelGuard() {
        NovaModelGuardProperties modelGuard = new NovaModelGuardProperties();
        modelGuard.setEnabled(false);
        return modelGuard;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<KeyResolver> emptyKeyResolverProvider() {
        ObjectProvider<KeyResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static final class FakePjp implements ProceedingJoinPoint {
        private final Object result;
        private final Object[] args;
        private final AtomicInteger proceedCount = new AtomicInteger();

        private FakePjp(Object result, Object... args) {
            this.result = result;
            this.args = args;
        }

        int proceedCount() {
            return proceedCount.get();
        }

        @Override
        public Object proceed() {
            proceedCount.incrementAndGet();
            return result;
        }

        @Override
        public Object proceed(Object[] args) {
            proceedCount.incrementAndGet();
            return result;
        }

        @Override
        public void set$AroundClosure(AroundClosure arc) {
        }

        @Override
        public Object getThis() {
            return this;
        }

        @Override
        public Object getTarget() {
            return this;
        }

        @Override
        public Object[] getArgs() {
            return args;
        }

        @Override
        public Signature getSignature() {
            return null;
        }

        @Override
        public SourceLocation getSourceLocation() {
            return null;
        }

        @Override
        public String getKind() {
            return "method-execution";
        }

        @Override
        public JoinPoint.StaticPart getStaticPart() {
            return null;
        }

        @Override
        public String toShortString() {
            return "FakePjp";
        }

        @Override
        public String toLongString() {
            return "FakePjp";
        }
    }
}
