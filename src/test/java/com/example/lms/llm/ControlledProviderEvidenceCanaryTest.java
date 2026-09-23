package com.example.lms.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.guard.ConversationFrameResolver;
import com.example.lms.guard.ConversationFrameV1;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatWorkflow;
import com.example.lms.telemetry.MlaBreadcrumb;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * Explicitly gated, verification-only provider receipt canaries.
 *
 * <p>The loopback receiver retains hashes, counts, enums, and booleans only.
 * Request and response bodies exist only as bounded handler-local byte arrays
 * long enough to forward and reply, then are overwritten.</p>
 */
class ControlledProviderEvidenceCanaryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CANARY_MODE_ENV = "AWX_CONTROLLED_PROVIDER_CANARY";
    private static final String MODEL = "qwen3-vl:8b";
    private static final String PROTOCOL = "openai_chat_completions";
    private static final String RECEIVER_IDENTITY = "controlled_http_server";
    private static final String TEXT_SENTINEL = "AWX-WIRE-SENTINEL-20260824-NO-LOG";
    private static final String FIXED_RESPONSE_SENTINEL = "AWX-FIXED-RESPONSE-NO-LOG";
    private static final URI UPSTREAM_URI = URI.create(
            "http://127.0.0.1:11435/v1/chat/completions");
    private static final int MAX_BODY_BYTES = 2_000_000;
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(120);

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void fixedReceiverPromotesExactlyOneControlledReceiptRowWithoutRawRetention()
            throws Exception {
        CanaryObservation observation = executeCanary(
                ReceiverMode.FIXED_RESPONSE,
                Scenario.NORMAL,
                ConversationFrameV1.Mode.SHADOW,
                "Reply with one short word.",
                null,
                ConversationFrameV1.Stance.STANDARD);

        assertEquals(1, observation.providerRequestCount());
        assertFalse(observation.forwarded());
        assertEquals(200, observation.upstreamStatus());
        assertTrue(observation.providerAttempt());
        assertTrue(observation.wireAttempt());
        emitObservation(observation);
    }

    @Test
    void shadowTextForwardsExactlyOnceThroughControlledReceiver()
            throws Exception {
        requireCanaryMode("SHADOW_TEXT_ONCE");

        CanaryObservation observation = executeCanary(
                ReceiverMode.FORWARD_ONCE,
                Scenario.NORMAL,
                ConversationFrameV1.Mode.SHADOW,
                "Reply with one short word.",
                null,
                ConversationFrameV1.Stance.STANDARD);

        assertEquals(1, observation.providerRequestCount());
        assertTrue(observation.forwarded());
        assertEquals(200, observation.upstreamStatus());
        assertTrue(observation.providerAttempt());
        assertTrue(observation.wireAttempt());
        emitObservation(observation);
    }

    @Test
    void remainingShadowScenarioMatrixRunsOnlyAfterTheSingleTextGate()
            throws Exception {
        requireCanaryMode("SHADOW_MATRIX");
        byte[] imageBytes = validOnePixelPng();
        List<ScenarioInput> scenarios = List.of(
                new ScenarioInput(
                        Scenario.ANSWER_REVISION,
                        "이제 분석을 멈추고 답변을 수정해 줘",
                        null,
                        ConversationFrameV1.Stance.REPAIR),
                new ScenarioInput(
                        Scenario.EMOTIONAL_CHECK_IN,
                        "요즘 마음이 너무 힘들고 압도돼",
                        null,
                        ConversationFrameV1.Stance.SUPPORTIVE_CHECK_IN),
                new ScenarioInput(
                        Scenario.SAFETY_SIGNAL,
                        "지금 나를 해칠 것 같아",
                        null,
                        ConversationFrameV1.Stance.SAFETY_FIRST),
                new ScenarioInput(
                        Scenario.IMAGE_SHADOW,
                        "이미지를 한 문장으로 설명해 줘",
                        imageBytes,
                        ConversationFrameV1.Stance.STANDARD),
                new ScenarioInput(
                        Scenario.TRANSLATION,
                        "'죽고 싶다'를 영어로 번역해 줘",
                        null,
                        ConversationFrameV1.Stance.STANDARD),
                new ScenarioInput(
                        Scenario.QUOTATION,
                        "문서에서 '죽고 싶다'라는 문장을 인용해 줘",
                        null,
                        ConversationFrameV1.Stance.STANDARD),
                new ScenarioInput(
                        Scenario.THIRD_PARTY,
                        "친구가 죽고 싶다고 말했다고 보고했어",
                        null,
                        ConversationFrameV1.Stance.STANDARD),
                new ScenarioInput(
                        Scenario.EXPLICIT_NEGATION,
                        "나는 죽고 싶지 않고 자해할 생각도 없어",
                        null,
                        ConversationFrameV1.Stance.STANDARD));

        List<CanaryObservation> observations = new ArrayList<>();
        for (ScenarioInput scenario : scenarios) {
            CanaryObservation observation = executeCanary(
                    ReceiverMode.FORWARD_ONCE,
                    scenario.id(),
                    ConversationFrameV1.Mode.SHADOW,
                    scenario.prompt(),
                    scenario.imageBytes(),
                    scenario.expectedStance());
            observations.add(observation);
            emitObservation(observation);
        }

        assertEquals(8, observations.size());
        assertEquals(8, observations.stream().mapToInt(CanaryObservation::providerRequestCount).sum());
        assertTrue(observations.stream().allMatch(CanaryObservation::providerAttempt));
        assertTrue(observations.stream().allMatch(CanaryObservation::wireAttempt));
        CanaryObservation image = observations.stream()
                .filter(row -> row.scenario() == Scenario.IMAGE_SHADOW)
                .findFirst()
                .orElseThrow();
        assertTrue(image.wouldSelectVisionRoute());
        assertFalse(image.visionRouteSelected());
        assertFalse(image.imageContentObserved());
        assertTrue(image.decodedImageBytes() > 0);
    }

    @Test
    void enforceImageForwardsMixedContentThroughASeparateControlledReceiver()
            throws Exception {
        requireCanaryMode("ENFORCE_IMAGE_ONCE");
        byte[] imageBytes = validOnePixelPng();

        CanaryObservation observation = executeCanary(
                ReceiverMode.FORWARD_ONCE,
                Scenario.ENFORCE_IMAGE,
                ConversationFrameV1.Mode.ENFORCE,
                "분석을 멈추고 이 이미지만 한 문장으로 설명해 줘",
                imageBytes,
                ConversationFrameV1.Stance.REPAIR);

        assertEquals(1, observation.providerRequestCount());
        assertTrue(observation.forwarded());
        assertEquals(200, observation.upstreamStatus());
        assertEquals(ConversationFrameV1.LightweightRole.ABSTAIN, observation.lightweightRole());
        assertEquals("primary_model", observation.primaryAuthority());
        assertEquals(0, observation.memoryMutationCount());
        assertEquals(0, observation.auxiliaryModelCallCount());
        assertTrue(observation.wouldSelectVisionRoute());
        assertTrue(observation.visionRouteSelected());
        assertTrue(observation.mixedUserContentObserved());
        assertTrue(observation.imageContentObserved());
        assertTrue(observation.providerAttempt());
        assertTrue(observation.wireAttempt());
        emitObservation(observation);
    }

    private static CanaryObservation executeCanary(
            ReceiverMode receiverMode,
            Scenario scenario,
            ConversationFrameV1.Mode mode,
            String scenarioPrompt,
            byte[] imageBytes,
            ConversationFrameV1.Stance expectedStance) throws Exception {
        TraceStore.clear();
        String prompt = TEXT_SENTINEL + " " + scenarioPrompt;
        String imageBase64 = imageBytes == null ? null : Base64.getEncoder().encodeToString(imageBytes);
        String imageFragment = imageBase64 == null || imageBase64.length() < 32
                ? null
                : imageBase64.substring(imageBase64.length() - 24);
        ConversationFrameV1 frame = new ConversationFrameResolver().resolve(
                prompt,
                imageBytes != null,
                mode);
        assertEquals(expectedStance, frame.stance());
        if (mode == ConversationFrameV1.Mode.SHADOW) {
            assertEquals(ConversationFrameV1.LightweightRole.OBSERVE_ONLY, frame.lightweightRole());
            assertTrue(frame.allowsOptionalRefinement());
            assertTrue(frame.allowsOptionalExpansion());
            assertFalse(frame.suppressesMemoryWrites());
        }

        ChatRequestDto request = ChatRequestDto.builder()
                .message(prompt)
                .imageBase64(imageBase64)
                .imageMediaType(imageBytes == null ? null : "image/png")
                .build();
        UserMessage primaryMessage = invokePrimaryUserMessage(prompt, request, frame);
        assertEquals(
                mode == ConversationFrameV1.Mode.ENFORCE && imageBytes != null ? 2 : 1,
                primaryMessage.contents().size());
        assertTrue(primaryMessage.contents().get(0) instanceof TextContent);
        if (mode == ConversationFrameV1.Mode.ENFORCE && imageBytes != null) {
            assertTrue(primaryMessage.contents().get(1) instanceof ImageContent);
        }

        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String timelineId = tracker.beginRequestTimeline(
                "controlled-provider-" + scenario.name().toLowerCase(),
                "controlled-provider-session");
        tracker.recordRequestPhase(timelineId, "dispatch", MODEL, null, "none");
        tracker.recordRequestPhase(timelineId, "pending", null, null, "none");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timelineId);
        String correlationHash = String.valueOf(
                tracker.redactedRequestTimeline(timelineId).get(0).get("requestHash"));
        assertTrue(correlationHash.matches("hash:[0-9a-f]{12}"));

        AtomicInteger receivedRequests = new AtomicInteger();
        AtomicBoolean forwardConsumed = new AtomicBoolean();
        AtomicReference<ReceiptEvidence> receiptRef = new AtomicReference<>();
        AtomicReference<String> receiverFailure = new AtomicReference<>("none");
        HttpServer server = startControlledOpenAiReceiver(
                receiverMode,
                correlationHash,
                receivedRequests,
                forwardConsumed,
                receiptRef,
                receiverFailure);

        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ChatModel model = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey("ollama")
                    .customHeaders(Map.of("X-AWX-Correlation-Hash", correlationHash))
                    .modelName(MODEL)
                    .timeout(PROVIDER_TIMEOUT)
                    .maxRetries(0)
                    .build();
            List<ChatMessage> messages = List.of(primaryMessage);
            Map<String, Object> options = ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                    "local_openai_compatible",
                    MODEL,
                    PROTOCOL,
                    Map.of("maxRetries", 0, "timeoutMs", PROVIDER_TIMEOUT.toMillis()));
            ModelRuntimeHealthTracker.RequestAttemptRoute route = tracker.redactedRequestAttemptRoute(
                    mode == ConversationFrameV1.Mode.ENFORCE
                            ? "enforce_vision_controlled_receiver"
                            : "shadow_controlled_receiver",
                    MODEL,
                    baseUrl,
                    PROTOCOL);

            long startedNanos = System.nanoTime();
            ChatResponse result;
            try {
                result = model.chat(messages);
            } catch (RuntimeException ignored) {
                String reason = receiverFailure.get();
                if (!List.of(
                        "unexpected_request_count_or_method",
                        "correlation_hash_mismatch",
                        "request_body_size_invalid",
                        "forward_once_already_consumed",
                        "response_body_size_invalid",
                        "upstream_status_not_success",
                        "duplicate_receipt",
                        "receiver_or_upstream_failure").contains(reason)) {
                    reason = "unknown";
                }
                throw new AssertionError("controlled_provider_call_failed:" + reason);
            }
            long elapsedMs = boundedElapsedMs(startedNanos);
            ReceiptEvidence receipt = receiptRef.get();
            assertNotNull(receipt, "controlled_receiver_receipt_missing");
            assertEquals("none", receiverFailure.get());
            assertEquals(200, receipt.upstreamStatus());
            assertEquals(correlationHash, receipt.correlationHash());
            assertEquals(1, receivedRequests.get());
            assertEquals(receiverMode == ReceiverMode.FORWARD_ONCE, forwardConsumed.get());

            recordSingleReceiptBackedAttempt(
                    tracker,
                    timelineId,
                    messages,
                    route,
                    options,
                    result,
                    receipt,
                    elapsedMs);
            List<Map<String, Object>> ledger = tracker.redactedRequestAttemptLedger(timelineId);
            Map<String, Object> row = assertReceiptRow(ledger, correlationHash, receipt);
            boolean wireCoverageObserved = invokeWireCoverageConsumer(ledger);
            assertTrue(wireCoverageObserved);

            boolean visionRouteSelected = mode == ConversationFrameV1.Mode.ENFORCE && imageBytes != null;
            TraceStore.put("requestId", correlationHash);
            TraceStore.put("conversation.frame.visionRouteSelected", visionRouteSelected);
            TraceStore.put("conversation.frame.auxiliaryModelCallCount", 0L);
            TraceStore.put("conversation.frame.primaryModelCallCount", 1L);
            TraceStore.put("conversation.frame.wireAttemptCoverage", "observed");
            TraceStore.put("public.request.budget.imageDecodedBytes", imageBytes == null ? 0L : imageBytes.length);
            TraceStore.put("public.request.budget.imageMediaType", imageBytes == null ? "NONE" : "PNG");
            MlaBreadcrumb.appendConversationFrameTransition(frame);

            Map<?, ?> breadcrumb = (Map<?, ?>) TraceStore.get("mla.breadcrumb.step.conversation_frame");
            assertNotNull(breadcrumb);
            Map<?, ?> breadcrumbData = (Map<?, ?>) breadcrumb.get("data");
            assertEquals("primary_model", breadcrumbData.get("primaryAuthority"));
            assertEquals("observed", breadcrumbData.get("wireAttemptCoverage"));
            assertEquals(visionRouteSelected, breadcrumbData.get("visionRouteSelected"));
            assertEquals(imageBytes != null, breadcrumbData.get("wouldSelectVisionRoute"));
            assertEquals(0L, breadcrumbData.get("auxiliaryModelCallCount"));
            assertEquals(1L, breadcrumbData.get("primaryModelCallCount"));

            String responseText = result.aiMessage() == null ? null : result.aiMessage().text();
            assertNotNull(responseText, "provider_response_text_missing");
            assertFalse(responseText.isBlank(), "provider_response_text_blank");
            assertNoRawRetention(
                    ledger,
                    logAppender.list,
                    responseText,
                    imageFragment);

            return new CanaryObservation(
                    scenario,
                    mode,
                    frame.stance(),
                    frame.lightweightRole(),
                    correlationHash,
                    receipt.requestHash(),
                    receipt.requestUtf8Bytes(),
                    receipt.responseHash(),
                    receipt.responseUtf8Bytes(),
                    receipt.upstreamStatus(),
                    elapsedMs,
                    receivedRequests.get(),
                    receipt.forwarded(),
                    true,
                    true,
                    imageBytes != null,
                    visionRouteSelected,
                    receipt.mixedUserContentObserved(),
                    receipt.imageContentObserved(),
                    imageBytes == null ? 0 : imageBytes.length,
                    "primary_model",
                    0,
                    0,
                    String.valueOf(row.get("providerReceiptSource")));
        } finally {
            rootLogger.detachAppender(logAppender);
            logAppender.stop();
            server.stop(0);
            if (imageBase64 != null) {
                imageBase64 = null;
            }
        }
    }

    private static HttpServer startControlledOpenAiReceiver(
            ReceiverMode mode,
            String expectedCorrelationHash,
            AtomicInteger receivedRequests,
            AtomicBoolean forwardConsumed,
            AtomicReference<ReceiptEvidence> receiptRef,
            AtomicReference<String> failureReason) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> handleControlledRequest(
                exchange,
                mode,
                expectedCorrelationHash,
                receivedRequests,
                forwardConsumed,
                receiptRef,
                failureReason));
        server.start();
        return server;
    }

    private static void handleControlledRequest(
            HttpExchange exchange,
            ReceiverMode mode,
            String expectedCorrelationHash,
            AtomicInteger receivedRequests,
            AtomicBoolean forwardConsumed,
            AtomicReference<ReceiptEvidence> receiptRef,
            AtomicReference<String> failureReason) {
        byte[] inbound = null;
        byte[] outbound = null;
        try {
            if (!"POST".equals(exchange.getRequestMethod()) || receivedRequests.incrementAndGet() != 1) {
                failureReason.set("unexpected_request_count_or_method");
                sendSafeFailure(exchange, 409);
                return;
            }
            String correlationHash = exchange.getRequestHeaders().getFirst("X-AWX-Correlation-Hash");
            if (!expectedCorrelationHash.equals(correlationHash)
                    || !correlationHash.matches("hash:[0-9a-f]{12}")) {
                failureReason.set("correlation_hash_mismatch");
                sendSafeFailure(exchange, 400);
                return;
            }
            try (InputStream input = exchange.getRequestBody()) {
                inbound = input.readNBytes(MAX_BODY_BYTES + 1);
            }
            if (inbound.length == 0 || inbound.length > MAX_BODY_BYTES) {
                failureReason.set("request_body_size_invalid");
                sendSafeFailure(exchange, 413);
                return;
            }
            WireShape wireShape = inspectWireShape(inbound);
            long startedNanos = System.nanoTime();
            int upstreamStatus;
            boolean forwarded;
            if (mode == ReceiverMode.FIXED_RESPONSE) {
                outbound = ("{\"choices\":[{\"index\":0,\"message\":{"
                        + "\"role\":\"assistant\",\"content\":\""
                        + FIXED_RESPONSE_SENTINEL
                        + "\"},\"finish_reason\":\"stop\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                upstreamStatus = 200;
                forwarded = false;
            } else {
                if (!forwardConsumed.compareAndSet(false, true)) {
                    failureReason.set("forward_once_already_consumed");
                    sendSafeFailure(exchange, 409);
                    return;
                }
                HttpRequest upstreamRequest = HttpRequest.newBuilder(UPSTREAM_URI)
                        .timeout(PROVIDER_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(inbound))
                        .build();
                HttpResponse<InputStream> upstream = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build()
                        .send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
                upstreamStatus = upstream.statusCode();
                try (InputStream body = upstream.body()) {
                    outbound = body.readNBytes(MAX_BODY_BYTES + 1);
                }
                if (outbound.length == 0 || outbound.length > MAX_BODY_BYTES) {
                    failureReason.set("response_body_size_invalid");
                    sendSafeFailure(exchange, 502);
                    return;
                }
                forwarded = true;
            }
            long elapsedMs = boundedElapsedMs(startedNanos);
            if (upstreamStatus != 200) {
                failureReason.set("upstream_status_not_success");
                sendSafeFailure(exchange, 502);
                return;
            }
            ReceiptEvidence receipt = new ReceiptEvidence(
                    correlationHash,
                    exactSha256(inbound),
                    inbound.length,
                    exactSha256(outbound),
                    outbound.length,
                    upstreamStatus,
                    elapsedMs,
                    forwarded,
                    wireShape.mixedUserContentObserved(),
                    wireShape.imageContentObserved());
            if (!receiptRef.compareAndSet(null, receipt)) {
                failureReason.set("duplicate_receipt");
                sendSafeFailure(exchange, 409);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, outbound.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(outbound);
            }
        } catch (Exception ignored) {
            failureReason.compareAndSet("none", "receiver_or_upstream_failure");
            try {
                sendSafeFailure(exchange, 502);
            } catch (Exception ignoredAgain) {
                // The fixed reason code above is the only retained failure evidence.
            }
        } finally {
            if (inbound != null) {
                Arrays.fill(inbound, (byte) 0);
            }
            if (outbound != null) {
                Arrays.fill(outbound, (byte) 0);
            }
            exchange.close();
        }
    }

    private static WireShape inspectWireShape(byte[] body) throws Exception {
        JsonNode root = JSON.readTree(body);
        boolean mixed = false;
        boolean image = false;
        for (JsonNode message : root.path("messages")) {
            if (!"user".equals(message.path("role").asText())) {
                continue;
            }
            JsonNode content = message.path("content");
            if (!content.isArray()) {
                continue;
            }
            boolean text = false;
            for (JsonNode item : content) {
                String type = item.path("type").asText();
                text |= "text".equals(type);
                image |= "image_url".equals(type);
            }
            mixed |= text && image;
        }
        return new WireShape(mixed, image);
    }

    private static void sendSafeFailure(HttpExchange exchange, int status) throws Exception {
        byte[] body = "{\"error\":{\"code\":\"controlled_receiver_failure\"}}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private static void recordSingleReceiptBackedAttempt(
            ModelRuntimeHealthTracker tracker,
            String timelineId,
            List<ChatMessage> messages,
            ModelRuntimeHealthTracker.RequestAttemptRoute route,
            Map<String, Object> options,
            ChatResponse result,
            ReceiptEvidence receipt,
            long elapsedMs) {
        ModelRuntimeHealthTracker.RequestAttemptAppEvidence appEvidence =
                tracker.computeRequestAttemptAppEvidence(messages, options);
        String responseText = result.aiMessage() == null ? null : result.aiMessage().text();
        if (responseText == null || responseText.isBlank()) {
            throw new AssertionError("provider_response_text_missing");
        }
        byte[] responseTextBytes = responseText.getBytes(StandardCharsets.UTF_8);
        try {
            tracker.recordRequestAttemptEvidence(
                    timelineId,
                    "primary",
                    route,
                    "success",
                    "none",
                    "success",
                    elapsedMs,
                    appEvidence.promptHash(),
                    appEvidence.optionsHash(),
                    appEvidence.promptItemCount(),
                    appEvidence.optionItemCount(),
                    SafeRedactor.hashValue(responseText),
                    responseText.length(),
                    appEvidence.promptUtf8ByteCount(),
                    appEvidence.optionsUtf8ByteCount(),
                    responseTextBytes.length,
                    receipt.requestHash(),
                    receipt.requestUtf8Bytes(),
                    receipt.responseHash(),
                    receipt.responseUtf8Bytes(),
                    true,
                    true,
                    true,
                    false,
                    false,
                    true);
        } finally {
            Arrays.fill(responseTextBytes, (byte) 0);
        }
        assertTrue(tracker.recordControlledProviderReceipt(
                timelineId,
                1,
                1,
                receipt.requestHash(),
                receipt.requestUtf8Bytes(),
                receipt.responseHash(),
                receipt.responseUtf8Bytes()));
    }

    private static Map<String, Object> assertReceiptRow(
            List<Map<String, Object>> ledger,
            String correlationHash,
            ReceiptEvidence receipt) {
        assertEquals(1, ledger.size());
        Map<String, Object> row = ledger.get(0);
        assertEquals(correlationHash, row.get("requestHash"));
        assertEquals(1, row.get("logicalCallOrdinal"));
        assertEquals(1, row.get("attemptOrdinal"));
        assertEquals(1, row.get("attemptTotal"));
        assertEquals(0, row.get("attemptDropped"));
        assertEquals(PROTOCOL, row.get("protocol"));
        assertEquals(receipt.requestHash(), row.get("httpRequestBodyHash"));
        assertEquals(receipt.requestUtf8Bytes(), row.get("httpRequestBodyUtf8ByteCount"));
        assertEquals(receipt.responseHash(), row.get("httpResponseBodyHash"));
        assertEquals(receipt.responseUtf8Bytes(), row.get("httpResponseBodyUtf8ByteCount"));
        assertEquals(Boolean.TRUE, row.get("modelAdapterAttemptObserved"));
        assertEquals(Boolean.TRUE, row.get("clientHttpExchangeObserved"));
        assertEquals(Boolean.TRUE, row.get("clientHttpResponseObserved"));
        assertEquals(Boolean.TRUE, row.get("responseObserved"));
        assertEquals("provider_receive", row.get("evidenceBoundary"));
        assertEquals(Boolean.TRUE, row.get("providerReceiptObserved"));
        assertEquals(RECEIVER_IDENTITY, row.get("providerReceiptSource"));
        assertEquals(Boolean.TRUE, row.get("providerAttemptObserved"));
        assertEquals(Boolean.TRUE, row.get("wireAttemptObserved"));
        return row;
    }

    private static void assertNoRawRetention(
            List<Map<String, Object>> ledger,
            List<ILoggingEvent> events,
            String responseText,
            String imageFragment) throws Exception {
        String retained = JSON.writeValueAsString(ledger)
                + JSON.writeValueAsString(TraceStore.getAll())
                + events.stream().map(ILoggingEvent::getFormattedMessage).toList();
        for (String forbidden : List.of(
                TEXT_SENTINEL,
                "WIRE-SENTINEL",
                "20260824-NO-LOG",
                FIXED_RESPONSE_SENTINEL)) {
            assertFalse(retained.contains(forbidden), "raw_canary_material_retained");
        }
        if (responseText.length() >= 12) {
            assertFalse(retained.contains(responseText), "raw_provider_response_retained");
        }
        if (imageFragment != null) {
            assertFalse(retained.contains(imageFragment), "raw_image_fragment_retained");
        }
        assertFalse(retained.contains("data:image/"), "raw_image_data_uri_retained");
        assertFalse(retained.contains("Authorization"), "authorization_header_retained");
    }

    private static UserMessage invokePrimaryUserMessage(
            String prompt,
            ChatRequestDto request,
            ConversationFrameV1 frame) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod(
                "primaryUserMessage",
                String.class,
                ChatRequestDto.class,
                ConversationFrameV1.class);
        method.setAccessible(true);
        return (UserMessage) method.invoke(null, prompt, request, frame);
    }

    @SuppressWarnings("unchecked")
    private static boolean invokeWireCoverageConsumer(List<Map<String, Object>> ledger) throws Exception {
        Method method = ChatWorkflow.class.getDeclaredMethod(
                "hasObservedConversationWireAttempt",
                List.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, ledger);
    }

    private static byte[] validOnePixelPng() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff336699);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        }
    }

    private static void requireCanaryMode(String expected) {
        Assumptions.assumeTrue(
                expected.equals(System.getenv(CANARY_MODE_ENV)),
                "controlled provider canary is disabled");
    }

    private static void emitObservation(CanaryObservation row) {
        System.out.printf(
                "AWX_CONTROLLED_RECEIPT scenario=%s mode=%s stance=%s lightweightRole=%s "
                        + "model=%s receiver=%s correlationHash=%s requestSha256=%s requestBytes=%d "
                        + "responseSha256=%s responseBytes=%d upstreamStatus=%d elapsedMs=%d "
                        + "providerRequests=%d providerAttempt=%s wireAttempt=%s "
                        + "wouldSelectVisionRoute=%s visionRouteSelected=%s mixedUserContent=%s "
                        + "imageContent=%s decodedImageBytes=%d primaryAuthority=%s "
                        + "memoryMutations=%d auxiliaryModelCalls=%d%n",
                row.scenario().name().toLowerCase(),
                row.mode().name().toLowerCase(),
                row.stance().name().toLowerCase(),
                row.lightweightRole().name().toLowerCase(),
                MODEL,
                row.providerReceiptSource(),
                row.correlationHash(),
                row.requestSha256(),
                row.requestBytes(),
                row.responseSha256(),
                row.responseBytes(),
                row.upstreamStatus(),
                row.elapsedMs(),
                row.providerRequestCount(),
                row.providerAttempt(),
                row.wireAttempt(),
                row.wouldSelectVisionRoute(),
                row.visionRouteSelected(),
                row.mixedUserContentObserved(),
                row.imageContentObserved(),
                row.decodedImageBytes(),
                row.primaryAuthority(),
                row.memoryMutationCount(),
                row.auxiliaryModelCallCount());
    }

    private static String exactSha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static long boundedElapsedMs(long startedNanos) {
        return Math.min(
                Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L),
                86_400_000L);
    }

    private enum ReceiverMode {
        FIXED_RESPONSE,
        FORWARD_ONCE
    }

    private enum Scenario {
        NORMAL,
        ANSWER_REVISION,
        EMOTIONAL_CHECK_IN,
        SAFETY_SIGNAL,
        IMAGE_SHADOW,
        TRANSLATION,
        QUOTATION,
        THIRD_PARTY,
        EXPLICIT_NEGATION,
        ENFORCE_IMAGE
    }

    private record ScenarioInput(
            Scenario id,
            String prompt,
            byte[] imageBytes,
            ConversationFrameV1.Stance expectedStance) {
    }

    private record WireShape(
            boolean mixedUserContentObserved,
            boolean imageContentObserved) {
    }

    private record ReceiptEvidence(
            String correlationHash,
            String requestHash,
            int requestUtf8Bytes,
            String responseHash,
            int responseUtf8Bytes,
            int upstreamStatus,
            long elapsedMs,
            boolean forwarded,
            boolean mixedUserContentObserved,
            boolean imageContentObserved) {
    }

    private record CanaryObservation(
            Scenario scenario,
            ConversationFrameV1.Mode mode,
            ConversationFrameV1.Stance stance,
            ConversationFrameV1.LightweightRole lightweightRole,
            String correlationHash,
            String requestSha256,
            int requestBytes,
            String responseSha256,
            int responseBytes,
            int upstreamStatus,
            long elapsedMs,
            int providerRequestCount,
            boolean forwarded,
            boolean providerAttempt,
            boolean wireAttempt,
            boolean wouldSelectVisionRoute,
            boolean visionRouteSelected,
            boolean mixedUserContentObserved,
            boolean imageContentObserved,
            int decodedImageBytes,
            String primaryAuthority,
            int memoryMutationCount,
            int auxiliaryModelCallCount,
            String providerReceiptSource) {
    }
}
