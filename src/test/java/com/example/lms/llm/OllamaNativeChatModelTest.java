package com.example.lms.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaNativeChatModelTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void creativeEffectiveHashRequiresAndConsumesFinalSamplingMarker() throws Exception {
        GuardContext creative = completeWildCreativeContext();
        GuardContextHolder.set(creative);
        java.lang.reflect.Method record = DynamicChatModelFactory.class.getDeclaredMethod(
                "recordCreativeEffectiveSampling", Double.class, Double.class);
        record.setAccessible(true);

        record.invoke(null, 0.34d, 0.98d);
        assertEquals(null, TraceStore.get("creative.emergence.effectiveOptionsHash"));

        creative.putPlanOverride("creative.emergence.final.providerSamplingPending", true);
        record.invoke(null, 0.34d, 0.98d);
        String effectiveHash = SafeRedactor.hashValue("WILD|0.34|0.98");
        assertEquals(effectiveHash, TraceStore.get("creative.emergence.effectiveOptionsHash"));
        assertEquals(null, creative.getPlanOverride("creative.emergence.final.providerSamplingPending"));

        record.invoke(null, 0.20d, 0.80d);
        assertEquals(effectiveHash, TraceStore.get("creative.emergence.effectiveOptionsHash"));
    }

    @Test
    void factoryFailureConsumesCreativeSamplingMarkerAndClearsStaleLineage() {
        GuardContext creative = completeWildCreativeContext();
        creative.putPlanOverride("creative.emergence.final.providerSamplingPending", true);
        creative.putPlanOverride("creative.emergence.effectiveOptionsHash", "hash:deadbeefdead");
        creative.putPlanOverride("creative.emergence.provider.effectiveTemperature", 1.31d);
        creative.putPlanOverride("creative.emergence.provider.effectiveTopP", 0.98d);
        GuardContextHolder.set(creative);
        TraceStore.put("creative.emergence.effectiveOptionsHash", "hash:deadbeefdead");
        DynamicChatModelFactory factory = configuredNativeFactory("http://127.0.0.1:11435/v1");
        ReflectionTestUtils.setField(factory, "defaultModelName", " ");

        assertThrows(IllegalStateException.class,
                () -> factory.lcWithTimeout(null, 1.31d, 0.98d, null, null, 32, 2, 0));

        assertEquals(null, creative.getPlanOverride("creative.emergence.final.providerSamplingPending"));
        assertEquals(null, creative.getPlanOverride("creative.emergence.effectiveOptionsHash"));
        assertEquals(null, creative.getPlanOverride("creative.emergence.provider.effectiveTemperature"));
        assertEquals(null, creative.getPlanOverride("creative.emergence.provider.effectiveTopP"));
        assertEquals(null, TraceStore.get("creative.emergence.effectiveOptionsHash"));
        assertEquals("sampling-option-unproven",
                TraceStore.get("creative.emergence.suppressedReason"));

        ChatModel recovered = factory.lcWithTimeout(
                "qwen3:8b", 1.31d, 0.98d, null, null, 32, 2, 0);
        assertTrue(recovered != null);
        assertEquals(null, TraceStore.get("creative.emergence.effectiveOptionsHash"),
                "a later factory call must not consume stale final-sampling authority");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"message\":{\"content\":\"\"}}", "{\"message\":{\"content\":\"   \"}}", "{\"message\":{}}", "",
            "{\"message\":{\"content\":\"\"},\"done_reason\":\"length\"}"})
    void blankAssistantResponsesAreClassifiedWithoutRetry(String responseBody) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length == 0 ? -1 : response.length);
            if (response.length > 0) exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl, "synthetic-native", Duration.ofSeconds(2), 32, 0.1d);
            LlmGatewayException failure = assertThrows(LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("Synthetic blank-response fixture."))));
            boolean outputLimit = responseBody.contains("length");
            assertEquals(outputLimit ? "output_limit_reached" : "blank_response", failure.reasonCode());
            assertEquals(outputLimit ? com.example.lms.llm.gateway.LlmFailureClass.BAD_REQUEST
                    : com.example.lms.llm.gateway.LlmFailureClass.PROVIDER_ERROR, failure.failureClass());
            assertEquals(1, calls.get(), "blank content must not trigger a native CPU retry");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void qwenThinkingModelSendsThinkFalseAndReturnsContent() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = startServer(requestBody);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl,
                    "qwen3:8b",
                    Duration.ofSeconds(2),
                    32,
                    0.1d);

            ChatResponse response = model.chat(List.of(UserMessage.from("private prompt must stay out of trace")));

            assertEquals("OK", response.aiMessage().text());
            assertTrue(requestBody.get().contains("\"think\":false"), requestBody.get());
            assertTrue(requestBody.get().contains("\"num_predict\":32"), requestBody.get());
            assertTrue(requestBody.get().contains("\"temperature\":0.1"), requestBody.get());
            assertEquals(Boolean.TRUE, TraceStore.get("llm.ollamaNative.thinkDisabled"));
            assertEquals(32, TraceStore.get("llm.ollamaNative.maxTokens"));

            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("private prompt"), trace);
            assertFalse(trace.contains("qwen3:8b"), trace);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nativeAdapterCanForceCpuFallbackWithoutRawModelTrace() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = startServer(requestBody);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl,
                    "qwen3:8b",
                    Duration.ofSeconds(2),
                    32,
                    0.1d,
                    0);

            ChatResponse response = model.chat(List.of(UserMessage.from("private prompt must stay out of trace")));

            assertEquals("OK", response.aiMessage().text());
            assertTrue(requestBody.get().contains("\"num_gpu\":0"), requestBody.get());
            assertEquals(0, TraceStore.get("llm.ollamaNative.numGpu"));
            assertEquals("cpu_fallback", TraceStore.get("llm.ollamaNative.gpuMode"));

            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("private prompt"), trace);
            assertFalse(trace.contains("qwen3:8b"), trace);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gpuDeviceLossOpensOnlyFailedEndpointEvenWhenCpuRetrySucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call = calls.incrementAndGet();
            byte[] response = (call == 1
                    ? "{\"error\":\"invalid main_gpu selection (available devices: 0)\"}"
                    : "{\"message\":{\"content\":\"CPU_OK\"},\"done_reason\":\"stop\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(call == 1 ? 500 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy =
                    new ModelRuntimeHealthTracker.EndpointQuarantinePolicy(
                            true, true, 600_000L, 60_000L, 2, 2, 16);
            OllamaNativeChatModel model = endpointAwareNativeModel(baseUrl, tracker, policy);

            ChatResponse response = model.chat(List.of(UserMessage.from("private endpoint-loss prompt")));

            assertEquals("CPU_OK", response.aiMessage().text());
            assertEquals(2, calls.get());
            ModelRuntimeHealthTracker.EndpointSnapshot open =
                    tracker.endpointSnapshot("local", baseUrl, System.currentTimeMillis()).orElseThrow();
            assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN, open.state());
            assertEquals("gpu_device_lost", open.lastReason());
            assertEquals(0, open.consecutiveGpuPrimarySuccesses());

            LlmGatewayException blocked = assertThrows(
                    LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("second private prompt"))));
            assertEquals("gpu_device_lost", blocked.reasonCode());
            assertEquals(2, calls.get(), "an OPEN endpoint must receive no later HTTP attempt");

            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("private endpoint-loss prompt"), trace);
            assertFalse(trace.contains("second private prompt"), trace);
            assertFalse(trace.contains("available devices"), trace);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runnerTerminationWithFreshMissingHardwareSignalOpensImmediately() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call = calls.incrementAndGet();
            byte[] response = (call == 1
                    ? "{\"error\":\"llama runner process has terminated: exit status 2\"}"
                    : "{\"message\":{\"content\":\"CPU_OK\"},\"done_reason\":\"stop\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(call == 1 ? 500 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy =
                    new ModelRuntimeHealthTracker.EndpointQuarantinePolicy(
                            true, true, 600_000L, 60_000L, 2, 2, 16);
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl, "qwen3:8b", Duration.ofSeconds(2), 32, 0.1d, null,
                    null, tracker, true, policy, () -> true);

            ChatResponse response = model.chat(List.of(UserMessage.from("runner signal probe")));

            assertEquals("CPU_OK", response.aiMessage().text());
            assertEquals(2, calls.get());
            ModelRuntimeHealthTracker.EndpointSnapshot snapshot =
                    tracker.endpointSnapshot("local", baseUrl, System.currentTimeMillis()).orElseThrow();
            assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN, snapshot.state());
            assertEquals("gpu_runner_terminated", snapshot.lastReason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gpuDeviceMismatchRetriesOnceWithCpuFallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> firstBody = new AtomicReference<>("");
        AtomicReference<String> secondBody = new AtomicReference<>("");
        String firstResponseBody = "{\"error\":\"llama_prepare_model_devices: invalid value for main_gpu: 0 (available devices: 0)\"}";
        String secondResponseBody = "{\"message\":{\"content\":\"OK\"},\"done_reason\":\"stop\"}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstBody.set(new String(bytes, StandardCharsets.UTF_8));
                byte[] response = firstResponseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, response.length);
                exchange.getResponseBody().write(response);
            } else {
                secondBody.set(new String(bytes, StandardCharsets.UTF_8));
                byte[] response = secondResponseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String timelineId = tracker.beginRequestTimeline("retry-request-private", "retry-session-private");
            tracker.recordRequestPhase(timelineId, "dispatch", "qwen3:8b", null, "none");
            tracker.recordRequestPhase(timelineId, "pending", "qwen3:8b", baseUrl, "none");
            TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl,
                    "qwen3:8b",
                    Duration.ofSeconds(2),
                    32,
                    0.1d,
                    null,
                    tracker);

            String appPrompt = "private prompt must stay out of trace";
            ChatResponse response = tracker.decorateRequestAttempt(
                    model,
                    "primary",
                    tracker.redactedRequestAttemptRoute(
                            "dynamic_factory", "qwen3:8b", baseUrl, "ollama_native"),
                    Map.of("appOption", "stable"))
                    .chat(List.of(UserMessage.from(appPrompt)));

            assertEquals("OK", response.aiMessage().text());
            assertEquals(2, calls.get());
            assertFalse(firstBody.get().contains("\"num_gpu\":0"), firstBody.get());
            assertTrue(secondBody.get().contains("\"num_gpu\":0"), secondBody.get());
            assertEquals(Boolean.TRUE, TraceStore.get("llm.ollamaNative.cpuRetry.used"));
            assertEquals("main_gpu_no_devices", TraceStore.get("llm.ollamaNative.cpuRetry.reason"));
            assertEquals("cpu_fallback_retry", TraceStore.get("llm.ollamaNative.gpuMode"));

            List<Map<String, Object>> attempts = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(2, attempts.size());
            assertEquals(List.of("primary", "fallback"),
                    attempts.stream().map(row -> row.get("role")).toList());
            assertEquals(List.of("failed", "success"),
                    attempts.stream().map(row -> row.get("outcome")).toList());
            assertEquals(List.of("gpu_device_lost", "none"),
                    attempts.stream().map(row -> row.get("failureClass")).toList(),
                    "the hard device-loss signature must retain its typed class in the attempt ledger");
            assertEquals(List.of(1, 1),
                    attempts.stream().map(row -> row.get("logicalCallOrdinal")).toList(),
                    "one decorated application call owns both physical Ollama attempts");
            assertEquals(List.of(1, 2),
                    attempts.stream().map(row -> row.get("attemptOrdinal")).toList());
            String expectedPromptHash = SafeRedactor.hashValue(canonicalMessages(appPrompt));
            String expectedOptionsHash = SafeRedactor.hashValue(canonicalOptions(Map.of("appOption", "stable")));
            assertTrue(attempts.stream().allMatch(row -> expectedPromptHash.equals(row.get("promptHash"))));
            assertTrue(attempts.stream().allMatch(row -> expectedOptionsHash.equals(row.get("optionsHash"))));
            assertFalse(attempts.get(0).get("httpRequestBodyHash")
                            .equals(attempts.get(1).get("httpRequestBodyHash")),
                    "the CPU retry changes the exact raw HTTP body, not the application options proof");
            assertEquals(exactSha256(firstBody.get()), attempts.get(0).get("httpRequestBodyHash"));
            assertEquals(exactSha256(secondBody.get()), attempts.get(1).get("httpRequestBodyHash"));
            assertEquals(exactSha256(firstResponseBody), attempts.get(0).get("httpResponseBodyHash"));
            assertEquals(exactSha256(secondResponseBody), attempts.get(1).get("httpResponseBodyHash"));
            assertEquals("hash:unknown", attempts.get(0).get("responseHash"));
            assertEquals(Boolean.FALSE, attempts.get(0).get("responseObserved"));
            assertEquals(SafeRedactor.hashValue("OK"), attempts.get(1).get("responseHash"));
            assertEquals(Boolean.TRUE, attempts.get(1).get("responseObserved"));
            assertTrue(attempts.stream().allMatch(row -> Boolean.TRUE.equals(row.get("modelAdapterAttemptObserved"))));
            assertTrue(attempts.stream().allMatch(row -> Boolean.TRUE.equals(row.get("clientHttpExchangeObserved"))));
            assertTrue(attempts.stream().allMatch(row -> Boolean.TRUE.equals(row.get("clientHttpResponseObserved"))));
            assertTrue(attempts.stream().allMatch(row -> Boolean.FALSE.equals(row.get("providerAttemptObserved"))));
            assertTrue(attempts.stream().allMatch(row -> Boolean.FALSE.equals(row.get("wireAttemptObserved"))));
            assertTrue(attempts.stream().allMatch(row -> "client_http_response".equals(row.get("evidenceBoundary"))));
            assertTrue(attempts.stream().allMatch(row -> Boolean.FALSE.equals(row.get("providerReceiptObserved"))));
            assertTrue(attempts.stream().allMatch(row -> "not_observed".equals(row.get("providerReceiptSource"))));

            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("private prompt"), trace);
            assertFalse(trace.contains("qwen3:8b"), trace);
            assertFalse(trace.contains("llama_prepare_model_devices"), trace);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "invalid main_gpu selection (available devices: 0)",
            "llama-server process no longer running: exit status 0xc0000005"
    })
    void zeroRetryFactoryOverrideSuppressesNativeCpuFallbackRetry(String errorMessage) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] response = new ObjectMapper().writeValueAsBytes(Map.of("error", errorMessage));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            DynamicChatModelFactory factory = configuredNativeFactory(baseUrl);
            ChatModel model = factory.lcWithTimeout(
                    "qwen3:8b", null, null, null, null, 32, 2, 0);

            WebClientResponseException failure = assertThrows(WebClientResponseException.class,
                    () -> model.chat(List.of(UserMessage.from("strict one-attempt probe"))));
            assertEquals(1, calls.get(),
                    "a zero retry override must also suppress the native GPU-to-CPU retry");
            assertTrue(failure.getStatusCode().value() == 500,
                    "the first native GPU failure must be propagated to the strict caller");
            assertEquals(null, TraceStore.get("llm.ollamaNative.cpuRetry.used"));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "llama runner process has terminated: exit status 2",
            "llama-server process has terminated: exit status 0xc0000005",
            "llama-server process no longer running: exit status 0xc0000005"
    })
    void runnerTerminatedRetriesOnceWithCpuFallbackWithoutRawBodyTrace(String errorMessage) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> secondBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            int call = calls.incrementAndGet();
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            if (call == 1) {
                byte[] response = new ObjectMapper().writeValueAsBytes(Map.of("error", errorMessage));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, response.length);
                exchange.getResponseBody().write(response);
            } else {
                secondBody.set(new String(bytes, StandardCharsets.UTF_8));
                byte[] response = "{\"message\":{\"content\":\"OK\"},\"done_reason\":\"stop\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl,
                    "qwen3:8b",
                    Duration.ofSeconds(2),
                    32,
                    0.1d);

            ChatResponse response = model.chat(List.of(UserMessage.from("private prompt must stay out of trace")));

            assertEquals("OK", response.aiMessage().text());
            assertEquals(2, calls.get());
            assertTrue(secondBody.get().contains("\"num_gpu\":0"), secondBody.get());
            assertEquals(Boolean.TRUE, TraceStore.get("llm.ollamaNative.cpuRetry.used"));
            assertEquals("runner_terminated_cpu_probe", TraceStore.get("llm.ollamaNative.cpuRetry.reason"));

            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("private prompt"), trace);
            assertFalse(trace.contains("qwen3:8b"), trace);
            assertFalse(trace.contains(errorMessage), trace);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "500,llama-server process no longer running,2",
            "500,llama-server process has not terminated,1",
            "500,llama-server request terminated,1",
            "500,llama-server memory not available,1",
            "400,llama-server process no longer running,1"
    })
    void runnerFailureResponsesRespectCpuRetryBoundary(int status, String errorMessage, int expectedCalls)
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> lastBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            calls.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = new ObjectMapper().writeValueAsBytes(Map.of("error", errorMessage));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "qwen3:8b", Duration.ofSeconds(2), 32, 0.1d);

            WebClientResponseException failure = assertThrows(WebClientResponseException.class,
                    () -> model.chat(List.of(UserMessage.from("synthetic bounded retry probe"))));

            assertEquals(status, failure.getStatusCode().value());
            assertEquals(expectedCalls, calls.get(), "a CPU probe failure must not trigger a third attempt");
            assertEquals(expectedCalls == 2, lastBody.get().contains("\"num_gpu\":0"));
            assertEquals(expectedCalls == 2 ? Boolean.TRUE : null,
                    TraceStore.get("llm.ollamaNative.cpuRetry.used"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runnerTerminationCauseRemainsVisibleWhenCpuFallbackTimesOut() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call = calls.incrementAndGet();
            if (call == 1) {
                byte[] response = "{\"error\":\"llama-server process has terminated: exit status 0xc0000005\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, response.length);
                exchange.getResponseBody().write(response);
            } else {
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                byte[] response = "{\"message\":{\"content\":\"late\"},\"done_reason\":\"stop\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl,
                    "qwen3:8b",
                    Duration.ofSeconds(2),
                    32,
                    0.1d);

            assertThrows(RuntimeException.class,
                    () -> model.chat(List.of(UserMessage.from("private prompt must stay out of trace"))));

            assertEquals(Boolean.TRUE, TraceStore.get("llm.ollamaNative.cpuRetry.used"));
            assertEquals(500, TraceStore.get("llm.localSmoke.operatorAction.upstreamStatus"));
            assertEquals("runner_terminated_cpu_probe",
                    TraceStore.get("llm.localSmoke.operatorAction.upstreamFailureClass"));
            assertEquals("inspect_ollama_runtime_capacity",
                    TraceStore.get("llm.localSmoke.operatorAction.upstreamNextAction"));

            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("private prompt"), trace);
            assertFalse(trace.contains("qwen3:8b"), trace);
            assertFalse(trace.contains("0xc0000005"), trace);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void callerInterruptIsRethrownWithoutDegradingModelRuntimeHealth() throws Exception {
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = startBlockingServer(requestEntered, releaseResponse);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        OllamaNativeChatModel model = new OllamaNativeChatModel(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "qwen3:8b",
                Duration.ofSeconds(30),
                32,
                0.1d,
                null,
                tracker);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                model.chat(List.of(UserMessage.from("private prompt must not enter diagnostics")));
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "ollama-native-interrupt-contract");
        caller.setDaemon(true);

        try {
            caller.start();
            assertTrue(requestEntered.await(2, TimeUnit.SECONDS), "loopback request must start");
            caller.interrupt();
            caller.join(2_000L);

            assertFalse(caller.isAlive(), "interrupted blocking caller must terminate");
            assertTrue(hasCancellationCause(failure.get()),
                    "cancellation cause must be preserved, failure=" + failure.get());
            assertTrue(tracker.redactedSnapshot("local", "qwen3:8b").isEmpty(),
                    "caller cancellation must not degrade model runtime health");
        } finally {
            releaseResponse.countDown();
            caller.interrupt();
            caller.join(2_000L);
            server.stop(0);
        }
    }

    @Test
    void malformedReceiptEndpointFailsClosedWithOnlyRedactedBreadcrumb() {
        String rawEndpointMarker = "receipt-endpoint-private-marker";
        OllamaNativeChatModel model = new OllamaNativeChatModel(
                "http://[" + rawEndpointMarker + "/v1",
                "qwen3:8b",
                Duration.ofSeconds(2),
                32,
                0.1d);

        Boolean eligible = ReflectionTestUtils.invokeMethod(
                model, "isExactControlledLoopbackEndpoint");

        String stage = "llm.ollamaNative.receiptEndpointEligibility";
        assertEquals(Boolean.FALSE, eligible);
        assertEquals(Boolean.TRUE, TraceStore.get(stage + ".suppressed"));
        assertEquals("IllegalArgumentException", TraceStore.get(stage + ".errorType"));
        assertTrue(String.valueOf(TraceStore.get(stage + ".errorHash")).startsWith("hash:"));
        assertTrue(((Number) TraceStore.get(stage + ".errorLength")).intValue() > 0);
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawEndpointMarker));
        assertFalse(trace.contains("providerAttemptObserved=true"));
        assertFalse(trace.contains("wireAttemptObserved=true"));
    }

    @Test
    void exactClientHttpPromptOptionsAndResponseHashesShareTheControllerOwnedRequestTimeline() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = startServer(requestBody);
        Logger proofLogger = (Logger) LoggerFactory.getLogger(
                ModelRuntimeHealthTracker.class.getName() + ".requestProof");
        ListAppender<ILoggingEvent> proofAppender = new ListAppender<>();
        proofAppender.start();
        proofLogger.addAppender(proofAppender);
        Level previousLevel = proofLogger.getLevel();
        proofLogger.setLevel(Level.INFO);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("request-private-321", "session-private-654");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        tracker.recordRequestPhase(timelineId, "dispatch", "qwen3:8b", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", "qwen3:8b", baseUrl, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        GuardContext creative = new GuardContext();
        creative.putPlanOverride("creative.emergence.active", true);
        creative.putPlanOverride("creative.emergence.profile", "WILD");
        creative.putPlanOverride("creative.emergence.search.temperature", 0.94d);
        creative.putPlanOverride("creative.emergence.search.rate", 0.80d);
        creative.putPlanOverride("creative.emergence.candidate.temperature", 1.36d);
        creative.putPlanOverride("creative.emergence.candidate.topP", 0.98d);
        creative.putPlanOverride("creative.emergence.final.temperature", 1.31d);
        creative.putPlanOverride("creative.emergence.final.topP", 0.98d);
        creative.putPlanOverride("creative.emergence.selfAsk.temperature", 0.93d);
        creative.putPlanOverride("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
        creative.putPlanOverride("creative.emergence.final.providerSamplingPending", true);
        creative.putPlanOverride("promptPose.application.intentSlot", "explore");
        GuardContextHolder.set(creative);
        ChatModel model = configuredNativeFactory(baseUrl, tracker).lcWithTimeout(
                "qwen3:8b", 0.1d, 1.25d, null, null, 32, 2, 0);
        assertEquals(SafeRedactor.hashValue("WILD|0.10|1.00"),
                TraceStore.get("creative.emergence.effectiveOptionsHash"));
        assertEquals(0.1d, creative.planDouble(
                "creative.emergence.provider.effectiveTemperature", Double.NaN));
        assertEquals(1.0d, creative.planDouble(
                "creative.emergence.provider.effectiveTopP", Double.NaN));
        assertEquals(null, creative.getPlanOverride("creative.emergence.final.providerSamplingPending"));
        java.util.LinkedHashMap<String, Object> ownedOptions = new java.util.LinkedHashMap<>();
        ownedOptions.put("temperature", 0.1d);
        ownedOptions.put("topP", 1.0d);
        ownedOptions.put("frequencyPenalty", null);
        ownedOptions.put("presencePenalty", null);
        ownedOptions.put("maxOutputTokens", 32);
        ownedOptions.put("timeoutMs", 2_000L);
        ownedOptions.put("maxRetries", 0);
        Map<String, Object> appOptions = ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                "ollama", "qwen3:8b", "ollama_native", ownedOptions);

        try {
            ChatResponse response = model.chat(List.of(UserMessage.from("wire-private-prompt-987")));
            assertEquals("OK", response.aiMessage().text());
            JsonNode received = new ObjectMapper().readTree(requestBody.get());
            assertEquals(0.1d, received.path("options").path("temperature").doubleValue());
            assertEquals(1.0d, received.path("options").path("top_p").doubleValue());

            String promptCanonicalJson = canonicalMessages("wire-private-prompt-987");
            String optionsCanonicalJson = canonicalOptions(appOptions);
            String expectedPromptHash = SafeRedactor.hashValue(promptCanonicalJson);
            String expectedOptionsHash = SafeRedactor.hashValue(optionsCanonicalJson);
            String responseBody = "{\"message\":{\"content\":\"OK\"},\"done_reason\":\"stop\"}";

            List<Map<String, Object>> clientAttempts = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, clientAttempts.size(), "one physical /api/chat exchange must produce one proof row");
            Map<String, Object> clientAttempt = clientAttempts.get(0);
            assertEquals(1, clientAttempt.get("logicalCallOrdinal"));
            assertEquals(1, clientAttempt.get("attemptOrdinal"));
            assertEquals("client_http_response", clientAttempt.get("evidenceBoundary"));
            assertEquals(Boolean.FALSE, clientAttempt.get("providerReceiptObserved"));
            assertEquals("not_observed", clientAttempt.get("providerReceiptSource"));
            assertEquals(Boolean.FALSE, clientAttempt.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, clientAttempt.get("wireAttemptObserved"));

            assertTrue(tracker.recordControlledProviderReceipt(
                    timelineId,
                    1,
                    1,
                    exactSha256(requestBody.get()),
                    requestBody.get().getBytes(StandardCharsets.UTF_8).length,
                    exactSha256(responseBody),
                    responseBody.getBytes(StandardCharsets.UTF_8).length),
                    "only the controlled HttpServer receiver can promote client HTTP evidence");
            tracker.recordRequestPhase(
                    timelineId, "final_boundary", SafeRedactor.hashValue("OK"), null, "none");

            List<Map<String, Object>> timeline = tracker.redactedRequestTimeline(timelineId);
            List<Map<String, Object>> attempts = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, attempts.size(), "receipt enrichment must not add a second attempt row");
            Map<String, Object> attempt = attempts.get(0);
            assertEquals(timelineId, attempt.get("timelineId"));
            assertEquals(timeline.get(0).get("requestHash"), attempt.get("requestHash"));
            assertEquals(timeline.get(0).get("sessionHash"), attempt.get("sessionHash"));
            assertEquals(1, attempt.get("logicalCallOrdinal"));
            assertEquals(1, attempt.get("attemptOrdinal"));
            assertEquals("ollama_native", attempt.get("protocol"));
            assertEquals(expectedPromptHash, attempt.get("promptHash"));
            assertEquals(expectedOptionsHash, attempt.get("optionsHash"));
            assertEquals(SafeRedactor.hashValue("OK"), attempt.get("responseHash"));
            assertEquals(1, attempt.get("promptItemCount"));
            assertEquals(13, attempt.get("optionItemCount"));
            assertEquals(promptCanonicalJson.getBytes(StandardCharsets.UTF_8).length,
                    attempt.get("promptUtf8ByteCount"));
            assertEquals(optionsCanonicalJson.getBytes(StandardCharsets.UTF_8).length,
                    attempt.get("optionsUtf8ByteCount"));
            assertEquals("OK".length(), attempt.get("responseCharCount"));
            assertEquals("OK".getBytes(StandardCharsets.UTF_8).length,
                    attempt.get("responseUtf8ByteCount"));
            assertEquals(exactSha256(requestBody.get()), attempt.get("httpRequestBodyHash"));
            assertEquals(requestBody.get().getBytes(StandardCharsets.UTF_8).length,
                    attempt.get("httpRequestBodyUtf8ByteCount"));
            assertEquals(exactSha256(responseBody), attempt.get("httpResponseBodyHash"));
            assertEquals(responseBody.getBytes(StandardCharsets.UTF_8).length,
                    attempt.get("httpResponseBodyUtf8ByteCount"));
            assertEquals(true, attempt.get("modelAdapterAttemptObserved"));
            assertEquals(true, attempt.get("clientHttpExchangeObserved"));
            assertEquals(true, attempt.get("clientHttpResponseObserved"));
            assertEquals("provider_receive", attempt.get("evidenceBoundary"));
            assertEquals(true, attempt.get("providerReceiptObserved"));
            assertEquals("controlled_http_server", attempt.get("providerReceiptSource"));
            assertEquals(true, attempt.get("providerAttemptObserved"));
            assertEquals(true, attempt.get("wireAttemptObserved"));
            assertEquals(true, attempt.get("responseObserved"));
            assertEquals("success", attempt.get("outcome"));
            assertEquals(1, attempt.get("attemptTotal"));
            assertEquals(0, attempt.get("attemptDropped"));
            Map<String, Object> finalBoundary = timeline.stream()
                    .filter(row -> "final_boundary".equals(row.get("phase")))
                    .findFirst()
                    .orElseThrow();
            assertEquals(attempt.get("responseHash"), finalBoundary.get("finalHash"),
                    "the selected final hash must match the unique receipt-backed response");

            String countOnlyEvidence = attempts.toString();
            assertFalse(countOnlyEvidence.contains("wire-private-prompt-987"));
            assertFalse(countOnlyEvidence.contains("request-private-321"));
            assertFalse(countOnlyEvidence.contains("session-private-654"));
            assertFalse(countOnlyEvidence.contains(responseBody));
            assertFalse(countOnlyEvidence.contains("qwen3:8b"));

            String requestHash = String.valueOf(attempt.get("requestHash"));
            List<String> matchingProofLines = proofAppender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains("[LLM_REQUEST_PROOF]"))
                    .filter(line -> line.contains("requestHash=" + requestHash))
                    .toList();
            assertEquals(1, matchingProofLines.size(),
                    "one physical Ollama row must emit exactly one centralized proof line");
            assertTrue(matchingProofLines.get(0).contains("evidenceBoundary=client_http_response"));
            assertTrue(matchingProofLines.get(0).contains("providerReceiptObserved=false"));
            assertTrue(matchingProofLines.get(0).contains("providerAttempt=false"));
            assertTrue(matchingProofLines.get(0).contains("wireAttempt=false"));
            List<String> matchingReceiptLines = proofAppender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains("[LLM_PROVIDER_RECEIPT]"))
                    .filter(line -> line.contains("requestHash=" + requestHash))
                    .toList();
            assertEquals(1, matchingReceiptLines.size());
            assertTrue(matchingReceiptLines.get(0).contains("logicalCallOrdinal=1"));
            assertTrue(matchingReceiptLines.get(0).contains("attemptOrdinal=1"));
            assertTrue(matchingReceiptLines.get(0).contains("evidenceBoundary=provider_receive"));
            assertTrue(matchingReceiptLines.get(0).contains("providerReceiptSource=controlled_http_server"));
        } finally {
            proofLogger.detachAppender(proofAppender);
            proofLogger.setLevel(previousLevel);
            server.stop(0);
        }
    }

    @Test
    void transportFailureAfterSubscriptionKeepsOllamaRequestProofWithoutClaimingResponseOrWire() throws Exception {
        HttpServer portReservation = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int unusedPort = portReservation.getAddress().getPort();
        portReservation.stop(0);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("ollama-transport-request", "ollama-transport-session");
        String baseUrl = "http://127.0.0.1:" + unusedPort + "/v1";
        tracker.recordRequestPhase(timelineId, "dispatch", "qwen3:8b", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", "qwen3:8b", baseUrl, "none");
        tracker.recordRequestSelection(
                timelineId,
                "router",
                "local",
                "ollama-native-route",
                "qwen3:8b",
                baseUrl,
                "ollama_native",
                false,
                false);
        ModelRuntimeHealthTracker.RouteHealthKey selectedRoute = tracker
                .requestRouteHealthKey(timelineId)
                .orElseThrow();
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        OllamaNativeChatModel model = new OllamaNativeChatModel(
                baseUrl, "qwen3:8b", Duration.ofSeconds(1), null, null, null, tracker, false);

        assertThrows(RuntimeException.class,
                () -> model.chat(List.of(UserMessage.from("ollama-transport-prompt-private"))));

        Map<String, Object> row = tracker.redactedRequestAttemptLedger(timelineId).get(0);
        assertTrue(String.valueOf(row.get("httpRequestBodyHash")).startsWith("sha256:"));
        assertTrue(((Number) row.get("httpRequestBodyUtf8ByteCount")).intValue() > 0);
        assertEquals("hash:unknown", row.get("httpResponseBodyHash"));
        assertEquals(Boolean.TRUE, row.get("clientHttpExchangeObserved"));
        assertEquals(Boolean.FALSE, row.get("clientHttpResponseObserved"));
        assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
        assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
        assertEquals(Boolean.FALSE, row.get("responseObserved"));
        assertEquals(1, row.get("logicalCallOrdinal"));
        assertEquals(1, row.get("attemptOrdinal"));
        assertEquals("client_http_exchange", row.get("evidenceBoundary"));
        assertEquals(Boolean.FALSE, row.get("providerReceiptObserved"));
        assertEquals("not_observed", row.get("providerReceiptSource"));
        ModelRuntimeHealthTracker.RouteSnapshot routeFailure = tracker
                .snapshot(selectedRoute)
                .orElseThrow();
        assertEquals(1L, routeFailure.failureCount());
        assertFalse(routeFailure.lastSuccess());
        assertEquals(1L, tracker.redactedSnapshots().stream()
                .filter(snapshot -> Boolean.TRUE.equals(snapshot.get("routeScoped")))
                .count(), "native failure must not create an adapter-global alias route");
        assertFalse((new ObjectMapper().writeValueAsString(tracker.redactedRequestAttemptLedger(timelineId))
                + TraceStore.getAll()).contains("ollama-transport-prompt-private"));
    }

    @Test
    void dynamicFactoryRoutesLoopbackQwenThinkingModelsThroughNativeAdapter() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/DynamicChatModelFactory.java"));

        assertTrue(source.contains("new OllamaNativeChatModel("));
        assertTrue(source.contains("shouldUseOllamaNativeThinkFalse"));
        assertTrue(source.contains("llm.ollamaNative.route"));
    }

    @Test
    void multimodalUserMessagePreservesImagesInNativePayload() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = startServer(requestBody);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl, "qwen3-vl:4b", Duration.ofSeconds(2), 32, 0.1d);
            UserMessage multimodal = UserMessage.from(
                    dev.langchain4j.data.message.TextContent.from("describe this"),
                    dev.langchain4j.data.message.ImageContent.from(
                            dev.langchain4j.data.image.Image.builder()
                                    .base64Data("c3ludGhldGljLWJpbmFyeQ==")
                                    .mimeType("image/png")
                                    .build()));

            ChatResponse response = model.chat(List.of(multimodal));

            assertEquals("OK", response.aiMessage().text());
            JsonNode payload = new ObjectMapper().readTree(requestBody.get());
            JsonNode user = payload.path("messages").get(0);
            assertEquals("user", user.path("role").asText());
            assertEquals("describe this", user.path("content").asText());
            assertEquals("c3ludGhldGljLWJpbmFyeQ==", user.path("images").get(0).asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void toolCallOnlyResponseParsesIntoAiMessageInsteadOfBlankFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = ("{\"message\":{\"content\":\"\",\"tool_calls\":["
                    + "{\"function\":{\"name\":\"lookup\",\"arguments\":{\"q\":\"synthetic\"}}}]},"
                    + "\"done_reason\":\"stop\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl, "qwen3:8b", Duration.ofSeconds(2), 32, 0.1d);

            ChatResponse response = model.chat(List.of(UserMessage.from("use the tool")));

            assertTrue(response.aiMessage().hasToolExecutionRequests());
            var request = response.aiMessage().toolExecutionRequests().get(0);
            assertEquals("lookup", request.name());
            assertTrue(request.arguments().contains("\"q\":\"synthetic\""), request.arguments());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestEntryPointCarriesToolsParametersAndAssistantToolCalls() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = startServer(requestBody);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl, "qwen3:8b", Duration.ofSeconds(2), 32, 0.1d);
            var toolSpec = dev.langchain4j.agent.tool.ToolSpecification.builder()
                    .name("lookup")
                    .description("lookup fixture")
                    .parameters(dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder()
                            .addStringProperty("q")
                            .required("q")
                            .build())
                    .build();
            dev.langchain4j.data.message.AiMessage toolCallMessage = dev.langchain4j.data.message.AiMessage.from(
                    dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                            .id("call_0")
                            .name("lookup")
                            .arguments("{\"q\":\"synthetic\"}")
                            .build());
            dev.langchain4j.data.message.ToolExecutionResultMessage toolResult =
                    dev.langchain4j.data.message.ToolExecutionResultMessage.from("call_0", "lookup", "tool answer");

            ChatResponse response = model.chat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                    .messages(UserMessage.from("ask"), toolCallMessage, toolResult)
                    .toolSpecifications(toolSpec)
                    .temperature(0.55)
                    .maxOutputTokens(17)
                    .build());

            assertEquals("OK", response.aiMessage().text());
            JsonNode payload = new ObjectMapper().readTree(requestBody.get());
            assertEquals("lookup", payload.path("tools").get(0).path("function").path("name").asText());
            assertEquals("object", payload.path("tools").get(0).path("function").path("parameters")
                    .path("type").asText());
            assertEquals(0.55, payload.path("options").path("temperature").asDouble(), 1e-9);
            assertEquals(17, payload.path("options").path("num_predict").asInt());
            JsonNode assistant = payload.path("messages").get(1);
            assertEquals("assistant", assistant.path("role").asText());
            assertEquals("lookup", assistant.path("tool_calls").get(0).path("function").path("name").asText());
            assertEquals("synthetic", assistant.path("tool_calls").get(0).path("function")
                    .path("arguments").path("q").asText());
            JsonNode tool = payload.path("messages").get(2);
            assertEquals("tool", tool.path("role").asText());
            assertEquals("tool answer", tool.path("content").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void urlOnlyImageFailsClosedWithoutHttpRequest() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = startServer(requestBody);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl, "qwen3-vl:4b", Duration.ofSeconds(2), 32, 0.1d);
            UserMessage remoteImage = UserMessage.from(
                    dev.langchain4j.data.message.TextContent.from("describe this"),
                    dev.langchain4j.data.message.ImageContent.from(
                            dev.langchain4j.data.image.Image.builder()
                                    .url("http://127.0.0.1/unused.png")
                                    .build()));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> model.chat(List.of(remoteImage)));
            assertEquals("unsupported_image_transport:url", failure.getMessage());
            assertEquals("", requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unsupportedContentTypeFailsClosedWithoutHttpRequest() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = startServer(requestBody);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl, "qwen3:8b", Duration.ofSeconds(2), 32, 0.1d);
            UserMessage audioOnly = UserMessage.from(new dev.langchain4j.data.message.Content() {
                @Override
                public dev.langchain4j.data.message.ContentType type() {
                    return dev.langchain4j.data.message.ContentType.AUDIO;
                }
            });

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> model.chat(List.of(audioOnly)));
            assertEquals("unsupported_content_type:AUDIO", failure.getMessage());
            assertEquals("", requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unknownMessageTypeFailsClosedWithoutHttpRequest() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = startServer(requestBody);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OllamaNativeChatModel model = new OllamaNativeChatModel(
                    baseUrl, "qwen3:8b", Duration.ofSeconds(2), 32, 0.1d);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> model.chat(List.of(
                            UserMessage.from("hi"),
                            new dev.langchain4j.data.message.CustomMessage(Map.of("k", "v")))));
            assertEquals("unsupported_message_type:CUSTOM", failure.getMessage());
            assertEquals("", requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void multimodalPromptFallbackNeverThrowsInCompletionsConversion() {
        UserMessage multimodal = UserMessage.from(
                dev.langchain4j.data.message.TextContent.from("first"),
                dev.langchain4j.data.message.ImageContent.from(
                        dev.langchain4j.data.image.Image.builder()
                                .base64Data("c3ludGhldGljLWJpbmFyeQ==")
                                .mimeType("image/png")
                                .build()),
                dev.langchain4j.data.message.TextContent.from("second"));
        String prompt = OpenAiEndpointCompatibility.toCompletionsPrompt(List.of(multimodal));
        assertTrue(prompt.contains("first"), prompt);
        assertTrue(prompt.contains("second"), prompt);
    }

    private static HttpServer startServer(AtomicReference<String> requestBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            requestBody.set(new String(bytes, StandardCharsets.UTF_8));
            byte[] response = "{\"message\":{\"content\":\"OK\"},\"done_reason\":\"stop\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static DynamicChatModelFactory configuredNativeFactory(String baseUrl) {
        return configuredNativeFactory(baseUrl, new ModelRuntimeHealthTracker());
    }

    private static DynamicChatModelFactory configuredNativeFactory(
            String baseUrl,
            ModelRuntimeHealthTracker tracker) {
        MockEnvironment environment = new MockEnvironment().withProperty("llm.api-key", "ollama");
        DynamicChatModelFactory factory = new DynamicChatModelFactory(
                environment, new KeyResolver(environment), tracker);
        ReflectionTestUtils.setField(factory, "defaultModelName", "qwen3:8b");
        ReflectionTestUtils.setField(factory, "localBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "fastLocalBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "highLocalBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "judgeLocalBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "coderLocalBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "visionLocalBaseUrl", baseUrl);
        ReflectionTestUtils.setField(factory, "localApiKey", "ollama");
        ReflectionTestUtils.setField(factory, "ownerToken", "");
        ReflectionTestUtils.setField(factory, "allowedHosts", "");
        ReflectionTestUtils.setField(factory, "allowPrivateRemote", false);
        ReflectionTestUtils.setField(factory, "requireAuthForRemote", true);
        ReflectionTestUtils.setField(factory, "dynamicMaxRetries", 2);
        ReflectionTestUtils.setField(factory, "ollamaNativeThinkFalseEnabled", true);
        return factory;
    }

    private static OllamaNativeChatModel endpointAwareNativeModel(
            String baseUrl,
            ModelRuntimeHealthTracker tracker,
            ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy) {
        return new OllamaNativeChatModel(
                baseUrl, "qwen3:8b", Duration.ofSeconds(2), 32, 0.1d, null,
                null, tracker, true, policy);
    }

    private static GuardContext completeWildCreativeContext() {
        GuardContext context = new GuardContext();
        context.putPlanOverride("creative.emergence.active", true);
        context.putPlanOverride("creative.emergence.profile", "WILD");
        context.putPlanOverride("creative.emergence.search.temperature", 0.94d);
        context.putPlanOverride("creative.emergence.search.rate", 0.80d);
        context.putPlanOverride("creative.emergence.candidate.temperature", 1.36d);
        context.putPlanOverride("creative.emergence.candidate.topP", 0.98d);
        context.putPlanOverride("creative.emergence.final.temperature", 1.31d);
        context.putPlanOverride("creative.emergence.final.topP", 0.98d);
        context.putPlanOverride("creative.emergence.selfAsk.temperature", 0.93d);
        context.putPlanOverride("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
        context.putPlanOverride("promptPose.application.intentSlot", "explore");
        return context;
    }

    private static HttpServer startBlockingServer(
            CountDownLatch requestEntered,
            CountDownLatch releaseResponse) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requestEntered.countDown();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] response = "{\"message\":{\"content\":\"late\"},\"done_reason\":\"stop\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static boolean hasCancellationCause(Throwable failure) {
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (cursor instanceof InterruptedException
                    || cursor instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static String exactSha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return "sha256:" + hex;
    }

    private static String canonicalMessages(String content) throws Exception {
        java.util.LinkedHashMap<String, String> row = new java.util.LinkedHashMap<>();
        row.put("role", "user");
        row.put("content", content);
        return new ObjectMapper().writeValueAsString(List.of(row));
    }

    private static String canonicalOptions(Map<String, ?> options) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        return mapper.writeValueAsString(options);
    }
}
