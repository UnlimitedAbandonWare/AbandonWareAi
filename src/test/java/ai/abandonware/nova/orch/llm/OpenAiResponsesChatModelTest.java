package ai.abandonware.nova.orch.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.search.TraceStore;
import com.example.lms.test.SecretFixtures;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiResponsesChatModelTest {

    @Test
    void responseUsagePreservesCacheReadCountAndServedModel() throws Exception {
        var request = new AtomicReference<String>("");
        var server = startResponsesServer("{\"status\":\"completed\",\"model\":\"gpt-5.6-luna\",\"output_text\":\"ok\",\"usage\":{\"input_tokens\":2048,\"output_tokens\":17,\"total_tokens\":2065,\"input_tokens_details\":{\"cached_tokens\":1024}}}", 200, request);
        try {
            var model = new OpenAiResponsesChatModel("http://127.0.0.1:"+server.getAddress().getPort()+"/v1", "loopback-key-value", "gpt-5.6-luna", 700);
            var response = model.chat(List.of(UserMessage.from("public synthetic cache contract")));
            assertEquals("gpt-5.6-luna", response.metadata().modelName());
            assertTrue(response.tokenUsage() instanceof dev.langchain4j.model.openai.OpenAiTokenUsage);
            var usage = (dev.langchain4j.model.openai.OpenAiTokenUsage) response.tokenUsage();
            assertEquals(2065, usage.totalTokenCount());
            assertEquals(1024, usage.inputTokensDetails().cachedTokens());
        } finally { server.stop(0); }
    }

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void routeFailureMessageUsesStableReasonInsteadOfExceptionClassName() {
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                "http://127.0.0.1:1/v1",
                "test-api-key",
                "gpt-5-mini",
                1_000L);

        ChatResponse response = model.chat(List.of(UserMessage.from("hello")));
        String text = response.aiMessage().text();

        assertTrue(text.contains("code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"), text);
        assertTrue(text.contains("error: responses-route-error"), text);
        assertFalse(text.contains("WebClientRequestException"), text);
        assertFalse(text.contains("ConnectException"), text);
        assertFalse(text.contains("127.0.0.1"), text);
    }

    @Test
    void placeholderApiKeyReturnsExpectedFailureBeforeNetworkPath() {
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline("responses-disabled-request", "responses-disabled-session");
        tracker.recordRequestPhase(timelineId, "dispatch", "gpt-5-mini", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                "http://127.0.0.1:1/v1",
                "sk-local",
                "gpt-5-mini",
                1_000L,
                tracker);

        ChatResponse response = model.chat(List.of(UserMessage.from("hello")));
        String text = response.aiMessage().text();

        assertTrue(text.contains("code: EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"), text);
        assertTrue(text.contains("actionTaken: ROUTE_RESPONSES(no_api_key)"), text);
        assertFalse(text.contains("responses-route-error"), text);
        assertFalse(text.contains("ConnectException"), text);
        assertFalse(text.contains("127.0.0.1"), text);
        List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("failed", row.get("outcome"));
        assertEquals("disabled", row.get("failureClass"));
        assertEquals("configuration_error", row.get("terminalClass"));
        assertEquals("hash:unknown", row.get("responseHash"));
        assertEquals(0, row.get("responseCharCount"));
        assertEquals(0, row.get("responseUtf8ByteCount"));
        assertEquals(Boolean.FALSE, row.get("modelAdapterAttemptObserved"));
        assertEquals(Boolean.FALSE, row.get("clientHttpExchangeObserved"));
        assertEquals(Boolean.FALSE, row.get("clientHttpResponseObserved"));
        assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
        assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
        assertEquals(Boolean.FALSE, row.get("responseObserved"));
    }

    @Test
    void outputCapMatchesSentPayloadAndOwnedOptionsHashWithoutInventingAbsentCap() throws Exception {
        for (Integer cap : new Integer[]{64, 32, null, 0, -1}) {
            AtomicReference<String> requestBody = new AtomicReference<>("");
            HttpServer server = startResponsesServer("{\"status\":\"completed\",\"output_text\":\"bounded ok\"}", 200, requestBody);
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String timeline = pendingTimeline(tracker, "bounded-request", "bounded-session");
            try {
                OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        "loopback-key-value", "gpt-usage-test", 1_000L,
                        tracker, "primary", null, null, cap);
                ChatResponse response = model.chat(List.of(UserMessage.from("bounded probe")));
                assertEquals("bounded ok", response.aiMessage().text());
                assertNull(response.tokenUsage(), "missing provider usage must remain unknown");
                Map<?, ?> payload = new ObjectMapper().readValue(requestBody.get(), Map.class);
                Integer expectedCap = cap != null && cap > 0 ? cap : null;
                assertEquals(expectedCap, payload.get("max_output_tokens"));
                assertEquals(expectedCap != null, payload.containsKey("max_output_tokens"));
                Map<String, Object> options = new LinkedHashMap<>();
                options.put("maxOutputTokens", expectedCap);
                options.put("timeoutMs", 5_000L);
                options.put("maxRetries", 0);
                options.put("fallbackEnabled", false);
                String expectedJson = canonicalOptions(ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                        "openai", "gpt-usage-test", "openai_responses", options));
                List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timeline);
                assertEquals(1, rows.size());
                assertEquals(SafeRedactor.hashValue(expectedJson), rows.get(0).get("optionsHash"));
                assertEquals(exactSha256(requestBody.get()), rows.get(0).get("httpRequestBodyHash"));
                assertEquals(Boolean.FALSE, rows.get(0).get("providerAttemptObserved"));
                assertEquals(Boolean.FALSE, rows.get(0).get("wireAttemptObserved"));
            } finally {
                server.stop(0);
                TraceStore.clear();
            }
        }
    }

    @Test
    void responsesRouteDoesNotUseNoopOnErrorResume() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java"));

        assertFalse(source.contains(".onErrorResume(e -> Mono.error(e))"));
        assertFalse(source.contains("import reactor.core.publisher.Mono;"));
    }

    @Test
    void malformedResponsesJsonWritesRedactedParseBreadcrumb() throws Exception {
        String body = "{\"status\":\"completed\",\"output_text\":\"" + SecretFixtures.openAiKey() + "\"";
        HttpServer server = startResponsesServer(body);
        try {
            OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "gpt-5-mini",
                    1_000L);

            var failure = assertThrows(LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("hello"))));
            assertEquals("responses_contract_error", failure.reasonCode());
            assertTrue(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.failed")));
            assertTrue(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.bodyPresent")));
            assertTrue(TraceStore.get("llm.responses.parse.bodyHash").equals(SafeRedactor.hashValue(body)));
            assertTrue(TraceStore.get("llm.responses.parse.bodyLength").equals(body.length()));
            assertTrue(TraceStore.get("llm.responses.parse.reason").equals("invalid_response_json"));
            assertTrue(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.invalid.suppressed")));
            assertFalse(TraceStore.getAll().containsValue(body));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatCompletionsCompatibleChoicesResponseExtractsAssistantText() throws Exception {
        String body = "{\"status\":\"completed\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"compat hello\"}}]}";
        HttpServer server = startResponsesServer(body);
        try {
            OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "gpt-5-mini",
                    1_000L);

            ChatResponse response = model.chat(List.of(UserMessage.from("hello")));

            assertTrue(response.aiMessage().text().contains("compat hello"));
            assertTrue("CHAT_COMPAT".equals(TraceStore.get("responsesModel.format")));
            assertFalse(Boolean.TRUE.equals(TraceStore.get("llm.responses.parse.emptyOutput")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitReasoningEffortAddsNestedResponsesOption() throws Exception {
        AtomicReference<String> receivedRequest = new AtomicReference<>("");
        HttpServer server = startResponsesServer(
                "{\"status\":\"completed\",\"output_text\":\"reasoned response\"}", 200, receivedRequest);
        try {
            OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "zai/glm-5.2",
                    5_000L,
                    null,
                    "primary",
                    null,
                    "high");

            ChatResponse response = model.chat(List.of(UserMessage.from("reason carefully")));

            assertEquals("reasoned response", response.aiMessage().text());
            var payload = new ObjectMapper().readTree(receivedRequest.get());
            assertEquals("high", payload.path("reasoning").path("effort").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void legacyConstructorsOmitReasoningOption() throws Exception {
        AtomicReference<String> receivedRequest = new AtomicReference<>("");
        HttpServer server = startResponsesServer(
                "{\"status\":\"completed\",\"output_text\":\"legacy response\"}", 200, receivedRequest);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        try {
            new OpenAiResponsesChatModel(baseUrl, "test-api-key", "gpt-5-mini", 5_000L)
                    .chat(List.of(UserMessage.from("legacy four argument constructor")));
            assertFalse(new ObjectMapper().readTree(receivedRequest.get()).has("reasoning"));

            receivedRequest.set("");
            new OpenAiResponsesChatModel(
                    baseUrl,
                    "test-api-key",
                    "gpt-5-mini",
                    5_000L,
                    new ModelRuntimeHealthTracker())
                    .chat(List.of(UserMessage.from("legacy tracker constructor")));
            assertFalse(new ObjectMapper().readTree(receivedRequest.get()).has("reasoning"));

            receivedRequest.set("");
            new OpenAiResponsesChatModel(
                    baseUrl,
                    "test-api-key",
                    "gpt-5-mini",
                    5_000L,
                    new ModelRuntimeHealthTracker(),
                    "fallback",
                    null)
                    .chat(List.of(UserMessage.from("legacy router constructor")));
            assertFalse(new ObjectMapper().readTree(receivedRequest.get()).has("reasoning"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void callerInterruptIsRethrownInsteadOfBecomingDiagnosticSuccess() throws Exception {
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = startBlockingResponsesServer(requestEntered, releaseResponse);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(
                tracker, "responses-cancel-request-private", "responses-cancel-session-private");
        String rawPrompt = "private prompt must not enter diagnostics";
        String keyLikeSentinel = SecretFixtures.openAiKey();
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                keyLikeSentinel,
                "gpt-5-mini",
                30_000L,
                tracker,
                "primary",
                tracker.redactedRequestAttemptRoute(
                        "cancel-route-private", "cancel-model-private",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses",
                        "openai_responses"));
        AtomicReference<ChatResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            TraceStore.clear();
            TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
            try {
                response.set(model.chat(List.of(UserMessage.from(rawPrompt))));
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                TraceStore.clear();
            }
        }, "openai-responses-interrupt-contract");
        caller.setDaemon(true);

        try {
            caller.start();
            assertTrue(requestEntered.await(2, TimeUnit.SECONDS), "loopback request must start");
            caller.interrupt();
            caller.join(2_000L);

            assertFalse(caller.isAlive(), "interrupted blocking caller must terminate");
            assertNull(response.get(), "cancellation must not become a diagnostic ChatResponse");
            assertTrue(hasCancellationCause(failure.get()),
                    "cancellation cause must be preserved, failure=" + failure.get());
            Map<String, Object> row = tracker.redactedRequestAttemptLedger(timelineId).get(0);
            assertEquals("cancelled", row.get("outcome"));
            assertEquals("cancelled_neutral", row.get("failureClass"));
            assertEquals(Boolean.FALSE, row.get("clientHttpResponseObserved"));
            assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("responseObserved"));
            String retained = new ObjectMapper().writeValueAsString(
                    tracker.redactedRequestAttemptLedger(timelineId));
            assertFalse(retained.contains(rawPrompt));
            assertFalse(retained.contains(keyLikeSentinel));
        } finally {
            releaseResponse.countDown();
            caller.interrupt();
            caller.join(2_000L);
            server.stop(0);
        }
    }

    @Test
    void typedQuotaReasonSurvivesResponsesConversionWithoutRetainingTheBody() throws Exception {
        String sentinel = SecretFixtures.openAiKey();
        String errorBody = "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"free_limit_reached: "
                + sentinel + " synthetic-private-detail\"}}";
        HttpServer server = startResponsesServer(errorBody, 429, new AtomicReference<>(""));
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "quota-request", "quota-session");
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                    baseUrl, "loopback-key-value", "gpt-quota-loopback", 5_000L, tracker, "primary",
                    tracker.redactedRequestAttemptRoute("quota-route", "gpt-quota-loopback",
                            baseUrl + "/responses", "openai_responses"));
            LlmGatewayException failure = assertThrows(LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from("synthetic quota probe"))));
            assertEquals("insufficient_quota", failure.reasonCode());
            assertEquals(LlmFailureClass.RATE_LIMIT_COOLDOWN, failure.failureClass());
            assertFalse(com.example.lms.llm.LlmErrorClassifier.classify(failure).retryable());
            assertNull(failure.getCause());
            List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, rows.size());
            String retained = failure + new ObjectMapper().writeValueAsString(rows) + TraceStore.getAll();
            assertFalse(retained.contains(sentinel));
            assertFalse(retained.contains(errorBody));
            assertFalse(retained.contains("synthetic-private-detail"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void trackerAndTypedRouteUseTheSameCategoricalHttpFailureClassWithoutRawEvidence() throws Exception {
        int[] statuses = {401, 403, 429, 503};
        LlmFailureClass[] expectedClasses = {
                LlmFailureClass.AUTH_MISSING,
                LlmFailureClass.AUTH_MISSING,
                LlmFailureClass.RATE_LIMIT_COOLDOWN,
                LlmFailureClass.HEALTH_DOWN
        };
        String[] expectedLedgerClasses = {
                "auth_missing", "auth_missing", "rate_limit_cooldown", "health_down"
        };
        String[] expectedReasonCodes = {
                "responses_http_401", "responses_http_403",
                "responses_http_429", "responses_http_503"
        };

        for (int index = 0; index < statuses.length; index++) {
            int status = statuses[index];
            String rawPrompt = "responses-http-private-prompt-" + status;
            String keyLikeSentinel = SecretFixtures.openAiKey();
            String errorBody = "{\"error\":\"" + keyLikeSentinel + "-private-body-" + status + "\"}";
            HttpServer server = startResponsesServer(errorBody, status, new AtomicReference<>(""));
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            String timelineId = pendingTimeline(
                    tracker, "responses-http-request-" + status, "responses-http-session-" + status);
            try {
                OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        keyLikeSentinel,
                        "responses-http-model-private-" + status,
                        5_000L,
                        tracker,
                        "primary",
                        tracker.redactedRequestAttemptRoute(
                                "responses-http-route-private-" + status,
                                "responses-http-model-private-" + status,
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses",
                                "openai_responses"));

                LlmGatewayException failure = assertThrows(
                        LlmGatewayException.class,
                        () -> model.chat(List.of(UserMessage.from(rawPrompt))),
                        "status=" + status);

                assertEquals(expectedClasses[index], failure.failureClass(), "status=" + status);
                assertEquals(expectedReasonCodes[index], failure.reasonCode(), "status=" + status);
                List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
                assertEquals(1, rows.size(), "status=" + status);
                Map<String, Object> row = rows.get(0);
                assertEquals("failed", row.get("outcome"), "status=" + status);
                assertEquals(expectedLedgerClasses[index], row.get("failureClass"), "status=" + status);
                assertEquals(Boolean.TRUE, row.get("clientHttpExchangeObserved"), "status=" + status);
                assertEquals(Boolean.TRUE, row.get("clientHttpResponseObserved"), "status=" + status);
                assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"), "status=" + status);
                assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"), "status=" + status);
                assertEquals(Boolean.FALSE, row.get("responseObserved"), "status=" + status);
                String retained = new ObjectMapper().writeValueAsString(rows) + TraceStore.getAll();
                assertFalse(retained.contains(rawPrompt), "status=" + status);
                assertFalse(retained.contains(errorBody), "status=" + status);
                assertFalse(retained.contains(keyLikeSentinel), "status=" + status);
            } finally {
                server.stop(0);
                TraceStore.clear();
            }
        }
    }

    @Test
    void trustedGatewayRoutingReceiptParserFailsClosedOutsideTheExactVercelResponsesBoundary() {
        String coherent = """
                {
                  "status":"completed",
                  "output_text":"ok",
                  "providerMetadata":{"gateway":{"routing":{
                    "finalProvider":"zai",
                    "attempts":[
                      {"provider":"bedrock","success":false,"statusCode":503},
                      {"provider":"zai","success":true,"statusCode":200}
                    ],
                    "totalProviderAttemptCount":2
                  }}}
                }
                """;

        OpenAiResponsesChatModel.GatewayProviderReceipt receipt =
                OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                        "https://ai-gateway.vercel.sh/v1/responses",
                        "zai/glm-5.2",
                        coherent);

        assertNotNull(receipt);
        assertEquals("zai", receipt.provider());
        assertEquals(200, receipt.statusCode());
        assertEquals(2, receipt.totalProviderAttemptCount());
        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "http://127.0.0.1:18080/v1/responses", "zai/glm-5.2", coherent));
        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "https://ai-gateway.vercel.sh/v1/responses?unsafe=true", "zai/glm-5.2", coherent));
        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "https://ai-gateway.vercel.sh/v1/responses", "openai/gpt-5", coherent));
        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "https://ai-gateway.vercel.sh/v1/responses", "zai/glm-5.2",
                coherent.replace("\"totalProviderAttemptCount\":2", "\"totalProviderAttemptCount\":1")));
        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "https://ai-gateway.vercel.sh/v1/responses", "zai/glm-5.2",
                coherent.replace("\"success\":false", "\"success\":true")));
        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "https://ai-gateway.vercel.sh/v1/responses", "zai/glm-5.2",
                coherent.replace("\"providerMetadata\"", "\"provider_metadata\"")));
    }

    @Test
    void malformedTrustedGatewayRoutingReceiptRecordsRedactedSuppressionWithoutBodyLeak() throws Exception {
        String sensitiveBodyMarker = "gateway-private-body-sentinel";
        String malformed = "{\"providerMetadata\":\"" + sensitiveBodyMarker;

        assertNull(OpenAiResponsesChatModel.trustedGatewayProviderReceipt(
                "https://ai-gateway.vercel.sh/v1/responses", "zai/glm-5.2", malformed));

        assertEquals(Boolean.TRUE,
                TraceStore.get("llm.responses.gatewayReceipt.parse.suppressed"));
        assertNotNull(TraceStore.get("llm.responses.gatewayReceipt.parse.errorType"));
        String retained = new ObjectMapper().writeValueAsString(TraceStore.getAll());
        assertFalse(retained.contains(sensitiveBodyMarker));
        assertFalse(retained.contains(malformed));
    }

    @Test
    void loopbackResponsesCannotSelfPromoteWithCoherentLookingGatewayMetadata() throws Exception {
        String assistantText = "loopback-attestation-result";
        String responseBody = """
                {
                  "status":"completed",
                  "output_text":"loopback-attestation-result",
                  "providerMetadata":{"gateway":{"routing":{
                    "finalProvider":"zai",
                    "attempts":[{"provider":"zai","success":true,"statusCode":200}],
                    "totalProviderAttemptCount":1
                  }}}
                }
                """;
        HttpServer server = startResponsesServer(responseBody, 200, new AtomicReference<>(""));
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "loopback-attestation-request", "loopback-attestation-session");
        try {
            OpenAiResponsesChatModel model = trackerAwareModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "zai/glm-5.2",
                    5_000L,
                    tracker);

            ChatResponse response = model.chat(List.of(UserMessage.from("loopback-attestation-prompt")));

            assertEquals(assistantText, response.aiMessage().text());
            Map<String, Object> row = tracker.redactedRequestAttemptLedger(timelineId).get(0);
            assertEquals("client_http_response", row.get("evidenceBoundary"));
            assertEquals(Boolean.FALSE, row.get("providerReceiptObserved"));
            assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directResponsesHttpSuccessRecordsExactSentAndReceivedUtf8EvidenceWithoutOuterDuplicate() throws Exception {
        String rawPrompt = "responses-request-private-가";
        String assistantText = "responses-response-private-나";
        String responseBody = "{\"status\":\"completed\",\"output_text\":\"" + assistantText + "\"}";
        AtomicReference<String> receivedRequest = new AtomicReference<>("");
        HttpServer server = startResponsesServer(responseBody, 200, receivedRequest);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "responses-request-id-private", "responses-session-id-private");
        try {
            OpenAiResponsesChatModel direct = trackerAwareModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "gpt-5-pro-private",
                    1_000L,
                    tracker);
            ChatResponse response = tracker.decorateRequestAttempt(
                    direct,
                    "primary",
                    tracker.redactedRequestAttemptRoute(
                            "outer-route-private",
                            "outer-model-private",
                            "http://127.0.0.1:1/v1",
                            "openai_responses"),
                    ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                            "openai", "outer-model-private", "openai_responses",
                            Map.of("timeoutMs", 1_000L, "maxRetries", 0)))
                    .chat(List.of(UserMessage.from(rawPrompt)));

            assertEquals(assistantText, response.aiMessage().text());
            List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, rows.size(), "the direct exchange must suppress the outer generic row");
            Map<String, Object> row = rows.get(0);
            String expectedPromptJson = canonicalMessages(rawPrompt);
            String expectedOptionsJson = canonicalOptions(ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                    "openai", "outer-model-private", "openai_responses",
                    Map.of("timeoutMs", 1_000L, "maxRetries", 0)));
            assertEquals(SafeRedactor.hashValue(expectedPromptJson), row.get("promptHash"));
            assertEquals(SafeRedactor.hashValue(expectedOptionsJson), row.get("optionsHash"));
            assertEquals(SafeRedactor.hashValue(assistantText), row.get("responseHash"));
            assertEquals(expectedPromptJson.getBytes(StandardCharsets.UTF_8).length,
                    row.get("promptUtf8ByteCount"));
            assertEquals(expectedOptionsJson.getBytes(StandardCharsets.UTF_8).length,
                    row.get("optionsUtf8ByteCount"));
            assertEquals(assistantText.getBytes(StandardCharsets.UTF_8).length,
                    row.get("responseUtf8ByteCount"));
            assertEquals(exactSha256(receivedRequest.get()), row.get("httpRequestBodyHash"));
            assertEquals(receivedRequest.get().getBytes(StandardCharsets.UTF_8).length,
                    row.get("httpRequestBodyUtf8ByteCount"));
            assertEquals(exactSha256(responseBody), row.get("httpResponseBodyHash"));
            assertEquals(responseBody.getBytes(StandardCharsets.UTF_8).length,
                    row.get("httpResponseBodyUtf8ByteCount"));
            assertEquals(Boolean.TRUE, row.get("modelAdapterAttemptObserved"));
            assertEquals(Boolean.TRUE, row.get("clientHttpExchangeObserved"));
            assertEquals(Boolean.TRUE, row.get("clientHttpResponseObserved"));
            assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
            assertEquals(Boolean.TRUE, row.get("responseObserved"));

            String retained = new ObjectMapper().writeValueAsString(rows) + TraceStore.getAll();
            assertFalse(retained.contains(rawPrompt));
            assertFalse(retained.contains(assistantText));
            assertFalse(retained.contains(responseBody));
            assertFalse(retained.contains("responses-request-id-private"));
            assertFalse(retained.contains("responses-session-id-private"));
            assertFalse(retained.contains("gpt-5-pro-private"));
            assertFalse(retained.contains("private-option"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directResponsesHttpErrorRetainsReceivedErrorBodyProofWithoutRawData() throws Exception {
        String errorBody = "{\"error\":\"" + SecretFixtures.openAiKey() + "-오류\"}";
        HttpServer server = startResponsesServer(errorBody, 503, new AtomicReference<>(""));
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(tracker, "responses-error-request-private", "responses-error-session-private");
        try {
            ChatResponse response = trackerAwareModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key",
                    "gpt-5-pro-private",
                    1_000L,
                    tracker).chat(List.of(UserMessage.from("responses-error-prompt-private")));

            assertTrue(response.aiMessage().text().contains("httpStatus: 503"));
            Map<String, Object> row = tracker.redactedRequestAttemptLedger(timelineId).get(0);
            assertEquals("failed", row.get("outcome"));
            assertEquals("hash:unknown", row.get("responseHash"));
            assertEquals(0, row.get("responseUtf8ByteCount"));
            assertEquals(exactSha256(errorBody), row.get("httpResponseBodyHash"));
            assertEquals(errorBody.getBytes(StandardCharsets.UTF_8).length,
                    row.get("httpResponseBodyUtf8ByteCount"));
            assertEquals(Boolean.TRUE, row.get("clientHttpExchangeObserved"));
            assertEquals(Boolean.TRUE, row.get("clientHttpResponseObserved"));
            assertEquals(Boolean.FALSE, row.get("providerAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("wireAttemptObserved"));
            assertEquals(Boolean.FALSE, row.get("responseObserved"));
            assertFalse((new ObjectMapper().writeValueAsString(tracker.redactedRequestAttemptLedger(timelineId))
                    + TraceStore.getAll()).contains(errorBody));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void multimodalProviderFailureLogExceptionAndLedgerNeverRetainBase64Sentinel() throws Exception {
        byte[] marker = ("awx-provider-image-boundary-" + "91e4b7c3")
                .getBytes(StandardCharsets.UTF_8);
        byte[] png = new byte[8 + marker.length];
        byte[] signature = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
        System.arraycopy(signature, 0, png, 0, signature.length);
        System.arraycopy(marker, 0, png, signature.length, marker.length);
        String encoded = Base64.getEncoder().encodeToString(png);
        int fragmentStart = encoded.length() / 3;
        String fragment = encoded.substring(fragmentStart, Math.min(encoded.length(), fragmentStart + 18));
        String markerText = new String(marker, StandardCharsets.UTF_8);

        AtomicReference<String> receivedRequest = new AtomicReference<>("");
        HttpServer server = startResponsesServer("{\"error\":\"bounded\"}", 503, receivedRequest);
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = pendingTimeline(
                tracker, "multimodal-failure-request", "multimodal-failure-session");
        OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-api-key",
                "gpt-5-pro-private",
                5_000L,
                tracker,
                "primary",
                tracker.redactedRequestAttemptRoute(
                        "multimodal-failure-route",
                        "gpt-5-pro-private",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses",
                        "openai_responses"));
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiResponsesChatModel.class);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        logger.setAdditive(false);
        try {
            LlmGatewayException failure = assertThrows(
                    LlmGatewayException.class,
                    () -> model.chat(List.of(UserMessage.from(
                            TextContent.from("describe"),
                            ImageContent.from(encoded, "image/png")))));
            assertEquals(LlmFailureClass.DISABLED, failure.failureClass());
            assertEquals("vision_model_unavailable", failure.reasonCode());
            assertEquals("", receivedRequest.get());
            List<Map<String, Object>> rows = tracker.redactedRequestAttemptLedger(timelineId);
            assertEquals(1, rows.size());
            assertEquals("disabled", rows.get(0).get("failureClass"));
            assertEquals(Boolean.FALSE, rows.get(0).get("wireAttemptObserved"));
            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            String retained = logged
                    + failure
                    + failure.getMessage()
                    + new ObjectMapper().writeValueAsString(rows)
                    + TraceStore.getAll();
            assertFalse(retained.contains(encoded));
            assertFalse(retained.contains(fragment));
            assertFalse(retained.contains(markerText));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            server.stop(0);
        }
    }

    @Test
    void malformedReceivedBodyAndTransportFailureKeepDistinctHttpObservationStates() throws Exception {
        String malformedBody = "{\"status\":\"completed\",\"output_text\":\"malformed-private-다\"";
        HttpServer server = startResponsesServer(malformedBody, 200, new AtomicReference<>(""));
        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String malformedTimeline = pendingTimeline(tracker, "malformed-request-private", "malformed-session-private");
        try {
            assertThrows(LlmGatewayException.class, () -> trackerAwareModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-api-key", "gpt-5-pro-private", 1_000L, tracker)
                    .chat(List.of(UserMessage.from("malformed-prompt-private"))));
            Map<String, Object> malformed = tracker.redactedRequestAttemptLedger(malformedTimeline).get(0);
            assertEquals("failed", malformed.get("outcome"));
            assertEquals("hash:unknown", malformed.get("responseHash"));
            assertEquals(0, malformed.get("responseUtf8ByteCount"));
            assertEquals(exactSha256(malformedBody), malformed.get("httpResponseBodyHash"));
            assertEquals(malformedBody.getBytes(StandardCharsets.UTF_8).length,
                    malformed.get("httpResponseBodyUtf8ByteCount"));
            assertEquals(Boolean.TRUE, malformed.get("clientHttpExchangeObserved"));
            assertEquals(Boolean.TRUE, malformed.get("clientHttpResponseObserved"));
            assertEquals(Boolean.FALSE, malformed.get("responseObserved"));
            assertFalse((new ObjectMapper().writeValueAsString(tracker.redactedRequestAttemptLedger(malformedTimeline))
                    + TraceStore.getAll()).contains(malformedBody));
        } finally {
            server.stop(0);
        }

        HttpServer portReservation = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int unusedPort = portReservation.getAddress().getPort();
        portReservation.stop(0);
        String transportTimeline = pendingTimeline(tracker, "transport-request-private", "transport-session-private");
        trackerAwareModel("http://127.0.0.1:" + unusedPort + "/v1", "test-api-key",
                "gpt-5-pro-private", 1_000L, tracker)
                .chat(List.of(UserMessage.from("transport-prompt-private")));
        Map<String, Object> transport = tracker.redactedRequestAttemptLedger(transportTimeline).get(0);
        String sentBody = "{\"model\":\"gpt-5-pro-private\",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"transport-prompt-private\"}]}]}";
        assertEquals("failed", transport.get("outcome"));
        assertEquals("hash:unknown", transport.get("responseHash"));
        assertEquals(0, transport.get("responseUtf8ByteCount"));
        assertEquals(exactSha256(sentBody), transport.get("httpRequestBodyHash"));
        assertEquals(sentBody.getBytes(StandardCharsets.UTF_8).length,
                transport.get("httpRequestBodyUtf8ByteCount"));
        assertEquals("hash:unknown", transport.get("httpResponseBodyHash"));
        assertEquals(0, transport.get("httpResponseBodyUtf8ByteCount"));
        assertEquals(Boolean.TRUE, transport.get("clientHttpExchangeObserved"));
        assertEquals(Boolean.FALSE, transport.get("clientHttpResponseObserved"));
        assertEquals(Boolean.FALSE, transport.get("providerAttemptObserved"));
        assertEquals(Boolean.FALSE, transport.get("wireAttemptObserved"));
        assertEquals(Boolean.FALSE, transport.get("responseObserved"));
        assertFalse((new ObjectMapper().writeValueAsString(tracker.redactedRequestAttemptLedger(transportTimeline))
                + TraceStore.getAll()).contains("transport-prompt-private"));
    }

    private static HttpServer startResponsesServer(String responseBody) throws IOException {
        return startResponsesServer(responseBody, 200, new AtomicReference<>(""));
    }

    private static HttpServer startResponsesServer(
            String responseBody,
            int status,
            AtomicReference<String> receivedRequest) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            receivedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static HttpServer startBlockingResponsesServer(
            CountDownLatch requestEntered,
            CountDownLatch releaseResponse) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requestEntered.countDown();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"status\":\"completed\",\"output_text\":\"late\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(bytes);
            }
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

    private static String pendingTimeline(
            ModelRuntimeHealthTracker tracker,
            String requestId,
            String sessionId) {
        String timelineId = tracker.beginRequestTimeline(requestId, sessionId);
        tracker.recordRequestPhase(timelineId, "dispatch", "gpt-5-pro-private", null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        return timelineId;
    }

    private static OpenAiResponsesChatModel trackerAwareModel(
            String baseUrl,
            String apiKey,
            String modelName,
            long timeoutMs,
            ModelRuntimeHealthTracker tracker) {
        return new OpenAiResponsesChatModel(baseUrl, apiKey, modelName, timeoutMs, tracker);
    }

    private static String exactSha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) {
                hex.append(String.format("%02x", valueByte));
            }
            return "sha256:" + hex;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String canonicalMessages(String content) throws Exception {
        LinkedHashMap<String, String> row = new LinkedHashMap<>();
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
