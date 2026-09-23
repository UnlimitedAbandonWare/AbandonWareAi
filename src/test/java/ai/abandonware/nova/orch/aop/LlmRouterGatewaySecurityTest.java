package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.router.LlmRouterContext;
import com.example.lms.guard.KeyResolver;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OpenAiModelParamMatrix;
import com.example.lms.llm.OpenAiTokenParamCompat;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmGatewayProperties;
import com.example.lms.llm.gateway.LlmRouteScorer;
import com.example.lms.llm.gateway.RoutingEligibility;
import com.example.lms.llm.spec.ModelSpecRegistry;
import com.example.lms.learning.gemini.GeminiGateway;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.sun.net.httpserver.HttpServer;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.SourceLocation;
import org.aspectj.runtime.internal.AroundClosure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmRouterGatewaySecurityTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
        LlmRouterContext.clear();
    }

    @Test
    void modelGuardTraceDoesNotStoreRawModelIdentifiers() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"llm.modelGuard.requestedModel\""));
        assertFalse(source.contains("TraceStore.put(\"llm.modelGuard.substituteChatModel\""));
        assertTrue(source.contains("TraceStore.put(\"llm.modelGuard.requestedModelHash\""));
        assertTrue(source.contains("TraceStore.put(\"llm.modelGuard.requestedModelLength\""));
        assertTrue(source.contains("TraceStore.put(\"llm.modelGuard.substituteChatModelHash\""));
        assertTrue(source.contains("TraceStore.put(\"llm.modelGuard.substituteChatModelLength\""));

        String guard = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/OpenAiChatModelGuardAspect.java"),
                StandardCharsets.UTF_8);
        assertFalse(guard.contains("TraceStore.put(\"llm.modelGuard.requestedModel\", ModelGuardSupport.canonicalModelName(requestedModel))"));
        assertFalse(guard.contains("TraceStore.put(\"llm.modelGuard.substituteChatModel\", substitute)"));
        assertTrue(guard.contains("TraceStore.put(\"llm.modelGuard.requestedModelHash\""));
        assertTrue(guard.contains("TraceStore.put(\"llm.modelGuard.requestedModelLength\""));
        assertTrue(guard.contains("TraceStore.put(\"llm.modelGuard.substituteChatModelHash\""));
        assertTrue(guard.contains("TraceStore.put(\"llm.modelGuard.substituteChatModelLength\""));
    }

    @Test
    void modelGuardSubstituteChatRejectsResponsesOnlySubstituteForRouterRoutes() throws Throwable {
        MockEnvironment env = baseEnv()
                .withProperty("llm.chat-model", "gpt-5-pro")
                .withProperty("OPENAI_API_KEY", "valid-test-key");
        LlmRouterProperties props = props("openai", "gpt-5-pro", "https://api.openai.com/v1");
        NovaModelGuardProperties modelGuard = new NovaModelGuardProperties();
        modelGuard.setMode(NovaModelGuardProperties.Mode.SUBSTITUTE_CHAT);
        LlmRouterAspect aspect = aspect(env, props, modelGuard);

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.openai",
                null,
                null,
                null,
                10));

        ExpectedFailureChatModel model = assertInstanceOf(ExpectedFailureChatModel.class, out);
        String message = model.chat(List.of()).aiMessage().text();
        assertTrue(message.contains("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"), message);
        assertTrue(message.contains("SUBSTITUTE_CHAT(no_chat_compatible_substitute)"), message);
        assertNull(LlmRouterContext.get());
    }

    @Test
    void modelGuardFailFastRouterExpectedFailureWritesReasonAndEndpointTrace() throws Throwable {
        MockEnvironment env = baseEnv()
                .withProperty("OPENAI_API_KEY", "valid-test-key");
        LlmRouterProperties props = props("openai", "gpt-5-pro", "https://api.openai.com/v1");
        NovaModelGuardProperties modelGuard = new NovaModelGuardProperties();
        modelGuard.setMode(NovaModelGuardProperties.Mode.FAIL_FAST);
        LlmRouterAspect aspect = aspect(env, props, modelGuard);

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.openai",
                null,
                null,
                null,
                10));

        ExpectedFailureChatModel model = assertInstanceOf(ExpectedFailureChatModel.class, out);
        assertTrue(model.chat(List.of()).aiMessage().text().contains("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"));
        assertEquals(Boolean.TRUE, TraceStore.get("llm.modelGuard.triggered"));
        assertEquals("FAIL_FAST", TraceStore.get("llm.modelGuard.mode"));
        assertEquals("/v1/chat/completions", TraceStore.get("llm.modelGuard.endpoint"));
        assertEquals("responses_only_model_on_chat_completions_endpoint",
                TraceStore.get("llm.modelGuard.failReason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("gpt-5-pro"));
        assertFalse(model.toString().contains("gpt-5-pro"));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void modelGuardSubstituteChatWithoutRouterSubstituteReturnsExpectedFailure() throws Throwable {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.owner-token-header", "X-Owner-Token")
                .withProperty("OPENAI_API_KEY", "valid-test-key");
        LlmRouterProperties props = props("openai", "gpt-5-pro", "https://api.openai.com/v1");
        NovaModelGuardProperties modelGuard = new NovaModelGuardProperties();
        modelGuard.setMode(NovaModelGuardProperties.Mode.SUBSTITUTE_CHAT);
        LlmRouterAspect aspect = aspect(env, props, modelGuard);

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.openai",
                null,
                null,
                null,
                10));

        ExpectedFailureChatModel model = assertInstanceOf(ExpectedFailureChatModel.class, out);
        String message = model.chat(List.of()).aiMessage().text();
        assertTrue(message.contains("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"), message);
        assertTrue(message.contains("SUBSTITUTE_CHAT(no_substitute_configured)"), message);
        assertNull(LlmRouterContext.get());
    }

    @Test
    void apiDisabledReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"llmrouter.api.disabledReason\", disabledReason);"));
        assertTrue(source.contains(
                "TraceStore.put(\"llmrouter.api.disabledReason\", SafeRedactor.traceLabelOrFallback(disabledReason, \"unknown\"));"));
    }

    @Test
    void apiDisabledReasonExceptionUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("\" disabledReason=\" + disabledReason"));
        assertTrue(source.contains("+ SafeRedactor.traceLabelOrFallback(disabledReason, \"unknown\"));"));
    }

    @Test
    void llmRouterAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "llm router diagnostics need redacted breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void llmRouterHelperCatchesUseSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"config.get\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"bandit.record\", ignore);"));
    }

    @Test
    void llmRouterSuppressedTraceHelperRecordsRedactedStageAndErrorType() throws Exception {
        String secret = "sk-" + "llmRouterSuppressedSecret123456789";
        Method method = LlmRouterAspect.class.getDeclaredMethod(
                "traceSuppressed",
                String.class,
                Exception.class);
        method.setAccessible(true);

        method.invoke(null, "providerDisabled.trace " + secret, new IllegalStateException("raw " + secret));

        Object stage = TraceStore.get("llmrouter.suppressed.stage");
        assertTrue(String.valueOf(stage).startsWith("hash:"), String.valueOf(stage));
        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.suppressed." + stage));
        assertEquals("IllegalStateException", TraceStore.get("llmrouter.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("llmrouter.suppressed." + stage + ".errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(secret), trace);
    }

    @Test
    void remoteMacMiniRouteFailsClosedWithoutAllowlist() {
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local");
        LlmRouterAspect aspect = aspect(env, props("gemma", "gemma3:4b", "https://macmini-ollama.internal/v1"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.gemma",
                        null,
                        null,
                        null,
                        10)));

        assertFalse(ex.getMessage().contains("sk-local"));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void remoteMacMiniRouteAllowsAllowlistedOwnerToken() throws Throwable {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local")
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("llm.provider-guard.allow-private-remote", "true")
                .withProperty("llm.provider-guard.allowed-hosts", "macmini-ollama.internal");
        LlmRouterAspect aspect = aspect(env, props("gemma", "gemma3:4b", "https://macmini-ollama.internal/v1"));

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.gemma",
                null,
                null,
                null,
                10));

        assertInstanceOf(ChatModel.class, out);
        assertEquals("gemma", LlmRouterContext.get().key());
        assertEquals("macmini-ollama.internal", TraceStore.get("llmrouter.endpointHost"));
        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
    }

    @Test
    void localApiKeyResolverSkipsSkLocalAndUsesRealEnvKey() {
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local")
                .withProperty("LLM_API_KEY", "local_real_key");
        LlmRouterAspect aspect = aspect(env, props("gemma", "gemma3:4b", "http://localhost:11434/v1"));

        String resolved = ReflectionTestUtils.invokeMethod(aspect, "resolveLocalApiKey");

        assertEquals("local_real_key", resolved);
    }

    @Test
    void conflictingLocalCredentialAliasesDisableOnlyTheLocalRoute() {
        String first = "local-router-secret-a";
        String second = "local-router-secret-b";
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", first)
                .withProperty("LLM_API_KEY", second);
        LlmRouterAspect aspect = aspect(env,
                props("gemma", "gemma3:4b", "http://localhost:11434/v1"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.gemma",
                        null,
                        null,
                        null,
                        10)));

        assertTrue(failure.getMessage().contains("conflicting-credential-aliases"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(first));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(second));
    }

    @Test
    void conflictingOpenAiCredentialAliasesFailClosedWithoutKeyResolverBean() {
        String first = "openai-router-secret-a";
        String second = "openai-router-secret-b";
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key-openai", first)
                .withProperty("OPENAI_API_KEY", second);
        LlmRouterAspect aspect = aspect(env,
                props("api", "gpt-4.1-mini", "https://api.openai.com/v1"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.api",
                        null,
                        null,
                        null,
                        10)));

        assertTrue(failure.getMessage().contains("conflicting-credential-aliases"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(first));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(second));
    }

    @Test
    void externalOpenRouterRouteDoesNotReceiveOwnerToken() throws Throwable {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("OPENROUTER_API_KEY", "openrouter-secret-value");
        LlmRouterAspect aspect = aspect(env, props("api3", "qwen/qwen3-32b", "https://api.openrouter.ai/api/v1"));

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.api3",
                null,
                null,
                null,
                10));

        assertInstanceOf(ChatModel.class, out);
        assertEquals("api3", LlmRouterContext.get().key());
        assertNull(TraceStore.get("llmrouter.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
    }

    @Test
    void openAiCompatibleGeminiBasePathIsPreservedForHttpRequest() throws Throwable {
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/openai";
            MockEnvironment env = baseEnv().withProperty("llm.api-key", "loopback-key-value");
            LlmRouterAspect aspect = aspect(env, props("gemini-loopback", "gpt-gemini-loopback", baseUrl));

            ChatModel model = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(new FakePjp(
                    "fallback",
                    "llmrouter.gemini-loopback",
                    null,
                    null,
                    null,
                    10)));
            model.chat(List.of(UserMessage.from("bounded route probe")));

            assertEquals("/v1beta/openai/chat/completions", requestPath.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void modernOpenAiCompatibleRouteSendsMaxCompletionTokens() throws Throwable {
        OpenAiModelParamMatrix matrix = new OpenAiModelParamMatrix();
        matrix.setByPrefix(Map.of("gpt-5", "max_completion_tokens"));
        OpenAiTokenParamCompat.registerMatrix(matrix);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            MockEnvironment env = baseEnv().withProperty("llm.api-key", "loopback-key-value");
            LlmRouterAspect aspect = aspect(env, props("openai-loopback", "gpt-5.5", baseUrl));

            ChatModel model = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(new FakePjp(
                    "fallback",
                    "llmrouter.openai-loopback",
                    1.0d,
                    1.0d,
                    null,
                    null,
                    1200,
                    10)));
            model.chat(List.of(UserMessage.from("bounded token probe")));

            var json = new ObjectMapper().readTree(requestBody.get());
            assertEquals(1200, json.path("max_completion_tokens").asInt(), requestBody.get());
            assertFalse(json.has("max_tokens"), requestBody.get());
        } finally {
            server.stop(0);
            OpenAiTokenParamCompat.registerMatrix(null);
        }
    }

    @Test
    void responseModelMismatchFailsClosedBeforeCandidateAcceptance() throws Throwable {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            byte[] response = ("""
                    {"id":"chatcmpl-probe","object":"chat.completion","created":1,
                     "model":"unexpected-provider-model",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            LlmRouterProperties routerProps = props("strict-loopback", "expected-route-model", baseUrl);
            routerProps.getModels().get("strict-loopback").setResponseModelVerificationRequired(true);
            MockEnvironment env = baseEnv().withProperty("llm.api-key", "loopback-key-value");
            LlmRouterAspect aspect = aspect(env, routerProps);
            ChatModel model = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(new FakePjp(
                    "fallback",
                    "llmrouter.strict-loopback",
                    null,
                    null,
                    null,
                    10)));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> model.chat(List.of(UserMessage.from("bounded identity probe"))));

            assertEquals("provider=model-router disabledReason=response_model_mismatch", failure.getMessage());
            assertEquals(1, requests.get());
            assertEquals("mismatch", TraceStore.get("llmrouter.responseModel.verdict"));
            assertEquals("fail", TraceStore.get("llmrouter.bandit.reward"));
            assertEquals("provider_error", TraceStore.get("llmrouter.bandit.failureClass"));
            assertFalse(TraceStore.getAll().toString().contains("expected-route-model"));
            assertFalse(TraceStore.getAll().toString().contains("unexpected-provider-model"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void strictResponseModelVerificationAcceptsOnlyExactOrRouteApprovedIdentity() {
        AtomicReference<String> responseModel = new AtomicReference<>("expected-route-model");
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok"))
                        .modelName(responseModel.get())
                        .build();
            }
        };
        ChatModel verifying = new LlmRouterAspect.ResponseModelVerifyingChatModel(
                delegate,
                "expected-route-model",
                List.of("provider-approved-snapshot"));

        assertEquals("ok", verifying.chat(List.of(UserMessage.from("probe"))).aiMessage().text());
        assertEquals("exact", TraceStore.get("llmrouter.responseModel.verdict"));

        TraceStore.clear();
        responseModel.set("provider-approved-snapshot");
        assertEquals("ok", verifying.chat(List.of(UserMessage.from("probe"))).aiMessage().text());
        assertEquals("approved_alias", TraceStore.get("llmrouter.responseModel.verdict"));
    }

    @Test
    void strictResponseModelVerificationRejectsMissingAndPrefixCollision() {
        AtomicReference<String> responseModel = new AtomicReference<>();
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok"))
                        .modelName(responseModel.get())
                        .build();
            }
        };
        ChatModel verifying = new LlmRouterAspect.ResponseModelVerifyingChatModel(
                delegate,
                "gpt-4o",
                List.of());

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> verifying.chat(List.of(UserMessage.from("probe"))));
        assertEquals("provider=model-router disabledReason=response_model_unverified", missing.getMessage());
        assertEquals("unverified", TraceStore.get("llmrouter.responseModel.verdict"));
        assertEquals("RESPONSE_MODEL_UNVERIFIED", new LlmGatewayFailureClassifier().classify(missing).name());

        TraceStore.clear();
        responseModel.set("gpt-4o-mini-2024-07-18");
        IllegalStateException collision = assertThrows(IllegalStateException.class,
                () -> verifying.chat(List.of(UserMessage.from("probe"))));
        assertEquals("provider=model-router disabledReason=response_model_mismatch", collision.getMessage());
        assertEquals("mismatch", TraceStore.get("llmrouter.responseModel.verdict"));
        assertFalse(TraceStore.getAll().toString().contains("gpt-4o-mini-2024-07-18"));
    }

    @Test
    void routedChatModelMakesOneHttpAttemptOnUpstreamFailure() throws Throwable {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            byte[] response = "{\"error\":{\"message\":\"upstream unavailable\",\"type\":\"server_error\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            MockEnvironment env = baseEnv().withProperty("llm.api-key", "loopback-key-value");
            LlmRouterAspect aspect = aspect(env, props("bounded-route", "gpt-bounded-route", baseUrl));
            ChatModel model = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(new FakePjp(
                    "fallback",
                    "llmrouter.bounded-route",
                    null,
                    null,
                    null,
                    10)));

            assertThrows(RuntimeException.class,
                    () -> model.chat(List.of(UserMessage.from("bounded retry probe"))));
            assertEquals(1, requests.get(), "one auxiliary role call must stay one physical HTTP attempt");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void currentApi3GroqRoutePublishesProviderAndEndpointProvenance() throws Throwable {
        String apiKey = "groq-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("GROQ_API_KEY", apiKey);
        LlmRouterAspect aspect = aspect(env,
                props("api3", "openai/gpt-oss-120b", "https://api.groq.com/openai/v1"));

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.api3",
                null,
                null,
                null,
                10));

        assertInstanceOf(ChatModel.class, out);
        assertEquals("api3", LlmRouterContext.get().key());
        assertEquals("api3", TraceStore.get("llmrouter.route.key"));
        assertEquals("groq", TraceStore.get("llmrouter.api.provider"));
        assertEquals("api.groq.com", TraceStore.get("llmrouter.endpointHost"));
        assertEquals("https", TraceStore.get("llmrouter.endpointScheme"));
        assertEquals("external-provider", TraceStore.get("llmrouter.endpointFamily"));
        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.api.hasKey"));
        assertNull(TraceStore.get("llmrouter.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(apiKey));
    }

    @Test
    void disabledMacMiniRouteDoesNotFallBackToLocalDefaults() {
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local");
        LlmRouterProperties props = props("macmini", "gemma3:4b", "https://macmini-ollama.internal/v1");
        props.getModels().get("macmini").setEnabled(false);
        LlmRouterAspect aspect = aspect(env, props);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.macmini",
                        null,
                        null,
                        null,
                        10)));

        assertEquals("provider=llmrouter.macmini disabledReason=route_disabled", ex.getMessage());
        assertEquals("macmini", TraceStore.get("llmrouter.route.key"));
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.route.enabled"));
        assertEquals("route_disabled", TraceStore.get("llmrouter.api.disabledReason"));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void disabledDirectRouteStopsBeforeUnavailableManagerAndEligibleFallback() throws Exception {
        AtomicInteger primaryRequests = new AtomicInteger();
        AtomicInteger fallbackRequests = new AtomicInteger();
        HttpServer primaryServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer fallbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        primaryServer.createContext("/", exchange -> {
            primaryRequests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        fallbackServer.createContext("/", exchange -> {
            fallbackRequests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        primaryServer.start();
        fallbackServer.start();
        try {
            LlmRouterProperties.ModelConfig primary = model("gpt-audit-primary",
                    "http://127.0.0.1:" + primaryServer.getAddress().getPort() + "/v1", false, 1);
            primary.setFallbackKey("cloud");
            LlmRouterProperties.ModelConfig backup = model("gpt-audit-backup",
                    "http://127.0.0.1:" + fallbackServer.getAddress().getPort() + "/v1", true, 1);
            LlmRouterProperties props = new LlmRouterProperties();
            props.setModels(Map.of("primary", primary, "cloud", backup));
            HybridLlmGatewayProbeService gateway = auditCloudGateway(primary, backup, true);
            LlmRouterAspect aspect = aspect(baseEnv().withProperty("llm.api-key", "loopback-key-value"), props, gateway);
            com.example.lms.config.LocalLlmProcessManager manager = mock(com.example.lms.config.LocalLlmProcessManager.class);
            when(manager.isAvailable(primary.getBaseUrl())).thenReturn(false);
            when(manager.isAvailable(backup.getBaseUrl())).thenReturn(true);
            ReflectionTestUtils.setField(aspect, "localLlmProcessManager", manager);

            LlmGatewayException failure = assertThrows(LlmGatewayException.class,
                    () -> aspect.aroundLcWithTimeout(new FakePjp(
                            "fallback", "llmrouter.primary", null, null, null, 10)));

            assertEquals("route_disabled", failure.reasonCode());
            assertEquals(LlmFailureClass.DISABLED, failure.failureClass());
            assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.route.enabled"));
            assertEquals("route_disabled", TraceStore.get("llmrouter.api.disabledReason"));
            assertNull(LlmRouterContext.get());
            assertEquals(0, primaryRequests.get());
            assertEquals(0, fallbackRequests.get());
            org.mockito.Mockito.verifyNoInteractions(manager);
            verify(gateway, org.mockito.Mockito.never()).evaluate(
                    org.mockito.Mockito.anyString(), any(), org.mockito.Mockito.anyString());
        } finally {
            primaryServer.stop(0);
            fallbackServer.stop(0);
        }
    }

    @Test
    void disabledSensitiveRouteKeyUsesDiagnosticLabelOnly() {
        String sensitiveRoute = "private-route-token=owner-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local");
        LlmRouterProperties props = props(sensitiveRoute, "gemma3:4b", "https://macmini-ollama.internal/v1");
        props.getModels().get(sensitiveRoute).setEnabled(false);
        LlmRouterAspect aspect = aspect(env, props);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter." + sensitiveRoute,
                        null,
                        null,
                        null,
                        10)));

        String routeTrace = String.valueOf(TraceStore.get("llmrouter.route.key"));
        assertFalse(ex.getMessage().contains(sensitiveRoute), ex.getMessage());
        assertFalse(routeTrace.contains(sensitiveRoute), routeTrace);
        assertTrue(routeTrace.startsWith("hash:"), routeTrace);
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.route.enabled"));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void blankExternalRouteDoesNotFallBackToLocalDefaults() {
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local");
        LlmRouterAspect aspect = aspect(env, props("external", "", ""));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.external",
                        null,
                        null,
                        null,
                        10)));

        assertEquals("provider=llmrouter.external disabledReason=missing_route_config", ex.getMessage());
        assertEquals("missing_route_config", TraceStore.get("llmrouter.api.disabledReason"));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void externalOpenRouterRouteRequiresProviderSpecificKey() {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("llm.api-key", "sk-local");
        LlmRouterAspect aspect = aspect(env, props("external", "openrouter/auto", "https://api.openrouter.ai/api/v1"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.external",
                        null,
                        null,
                        null,
                        10)));

        assertEquals("provider=openrouter disabledReason="
                + SafeRedactor.hashValue("missing OPENROUTER_API_KEY"), ex.getMessage());
        assertEquals("openrouter", TraceStore.get("llmrouter.api.provider"));
        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.api.providerDisabled"));
        assertEquals("provider-disabled", TraceStore.get("llmrouter.api.failureClass"));
        assertEquals("api.openrouter.ai", TraceStore.get("llmrouter.api.endpointHost"));
        assertEquals("https", TraceStore.get("llmrouter.api.endpointScheme"));
        assertEquals("external-provider", TraceStore.get("llmrouter.api.endpointFamily"));
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.api.hasKey"));
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.api.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void externalOpenCodeRouteRequiresProviderSpecificKey() {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("llm.api-key", "sk-local");
        LlmRouterAspect aspect = aspect(env,
                props("external", "deepseek-v4-flash-free", "https://opencode.ai/zen/v1"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.external",
                        null,
                        null,
                        null,
                        10)));

        assertEquals("provider=opencode disabledReason="
                + SafeRedactor.hashValue("missing OPENCODE_API_KEY"), ex.getMessage());
        assertEquals("opencode", TraceStore.get("llmrouter.api.provider"));
        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.api.providerDisabled"));
        assertEquals("provider-disabled", TraceStore.get("llmrouter.api.failureClass"));
        assertEquals("opencode.ai", TraceStore.get("llmrouter.api.endpointHost"));
        assertEquals("https", TraceStore.get("llmrouter.api.endpointScheme"));
        assertEquals("external-provider", TraceStore.get("llmrouter.api.endpointFamily"));
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.api.hasKey"));
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.api.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void externalOpenCodeRouteDoesNotReceiveOwnerToken() throws Throwable {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("OPENCODE_API_KEY", "opencode-secret-value");
        LlmRouterAspect aspect = aspect(env,
                props("external", "deepseek-v4-flash-free", "https://opencode.ai/zen/v1"));

        Object out = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.external",
                null,
                null,
                null,
                10));

        assertInstanceOf(ChatModel.class, out);
        assertEquals("external", LlmRouterContext.get().key());
        assertEquals("opencode.ai", TraceStore.get("llmrouter.endpointHost"));
        assertEquals("https", TraceStore.get("llmrouter.endpointScheme"));
        assertEquals("external-provider", TraceStore.get("llmrouter.endpointFamily"));
        assertNull(TraceStore.get("llmrouter.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
        assertFalse(TraceStore.getAll().toString().contains("opencode-secret-value"));
    }

    @Test
    void externalGeminiRouteRequiresProviderSpecificKey() throws Throwable {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("llm.api-key", "sk-local")
                .withProperty("gemini.gateway.purpose.router.enabled", "true");
        LlmRouterAspect aspect = aspect(env,
                props("gemini-pro", "gemini-2.5-pro", "https://generativelanguage.googleapis.com/v1beta/openai"));

        ChatModel disabled = assertInstanceOf(ChatModel.class,
                aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.gemini-pro",
                        null,
                        null,
                        null,
                        10)));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> disabled.chat(List.of()));

        assertEquals("provider=gemini disabledReason=missing-credential", ex.getMessage());
        assertEquals("gemini", TraceStore.get("llmrouter.api.provider"));
        assertEquals(Boolean.FALSE, TraceStore.get("llmrouter.api.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void externalGeminiAndMistralRoutesDoNotReceiveOwnerToken() throws Throwable {
        String ownerToken = "owner-proxy-secret-value";
        MockEnvironment env = baseEnv()
                .withProperty("llm.owner-token", ownerToken)
                .withProperty("GEMINI_API_KEY", "gemini-secret-value")
                .withProperty("gemini.gateway.purpose.router.enabled", "true")
                .withProperty("MISTRAL_API_KEY", "mistral-secret-value");

        LlmRouterAspect gemini = aspect(env,
                props("gemini-pro", "gemini-2.5-pro", "https://generativelanguage.googleapis.com/v1beta/openai"));
        Object geminiOut = gemini.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.gemini-pro",
                null,
                null,
                null,
                10));
        assertInstanceOf(ChatModel.class, geminiOut);
        assertEquals("gemini-pro", LlmRouterContext.get().key());
        assertEquals("gemini-pro", TraceStore.get("llmrouter.route.key"));
        assertEquals("gemini", TraceStore.get("llmrouter.api.provider"));
        assertEquals("generativelanguage.googleapis.com", TraceStore.get("llmrouter.endpointHost"));
        assertEquals("https", TraceStore.get("llmrouter.endpointScheme"));
        assertEquals("external-provider", TraceStore.get("llmrouter.endpointFamily"));
        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.api.hasKey"));
        assertNull(TraceStore.get("llmrouter.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
        assertFalse(TraceStore.getAll().toString().contains("gemini-secret-value"));

        TraceStore.clear();
        LlmRouterContext.clear();

        LlmRouterAspect mistral = aspect(env,
                props("mistral-medium", "mistral-medium-latest", "https://api.mistral.ai/v1"));
        Object mistralOut = mistral.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.mistral-medium",
                null,
                null,
                null,
                10));
        assertInstanceOf(ChatModel.class, mistralOut);
        assertEquals("mistral-medium", LlmRouterContext.get().key());
        assertEquals("external-provider", TraceStore.get("llmrouter.endpointFamily"));
        assertNull(TraceStore.get("llmrouter.hasOwnerToken"));
        assertFalse(TraceStore.getAll().toString().contains(ownerToken));
        assertFalse(TraceStore.getAll().toString().contains("mistral-secret-value"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void geminiRouterRouteDelegatesModelConstructionToGeminiGateway() throws Throwable {
        MockEnvironment env = baseEnv().withProperty("llm.api-key", "sk-local");
        LlmRouterProperties props = props(
                "gemini-pro",
                "gemini-2.5-pro-test",
                "http://127.0.0.1:65534/v1beta/openai");
        props.getModels().get("gemini-pro").setProvider("gemini");

        ChatModel delegated = new ChatModel() {
            @Override
            public ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("gateway-owned"))
                        .modelName("gemini-2.5-pro-test")
                        .build();
            }
        };
        GeminiGateway geminiGateway = mock(GeminiGateway.class);
        when(geminiGateway.buildOpenAiCompatibleChatModel(any(GeminiGateway.RouterSpec.class)))
                .thenReturn(delegated);
        ObjectProvider<KeyResolver> keyResolverProvider = mock(ObjectProvider.class);

        LlmRouterAspect aspect = new LlmRouterAspect(
                env,
                props,
                new LlmRouterBandit(props),
                new NovaModelGuardProperties(),
                keyResolverProvider,
                null,
                null,
                new LlmGatewayFailureClassifier(),
                null,
                geminiGateway);

        Object routed = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.gemini-pro",
                0.2,
                0.9,
                null,
                128));

        assertEquals("gateway-owned", ((ChatModel) routed).chat(List.of(UserMessage.from("probe"))).aiMessage().text());
        verify(geminiGateway).buildOpenAiCompatibleChatModel(any(GeminiGateway.RouterSpec.class));
    }

    @Test
    void anthropicNativeRouteStaysUnsupportedWithoutAdapter() {
        MockEnvironment env = baseEnv()
                .withProperty("ANTHROPIC_API_KEY", "anthropic-secret-value");
        LlmRouterAspect aspect = aspect(env,
                props("anthropic", "claude-current-family", "https://api.anthropic.com/v1"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback",
                        "llmrouter.anthropic",
                        null,
                        null,
                        null,
                        10)));

        assertEquals("provider=anthropic disabledReason=unsupported_anthropic_native_route", ex.getMessage());
        assertEquals("anthropic", TraceStore.get("llmrouter.api.provider"));
        assertEquals("unsupported-provider", TraceStore.get("llmrouter.api.failureClass"));
        assertFalse(TraceStore.getAll().toString().contains("anthropic-secret-value"));
        assertNull(LlmRouterContext.get());
    }

    @Test
    void autoSkipsDisabledAndZeroWeightExternalRoutes() {
        LlmRouterProperties props = new LlmRouterProperties();
        Map<String, LlmRouterProperties.ModelConfig> models = new LinkedHashMap<>();
        models.put("macmini", model("gemma3:4b", "https://macmini-ollama.internal/v1", false, 10.0d));
        models.put("external", model("openrouter/auto", "https://api.openrouter.ai/api/v1", true, 0.0d));
        models.put("gemma", model("gemma3:4b", "http://localhost:11434/v1", true, 1.0d));
        props.setModels(models);

        LlmRouterBandit.Selected selected = new LlmRouterBandit(props).pick("llmrouter.auto");

        assertEquals("gemma", selected.key());
        assertEquals("gemma3:4b", selected.cfg().getName());
    }

    @Test
    void enforceModeAutoSkipsFallbackOnlyRoute() throws Throwable {
        MockEnvironment env = baseEnv()
                .withProperty("llm.api-key", "sk-local");
        LlmRouterProperties props = new LlmRouterProperties();
        Map<String, LlmRouterProperties.ModelConfig> models = new LinkedHashMap<>();
        LlmRouterProperties.ModelConfig cloud = model("qwen/qwen3-32b", "https://api.groq.com/openai/v1", true, 10.0d);
        cloud.setFallbackOnly(true);
        models.put("api3", cloud);
        models.put("gemma", model("gemma3:4b", "http://localhost:11434/v1", true, 1.0d));
        props.setModels(models);
        LlmGatewayProperties gatewayProps = new LlmGatewayProperties();
        gatewayProps.setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
        gatewayProps.getSpecRegistry().setEnabled(false);
        HybridLlmGatewayProbeService gateway = new HybridLlmGatewayProbeService(
                gatewayProps,
                new ModelRuntimeHealthTracker(),
                new ModelSpecRegistry(new ObjectMapper(), gatewayProps),
                new LlmRouteScorer());
        LlmRouterAspect aspect = aspect(env, props, gateway);

        aspect.aroundLcWithTimeout(new FakePjp(
                "fallback",
                "llmrouter.auto",
                null,
                null,
                null,
                10));

        assertEquals("gemma", LlmRouterContext.get().key());
    }

    @Test
    void routeConfigKeepsDeviceFallbackSeparateFromCloudFallback() {
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();

        cfg.setFallbackKey("cloud-api3");
        cfg.setDeviceFallbackKey("local-3060");
        cfg.setDeviceRole("rtx3090");

        assertEquals("cloud-api3", cfg.getFallbackKey());
        assertEquals("local-3060", cfg.getDeviceFallbackKey());
        assertEquals("rtx3090", cfg.getDeviceRole());
    }

    @Test
    void directDeviceLossDoesNotSilentlySubstituteWithoutDeviceFallbackKey() {
        String failedEndpoint = "http://127.0.0.1:21434/v1";
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        HybridLlmGatewayProbeService gateway = endpointEnforcingGateway(tracker);
        openEndpoint(tracker, gateway, failedEndpoint);
        LlmRouterProperties.ModelConfig primary = localDeviceModel(
                "qwen3:30b", failedEndpoint, "rtx3090");
        LlmRouterProperties props = new LlmRouterProperties();
        props.setModels(Map.of("primary", primary));
        LlmRouterAspect aspect = aspect(baseEnv(), props, gateway);

        LlmGatewayException failure = assertThrows(LlmGatewayException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback", "llmrouter.primary", null, null, null, 10)));

        assertEquals("gpu_device_lost", failure.reasonCode());
        assertNull(LlmRouterContext.get());
    }

    @Test
    void directDeviceLossUsesOnlyExplicitHealthyDifferentLocalEndpoint() throws Throwable {
        String failedEndpoint = "http://127.0.0.1:21435/v1";
        String fallbackEndpoint = "http://127.0.0.1:21436/v1";
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        HybridLlmGatewayProbeService gateway = endpointEnforcingGateway(tracker);
        openEndpoint(tracker, gateway, failedEndpoint);
        LlmRouterProperties.ModelConfig primary = localDeviceModel(
                "qwen3:30b", failedEndpoint, "rtx3090");
        primary.setDeviceFallbackKey("backup");
        LlmRouterProperties.ModelConfig backup = localDeviceModel(
                "qwen3:8b", fallbackEndpoint, "rtx3060");
        LlmRouterProperties props = new LlmRouterProperties();
        props.setModels(Map.of("primary", primary, "backup", backup));
        LlmRouterAspect aspect = aspect(baseEnv(), props, gateway);

        Object routed = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback", "llmrouter.primary", null, null, null, 10));

        assertInstanceOf(ChatModel.class, routed);
        assertEquals("backup", LlmRouterContext.get().key());
        assertEquals(ModelRuntimeHealthTracker.endpointIdentityHash(fallbackEndpoint),
                ModelRuntimeHealthTracker.endpointIdentityHash(LlmRouterContext.get().baseUrl()));
    }

    @Test
    void explicitDeviceFallbackRequiresObservedEligibleProbeResult() {
        String failedEndpoint = "http://127.0.0.1:21445/v1";
        String fallbackEndpoint = "http://127.0.0.1:21446/v1";
        LlmRouterProperties.ModelConfig primary = localDeviceModel(
                "qwen3:30b", failedEndpoint, "rtx3090");
        primary.setDeviceFallbackKey("backup");
        LlmRouterProperties.ModelConfig backup = localDeviceModel(
                "qwen3:8b", fallbackEndpoint, "rtx3060");
        LlmRouterProperties props = new LlmRouterProperties();
        props.setModels(Map.of("primary", primary, "backup", backup));

        HybridLlmGatewayProbeService gateway = mock(HybridLlmGatewayProbeService.class);
        when(gateway.isEnforce()).thenReturn(true);
        when(gateway.evaluate("primary", primary, "chat")).thenReturn(RoutingEligibility.blocked(
                "primary",
                "local",
                primary.getName(),
                "chat",
                0,
                false,
                List.of(LlmFailureClass.GPU_DEVICE_LOST),
                Map.of()));
        when(gateway.evaluate("backup", backup, "chat")).thenReturn(null);
        LlmRouterAspect aspect = aspect(baseEnv(), props, gateway);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback", "llmrouter.primary", null, null, null, 10)));

        assertTrue(failure.getMessage().contains("device_fallback_eligibility_unavailable"), failure.getMessage());
        assertNull(LlmRouterContext.get());
    }

    @Test
    void deviceRoleEndpointCollisionFailsClosedAsGpuRoleAmbiguous() {
        String failedEndpoint = "http://127.0.0.1:21437/v1";
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        HybridLlmGatewayProbeService gateway = endpointEnforcingGateway(tracker);
        openEndpoint(tracker, gateway, failedEndpoint);
        LlmRouterProperties.ModelConfig primary = localDeviceModel(
                "qwen3:30b", failedEndpoint, "rtx3090");
        primary.setDeviceFallbackKey("backup");
        LlmRouterProperties.ModelConfig backup = localDeviceModel(
                "qwen3:8b", "http://127.0.0.1:21437/other/path", "rtx3060");
        LlmRouterProperties props = new LlmRouterProperties();
        props.setModels(Map.of("primary", primary, "backup", backup));
        LlmRouterAspect aspect = aspect(baseEnv(), props, gateway);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> aspect.aroundLcWithTimeout(new FakePjp(
                        "fallback", "llmrouter.primary", null, null, null, 10)));

        assertTrue(failure.getMessage().contains("gpu_role_ambiguous"), failure.getMessage());
        assertNull(LlmRouterContext.get());
    }

    @Test
    void autoExcludesEveryRouteOnOpenEndpointAndSelectsDifferentHealthyEndpoint() throws Throwable {
        String failedEndpoint = "http://127.0.0.1:21438/v1";
        String healthyEndpoint = "http://127.0.0.1:21439/v1";
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        HybridLlmGatewayProbeService gateway = endpointEnforcingGateway(tracker);
        openEndpoint(tracker, gateway, failedEndpoint);
        LlmRouterProperties.ModelConfig primary = localDeviceModel(
                "qwen3:30b", failedEndpoint, "rtx3090");
        primary.setWeight(10.0d);
        LlmRouterProperties.ModelConfig shared = localDeviceModel(
                "gemma4:26b", "http://127.0.0.1:21438/other/path", "rtx3090");
        shared.setWeight(9.0d);
        LlmRouterProperties.ModelConfig healthy = localDeviceModel(
                "qwen3:8b", healthyEndpoint, "rtx3060");
        healthy.setWeight(1.0d);
        LlmRouterProperties props = new LlmRouterProperties();
        Map<String, LlmRouterProperties.ModelConfig> models = new LinkedHashMap<>();
        models.put("primary", primary);
        models.put("shared", shared);
        models.put("healthy", healthy);
        props.setModels(models);
        LlmRouterAspect aspect = aspect(baseEnv(), props, gateway);

        Object routed = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback", "llmrouter.auto", null, null, null, 10));

        assertInstanceOf(ChatModel.class, routed);
        assertEquals("healthy", LlmRouterContext.get().key());
        assertEquals(
                ModelRuntimeHealthTracker.endpointIdentityHash(healthyEndpoint),
                ModelRuntimeHealthTracker.endpointIdentityHash(LlmRouterContext.get().baseUrl()));
    }

    @Test
    void allLocalEndpointsOpenReturnsTypedFailureWithZeroNewHttpAttempts() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer firstServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer secondServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        firstServer.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        secondServer.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        firstServer.start();
        secondServer.start();
        try {
            String firstEndpoint = "http://127.0.0.1:" + firstServer.getAddress().getPort() + "/v1";
            String secondEndpoint = "http://127.0.0.1:" + secondServer.getAddress().getPort() + "/v1";
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            HybridLlmGatewayProbeService gateway = endpointEnforcingGateway(tracker);
            openEndpoint(tracker, gateway, firstEndpoint);
            openEndpoint(tracker, gateway, secondEndpoint);
            LlmRouterProperties props = new LlmRouterProperties();
            props.setModels(Map.of(
                    "first", localDeviceModel("qwen3:30b", firstEndpoint, "rtx3090"),
                    "second", localDeviceModel("qwen3:8b", secondEndpoint, "rtx3060")));
            LlmRouterAspect aspect = aspect(baseEnv(), props, gateway);

            LlmGatewayException failure = assertThrows(
                    LlmGatewayException.class,
                    () -> aspect.aroundLcWithTimeout(new FakePjp(
                            "fallback", "llmrouter.auto", null, null, null, 10)));

            assertEquals("gpu_device_lost", failure.reasonCode());
            assertEquals(0, requests.get());
            assertNull(LlmRouterContext.get());
        } finally {
            firstServer.stop(0);
            secondServer.stop(0);
        }
    }

    @Test
    void allLocalEndpointsOpenUsesCloudOnlyWhenExistingCloudPolicyIsEnabled() throws Throwable {
        String failedEndpoint = "http://127.0.0.1:21440/v1";
        MockEnvironment env = baseEnv().withProperty("GROQ_API_KEY", "gsk-test-cloud-policy");
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        LlmGatewayProperties gatewayProperties = new LlmGatewayProperties();
        gatewayProperties.setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
        gatewayProperties.getSpecRegistry().setEnabled(false);
        gatewayProperties.getLocalDeviceFailover().setEnabled(true);
        gatewayProperties.getLocalDeviceFailover().setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
        gatewayProperties.getCloud().setEnabled(true);
        gatewayProperties.getCloud().setRouteKey("api3");
        tracker.recordEndpointDeviceLoss(
                "local",
                failedEndpoint,
                gatewayProperties.getLocalDeviceFailover().toEndpointQuarantinePolicy(),
                System.currentTimeMillis());
        HybridLlmGatewayProbeService gateway = new HybridLlmGatewayProbeService(
                gatewayProperties,
                tracker,
                new ModelSpecRegistry(new ObjectMapper(), gatewayProperties),
                new LlmRouteScorer(),
                env);
        LlmRouterProperties.ModelConfig primary = localDeviceModel(
                "qwen3:30b", failedEndpoint, "rtx3090");
        LlmRouterProperties.ModelConfig cloud = model(
                "openai/gpt-oss-120b", "https://api.groq.com/openai/v1", true, 0.0d);
        cloud.setProvider("groq");
        cloud.setStage("chat");
        cloud.setFallbackOnly(true);
        cloud.setCredentialEnv("GROQ_API_KEY");
        LlmRouterProperties props = new LlmRouterProperties();
        props.setModels(Map.of("primary", primary, "api3", cloud));
        LlmRouterAspect aspect = aspect(env, props, gateway);

        Object routed = aspect.aroundLcWithTimeout(new FakePjp(
                "fallback", "llmrouter.auto", null, null, null, 10));

        assertInstanceOf(ChatModel.class, routed);
        assertEquals("api3", LlmRouterContext.get().key());
        assertEquals("groq", TraceStore.get("llmrouter.api.provider"));
    }

    @Test
    void routedChatModelRecordsBanditOutcomeOnChatSuccess() {
        LlmRouterProperties props = props("gemma", "gemma3:4b", "http://localhost:11434/v1");
        LlmRouterBandit bandit = new LlmRouterBandit(props);
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok"))
                        .build();
            }
        };
        ChatModel recording = new LlmRouterAspect.RecordingChatModel(
                delegate,
                bandit,
                "gemma",
                new LlmGatewayFailureClassifier());

        assertEquals("ok", recording.chat(List.of(UserMessage.from("probe"))).aiMessage().text());

        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.bandit.rewardRecorded"));
        assertEquals("success", TraceStore.get("llmrouter.bandit.reward"));
        assertEquals("none", TraceStore.get("llmrouter.bandit.failureClass"));
        List<?> breadcrumbs = assertInstanceOf(List.class, TraceStore.get("ml.breadcrumbs.v1"));
        Map<?, ?> row = assertInstanceOf(Map.class, breadcrumbs.get(0));
        assertEquals("LlmRouterBandit", row.get("component"));
        assertEquals("llm_router_reward", row.get("decision"));
        Map<?, ?> data = assertInstanceOf(Map.class, row.get("data"));
        assertEquals("gemma", data.get("llmArm"));
        assertEquals(1.0d, data.get("reward"));
        assertEquals("none", data.get("failureClass"));
        assertInstanceOf(Map.class, TraceStore.get("mla.breadcrumb.llm.reward.gemma"));
    }

    @Test
    void routedChatModelRecordsBanditOutcomeOnUncheckedFailure() {
        LlmRouterProperties props = props("gemma", "gemma3:4b", "http://localhost:11434/v1");
        LlmRouterBandit bandit = new LlmRouterBandit(props);
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
                throw new NullPointerException("simulated unchecked model failure");
            }
        };
        ChatModel recording = new LlmRouterAspect.RecordingChatModel(
                delegate,
                bandit,
                "gemma",
                new LlmGatewayFailureClassifier());

        assertThrows(NullPointerException.class, () -> recording.chat(List.of(UserMessage.from("probe"))));

        assertEquals(Boolean.TRUE, TraceStore.get("llmrouter.bandit.rewardRecorded"));
        assertEquals("fail", TraceStore.get("llmrouter.bandit.reward"));
        assertEquals("unknown", TraceStore.get("llmrouter.bandit.failureClass"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void enforcedCloudFallbackRequiresObservedEligibleProbeResult(boolean unavailable) {
        LlmRouterProperties.ModelConfig primary = model("gpt-audit-primary", "http://127.0.0.1:21571/v1", true, 1);
        primary.setFallbackKey("cloud");
        LlmRouterProperties.ModelConfig cloud = model("gpt-audit-cloud", "http://127.0.0.1:21572/v1", true, 1);
        LlmRouterProperties props = new LlmRouterProperties();
        props.setModels(Map.of("primary", primary, "cloud", cloud));
        HybridLlmGatewayProbeService gateway = auditCloudGateway(primary, cloud, true);
        when(gateway.evaluate("primary", primary, "chat")).thenReturn(auditBlockedRoute("primary", primary));
        when(gateway.evaluate("cloud", cloud, "chat")).thenReturn(unavailable ? null : auditBlockedRoute("cloud", cloud));
        LlmRouterAspect aspect = aspect(baseEnv().withProperty("llm.api-key", "loopback-key-value"), props, gateway);

        assertThrows(IllegalStateException.class, () -> aspect.aroundLcWithTimeout(new FakePjp(
                "fallback", "llmrouter.primary", null, null, null, 10)));
        assertNull(LlmRouterContext.get(), "rejected cloud route must never become the selected model");
        verify(gateway).evaluate("cloud", cloud, "chat");
    }

    @Test
    void enforcedEligibleCloudFallbackOnlyRouteRemainsAvailable() throws Throwable {
        LlmRouterProperties.ModelConfig primary = model("gpt-audit-primary", "http://127.0.0.1:21571/v1", true, 1);
        primary.setFallbackKey("cloud");
        LlmRouterProperties.ModelConfig cloud = model("gpt-audit-cloud", "http://127.0.0.1:21572/v1", true, 1);
        cloud.setFallbackOnly(true);
        LlmRouterProperties props = new LlmRouterProperties();
        props.setModels(Map.of("primary", primary, "cloud", cloud));
        HybridLlmGatewayProbeService gateway = auditCloudGateway(primary, cloud, true);
        when(gateway.evaluate("primary", primary, "chat")).thenReturn(auditBlockedRoute("primary", primary));
        LlmRouterAspect aspect = aspect(baseEnv().withProperty("llm.api-key", "loopback-key-value"), props, gateway);

        assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(new FakePjp(
                "fallback", "llmrouter.primary", null, null, null, 10)));
        assertEquals("cloud", LlmRouterContext.get().key());
    }

    @Test
    void unavailableLocalProcessCannotSelectIneligibleCloudFallback() {
        LlmRouterProperties.ModelConfig primary = model("gpt-audit-primary", "http://127.0.0.1:21571/v1", true, 1);
        primary.setFallbackKey("cloud");
        LlmRouterProperties.ModelConfig cloud = model("gpt-audit-cloud", "http://127.0.0.1:21572/v1", true, 1);
        LlmRouterProperties props = new LlmRouterProperties();
        props.setModels(Map.of("primary", primary, "cloud", cloud));
        HybridLlmGatewayProbeService gateway = auditCloudGateway(primary, cloud, true);
        when(gateway.evaluate("cloud", cloud, "chat")).thenReturn(auditBlockedRoute("cloud", cloud));
        LlmRouterAspect aspect = aspect(baseEnv().withProperty("llm.api-key", "loopback-key-value"), props, gateway);
        com.example.lms.config.LocalLlmProcessManager manager = mock(com.example.lms.config.LocalLlmProcessManager.class);
        when(manager.isAvailable(primary.getBaseUrl())).thenReturn(false);
        when(manager.isAvailable(cloud.getBaseUrl())).thenReturn(true);
        ReflectionTestUtils.setField(aspect, "localLlmProcessManager", manager);

        assertThrows(IllegalStateException.class, () -> aspect.aroundLcWithTimeout(new FakePjp(
                "fallback", "llmrouter.primary", null, null, null, 10)));
        assertNull(LlmRouterContext.get());
        verify(gateway).evaluate("cloud", cloud, "chat");
        verify(manager, org.mockito.Mockito.never()).recordFallback(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"blocked", "unavailable", "eligible", "observe"})
    void lazyCloudFallbackChecksCurrentEligibilityOnlyWhenActivated(String mode) throws Throwable {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int attempt = requests.incrementAndGet();
            String payload = attempt == 1
                    ? "{\"error\":{\"message\":\"synthetic primary unavailable\",\"type\":\"server_error\"}}"
                    : "{\"id\":\"synthetic\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-audit-cloud\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"synthetic fallback\"},\"finish_reason\":\"stop\"}]}";
            byte[] response = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(attempt == 1 ? 503 : 200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            LlmRouterProperties.ModelConfig primary = model("gpt-audit-primary", baseUrl, true, 1);
            primary.setFallbackKey("cloud");
            LlmRouterProperties.ModelConfig cloud = model("gpt-audit-cloud", baseUrl, true, 1);
            LlmRouterProperties props = new LlmRouterProperties();
            props.setModels(Map.of("primary", primary, "cloud", cloud));
            HybridLlmGatewayProbeService gateway = auditCloudGateway(primary, cloud, !mode.equals("observe"));
            when(gateway.evaluate("cloud", cloud, "chat")).thenAnswer(invocation -> {
                if (requests.get() == 0 || mode.equals("eligible")) return auditEligibleRoute("cloud", cloud);
                return mode.equals("unavailable") ? null : auditBlockedRoute("cloud", cloud);
            });
            LlmRouterAspect aspect = aspect(baseEnv().withProperty("llm.api-key", "loopback-key-value"), props, gateway);
            ChatModel routed = assertInstanceOf(ChatModel.class, aspect.aroundLcWithTimeout(new FakePjp(
                    "fallback", "llmrouter.primary", null, null, null, 10)));
            verify(gateway, org.mockito.Mockito.never()).evaluate("cloud", cloud, "chat");
            assertEquals(0, requests.get());

            if (mode.equals("blocked") || mode.equals("unavailable")) {
                RuntimeException failure = assertThrows(RuntimeException.class,
                        () -> routed.chat(List.of(UserMessage.from("Synthetic cloud eligibility fixture."))));
                assertEquals(LlmFailureClass.HEALTH_DOWN, new LlmGatewayFailureClassifier().classify(failure));
                assertEquals(1, requests.get(), "eligibility changed after construction: cloud must receive no request");
                assertEquals("no_eligible_fallback", TraceStore.get("llm.gateway.fallback.skippedReason"));
            } else {
                assertEquals("synthetic fallback", routed.chat(List.of(UserMessage.from("Synthetic cloud eligibility fixture."))).aiMessage().text());
                assertEquals(2, requests.get(), "eligible and OBSERVE fallback must remain usable");
            }
            if (mode.equals("observe")) verify(gateway, org.mockito.Mockito.never()).evaluate("cloud", cloud, "chat");
            else verify(gateway).evaluate("cloud", cloud, "chat");
        } finally {
            server.stop(0);
        }
    }

    private static HybridLlmGatewayProbeService auditCloudGateway(
            LlmRouterProperties.ModelConfig primary, LlmRouterProperties.ModelConfig cloud, boolean enforce) {
        HybridLlmGatewayProbeService gateway = mock(HybridLlmGatewayProbeService.class);
        when(gateway.isEnforce()).thenReturn(enforce);
        when(gateway.cloudFallbackEnabled()).thenReturn(true);
        when(gateway.cloudRouteKey()).thenReturn("cloud");
        when(gateway.evaluate("primary", primary, "chat")).thenReturn(auditEligibleRoute("primary", primary));
        when(gateway.evaluate("cloud", cloud, "chat")).thenReturn(auditEligibleRoute("cloud", cloud));
        return gateway;
    }

    private static RoutingEligibility auditEligibleRoute(String key, LlmRouterProperties.ModelConfig cfg) {
        return RoutingEligibility.eligible(key, "local", cfg.getName(), "chat", 100, cfg.isFallbackOnly(), Map.of());
    }

    private static RoutingEligibility auditBlockedRoute(String key, LlmRouterProperties.ModelConfig cfg) {
        return RoutingEligibility.blocked(key, "local", cfg.getName(), "chat", 0, cfg.isFallbackOnly(),
                List.of(LlmFailureClass.PROVIDER_ERROR), Map.of());
    }

    private static MockEnvironment baseEnv() {
        return new MockEnvironment()
                .withProperty("llm.chat-model", "gemma3:4b")
                .withProperty("llm.owner-token-header", "X-Owner-Token");
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

    private static LlmRouterProperties.ModelConfig model(String modelName, String baseUrl, boolean enabled, double weight) {
        LlmRouterProperties.ModelConfig cfg = new LlmRouterProperties.ModelConfig();
        cfg.setEnabled(enabled);
        cfg.setName(modelName);
        cfg.setBaseUrl(baseUrl);
        cfg.setWeight(weight);
        return cfg;
    }

    private static LlmRouterProperties.ModelConfig localDeviceModel(
            String modelName,
            String baseUrl,
            String deviceRole) {
        LlmRouterProperties.ModelConfig cfg = model(modelName, baseUrl, true, 1.0d);
        cfg.setProvider("local");
        cfg.setStage("chat");
        cfg.setDeviceRole(deviceRole);
        return cfg;
    }

    private static HybridLlmGatewayProbeService endpointEnforcingGateway(ModelRuntimeHealthTracker tracker) {
        LlmGatewayProperties gatewayProps = new LlmGatewayProperties();
        gatewayProps.setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
        gatewayProps.getSpecRegistry().setEnabled(false);
        gatewayProps.getLocalDeviceFailover().setEnabled(true);
        gatewayProps.getLocalDeviceFailover().setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
        return new HybridLlmGatewayProbeService(
                gatewayProps,
                tracker,
                new ModelSpecRegistry(new ObjectMapper(), gatewayProps),
                new LlmRouteScorer());
    }

    private static void openEndpoint(
            ModelRuntimeHealthTracker tracker,
            HybridLlmGatewayProbeService gateway,
            String endpoint) {
        LlmGatewayProperties properties = (LlmGatewayProperties) ReflectionTestUtils.getField(gateway, "properties");
        tracker.recordEndpointDeviceLoss(
                "local",
                endpoint,
                properties.getLocalDeviceFailover().toEndpointQuarantinePolicy(),
                System.currentTimeMillis());
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(MockEnvironment env, LlmRouterProperties props) {
        return aspect(env, props, (HybridLlmGatewayProbeService) null);
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(MockEnvironment env, LlmRouterProperties props, HybridLlmGatewayProbeService gateway) {
        ObjectProvider<KeyResolver> keyResolverProvider = mock(ObjectProvider.class);
        when(keyResolverProvider.getIfAvailable()).thenReturn(null);
        NovaModelGuardProperties modelGuard = new NovaModelGuardProperties();
        modelGuard.setEnabled(false);
        return aspect(env, props, modelGuard, gateway);
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(MockEnvironment env, LlmRouterProperties props, NovaModelGuardProperties modelGuard) {
        return aspect(env, props, modelGuard, null);
    }

    @SuppressWarnings("unchecked")
    private static LlmRouterAspect aspect(MockEnvironment env, LlmRouterProperties props,
                                          NovaModelGuardProperties modelGuard,
                                          HybridLlmGatewayProbeService gateway) {
        ObjectProvider<KeyResolver> keyResolverProvider = mock(ObjectProvider.class);
        when(keyResolverProvider.getIfAvailable()).thenReturn(null);
        GeminiGateway geminiGateway = new GeminiGateway(
                WebClient.builder(),
                new ProviderCredentialResolver(env),
                env);
        return new LlmRouterAspect(
                env,
                props,
                new LlmRouterBandit(props),
                modelGuard,
                keyResolverProvider,
                gateway,
                null,
                new LlmGatewayFailureClassifier(),
                null,
                geminiGateway);
    }

    private static final class FakePjp implements ProceedingJoinPoint {
        private final Object result;
        private final Object[] args;

        private FakePjp(Object result, Object... args) {
            this.result = result;
            this.args = args;
        }

        @Override
        public Object proceed() {
            return result;
        }

        @Override
        public Object proceed(Object[] args) {
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
