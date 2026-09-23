package com.example.lms.llm;

import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Minimal Ollama-native ChatModel for qwen thinking models.
 *
 * <p>LangChain4j 1.0.1's OpenAI-compatible builder cannot add Ollama's
 * native {@code think=false} request flag. This adapter is intentionally
 * narrow: it is selected only by {@link DynamicChatModelFactory} for loopback
 * qwen thinking models where blank content has been observed.</p>
 */
public final class OllamaNativeChatModel implements ChatModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LlmGatewayFailureClassifier FAILURE_CLASSIFIER =
            new LlmGatewayFailureClassifier();
    private static final AtomicLong LAST_NATIVE_SUCCESS_EPOCH_MS = new AtomicLong(0L);
    private static final String RECEIPT_VERSION_HEADER = "X-AWX-Controlled-Provider-Receipt";
    private static final String RECEIPT_REQUEST_HASH_HEADER = "X-AWX-Provider-Request-SHA256";
    private static final String RECEIPT_REQUEST_BYTES_HEADER = "X-AWX-Provider-Request-Bytes";
    private static final String RECEIPT_RESPONSE_HASH_HEADER = "X-AWX-Provider-Response-SHA256";
    private static final String RECEIPT_RESPONSE_BYTES_HEADER = "X-AWX-Provider-Response-Bytes";
    private static final String RECEIPT_VERSION = "v1;source=controlled_http_server";

    private final WebClient client;
    private final String chatUrl;
    private final String modelName;
    private final Duration timeout;
    private final Integer maxTokens;
    private final Double temperature;
    private final Double topP;
    private final Integer numGpu;
    private final ModelRuntimeHealthTracker modelRuntimeHealthTracker;
    private final boolean cpuFallbackRetryAllowed;
    private final ModelRuntimeHealthTracker.EndpointQuarantinePolicy endpointQuarantinePolicy;
    private final BooleanSupplier hardwareMissingSignal;
    private final String requestAttemptRole;

    /**
     * Keeps native-route eligibility identical for the direct factory and the
     * logical llmrouter path. Remote endpoints are deliberately excluded.
     */
    public static boolean supportsThinkFalseRoute(
            boolean enabled,
            String modelName,
            String baseUrl) {
        if (!enabled) {
            return false;
        }
        String model = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        if (!(model.contains("qwen3:")
                || model.contains("qwen3.5:9b")
                || model.contains("qwen3-vl")
                || model.equals("gemma4:12b"))) {
            return false;
        }
        return LocalLlmGatewaySecurity.isLoopbackBaseUrl(baseUrl);
    }

    /**
     * Builds a native model whose replay is owned by the outer route-level
     * fallback wrapper. This prevents a hidden same-endpoint CPU retry from
     * consuming the single explicit device-fallback attempt.
     */
    public static OllamaNativeChatModel forRoutedRequest(
            String openAiCompatBaseUrl,
            String modelName,
            Duration timeout,
            Integer maxTokens,
            Double temperature,
            Double topP,
            Integer numGpu,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker,
            String requestAttemptRole) {
        return new OllamaNativeChatModel(
                openAiCompatBaseUrl,
                modelName,
                timeout,
                maxTokens,
                temperature,
                topP,
                numGpu,
                modelRuntimeHealthTracker,
                false,
                ModelRuntimeHealthTracker.EndpointQuarantinePolicy.disabled(),
                () -> false,
                requestAttemptRole);
    }

    public OllamaNativeChatModel(String openAiCompatBaseUrl,
                                 String modelName,
                                 Duration timeout,
                                  Integer maxTokens,
                                  Double temperature) {
        this(openAiCompatBaseUrl, modelName, timeout, maxTokens, temperature, null);
    }

    public OllamaNativeChatModel(String openAiCompatBaseUrl,
                                 String modelName,
                                  Duration timeout,
                                  Integer maxTokens,
                                  Double temperature,
                                  Integer numGpu) {
        this(openAiCompatBaseUrl, modelName, timeout, maxTokens, temperature, numGpu, null);
    }

    public OllamaNativeChatModel(String openAiCompatBaseUrl,
                                 String modelName,
                                 Duration timeout,
                                 Integer maxTokens,
                                 Double temperature,
                                 Integer numGpu,
                                 ModelRuntimeHealthTracker modelRuntimeHealthTracker) {
        this(openAiCompatBaseUrl, modelName, timeout, maxTokens, temperature,
                numGpu, modelRuntimeHealthTracker, true);
    }

    public OllamaNativeChatModel(String openAiCompatBaseUrl,
                                 String modelName,
                                 Duration timeout,
                                 Integer maxTokens,
                                 Double temperature,
                                 Integer numGpu,
                                 ModelRuntimeHealthTracker modelRuntimeHealthTracker,
                                 boolean cpuFallbackRetryAllowed) {
        this(openAiCompatBaseUrl, modelName, timeout, maxTokens, temperature, null,
                numGpu, modelRuntimeHealthTracker, cpuFallbackRetryAllowed,
                ModelRuntimeHealthTracker.EndpointQuarantinePolicy.disabled());
    }

    public OllamaNativeChatModel(String openAiCompatBaseUrl,
                                 String modelName,
                                 Duration timeout,
                                 Integer maxTokens,
                                 Double temperature,
                                 Integer numGpu,
                                 ModelRuntimeHealthTracker modelRuntimeHealthTracker,
                                 boolean cpuFallbackRetryAllowed,
                                 ModelRuntimeHealthTracker.EndpointQuarantinePolicy endpointQuarantinePolicy) {
        this(openAiCompatBaseUrl, modelName, timeout, maxTokens, temperature, null,
                numGpu, modelRuntimeHealthTracker, cpuFallbackRetryAllowed, endpointQuarantinePolicy);
    }

    OllamaNativeChatModel(String openAiCompatBaseUrl,
                          String modelName,
                          Duration timeout,
                          Integer maxTokens,
                          Double temperature,
                          Double topP,
                          Integer numGpu,
                          ModelRuntimeHealthTracker modelRuntimeHealthTracker,
                          boolean cpuFallbackRetryAllowed) {
        this(openAiCompatBaseUrl, modelName, timeout, maxTokens, temperature, topP,
                numGpu, modelRuntimeHealthTracker, cpuFallbackRetryAllowed,
                ModelRuntimeHealthTracker.EndpointQuarantinePolicy.disabled());
    }

    OllamaNativeChatModel(String openAiCompatBaseUrl,
                          String modelName,
                          Duration timeout,
                          Integer maxTokens,
                          Double temperature,
                          Double topP,
                          Integer numGpu,
                          ModelRuntimeHealthTracker modelRuntimeHealthTracker,
                          boolean cpuFallbackRetryAllowed,
                          ModelRuntimeHealthTracker.EndpointQuarantinePolicy endpointQuarantinePolicy) {
        this(openAiCompatBaseUrl, modelName, timeout, maxTokens, temperature, topP,
                numGpu, modelRuntimeHealthTracker, cpuFallbackRetryAllowed,
                endpointQuarantinePolicy, () -> false);
    }

    OllamaNativeChatModel(String openAiCompatBaseUrl,
                          String modelName,
                          Duration timeout,
                          Integer maxTokens,
                          Double temperature,
                          Double topP,
                          Integer numGpu,
                          ModelRuntimeHealthTracker modelRuntimeHealthTracker,
                          boolean cpuFallbackRetryAllowed,
                          ModelRuntimeHealthTracker.EndpointQuarantinePolicy endpointQuarantinePolicy,
                          BooleanSupplier hardwareMissingSignal) {
        this(openAiCompatBaseUrl, modelName, timeout, maxTokens, temperature, topP,
                numGpu, modelRuntimeHealthTracker, cpuFallbackRetryAllowed,
                endpointQuarantinePolicy, hardwareMissingSignal, "primary");
    }

    private OllamaNativeChatModel(String openAiCompatBaseUrl,
                                  String modelName,
                                  Duration timeout,
                                  Integer maxTokens,
                                  Double temperature,
                                  Double topP,
                                  Integer numGpu,
                                  ModelRuntimeHealthTracker modelRuntimeHealthTracker,
                                  boolean cpuFallbackRetryAllowed,
                                  ModelRuntimeHealthTracker.EndpointQuarantinePolicy endpointQuarantinePolicy,
                                  BooleanSupplier hardwareMissingSignal,
                                  String requestAttemptRole) {
        this.client = WebClient.builder().build();
        this.chatUrl = nativeChatUrl(openAiCompatBaseUrl);
        this.modelName = modelName == null ? "" : modelName.trim();
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.topP = topP;
        this.numGpu = numGpu == null || numGpu < 0 ? null : numGpu;
        this.modelRuntimeHealthTracker = modelRuntimeHealthTracker;
        this.cpuFallbackRetryAllowed = cpuFallbackRetryAllowed;
        this.endpointQuarantinePolicy = endpointQuarantinePolicy == null
                ? ModelRuntimeHealthTracker.EndpointQuarantinePolicy.disabled()
                : endpointQuarantinePolicy;
        this.hardwareMissingSignal = hardwareMissingSignal == null ? () -> false : hardwareMissingSignal;
        this.requestAttemptRole = "fallback".equalsIgnoreCase(requestAttemptRole)
                ? "fallback"
                : "primary";
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return chatStructured(request.messages(), schemaFromResponseFormat(request.responseFormat()), request);
    }

    /** Optional native JSON schema; shares the existing budget and interruptible transport. */
    public ChatResponse chatStructured(List<ChatMessage> messages, Map<String, Object> schema) {
        return chatStructured(messages, schema, null);
    }

    private ChatResponse chatStructured(List<ChatMessage> messages, Object schema, ChatRequest request) {
        String prompt = OpenAiEndpointCompatibility.toCompletionsPrompt(messages);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", modelName);
        payload.put("stream", false);
        payload.put("think", false);
        payload.put("messages", toNativeMessages(messages, prompt));
        if (schema != null && !(schema instanceof Map<?, ?> schemaMap && schemaMap.isEmpty())) {
            payload.put("format", schema);
        }
        if (request != null && request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            payload.put("tools", toNativeTools(request.toolSpecifications()));
        }
        Map<String, Object> options = new LinkedHashMap<>();
        Integer effectiveMaxTokens = request == null || request.maxOutputTokens() == null
                ? maxTokens
                : request.maxOutputTokens();
        Double effectiveTemperature = request == null || request.temperature() == null
                ? temperature
                : request.temperature();
        Double effectiveTopP = request == null || request.topP() == null
                ? topP
                : request.topP();
        if (effectiveMaxTokens != null && effectiveMaxTokens > 0) {
            options.put("num_predict", effectiveMaxTokens);
        }
        if (effectiveTemperature != null) {
            options.put("temperature", effectiveTemperature);
        }
        if (effectiveTopP != null) {
            options.put("top_p", effectiveTopP);
        }
        if (request != null && request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            options.put("stop", request.stopSequences());
        }
        if (numGpu != null) {
            options.put("num_gpu", numGpu);
        }
        if (!options.isEmpty()) {
            payload.put("options", options);
        }

        traceRequest(prompt);
        ModelRuntimeHealthTracker.EndpointAccess endpointAccess = acquireEndpointAccess();
        traceEndpointAccess(endpointAccess);
        if (!endpointAccess.allowed()) {
            throw new LlmGatewayException(
                    "Local endpoint quarantined endpointHash=" + endpointAccess.endpointHash(),
                    LlmFailureClass.GPU_DEVICE_LOST,
                    "gpu_device_lost");
        }
        try {
            ChatResponse primary = chatOnce(payload, requestAttemptRole, messages);
            completeEndpointAccess(endpointAccess, responseHasText(primary));
            return primary;
        } catch (WebClientResponseException ex) {
            LlmFailureClass primaryFailureClass = FAILURE_CLASSIFIER.classify(ex);
            String cpuRetryReason = cpuFallbackRetryAllowed && numGpu == null
                    ? cpuFallbackReason(ex)
                    : null;
            recordEndpointPrimaryFailure(endpointAccess, primaryFailureClass, cpuRetryReason);
            if (cpuRetryReason != null) {
                recordCpuFallbackRetry(ex, cpuRetryReason);
                Map<String, Object> retryOptions = new LinkedHashMap<>(options);
                retryOptions.put("num_gpu", 0);
                payload.put("options", retryOptions);
                try {
                    return chatOnce(payload, "fallback", messages);
                } catch (WebClientResponseException retryEx) {
                    traceHttpFailure(retryEx);
                    recordNativeFailure(failureClassForStatus(retryEx.getStatusCode().value()));
                    traceSuppressed("llm.ollamaNative.call", retryEx);
                    throw retryEx;
                }
            }
            traceHttpFailure(ex);
            recordNativeFailure(failureClassForStatus(ex.getStatusCode().value()));
            traceSuppressed("llm.ollamaNative.call", ex);
            throw ex;
        } catch (RuntimeException ex) {
            if (LlmGatewayFailureClassifier.isCancellation(ex)) {
                if (modelRuntimeHealthTracker != null) modelRuntimeHealthTracker.releaseEndpointAccess(endpointAccess);
                throw ex;
            }
            LlmFailureClass failureClass = FAILURE_CLASSIFIER.classify(ex);
            recordEndpointPrimaryFailure(endpointAccess, failureClass, null);
            recordNativeFailure(ex instanceof LlmGatewayException gatewayFailure
                    ? gatewayFailure.reasonCode()
                    : failureClass.name().toLowerCase(java.util.Locale.ROOT));
            traceSuppressed("llm.ollamaNative.call", ex);
            throw ex;
        }
    }

    private ModelRuntimeHealthTracker.EndpointAccess acquireEndpointAccess() {
        if (modelRuntimeHealthTracker == null) {
            return new ModelRuntimeHealthTracker.EndpointAccess(
                    true,
                    false,
                    false,
                    ModelRuntimeHealthTracker.EndpointState.CLOSED,
                    "local",
                    ModelRuntimeHealthTracker.endpointIdentityHash(chatUrl),
                    "tracker_absent",
                    0L,
                    0L);
        }
        return modelRuntimeHealthTracker.acquireEndpointAccess(
                "local",
                chatUrl,
                endpointQuarantinePolicy,
                System.currentTimeMillis());
    }

    private void recordEndpointPrimaryFailure(
            ModelRuntimeHealthTracker.EndpointAccess endpointAccess,
            LlmFailureClass failureClass,
            String cpuRetryReason) {
        if (modelRuntimeHealthTracker == null || !endpointQuarantinePolicy.enabled()) {
            return;
        }
        long observedAtEpochMs = System.currentTimeMillis();
        if (failureClass == LlmFailureClass.GPU_DEVICE_LOST) {
            modelRuntimeHealthTracker.recordEndpointDeviceLoss(
                    "local", chatUrl, endpointQuarantinePolicy, observedAtEpochMs);
        } else if ("runner_terminated_cpu_probe".equals(cpuRetryReason)) {
            modelRuntimeHealthTracker.recordEndpointRunnerTermination(
                    "local", chatUrl, freshHardwareMissingSignal(), endpointQuarantinePolicy, observedAtEpochMs);
        } else {
            modelRuntimeHealthTracker.recordEndpointTransientFailure(
                    "local", chatUrl, modelName, failureClass, endpointQuarantinePolicy, observedAtEpochMs);
        }
        modelRuntimeHealthTracker.completeEndpointAccess(
                endpointAccess, false, endpointQuarantinePolicy, observedAtEpochMs);
        traceEndpointSnapshot(failureClass, observedAtEpochMs);
    }

    private boolean freshHardwareMissingSignal() {
        try {
            return hardwareMissingSignal.getAsBoolean();
        } catch (RuntimeException ignored) {
            TraceStore.put("llm.localEndpoint.hardwareSignal", "unavailable");
            return false;
        }
    }

    private void completeEndpointAccess(
            ModelRuntimeHealthTracker.EndpointAccess endpointAccess,
            boolean gpuPrimarySuccess) {
        if (modelRuntimeHealthTracker == null || !endpointQuarantinePolicy.enabled()) {
            return;
        }
        long observedAtEpochMs = System.currentTimeMillis();
        if (gpuPrimarySuccess) modelRuntimeHealthTracker.recordEndpointModelSuccess("local", chatUrl, modelName);
        modelRuntimeHealthTracker.completeEndpointAccess(
                endpointAccess, gpuPrimarySuccess && !endpointAccess.halfOpenPermit(), endpointQuarantinePolicy, observedAtEpochMs);
        traceEndpointSnapshot(
                gpuPrimarySuccess ? LlmFailureClass.NONE : LlmFailureClass.PROVIDER_ERROR,
                observedAtEpochMs);
    }

    private static boolean responseHasText(ChatResponse response) {
        return response != null
                && response.aiMessage() != null
                && ((response.aiMessage().text() != null && !response.aiMessage().text().isBlank())
                        || response.aiMessage().hasToolExecutionRequests());
    }

    private static void traceEndpointAccess(ModelRuntimeHealthTracker.EndpointAccess access) {
        if (access == null) {
            return;
        }
        TraceStore.put("llm.localEndpoint.state", access.state().name().toLowerCase(java.util.Locale.ROOT));
        TraceStore.put("llm.localEndpoint.endpointHash", access.endpointHash());
        TraceStore.put("llm.localEndpoint.halfOpenPermit", access.halfOpenPermit());
        TraceStore.put("llm.localEndpoint.retryAfterMs", Math.max(0L, access.retryAfterMs()));
        Object existingDecision = TraceStore.get("llm.localEndpoint.selectionDecision");
        if (!"device_fallback".equals(String.valueOf(existingDecision))) {
            TraceStore.put("llm.localEndpoint.selectionDecision", access.decision());
        }
    }

    private void traceEndpointSnapshot(LlmFailureClass failureClass, long observedAtEpochMs) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        modelRuntimeHealthTracker.endpointSnapshot("local", chatUrl, observedAtEpochMs)
                .ifPresent(snapshot -> {
                    TraceStore.put("llm.localEndpoint.state",
                            snapshot.state().name().toLowerCase(java.util.Locale.ROOT));
                    TraceStore.put("llm.localEndpoint.endpointHash", snapshot.endpointHash());
                    TraceStore.put("llm.localEndpoint.failureClass",
                            (failureClass == null ? LlmFailureClass.UNKNOWN : failureClass)
                                    .name().toLowerCase(java.util.Locale.ROOT));
                    TraceStore.put("llm.localEndpoint.opened",
                            snapshot.state() != ModelRuntimeHealthTracker.EndpointState.CLOSED);
                    TraceStore.put("llm.localEndpoint.retryAfterMs", snapshot.retryAfterMs());
                    TraceStore.put("llm.localEndpoint.gpuPrimarySuccessCount",
                            snapshot.consecutiveGpuPrimarySuccesses());
                });
    }

    private ChatResponse chatOnce(Map<String, Object> payload, String role, List<ChatMessage> messages) {
        com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
        long startedNanos = System.nanoTime();
        String requestJson;
        try {
            requestJson = MAPPER.writeValueAsString(payload);
        } catch (Exception serializationFailure) {
            recordRequestHttpExchange(
                    messages,
                    role,
                    null,
                    "failed",
                    "unknown",
                    "error",
                    null,
                    null,
                    false,
                    false,
                    elapsedMs(startedNanos));
            throw new IllegalStateException("Ollama native request serialization failed", serializationFailure);
        }
        boolean clientHttpExchangeObserved = false;
        boolean clientHttpResponseObserved = false;
        boolean responseEvidenceRecorded = false;
        String observedResponseBody = null;
        HttpHeaders observedResponseHeaders = null;
        var exactRun = com.example.lms.service.chat.ChatRunExecutionContext.current();
        var clientAttempt = modelRuntimeHealthTracker == null ? null : modelRuntimeHealthTracker.beginClientAttempt(role);
        try (var cancellation = com.example.lms.service.chat.ChatRunExecutionContext.interruptibleCall("ollama_native")) {
            long waitMs = com.example.lms.service.chat.ChatRunExecutionContext.capRequestWait(timeout.toMillis());
            var requestBudget = com.abandonware.ai.addons.budget.TimeBudgetContext.get();
            var responseMono = client.post()
                    .uri(modelRuntimeHealthTracker == null ? chatUrl : modelRuntimeHealthTracker.resolveServiceEndpoint(chatUrl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .httpRequest(request -> request.beforeCommit(() -> {
                        if (requestBudget != null && requestBudget.expired()) {
                            throw new LlmGatewayException("request budget exhausted", LlmFailureClass.TIMEOUT_SOFT, "request_budget_exhausted");
                        }
                        if (clientAttempt != null) clientAttempt.started("spring_client_request_commit");
                        else if (exactRun != null && !exactRun.admitCall(() -> {})) {
                            throw new java.util.concurrent.CancellationException("exact chat run cancelled before HTTP commit");
                        }
                        return reactor.core.publisher.Mono.empty();
                    }))
                    .bodyValue(requestJson)
                    .retrieve()
                    .toEntity(String.class)
                    .timeout(Duration.ofMillis(waitMs));
            clientHttpExchangeObserved = true;
            ResponseEntity<String> response = responseMono.block();
            if (clientAttempt != null) clientAttempt.finished(null);
            String body = response == null ? null : response.getBody();
            observedResponseBody = body;
            observedResponseHeaders = response == null ? null : response.getHeaders();
            clientHttpResponseObserved = response != null;
            AiMessage aiMessage = extractAiMessage(body);
            String text = aiMessage == null ? null : aiMessage.text();
            boolean usable = (text != null && !text.isBlank())
                    || (aiMessage != null && aiMessage.hasToolExecutionRequests());
            recordRequestHttpExchange(
                    messages,
                    role,
                    requestJson,
                    usable ? "success" : "failed",
                    usable ? "none" : "provider_error",
                    usable ? "success" : "blank_response",
                    body,
                    usable && text != null && !text.isBlank() ? text : null,
                    true,
                    true,
                    elapsedMs(startedNanos));
            responseEvidenceRecorded = true;
            recordControlledProviderReceipt(
                    requestJson,
                    body,
                    response == null ? null : response.getHeaders());
            if (!usable) {
                recordNativeFailure("blank_response");
                throw new LlmGatewayException(
                        "Ollama native response did not contain assistant text or tool calls",
                        LlmFailureClass.PROVIDER_ERROR,
                        "blank_response");
            }
            recordNativeSuccess();
            return ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .build();
        } catch (WebClientResponseException ex) {
            if (clientAttempt != null) clientAttempt.finished(ex);
            String body = ex.getResponseBodyAsString();
            String failureClass = FAILURE_CLASSIFIER.classify(ex)
                    .name()
                    .toLowerCase(java.util.Locale.ROOT);
            recordRequestHttpExchange(
                    messages,
                    role,
                    requestJson,
                    "failed",
                    failureClass,
                    "error",
                    body,
                    null,
                    true,
                    true,
                    elapsedMs(startedNanos));
            recordControlledProviderReceipt(requestJson, body, ex.getHeaders());
            throw ex;
        } catch (RuntimeException ex) {
            if (clientAttempt != null) clientAttempt.finished(ex);
            if (!responseEvidenceRecorded) {
                boolean cancelled = LlmGatewayFailureClassifier.isCancellation(ex);
                LlmFailureClass failureClass = FAILURE_CLASSIFIER.classify(ex);
                String terminalClass = cancelled
                        ? "cancelled"
                        : ex instanceof LlmGatewayException gatewayFailure
                                ? gatewayFailure.reasonCode()
                                : "error";
                recordRequestHttpExchange(
                        messages,
                        role,
                        requestJson,
                        cancelled ? "cancelled" : "failed",
                        cancelled
                                ? "cancelled_neutral"
                                : failureClass.name().toLowerCase(java.util.Locale.ROOT),
                        terminalClass,
                        observedResponseBody,
                        null,
                        clientHttpExchangeObserved,
                        clientHttpResponseObserved,
                        elapsedMs(startedNanos));
                recordControlledProviderReceipt(
                        requestJson,
                        observedResponseBody,
                        observedResponseHeaders);
            }
            throw ex;
        } finally {
            if (clientAttempt != null) clientAttempt.close();
        }
    }

    private void recordControlledProviderReceipt(
            String requestJson,
            String responseBody,
            HttpHeaders responseHeaders) {
        if (modelRuntimeHealthTracker == null
                || requestJson == null
                || responseBody == null
                || responseHeaders == null
                || !isExactControlledLoopbackEndpoint()) {
            return;
        }
        String requestHash = exactSha256(requestJson);
        String responseHash = exactSha256(responseBody);
        int requestBytes = utf8Length(requestJson);
        int responseBytes = utf8Length(responseBody);
        int attestedRequestBytes = positiveReceiptCount(
                responseHeaders.getFirst(RECEIPT_REQUEST_BYTES_HEADER));
        int attestedResponseBytes = positiveReceiptCount(
                responseHeaders.getFirst(RECEIPT_RESPONSE_BYTES_HEADER));
        if (!RECEIPT_VERSION.equals(responseHeaders.getFirst(RECEIPT_VERSION_HEADER))
                || !requestHash.equals(responseHeaders.getFirst(RECEIPT_REQUEST_HASH_HEADER))
                || !responseHash.equals(responseHeaders.getFirst(RECEIPT_RESPONSE_HASH_HEADER))
                || requestBytes != attestedRequestBytes
                || responseBytes != attestedResponseBytes) {
            return;
        }
        Object rawTimelineId = TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
        String timelineId = rawTimelineId == null ? "" : String.valueOf(rawTimelineId).trim();
        if (timelineId.isBlank()) {
            return;
        }
        modelRuntimeHealthTracker.recordControlledProviderReceiptByHttpBodies(
                timelineId,
                "ollama_native",
                requestHash,
                requestBytes,
                responseHash,
                responseBytes);
    }

    private boolean isExactControlledLoopbackEndpoint() {
        try {
            URI endpoint = URI.create(chatUrl);
            return "http".equalsIgnoreCase(endpoint.getScheme())
                    && endpoint.getUserInfo() == null
                    && "127.0.0.1".equals(endpoint.getHost());
        } catch (RuntimeException invalidEndpoint) {
            traceSuppressed("llm.ollamaNative.receiptEndpointEligibility", invalidEndpoint);
            return false;
        }
    }

    private static int positiveReceiptCount(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,7}")) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed <= 50_000_000 ? parsed : -1;
        } catch (NumberFormatException invalidCount) {
            traceSuppressed("llm.ollamaNative.receiptCount", invalidCount);
            return -1;
        }
    }

    private void recordRequestHttpExchange(
            List<ChatMessage> messages,
            String role,
            String requestJson,
            String outcome,
            String failureClass,
            String terminalClass,
            String responseBody,
            String assistantText,
            boolean clientHttpExchangeObserved,
            boolean clientHttpResponseObserved,
            long elapsedMs) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        Object rawTimelineId = TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
        String timelineId = rawTimelineId == null ? "" : String.valueOf(rawTimelineId).trim();
        if (timelineId.isBlank()) {
            return;
        }
        try {
            ModelRuntimeHealthTracker.RequestAttemptAppEvidence appEvidence =
                    modelRuntimeHealthTracker.currentOrComputeRequestAttemptAppEvidence(
                            timelineId,
                            messages,
                            requestAttemptOptionEnvelope());
            boolean appResponseObserved = assistantText != null;
            String observedHttpRequest = clientHttpExchangeObserved ? requestJson : null;
            String observedHttpResponse = clientHttpResponseObserved ? responseBody : null;
            ModelRuntimeHealthTracker.RequestAttemptRoute route =
                    modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                            "dynamic_factory",
                            modelName,
                            chatUrl,
                            "ollama_native");
            modelRuntimeHealthTracker.recordRequestAttemptEvidence(
                    timelineId,
                    role,
                    route,
                    outcome,
                    failureClass,
                    terminalClass,
                    elapsedMs,
                    appEvidence.promptHash(),
                    appEvidence.optionsHash(),
                    appEvidence.promptItemCount(),
                    appEvidence.optionItemCount(),
                    appResponseObserved ? SafeRedactor.hashValue(assistantText) : "hash:unknown",
                    appResponseObserved ? assistantText.length() : 0,
                    appEvidence.promptUtf8ByteCount(),
                    appEvidence.optionsUtf8ByteCount(),
                    appResponseObserved ? utf8Length(assistantText) : 0,
                    exactSha256(observedHttpRequest),
                    utf8Length(observedHttpRequest),
                    exactSha256(observedHttpResponse),
                    utf8Length(observedHttpResponse),
                    true,
                    clientHttpExchangeObserved,
                    clientHttpResponseObserved,
                    false,
                    false,
                    appResponseObserved);
        } catch (RuntimeException ex) {
            traceSuppressed("llm.ollamaNative.requestHttpExchange", ex);
        }
    }

    private Map<String, Object> requestAttemptOptionEnvelope() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("provider", "ollama");
        options.put("model", modelName);
        options.put("protocol", "ollama_native");
        options.put("temperature", temperature);
        options.put("topP", topP);
        options.put("maxTokens", maxTokens);
        options.put("timeoutMs", timeout.toMillis());
        options.put("numGpu", numGpu);
        options.put("cpuFallbackRetryAllowed", cpuFallbackRetryAllowed);
        return options;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String exactSha256(String value) {
        if (value == null) {
            return "hash:unknown";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return "sha256:" + hex;
        } catch (Exception ex) {
            traceSuppressed("llm.ollamaNative.evidenceHash", ex);
            return "hash:unknown";
        }
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private AiMessage extractAiMessage(String body) {
        if (body == null || body.isBlank()) {
            TraceStore.put("llm.ollamaNative.emptyBody", true);
            throw new LlmGatewayException(
                    "Ollama native response body was empty",
                    LlmFailureClass.PROVIDER_ERROR,
                    "blank_response");
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            String error = boundedErrorSignal(root.path("error"));
            if (!error.isBlank()) {
                LlmFailureClass classified = FAILURE_CLASSIFIER.classify(
                        new IllegalStateException(error));
                if (classified == LlmFailureClass.NONE || classified == LlmFailureClass.UNKNOWN) {
                    classified = LlmFailureClass.PROVIDER_ERROR;
                }
                TraceStore.put("llm.ollamaNative.errorEnvelope", true);
                TraceStore.put("llm.ollamaNative.errorLength", error.length());
                TraceStore.put("llm.ollamaNative.errorHash", SafeRedactor.hashValue(error));
                TraceStore.put("llm.ollamaNative.errorClass",
                        classified.name().toLowerCase(java.util.Locale.ROOT));
                throw new LlmGatewayException(
                        "Ollama native response reported a classified failure",
                        classified,
                        reasonCodeFor(classified));
            }
            String text = root.path("message").path("content").asText("");
            String thinking = root.path("message").path("thinking").asText("");
            String doneReason = root.path("done_reason").asText("");
            TraceStore.put("llm.ollamaNative.contentLength", text.length());
            TraceStore.put("llm.ollamaNative.thinkingLength", thinking.length());
            if (!text.isBlank()) {
                traceNonBlankSuccess();
            }
            if (!doneReason.isBlank()) {
                TraceStore.put("llm.ollamaNative.doneReason",
                        SafeRedactor.traceLabelOrFallback(doneReason, "unknown"));
            }
            if (text.isBlank() && "length".equals(doneReason)) {
                throw new LlmGatewayException("Ollama output limit reached before assistant text",
                        LlmFailureClass.BAD_REQUEST, "output_limit_reached");
            }
            List<ToolExecutionRequest> toolCalls = nativeToolCalls(root.path("message").path("tool_calls"));
            if (!toolCalls.isEmpty()) {
                TraceStore.put("llm.ollamaNative.toolCalls", toolCalls.size());
            }
            AiMessage.Builder aiMessage = AiMessage.builder();
            if (text != null && !text.isBlank()) {
                aiMessage.text(text);
            }
            if (!toolCalls.isEmpty()) {
                aiMessage.toolExecutionRequests(toolCalls);
            }
            return aiMessage.build();
        } catch (LlmGatewayException classifiedFailure) {
            throw classifiedFailure;
        } catch (Exception ex) {
            traceSuppressed("llm.ollamaNative.parse", ex);
            throw new LlmGatewayException(
                    "Ollama native response was malformed",
                    LlmFailureClass.PROVIDER_ERROR,
                    "malformed_response");
        }
    }

    private static List<ToolExecutionRequest> nativeToolCalls(JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<ToolExecutionRequest> requests = new java.util.ArrayList<>();
        int index = 0;
        for (JsonNode callNode : toolCallsNode) {
            JsonNode function = callNode.path("function");
            String name = function.path("name").asText("");
            if (name.isBlank()) {
                index++;
                continue;
            }
            JsonNode arguments = function.path("arguments");
            String argumentsJson = "{}";
            try {
                if (arguments.isTextual()) {
                    argumentsJson = arguments.asText("{}");
                } else if (!arguments.isMissingNode() && !arguments.isNull()) {
                    argumentsJson = MAPPER.writeValueAsString(arguments);
                }
            } catch (Exception serializationFailure) {
                argumentsJson = "{}";
            }
            JsonNode idNode = callNode.path("id");
            String id = idNode.isTextual() && !idNode.asText().isBlank()
                    ? idNode.asText()
                    : "call_" + index;
            requests.add(ToolExecutionRequest.builder()
                    .id(id)
                    .name(name)
                    .arguments(argumentsJson)
                    .build());
            index++;
        }
        return List.copyOf(requests);
    }

    private static String boundedErrorSignal(JsonNode errorNode) {
        if (errorNode == null || errorNode.isMissingNode() || errorNode.isNull()) {
            return "";
        }
        if (errorNode.isTextual()) {
            return boundedErrorPart(errorNode.asText(""), 2_048);
        }
        if (!errorNode.isObject()) {
            return boundedErrorPart(errorNode.asText(""), 512);
        }
        String code = boundedErrorPart(errorNode.path("code").asText(""), 512);
        String type = boundedErrorPart(errorNode.path("type").asText(""), 512);
        String message = boundedErrorPart(errorNode.path("message").asText(""), 1_024);
        return String.join(" ", code, type, message).trim();
    }

    private static String boundedErrorPart(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static String reasonCodeFor(LlmFailureClass failureClass) {
        return switch (failureClass) {
            case GPU_DEVICE_LOST -> "gpu_device_lost";
            case VRAM_OOM -> "vram_oom";
            case MODEL_MISSING -> "model_missing";
            case AUTH_MISSING -> "auth_failed";
            case BAD_REQUEST -> "bad_request";
            case RATE_LIMIT_COOLDOWN -> "rate_limited";
            case TIMEOUT_SOFT -> "timeout_before_first_token";
            default -> "provider_error";
        };
    }

    private static String cpuFallbackReason(WebClientResponseException ex) {
        if (ex == null || ex.getStatusCode().value() < 500) {
            return null;
        }
        String body = ex.getResponseBodyAsString();
        String text = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("main_gpu") && text.contains("available devices: 0")) {
            return "main_gpu_no_devices";
        }
        if (text.contains("runner process has terminated")
                || text.contains("llama-server process has terminated")
                || text.contains("llama-server process no longer running")) {
            return "runner_terminated_cpu_probe";
        }
        return null;
    }

    private static void recordCpuFallbackRetry(WebClientResponseException ex, String reason) {
        String body = ex == null ? null : ex.getResponseBodyAsString();
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        int previousStatus = ex == null ? 0 : ex.getStatusCode().value();
        TraceStore.put("llm.ollamaNative.cpuRetry.used", true);
        TraceStore.put("llm.ollamaNative.cpuRetry.reason", safeReason);
        TraceStore.put("llm.ollamaNative.cpuRetry.previousStatus", previousStatus);
        TraceStore.put("llm.ollamaNative.cpuRetry.previousBodyLength",
                body == null ? 0 : body.length());
        TraceStore.put("llm.ollamaNative.cpuRetry.previousBodyHash",
                SafeRedactor.hashValue(body));
        TraceStore.put("llm.ollamaNative.numGpu", 0);
        TraceStore.put("llm.ollamaNative.gpuMode", "cpu_fallback_retry");
        TraceStore.put("llm.localSmoke.operatorAction.upstreamStatus", previousStatus);
        TraceStore.put("llm.localSmoke.operatorAction.upstreamFailureClass", safeReason);
        TraceStore.put("llm.localSmoke.operatorAction.upstreamNextAction",
                "inspect_ollama_runtime_capacity");
    }

    private static List<Map<String, Object>> toNativeMessages(List<ChatMessage> messages, String fallbackPrompt) {
        if (messages == null || messages.isEmpty()) {
            return List.of(Map.of("role", "user", "content", fallbackPrompt == null ? "" : fallbackPrompt));
        }
        java.util.ArrayList<Map<String, Object>> out = new java.util.ArrayList<>();
        for (ChatMessage message : messages) {
            Map<String, Object> nativeMessage = toNativeMessage(message);
            if (nativeMessage != null) {
                out.add(nativeMessage);
            }
        }
        if (out.isEmpty()) {
            out.add(Map.of("role", "user", "content", fallbackPrompt == null ? "" : fallbackPrompt));
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> toNativeMessage(ChatMessage message) {
        if (message instanceof SystemMessage systemMessage) {
            String text = systemMessage.text();
            if (text == null || text.isBlank()) {
                return null;
            }
            return Map.of("role", "system", "content", text);
        }
        if (message instanceof AiMessage aiMessage) {
            String text = aiMessage.text();
            List<ToolExecutionRequest> toolCalls = aiMessage.toolExecutionRequests();
            boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
            if ((text == null || text.isBlank()) && !hasToolCalls) {
                return null;
            }
            java.util.LinkedHashMap<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("role", "assistant");
            entry.put("content", text == null ? "" : text);
            if (hasToolCalls) {
                java.util.ArrayList<Map<String, Object>> nativeCalls = new java.util.ArrayList<>();
                for (ToolExecutionRequest toolCall : toolCalls) {
                    java.util.LinkedHashMap<String, Object> function = new java.util.LinkedHashMap<>();
                    function.put("name", toolCall.name());
                    function.put("arguments", toolCallArguments(toolCall.arguments()));
                    nativeCalls.add(Map.of("function", function));
                }
                entry.put("tool_calls", nativeCalls);
            }
            return entry;
        }
        if (message instanceof ToolExecutionResultMessage toolResult) {
            java.util.LinkedHashMap<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("role", "tool");
            entry.put("content", toolResult.text() == null ? "" : toolResult.text());
            if (toolResult.toolName() != null && !toolResult.toolName().isBlank()) {
                entry.put("name", toolResult.toolName());
            }
            return entry;
        }
        if (message instanceof UserMessage userMessage) {
            StringBuilder text = new StringBuilder();
            java.util.ArrayList<String> images = new java.util.ArrayList<>();
            for (Content content : userMessage.contents()) {
                if (content instanceof TextContent textContent) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(textContent.text());
                } else if (content instanceof ImageContent imageContent) {
                    String base64 = imageContent.image() == null ? null : imageContent.image().base64Data();
                    if (base64 != null && !base64.isBlank()) {
                        images.add(base64);
                    } else {
                        throw new IllegalArgumentException("unsupported_image_transport:"
                                + (imageContent.image() != null && imageContent.image().url() != null
                                        ? "url" : "missing"));
                    }
                } else if (content != null) {
                    throw new IllegalArgumentException("unsupported_content_type:" + content.type());
                }
            }
            if (text.length() == 0 && images.isEmpty()) {
                return null;
            }
            java.util.LinkedHashMap<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("role", "user");
            entry.put("content", text.toString());
            if (!images.isEmpty()) {
                entry.put("images", images);
            }
            return entry;
        }
        throw new IllegalArgumentException("unsupported_message_type:"
                + (message == null ? "null" : message.type()));
    }

    private static Object toolCallArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(arguments, Map.class);
        } catch (Exception unparsed) {
            return Map.of("arguments", arguments);
        }
    }

    private static List<Map<String, Object>> toNativeTools(List<ToolSpecification> toolSpecifications) {
        java.util.ArrayList<Map<String, Object>> tools = new java.util.ArrayList<>();
        for (ToolSpecification tool : toolSpecifications) {
            java.util.LinkedHashMap<String, Object> function = new java.util.LinkedHashMap<>();
            function.put("name", tool.name());
            if (tool.description() != null && !tool.description().isBlank()) {
                function.put("description", tool.description());
            }
            function.put("parameters", tool.parameters() == null
                    ? Map.of("type", "object", "properties", Map.of())
                    : schemaElementToMap(tool.parameters()));
            tools.add(Map.of("type", "function", "function", function));
        }
        return tools;
    }

    private static Object schemaFromResponseFormat(ResponseFormat responseFormat) {
        if (responseFormat == null || responseFormat.type() != ResponseFormatType.JSON) {
            return null;
        }
        JsonSchema jsonSchema = responseFormat.jsonSchema();
        if (jsonSchema == null) {
            return "json";
        }
        java.util.LinkedHashMap<String, Object> format = new java.util.LinkedHashMap<>();
        format.put("type", "object");
        if (jsonSchema.name() != null && !jsonSchema.name().isBlank()) {
            format.put("title", jsonSchema.name());
        }
        if (jsonSchema.rootElement() != null) {
            format.putAll(schemaElementToMap(jsonSchema.rootElement()));
        }
        return format;
    }

    private static Map<String, Object> schemaElementToMap(JsonSchemaElement element) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        if (element instanceof JsonObjectSchema objectSchema) {
            map.put("type", "object");
            if (objectSchema.description() != null) map.put("description", objectSchema.description());
            if (objectSchema.properties() != null && !objectSchema.properties().isEmpty()) {
                java.util.LinkedHashMap<String, Object> properties = new java.util.LinkedHashMap<>();
                objectSchema.properties().forEach((name, property) -> properties.put(name, schemaElementToMap(property)));
                map.put("properties", properties);
            }
            if (objectSchema.required() != null && !objectSchema.required().isEmpty()) {
                map.put("required", objectSchema.required());
            }
            if (objectSchema.additionalProperties() != null) {
                map.put("additionalProperties", objectSchema.additionalProperties());
            }
            if (objectSchema.definitions() != null && !objectSchema.definitions().isEmpty()) {
                java.util.LinkedHashMap<String, Object> definitions = new java.util.LinkedHashMap<>();
                objectSchema.definitions().forEach((name, definition) -> definitions.put(name, schemaElementToMap(definition)));
                map.put("definitions", definitions);
            }
            return map;
        }
        if (element instanceof JsonArraySchema arraySchema) {
            map.put("type", "array");
            if (arraySchema.description() != null) map.put("description", arraySchema.description());
            if (arraySchema.items() != null) map.put("items", schemaElementToMap(arraySchema.items()));
            return map;
        }
        if (element instanceof JsonEnumSchema enumSchema) {
            map.put("type", "string");
            if (enumSchema.description() != null) map.put("description", enumSchema.description());
            if (enumSchema.enumValues() != null) map.put("enum", enumSchema.enumValues());
            return map;
        }
        if (element instanceof JsonStringSchema stringSchema) {
            map.put("type", "string");
            if (stringSchema.description() != null) map.put("description", stringSchema.description());
            return map;
        }
        if (element instanceof JsonIntegerSchema integerSchema) {
            map.put("type", "integer");
            if (integerSchema.description() != null) map.put("description", integerSchema.description());
            return map;
        }
        if (element instanceof JsonNumberSchema numberSchema) {
            map.put("type", "number");
            if (numberSchema.description() != null) map.put("description", numberSchema.description());
            return map;
        }
        if (element instanceof JsonBooleanSchema booleanSchema) {
            map.put("type", "boolean");
            if (booleanSchema.description() != null) map.put("description", booleanSchema.description());
            return map;
        }
        if (element instanceof JsonNullSchema) {
            map.put("type", "null");
            return map;
        }
        if (element instanceof JsonReferenceSchema referenceSchema) {
            if (referenceSchema.reference() != null) map.put("$ref", referenceSchema.reference());
            return map;
        }
        if (element instanceof JsonAnyOfSchema anyOfSchema) {
            if (anyOfSchema.description() != null) map.put("description", anyOfSchema.description());
            if (anyOfSchema.anyOf() != null) {
                java.util.ArrayList<Object> anyOf = new java.util.ArrayList<>();
                for (JsonSchemaElement option : anyOfSchema.anyOf()) {
                    anyOf.add(schemaElementToMap(option));
                }
                map.put("anyOf", anyOf);
            }
            return map;
        }
        map.put("type", "object");
        return map;
    }

    private void traceRequest(String prompt) {
        TraceStore.put("llm.ollamaNative.route", true);
        TraceStore.put("llm.ollamaNative.thinkDisabled", true);
        TraceStore.put("llm.ollamaNative.modelHash", SafeRedactor.hashValue(modelName));
        TraceStore.put("llm.ollamaNative.modelLength", modelName.length());
        TraceStore.put("llm.ollamaNative.promptHash", SafeRedactor.hashValue(prompt));
        TraceStore.put("llm.ollamaNative.promptLength", prompt == null ? 0 : prompt.length());
        TraceStore.put("llm.ollamaNative.maxTokens", maxTokens == null ? 0 : Math.max(0, maxTokens));
        TraceStore.put("llm.ollamaNative.endpointHost", LocalLlmGatewaySecurity.endpointHost(chatUrl));
        if (numGpu != null) {
            TraceStore.put("llm.ollamaNative.numGpu", numGpu);
            TraceStore.put("llm.ollamaNative.gpuMode", numGpu == 0 ? "cpu_fallback" : "gpu_forced");
        }
    }

    private static void traceNonBlankSuccess() {
        LAST_NATIVE_SUCCESS_EPOCH_MS.set(System.currentTimeMillis());
        TraceStore.put("llm.ollamaNative.success", true);
        TraceStore.put("llm.ollamaNative.lastNativeSuccessAgeMs", 0L);
        TraceStore.put("llm.localSmoke.operatorAction.triggered", false);
        TraceStore.put("llm.localSmoke.operatorAction.triggerReason", "native_success");
        TraceStore.put("llm.localSmoke.operatorAction.failureClass", "none");
        TraceStore.put("llm.localSmoke.operatorAction.nextAction", "none");
        TraceStore.put("llm.localSmoke.operatorAction.actionScore", 0);
        TraceStore.put("llm.localSmoke.operatorAction.scoreDelta", 0);
        TraceStore.put("llm.localSmoke.operatorAction.negativeSignalCount", 0);
        TraceStore.put("llm.localSmoke.operatorAction.upstreamStatus", 200);
        TraceStore.put("llm.localSmoke.operatorAction.upstreamFailureClass", "none");
        TraceStore.put("llm.localSmoke.operatorAction.upstreamNextAction", "none");
    }

    public static boolean hasRecentNativeSuccess(Duration maxAge) {
        long last = LAST_NATIVE_SUCCESS_EPOCH_MS.get();
        if (last <= 0L) {
            return false;
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - last);
        long maxAgeMs = maxAge == null ? Duration.ofMinutes(30).toMillis() : Math.max(0L, maxAge.toMillis());
        return ageMs <= maxAgeMs;
    }

    private void recordNativeSuccess() {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        try {
            modelRuntimeHealthTracker.recordAttemptSuccess(
                    "local",
                    modelName,
                    OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);
        } catch (RuntimeException ex) {
            traceSuppressed("llm.ollamaNative.modelRuntimeHealth.success", ex);
        }
    }

    private void recordNativeFailure(String reason) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        try {
            modelRuntimeHealthTracker.recordCurrentRequestRouteFailure(modelName, reason);
        } catch (RuntimeException ex) {
            traceSuppressed("llm.ollamaNative.modelRuntimeHealth.failure", ex);
        }
    }

    private void traceHttpFailure(WebClientResponseException ex) {
        int status = ex.getStatusCode().value();
        String responseBody = ex.getResponseBodyAsString();
        int bodyLength = responseBody == null ? 0 : responseBody.length();
        String failureClass = failureClassForStatus(status);
        String nextAction = nextActionForStatus(status);
        String endpointHost = LocalLlmGatewaySecurity.endpointHost(chatUrl);

        TraceStore.put("llm.ollamaNative.httpStatus", status);
        TraceStore.put("llm.ollamaNative.responseBodyLength", bodyLength);
        TraceStore.put("llm.ollamaNative.responseBodyHash", SafeRedactor.hashValue(responseBody));
        TraceStore.put("llm.ollamaNative.failureClass", failureClass);
        TraceStore.put("llm.ollamaNative.nextAction", nextAction);
        TraceStore.put("llm.ollamaNative.endpointHost", endpointHost);
        TraceStore.put("llm.localSmoke.operatorAction.upstreamStatus", status);
        TraceStore.put("llm.localSmoke.operatorAction.upstreamFailureClass", failureClass);
        TraceStore.put("llm.localSmoke.operatorAction.upstreamNextAction", nextAction);
        TraceStore.put("llm.localSmoke.operatorAction.endpointHost", endpointHost);
    }

    private static void traceSuppressed(String stage, Throwable ex) {
        TraceStore.put(stage + ".suppressed", true);
        TraceStore.put(stage + ".errorType", ex == null ? "unknown"
                : SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
        String message = ex == null ? null : ex.getMessage();
        TraceStore.put(stage + ".errorHash", SafeRedactor.hashValue(message));
        TraceStore.put(stage + ".errorLength", message == null ? 0 : message.length());
    }

    private static String failureClassForStatus(int status) {
        if (status >= 500) {
            return "ollama_upstream_5xx";
        }
        if (status == 429) {
            return "ollama_rate_limit";
        }
        if (status >= 400) {
            return "ollama_upstream_4xx";
        }
        return "ollama_upstream_http_error";
    }

    private static String nextActionForStatus(int status) {
        if (status >= 500) {
            return "inspect_ollama_runtime_capacity";
        }
        if (status == 429) {
            return "respect_ollama_retry_after";
        }
        if (status >= 400) {
            return "inspect_ollama_request_contract";
        }
        return "inspect_ollama_http_failure";
    }

    private static String nativeChatUrl(String openAiCompatBaseUrl) {
        String url = OpenAiCompatBaseUrl.sanitize(openAiCompatBaseUrl);
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - 3);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url + "/api/chat";
    }
}
