package ai.abandonware.nova.orch.llm;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OpenAiCompatBaseUrl;
import com.example.lms.llm.OpenAiEndpointCompatibility;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmResponseTerminalException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.search.TraceStore;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.trace.SafeChatMessageLog;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Adapter ChatModel that calls OpenAI Responses API (/v1/responses).
 *
 * <p>Used as a fail-soft route when the requested model is not compatible with /v1/chat/completions.</p>
 */
public final class OpenAiResponsesChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesChatModel.class);
    private static final LlmGatewayFailureClassifier FAILURE_CLASSIFIER = new LlmGatewayFailureClassifier();

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final long MIN_RESPONSES_TIMEOUT_MS = 5_000L;
    private static final String VERCEL_AI_GATEWAY_RESPONSES_URL =
            "https://ai-gateway.vercel.sh/v1/responses";
    private static final String VERCEL_ZAI_PROVIDER = "zai";
    private static final int MAX_GATEWAY_PROVIDER_ATTEMPTS = 8;

    private final WebClient client;
    private final ObjectMapper mapper;
    private final String modelName;
    private final long timeoutMs;
    private final String responsesUrl;
    private final boolean apiKeyPresent;
    private final ModelRuntimeHealthTracker modelRuntimeHealthTracker;
    private final String requestAttemptRole;
    private final ModelRuntimeHealthTracker.RequestAttemptRoute requestAttemptRoute;
    private final boolean typedRouterFailures;
    private final String reasoningEffort;
    private final Integer maxOutputTokens;
    private final Supplier<String> oauthAccessToken;
    private java.util.function.Consumer<LlmResponseTerminalException> oauthTerminalObserver = failure -> {};

    public OpenAiResponsesChatModel(String baseUrl, String modelName, long timeoutMs,
            Supplier<String> oauthAccessToken,
            java.util.function.Consumer<LlmResponseTerminalException> terminalObserver) {
        this(baseUrl, modelName, timeoutMs, oauthAccessToken);
        this.oauthTerminalObserver = java.util.Objects.requireNonNull(terminalObserver);
    }

    /** OAuth transport; callers must supply a validated account binding. Bearers are never logged or cached. */
    public OpenAiResponsesChatModel(String baseUrl, String modelName, long timeoutMs,
            Supplier<String> oauthAccessToken) {
        this(baseUrl, null, modelName, timeoutMs, null, "primary", null, null, null,
                java.util.Objects.requireNonNull(oauthAccessToken));
    }

    public OpenAiResponsesChatModel(String baseUrl, String apiKey, String modelName, long timeoutMs) {
        this(baseUrl, apiKey, modelName, timeoutMs, null);
    }

    /** Internal construction seam for request-correlated, count/hash-only HTTP evidence. */
    public OpenAiResponsesChatModel(
            String baseUrl,
            String apiKey,
            String modelName,
            long timeoutMs,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker) {
        this(baseUrl, apiKey, modelName, timeoutMs, modelRuntimeHealthTracker, "primary", null);
    }

    /** Internal router seam retaining the outer request-attempt role and route for direct HTTP proof. */
    public OpenAiResponsesChatModel(
            String baseUrl,
            String apiKey,
            String modelName,
            long timeoutMs,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker,
            String requestAttemptRole,
            ModelRuntimeHealthTracker.RequestAttemptRoute requestAttemptRoute) {
        this(baseUrl, apiKey, modelName, timeoutMs, modelRuntimeHealthTracker,
                requestAttemptRole, requestAttemptRoute, null);
    }

    /** Optional Responses reasoning control; legacy constructors deliberately omit it. */
    public OpenAiResponsesChatModel(
            String baseUrl,
            String apiKey,
            String modelName,
            long timeoutMs,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker,
            String requestAttemptRole,
            ModelRuntimeHealthTracker.RequestAttemptRoute requestAttemptRoute,
            String reasoningEffort) {
        this(baseUrl, apiKey, modelName, timeoutMs, modelRuntimeHealthTracker,
                requestAttemptRole, requestAttemptRoute, reasoningEffort, null);
    }

    /** Carries the already-resolved output cap; absence preserves provider-default behavior. */
    public OpenAiResponsesChatModel(
            String baseUrl,
            String apiKey,
            String modelName,
            long timeoutMs,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker,
            String requestAttemptRole,
            ModelRuntimeHealthTracker.RequestAttemptRoute requestAttemptRoute,
            String reasoningEffort,
            Integer maxOutputTokens) {
        this(baseUrl, apiKey, modelName, timeoutMs, modelRuntimeHealthTracker, requestAttemptRole,
                requestAttemptRoute, reasoningEffort, maxOutputTokens, null);
    }

    private OpenAiResponsesChatModel(String baseUrl, String apiKey, String modelName, long timeoutMs,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker, String requestAttemptRole,
            ModelRuntimeHealthTracker.RequestAttemptRoute requestAttemptRoute, String reasoningEffort,
            Integer maxOutputTokens, Supplier<String> oauthAccessToken) {
        this.modelName = (modelName == null) ? "" : modelName;
        this.timeoutMs = timeoutMs;
        this.responsesUrl = OpenAiCompatBaseUrl.sanitize(baseUrl) + "/responses";
        this.modelRuntimeHealthTracker = modelRuntimeHealthTracker;
        this.requestAttemptRole = "fallback".equals(requestAttemptRole) ? "fallback" : "primary";
        this.requestAttemptRoute = requestAttemptRoute;
        this.typedRouterFailures = modelRuntimeHealthTracker != null && requestAttemptRoute != null;
        this.reasoningEffort = StringUtils.hasText(reasoningEffort) ? reasoningEffort.trim() : null;
        this.maxOutputTokens = maxOutputTokens != null && maxOutputTokens > 0 ? maxOutputTokens : null;
        this.oauthAccessToken = oauthAccessToken;
        if (oauthAccessToken != null) validateOAuthEndpoint(responsesUrl);
        String normalizedApiKey = ConfigValueGuards.isMissing(apiKey) ? null : apiKey.trim();
        this.apiKeyPresent = normalizedApiKey != null;

        this.mapper = new ObjectMapper();
        WebClient.Builder builder = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(reactor.netty.http.client.HttpClient.create()
                        .responseTimeout(Duration.ofMillis(oauthAccessToken != null
                                ? Math.max(1L, timeoutMs) : Math.max(MIN_RESPONSES_TIMEOUT_MS, timeoutMs)))));
        if (normalizedApiKey != null) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + normalizedApiKey);
        }
        this.client = builder.build();
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        if ((request.toolSpecifications() != null && !request.toolSpecifications().isEmpty())
                || request.toolChoice() != null) {
            throw unsupportedInput("responses_tools_unsupported");
        }
        return chat(request.messages(), request.maxOutputTokens());
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        return chat(messages, null);
    }

    private ChatResponse chat(List<ChatMessage> messages, Integer requestMaxOutputTokens) {
        if (oauthAccessToken != null) return chatOAuth(messages);
        SafeChatMessageLog.Summary messageSummary = SafeChatMessageLog.summarize(messages);
        if (messageSummary.imagePresent()) {
            String reasonCode = "vision_model_unavailable";
            String evidenceNeeded = "evidence_needed: " + reasonCode;
            TraceStore.put("llm.responses.multimodalBlocked", true);
            TraceStore.put("llm.responses.multimodalBlocked.reason", reasonCode);
            TraceStore.put("llm.responses.multimodalBlocked.contentTypes", messageSummary.contentTypes());
            TraceStore.put("llm.responses.multimodalBlocked.decodedImageBytes",
                    messageSummary.decodedImageBytes());
            TraceStore.put("llm.responses.multimodalBlocked.imageMediaType",
                    messageSummary.imageMediaType().name());
            recordDisabledAttempt(messages, evidenceNeeded);
            log.warn(
                    "OpenAI /responses request blocked: reason={} modelHash={} contentTypes={} imagePresent={} decodedImageBytes={} imageMediaType={}",
                    reasonCode,
                    SafeRedactor.hashValue(modelName),
                    messageSummary.contentTypes(),
                    true,
                    messageSummary.decodedImageBytes(),
                    messageSummary.imageMediaType());
            if (typedRouterFailures) {
                throw new LlmGatewayException(
                        evidenceNeeded,
                        LlmFailureClass.DISABLED,
                        reasonCode);
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(evidenceNeeded))
                    .build();
        }
        if (!apiKeyPresent) {
            String msg = ModelGuardSupport.buildExpectedFailureMessage(modelName, "/v1/responses",
                    "ROUTE_RESPONSES(no_api_key)");
            recordDisabledAttempt(messages, msg);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(msg))
                    .build();
        }

        List<Map<String, Object>> input = responsesInput(messages);

        Map<String, Object> req = responsesPayload(input, requestMaxOutputTokens);
        long startedNanos = System.nanoTime();
        String requestJson = null;
        boolean clientHttpExchangeObserved = false;

        try {
            requestJson = mapper.writeValueAsString(req);
            var responseMono = client.post()
                    .uri(responsesUrl)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(requestJson)
                    .retrieve()
                    .bodyToMono(String.class);
            if (!ChatRunExecutionContext.isAcceptedExecution()) {
                responseMono = responseMono.timeout(Duration.ofMillis(Math.max(MIN_RESPONSES_TIMEOUT_MS, timeoutMs)));
            }
            clientHttpExchangeObserved = true;
            String json;
            try (var wait = ChatRunExecutionContext.interruptibleCall("jdk_http")) {
                json = responseMono.block();
            }

            String out = extractAssistantText(json);
            boolean usable = StringUtils.hasText(out);
            ResponseState state = responseState(json, usable);
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .id(responseId(json)).modelName(extractModelName(json))
                    .tokenUsage(extractTokenUsage(json)).finishReason(state.finishReason()).build();
            boolean completed = state.reason() == null;
            recordRequestHttpExchange(
                    messages,
                    requestJson,
                    completed ? "success" : state.failureClass() == LlmFailureClass.CANCELLED_NEUTRAL
                            ? "cancelled" : usable && state.failureClass() == LlmFailureClass.NONE ? "partial" : "failed",
                    failureClassLabel(state.failureClass()),
                    completed ? "success" : state.reason(),
                    json,
                    usable ? out : null,
                    true,
                    true,
                    elapsedMs(startedNanos));
            if (!completed) {
                throw new LlmResponseTerminalException(state.reason(), state.failureClass(),
                        usable ? out : null, metadata, state.status(), state.incompleteReason(), state.providerCode());
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(out))
                    .metadata(metadata)
                    .build();

        } catch (LlmGatewayException typedFailure) {
            throw typedFailure;
        } catch (WebClientResponseException wcre) {
            if (modelRuntimeHealthTracker != null) modelRuntimeHealthTracker.recordApiFailure(responsesUrl, modelName, wcre);
            LlmFailureClass failureClass = FAILURE_CLASSIFIER.classify(wcre);
            recordRequestHttpExchange(
                    messages,
                    requestJson,
                    "failed",
                    failureClassLabel(failureClass),
                    "error",
                    wcre.getResponseBodyAsString(),
                    null,
                    true,
                    true,
                    elapsedMs(startedNanos));
            log.warn("OpenAI /responses failed: status={} modelHash={}",
                    wcre.getRawStatusCode(), com.example.lms.trace.SafeRedactor.hashValue(modelName));
            if (typedRouterFailures) {
                throw typedProviderFailure(
                        LlmGatewayFailureClassifier.hasNonReplayableReason(wcre)
                                ? "insufficient_quota" : httpReasonCode(wcre.getRawStatusCode()),
                        failureClass);
            }
            String msg = ModelGuardSupport.buildExpectedFailureMessage(modelName, "/v1/responses", "ROUTE_RESPONSES")
                    + "httpStatus: " + wcre.getRawStatusCode() + "\n";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(msg))
                    .build();

        } catch (Exception e) {
            LlmFailureClass failureClass = FAILURE_CLASSIFIER.classify(e);
            boolean cancelled = failureClass == LlmFailureClass.CANCELLED_NEUTRAL;
            if (!cancelled && modelRuntimeHealthTracker != null) modelRuntimeHealthTracker.recordApiFailure(responsesUrl, modelName, e);
            recordRequestHttpExchange(
                    messages,
                    requestJson,
                    cancelled ? "cancelled" : "failed",
                    failureClassLabel(failureClass),
                    cancelled ? "cancelled" : "error",
                    null,
                    null,
                    clientHttpExchangeObserved,
                    false,
                    elapsedMs(startedNanos));
            if (cancelled) {
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                Thread.currentThread().interrupt();
                throw new RuntimeException("OpenAI Responses call interrupted", e);
            }
            log.warn("OpenAI /responses failed: modelHash={} failureReason={} errorType={}",
                    com.example.lms.trace.SafeRedactor.hashValue(modelName),
                    "responses-route-error",
                    com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"));
            if (typedRouterFailures) {
                throw typedProviderFailure("responses_route_failure", failureClass);
            }
            String msg = ModelGuardSupport.buildExpectedFailureMessage(modelName, "/v1/responses", "ROUTE_RESPONSES")
                    + "error: responses-route-error\n";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(msg))
                    .build();
        }
    }

    private static void validateOAuthEndpoint(String url) {
        java.net.URI uri = java.net.URI.create(url);
        boolean official = "https://api.openai.com/v1/responses".equals(url);
        boolean fixture = "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() > 0 && "/v1/responses".equals(uri.getPath());
        if ((!official && !fixture) || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("chatgpt_oauth_endpoint_not_allowed");
    }

    private static LlmResponseTerminalException oauthFailure(String reason, LlmFailureClass failure, String code) {
        return new LlmResponseTerminalException(reason, failure, null, null, "failed", null, code);
    }

    private String safeProviderCode(com.fasterxml.jackson.databind.JsonNode node) {
        String code = node.path("code").asText(null);
        return code != null && code.matches("[a-z0-9_]{1,64}") ? code : null;
    }

    /** Accepted runs use network read-stall protection, renewed by bytes including SSE heartbeats. */
    private ChatResponse chatOAuth(List<ChatMessage> messages) {
        boolean acceptedRun = ChatRunExecutionContext.isAcceptedExecution();
        long started = System.nanoTime();
        String requestHash = com.example.lms.trace.SafeRedactor.hashValueOrPreserve(org.slf4j.MDC.get("x-request-id"));
        OAuthPhase phase = new OAuthPhase(started, requestHash);
        phase.mark("adapter_entry");
        try (var wait = ChatRunExecutionContext.interruptibleCall("jdk_http")) {
            if (timeoutMs <= 0) throw oauthFailure("chatgpt_oauth_deadline_exhausted", LlmFailureClass.TIMEOUT_SOFT, null);
            List<Map<String, Object>> input = responsesInput(messages);
            Map<String, Object> payload = OpenAiEndpointCompatibility.chatGptResponsesPayload(modelName, input);
            String access = oauthAccessToken.get();
            if (ConfigValueGuards.isMissing(access))
                throw oauthFailure("chatgpt_oauth_credential_unavailable", LlmFailureClass.AUTH_MISSING, null);
            long remainingMs = timeoutMs - java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (!acceptedRun && remainingMs <= 0)
                throw oauthFailure("chatgpt_oauth_deadline_exhausted", LlmFailureClass.TIMEOUT_SOFT, null);
            OAuthStream stream = new OAuthStream();
            phase.mark("http_start");
            client.post().uri(responsesUrl).header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                    .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(payload)
                    .exchangeToFlux(response -> {
                        phase.httpStatus = response.statusCode().value();
                        String rawType = response.headers().asHttpHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
                        phase.contentType = safeOAuthContentType(rawType);
                        phase.mark("http_headers");
                        if (response.statusCode().isError()) return response.createException().flatMapMany(reactor.core.publisher.Flux::error);
                        var observed = response.mutate().body(bytes -> bytes.doOnNext(buffer -> {
                            if (phase.firstByte.compareAndSet(false, true)) phase.mark("http_first_byte");
                        })).build();
                        MediaType type;
                        try { type = rawType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(rawType); }
                        catch (IllegalArgumentException invalid) {
                            return oauthUnexpectedBody(observed);
                        }
                        if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type))
                            return observed.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
                        boolean json = "application".equals(type.getType())
                                && ("json".equals(type.getSubtype()) || type.getSubtype().endsWith("+json"));
                        if (json) return oauthJsonBody(observed.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class), stream, phase);
                        if (rawType == null || MediaType.TEXT_PLAIN.isCompatibleWith(type)
                                || MediaType.APPLICATION_OCTET_STREAM.isCompatibleWith(type))
                            return oauthSniffedBody(observed, stream, phase);
                        return oauthUnexpectedBody(observed);
                    })
                    .doOnNext(event -> {
                        if (phase.firstEvent.compareAndSet(false, true)) phase.mark("sse_first_event");
                        stream.accept(event);
                        if (!stream.deltas.isEmpty() && phase.firstDelta.compareAndSet(false, true)) phase.mark("sse_first_delta");
                        if (stream.terminal) phase.mark("terminal");
                    }).takeUntil(event -> stream.terminal)
                    .then().transform(completion -> acceptedRun ? completion
                            : completion.timeout(Duration.ofMillis(remainingMs))).block();
            if (!stream.terminal)
                throw oauthFailure("chatgpt_oauth_stream_interrupted", LlmFailureClass.PROVIDER_ERROR, null);
            return oauthResponse(stream.completedJson);
        } catch (LlmResponseTerminalException terminal) {
            observeOauthTerminal(terminal);
            throw terminal;
        } catch (WebClientResponseException http) {
            String code = null;
            try { code = safeProviderCode(mapper.readTree(http.getResponseBodyAsString()).path("error")); }
            catch (Exception ignored) { /* Admission may have a detail-only or non-JSON body. */ }
            var terminal = oauthFailure("chatgpt_oauth_http_" + http.getStatusCode().value(), FAILURE_CLASSIFIER.classify(http), code);
            observeOauthTerminal(terminal);
            throw terminal;
        } catch (Exception error) {
            if (error instanceof InterruptedException || Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
            Throwable cause = error;
            boolean deadline = false;
            boolean readStall = false;
            for (int i = 0; cause != null && i < 8; i++, cause = cause.getCause()) {
                if (cause instanceof io.netty.handler.timeout.ReadTimeoutException) { readStall = true; break; }
                if (cause instanceof java.util.concurrent.TimeoutException) { deadline = true; break; }
            }
            String reason = readStall ? "chatgpt_oauth_read_stall"
                    : deadline ? "chatgpt_oauth_deadline_exhausted" : "chatgpt_oauth_request_failed";
            phase.mark(readStall ? phase.httpStatus > 0 ? "read_stall" : "before_headers_timeout"
                    : deadline ? phase.httpStatus > 0 ? "awaiting_terminal_timeout" : "before_headers_timeout" : "transport_error");
            if (readStall) {
                TraceStore.put("chatgpt.oauth.timeoutSource", "OpenAiResponsesChatModel.responseTimeout");
            }
            var terminal = oauthFailure(reason, (deadline || readStall) ? LlmFailureClass.TIMEOUT_SOFT : FAILURE_CLASSIFIER.classify(error), null);
            observeOauthTerminal(terminal);
            throw terminal;
        } finally {
            TraceStore.put("chatgpt.oauth.phase", phase.last);
            TraceStore.put("chatgpt.oauth.httpStatus", phase.httpStatus);
            TraceStore.put("chatgpt.oauth.firstByteObserved", phase.firstByte.get());
            TraceStore.put("chatgpt.oauth.firstEventObserved", phase.firstEvent.get());
            TraceStore.put("chatgpt.oauth.firstDeltaObserved", phase.firstDelta.get());
        }
    }

    private ChatResponse oauthResponse(String json) {
        String text = extractAssistantText(json);
        ResponseState state = responseState(json, StringUtils.hasText(text));
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().id(responseId(json))
                .modelName(extractModelName(json)).tokenUsage(extractTokenUsage(json)).finishReason(state.finishReason()).build();
        if (state.reason() != null)
            throw new LlmResponseTerminalException(state.reason(), state.failureClass(), null, metadata,
                    state.status(), state.incompleteReason(), state.providerCode());
        ChatResponse observed = ChatResponse.builder().aiMessage(AiMessage.from(text)).metadata(metadata).build();
        com.example.lms.dto.GenerationObservation.capture(observed, "chatgpt_oauth",
                com.example.lms.trace.SafeRedactor.hashValue(responsesUrl), 0, null).store();
        return observed;
    }

    private static String safeOAuthContentType(String rawType) {
        if (rawType == null) return "absent";
        String[] parts = rawType.split(";", 2)[0].toLowerCase(Locale.ROOT).split("/", 2);
        if (parts.length != 2) return "invalid";
        String safe = parts[0].replaceAll("[^a-z0-9.+-]", "") + "/"
                + parts[1].replaceAll("[^a-z0-9.+-]", "");
        return safe.substring(0, Math.min(64, safe.length()));
    }

    private reactor.core.publisher.Flux<ServerSentEvent<String>> oauthUnexpectedBody(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        // Consume/release one buffer then cancel; an unsupported streaming body need not reach EOF.
        return response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class).take(1)
                .doOnNext(org.springframework.core.io.buffer.DataBufferUtils::release)
                .thenMany(reactor.core.publisher.Flux.error(
                        oauthFailure("chatgpt_oauth_unexpected_content_type", LlmFailureClass.PROVIDER_ERROR, null)));
    }

    private reactor.core.publisher.Flux<ServerSentEvent<String>> oauthJsonBody(
            reactor.core.publisher.Flux<org.springframework.core.io.buffer.DataBuffer> body, OAuthStream stream, OAuthPhase phase) {
        return org.springframework.core.io.buffer.DataBufferUtils.join(body, OAuthStream.MAX_CHARS * 4)
                .onErrorMap(org.springframework.core.io.buffer.DataBufferLimitException.class,
                        limit -> oauthFailure("chatgpt_oauth_stream_limit", LlmFailureClass.PROVIDER_ERROR, null))
                .map(buffer -> {
                    try { return buffer.toString(StandardCharsets.UTF_8); }
                    finally { org.springframework.core.io.buffer.DataBufferUtils.release(buffer); }
                }).defaultIfEmpty("")
                .doOnNext(json -> {
                    if (json.length() > OAuthStream.MAX_CHARS)
                        throw oauthFailure("chatgpt_oauth_stream_limit", LlmFailureClass.PROVIDER_ERROR, null);
                    stream.completedJson = json;
                    stream.terminal = true;
                    phase.mark("terminal");
                }).thenMany(reactor.core.publisher.Flux.empty());
    }

    private reactor.core.publisher.Flux<ServerSentEvent<String>> oauthSniffedBody(
            org.springframework.web.reactive.function.client.ClientResponse response, OAuthStream stream, OAuthPhase phase) {
        // Only a bounded prefix is inspected; byte copies release pooled buffers before sniff/cancel.
        StringBuilder prefix = new StringBuilder(64);
        return response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class).map(buffer -> {
            try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return bytes;
            } finally { org.springframework.core.io.buffer.DataBufferUtils.release(buffer); }
        }).bufferUntil(bytes -> {
            for (byte value : bytes) {
                if (prefix.length() == 64) break;
                prefix.append((char) (value & 0xff));
            }
            String lead = prefix.toString().stripLeading();
            return lead.startsWith("{") || lead.startsWith("event:") || lead.startsWith("data:")
                    || prefix.length() == 64
                    || !("event:".startsWith(lead) || "data:".startsWith(lead));
        }).switchOnFirst((signal, chunks) -> {
            if (signal.isOnError()) return reactor.core.publisher.Flux.error(signal.getThrowable());
            if (!signal.hasValue()) return reactor.core.publisher.Flux.error(
                    oauthFailure("chatgpt_oauth_stream_required", LlmFailureClass.PROVIDER_ERROR, null));
            String lead = prefix.toString().stripLeading();
            var body = chunks.concatMapIterable(group -> group)
                    .<org.springframework.core.io.buffer.DataBuffer>map(bytes -> org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(bytes));
            if (lead.startsWith("{")) return oauthJsonBody(body, stream, phase);
            if (lead.startsWith("event:") || lead.startsWith("data:"))
                return response.mutate().headers(headers -> headers.setContentType(MediaType.TEXT_EVENT_STREAM))
                        .body(body).build().bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
            return reactor.core.publisher.Flux.error(
                    oauthFailure("chatgpt_oauth_unexpected_content_type", LlmFailureClass.PROVIDER_ERROR, null));
        });
    }

    private static final class OAuthPhase {
        private final long started;
        private final String requestHash;
        private final java.util.concurrent.atomic.AtomicBoolean firstByte = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean firstEvent = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean firstDelta = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile int httpStatus;
        private String contentType = "absent";
        private volatile String last = "adapter_entry";
        OAuthPhase(long started, String requestHash) { this.started = started; this.requestHash = requestHash; }
        void mark(String stage) {
            last = stage;
            log.info("[plan9-oauth-phase] phase={} count=1 requestHash={} atEpochMs={} elapsedMs={} httpStatus={}{}",
                    stage, requestHash, System.currentTimeMillis(), (System.nanoTime()-started)/1_000_000L, httpStatus,
                    "http_headers".equals(stage) ? " contentType=" + contentType : "");
        }
    }

    private void observeOauthTerminal(LlmResponseTerminalException terminal) {
        ChatRunExecutionContext run = ChatRunExecutionContext.current();
        log.info("[AWX] timeoutSource=OpenAiResponsesChatModel.chatOAuth phase=provider_terminal runHash={} requestHash={} reasonCode={} failureClass={} action=no_replay",
                run == null ? "not_observed" : run.redactedRunIdentity(),
                SafeRedactor.hashValueOrPreserve(org.slf4j.MDC.get("x-request-id")),
                terminal.reasonCode(), terminal.failureClass().name());
        try { oauthTerminalObserver.accept(terminal); }
        catch (RuntimeException unavailable) {
            TraceStore.put("chatgpt.oauth.terminalObserver", "evidence_needed");
        }
    }

    private final class OAuthStream {
        private static final int MAX_CHARS = 2_000_000;
        private int chars, events;
        private final StringBuilder deltas = new StringBuilder();
        private boolean terminal;
        private String completedJson;

        void accept(ServerSentEvent<String> event) {
            String data = event.data();
            if (++events > 10_000 || (data != null && (chars += data.length()) > MAX_CHARS))
                throw oauthFailure("chatgpt_oauth_stream_limit", LlmFailureClass.PROVIDER_ERROR, null);
            if (!StringUtils.hasText(data)) return;
            try {
                var node = mapper.readTree(data);
                String type = node.path("type").asText("");
                if (type.isBlank() || (StringUtils.hasText(event.event()) && !type.equals(event.event())))
                    throw oauthFailure("chatgpt_oauth_invalid_event", LlmFailureClass.PROVIDER_ERROR, null);
                switch (type) {
                    case "response.output_text.delta" -> {
                        if (!node.path("delta").isTextual())
                            throw oauthFailure("chatgpt_oauth_invalid_event", LlmFailureClass.PROVIDER_ERROR, null);
                        deltas.append(node.path("delta").asText());
                    }
                    case "response.completed" -> {
                        var response = node.path("response");
                        if (!response.isObject() || !"completed".equals(response.path("status").asText()))
                            throw oauthFailure("chatgpt_oauth_invalid_completion", LlmFailureClass.PROVIDER_ERROR, null);
                        var copy = ((com.fasterxml.jackson.databind.node.ObjectNode) response).deepCopy();
                        if (!StringUtils.hasText(extractAssistantText(copy.toString())) && !deltas.isEmpty())
                            copy.put("output_text", deltas.toString());
                        completedJson = copy.toString(); terminal = true;
                    }
                    case "response.failed", "response.incomplete", "response.cancelled", "error" -> {
                        var response = node.path("response");
                        String code = safeProviderCode("error".equals(type) ? node : response.path("error"));
                        String status = "error".equals(type) ? "failed" : type.substring("response.".length());
                        String incomplete = response.path("incomplete_details").path("reason").asText("");
                        if (!"max_output_tokens".equals(incomplete) && !"content_filter".equals(incomplete)) incomplete = null;
                        String json = response.toString();
                        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                                .id(responseId(json)).modelName(extractModelName(json))
                                .tokenUsage(extractTokenUsage(json))
                                .finishReason(responseState(json, false).finishReason()).build();
                        throw new LlmResponseTerminalException("chatgpt_oauth_" + type.replace('.', '_'),
                                "response.cancelled".equals(type) ? LlmFailureClass.CANCELLED_NEUTRAL : LlmFailureClass.PROVIDER_ERROR,
                                null, metadata, status, incomplete, code);
                    }
                    default -> { /* Forward-compatible nonterminal lifecycle events. */ }
                }
            } catch (LlmResponseTerminalException terminal) { throw terminal; }
            catch (Exception malformed) {
                throw oauthFailure("chatgpt_oauth_invalid_event", LlmFailureClass.PROVIDER_ERROR, null);
            }
        }
    }

    private Map<String, Object> responsesPayload(List<Map<String, Object>> input, Integer requestMaxOutputTokens) {
        Integer effectiveMaxOutputTokens = requestMaxOutputTokens != null && requestMaxOutputTokens > 0
                ? requestMaxOutputTokens
                : maxOutputTokens;
        Map<String, Object> payload = OpenAiEndpointCompatibility.responsesPayload(
                modelName, null, effectiveMaxOutputTokens, null);
        payload.put("input", input);
        if (reasoningEffort != null) {
            payload.put("reasoning", Map.of("effort", reasoningEffort));
        }
        return payload;
    }

    private static LlmGatewayException unsupportedInput(String reason) {
        return new LlmGatewayException("Unsupported Responses input: " + reason,
                LlmFailureClass.BAD_REQUEST, reason);
    }

    private static List<Map<String, Object>> responsesInput(List<ChatMessage> messages) {
        List<Map<String, Object>> input = new java.util.ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage
                    || (message instanceof AiMessage ai && ai.hasToolExecutionRequests())) {
                throw unsupportedInput("responses_tools_unsupported");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            if (message instanceof SystemMessage system) {
                item.put("role", "system");
                item.put("content", system.text());
            } else if (message instanceof AiMessage ai) {
                item.put("role", "assistant");
                item.put("content", ai.text());
            } else if (message instanceof UserMessage user) {
                item.put("role", "user");
                List<Map<String, Object>> content = new java.util.ArrayList<>();
                for (var part : user.contents()) {
                    if (!(part instanceof TextContent text)) {
                        throw unsupportedInput("responses_content_unsupported");
                    }
                    Map<String, Object> textPart = new LinkedHashMap<>();
                    textPart.put("type", "input_text");
                    textPart.put("text", text.text());
                    content.add(textPart);
                }
                item.put("content", content);
            } else {
                throw unsupportedInput("responses_content_unsupported");
            }
            input.add(item);
        }
        return input;
    }

    private record ResponseState(String status, String reason, FinishReason finishReason,
            LlmFailureClass failureClass, String incompleteReason, String providerCode) {}

    private ResponseState responseState(String json, boolean hasText) {
        try {
            var root = mapper.readTree(json);
            if (root == null || !root.isObject() || !root.path("status").isTextual()) {
                return contractError();
            }
            String status = root.path("status").asText();
            String incomplete = null;
            String reason = null;
            FinishReason finish = null;
            LlmFailureClass failure = LlmFailureClass.NONE;
            switch (status) {
                case "completed" -> finish = FinishReason.STOP;
                case "incomplete" -> {
                    incomplete = root.path("incomplete_details").path("reason").asText();
                    if ("max_output_tokens".equals(incomplete)) {
                        reason = "output_limit_reached"; finish = FinishReason.LENGTH;
                    } else if ("content_filter".equals(incomplete)) {
                        reason = "content_filter"; finish = FinishReason.CONTENT_FILTER;
                    } else return contractError();
                }
                case "failed" -> { reason = "responses_failed"; failure = LlmFailureClass.PROVIDER_ERROR; }
                case "cancelled" -> { reason = "responses_cancelled"; failure = LlmFailureClass.CANCELLED_NEUTRAL; }
                case "queued", "in_progress" -> reason = "responses_not_complete";
                default -> { return contractError(); }
            }
            if ("completed".equals(status) || "incomplete".equals(status)) {
                for (var item : root.path("output")) {
                    if ("function_call".equals(item.path("type").asText())) {
                        return new ResponseState(status, "responses_tools_unsupported", null,
                                LlmFailureClass.BAD_REQUEST, incomplete, null);
                    }
                    for (var content : item.path("content")) {
                        if ("refusal".equals(content.path("type").asText())) {
                            reason = "refusal"; finish = FinishReason.CONTENT_FILTER;
                        }
                    }
                }
            }
            if (reason == null && !hasText) return contractError();
            String code = root.path("error").path("code").asText(null);
            if (code != null && !code.matches("[a-z0-9_]{1,64}")) code = null;
            return new ResponseState(status, reason, finish, failure, incomplete, code);
        } catch (Exception invalid) {
            return contractError();
        }
    }

    private static ResponseState contractError() {
        return new ResponseState("unknown", "responses_contract_error", null,
                LlmFailureClass.PROVIDER_ERROR, null, null);
    }

    private String responseId(String json) {
        try {
            String id = mapper.readTree(json).path("id").asText(null);
            return id != null && id.matches("[A-Za-z0-9_-]{1,128}") ? id : null;
        } catch (Exception invalid) { return null; }
    }

    private static LlmGatewayException typedProviderFailure(
            String reasonCode,
            LlmFailureClass failureClass) {
        return new LlmGatewayException(
                "OpenAI Responses provider failure: " + reasonCode,
                failureClass == null ? LlmFailureClass.UNKNOWN : failureClass,
                reasonCode);
    }

    private static String httpReasonCode(int statusCode) {
        return statusCode >= 100 && statusCode <= 999
                ? "responses_http_" + statusCode
                : "responses_http_unknown";
    }

    private static String failureClassLabel(LlmFailureClass failureClass) {
        return (failureClass == null ? LlmFailureClass.UNKNOWN : failureClass)
                .name()
                .toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private String extractAssistantText(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            traceSuppressed("llm.responses.parse.invalid", null);
            traceInvalidResponsesJson(json);
            return null;
        }
        try {
            Map<String, Object> m = mapper.readValue(json, MAP);

            // 1) Some variants return output_text directly
            Object ot = m.get("output_text");
            if (ot instanceof String s && StringUtils.hasText(s)) {
                TraceStore.put("responsesModel.format", "RESPONSES_API");
                return s;
            }

            // 2) Common shape: output[] -> {type:"message", content:[{type:"output_text", text:"..."}]}
            Object out = m.get("output");
            if (out instanceof List<?> outList) {
                StringBuilder sb = new StringBuilder();
                for (Object o : outList) {
                    if (!(o instanceof Map<?, ?> mm)) {
                        continue;
                    }
                    Object type = mm.get("type");
                    if (!("message".equals(type))) {
                        continue;
                    }
                    Object content = mm.get("content");
                    if (!(content instanceof List<?> contentList)) {
                        continue;
                    }
                    for (Object c : contentList) {
                        if (!(c instanceof Map<?, ?> cm)) {
                            continue;
                        }
                        Object cType = cm.get("type");
                        if (!("output_text".equals(cType))) {
                            continue;
                        }
                        Object text = cm.get("text");
                        if (text instanceof String ts && StringUtils.hasText(ts)) {
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            sb.append(ts);
                        }
                    }
                }
                if (sb.length() > 0) {
                    TraceStore.put("responsesModel.format", "RESPONSES_API");
                    return sb.toString();
                }
            }

            Object choices = m.get("choices");
            if (choices instanceof List<?> choiceList) {
                for (Object choice : choiceList) {
                    if (!(choice instanceof Map<?, ?> choiceMap)) {
                        continue;
                    }
                    Object message = choiceMap.get("message");
                    if (!(message instanceof Map<?, ?> messageMap)) {
                        continue;
                    }
                    Object content = messageMap.get("content");
                    if (content instanceof String s && StringUtils.hasText(s)) {
                        TraceStore.put("responsesModel.format", "CHAT_COMPAT");
                        return s;
                    }
                }
            }
            TraceStore.put("responsesModel.format", "UNKNOWN");
            TraceStore.put("responsesModel.parseWarn", "no_text_extracted");
        } catch (Exception ex) {
            TraceStore.put("responsesModel.parseWarn", "responses_format_failed:"
                    + SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            traceSuppressed("llm.responses.parse.invalid", ex);
            traceInvalidResponsesJson(json);
        }
        return null;
    }

    private TokenUsage extractTokenUsage(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            Map<String, Object> root = mapper.readValue(json, MAP);
            Object rawUsage = root.get("usage");
            if (!(rawUsage instanceof Map<?, ?> usage)) {
                return null;
            }
            Integer input = nonNegativeInt(usage.get("input_tokens"));
            Integer output = nonNegativeInt(usage.get("output_tokens"));
            Integer total = nonNegativeInt(usage.get("total_tokens"));
            if (input == null && output == null && total == null) return null;
            var builder = dev.langchain4j.model.openai.OpenAiTokenUsage.builder()
                    .inputTokenCount(input).outputTokenCount(output).totalTokenCount(total);
            if (usage.get("input_tokens_details") instanceof Map<?, ?> details) {
                Integer cached = nonNegativeInt(details.get("cached_tokens"));
                if (cached != null && (input == null || cached <= input)) builder.inputTokensDetails(
                        dev.langchain4j.model.openai.OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(cached).build());
            }
            return builder.build();
        } catch (Exception ignored) {
            traceSuppressed("llm.responses.usage.parse", ignored);
            return null;
        }
    }

    private String extractModelName(String json) {
        try {
            Object value = mapper.readValue(json, MAP).get("model");
            return value instanceof String name && name.matches("[A-Za-z0-9_.:/-]{1,96}") ? name : null;
        } catch (Exception ignored) { return null; }
    }

    private void recordRequestHttpExchange(
            List<ChatMessage> messages,
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
            String httpRequestBodyHash = exactSha256(observedHttpRequest);
            int httpRequestBodyUtf8ByteCount = utf8Length(observedHttpRequest);
            String httpResponseBodyHash = exactSha256(observedHttpResponse);
            int httpResponseBodyUtf8ByteCount = utf8Length(observedHttpResponse);
            modelRuntimeHealthTracker.recordRequestAttemptEvidence(
                    timelineId,
                    requestAttemptRole,
                    requestAttemptRoute == null
                            ? modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                                    "openai_responses", modelName, responsesUrl, "openai_responses")
                            : requestAttemptRoute,
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
                    httpRequestBodyHash,
                    httpRequestBodyUtf8ByteCount,
                    httpResponseBodyHash,
                    httpResponseBodyUtf8ByteCount,
                    true,
                    clientHttpExchangeObserved,
                    clientHttpResponseObserved,
                    false,
                    false,
                    appResponseObserved);
            if ("success".equals(outcome)
                    && appResponseObserved
                    && clientHttpExchangeObserved
                    && clientHttpResponseObserved) {
                GatewayProviderReceipt receipt = trustedGatewayProviderReceipt(
                        responsesUrl, modelName, responseBody);
                if (receipt != null) {
                    modelRuntimeHealthTracker.recordAttestedVercelGatewayReceipt(
                            timelineId,
                            responsesUrl,
                            "openai_responses",
                            receipt.provider(),
                            receipt.statusCode(),
                            receipt.totalProviderAttemptCount(),
                            httpRequestBodyHash,
                            httpRequestBodyUtf8ByteCount,
                            httpResponseBodyHash,
                            httpResponseBodyUtf8ByteCount);
                }
            }
        } catch (RuntimeException evidenceFailure) {
            traceSuppressed("llm.responses.requestHttpExchange", evidenceFailure);
        }
    }

    /**
     * Parse only Vercel's documented AI Gateway routing metadata shape. The
     * OpenResponses standard itself does not promise this extension, so absence
     * or any inconsistency deliberately returns no receipt.
     */
    static GatewayProviderReceipt trustedGatewayProviderReceipt(
            String responsesUrl,
            String modelName,
            String responseBody) {
        if (!VERCEL_AI_GATEWAY_RESPONSES_URL.equals(responsesUrl)
                || modelName == null
                || !modelName.trim().toLowerCase(Locale.ROOT).startsWith(VERCEL_ZAI_PROVIDER + "/")
                || !StringUtils.hasText(responseBody)) {
            return null;
        }
        try {
            Map<String, Object> root = new ObjectMapper().readValue(responseBody, MAP);
            if (!(root.get("providerMetadata") instanceof Map<?, ?> providerMetadata)
                    || !(providerMetadata.get("gateway") instanceof Map<?, ?> gateway)
                    || !(gateway.get("routing") instanceof Map<?, ?> routing)
                    || !(routing.get("attempts") instanceof List<?> attempts)) {
                return null;
            }
            String finalProvider = canonicalGatewayProvider(routing.get("finalProvider"));
            Integer totalProviderAttemptCount = nonNegativeInt(routing.get("totalProviderAttemptCount"));
            if (!VERCEL_ZAI_PROVIDER.equals(finalProvider)
                    || totalProviderAttemptCount == null
                    || totalProviderAttemptCount <= 0
                    || totalProviderAttemptCount > MAX_GATEWAY_PROVIDER_ATTEMPTS
                    || totalProviderAttemptCount != attempts.size()) {
                return null;
            }
            String successfulProvider = null;
            int successfulStatusCode = 0;
            int successCount = 0;
            for (Object rawAttempt : attempts) {
                if (!(rawAttempt instanceof Map<?, ?> attempt)
                        || !(attempt.get("success") instanceof Boolean success)) {
                    return null;
                }
                String attemptProvider = canonicalGatewayProvider(attempt.get("provider"));
                if (attemptProvider == null) {
                    return null;
                }
                if (success) {
                    Integer statusCode = nonNegativeInt(attempt.get("statusCode"));
                    if (statusCode == null || statusCode != 200) {
                        return null;
                    }
                    successCount++;
                    successfulProvider = attemptProvider;
                    successfulStatusCode = statusCode;
                }
            }
            if (successCount != 1 || !finalProvider.equals(successfulProvider)) {
                return null;
            }
            return new GatewayProviderReceipt(
                    successfulProvider, successfulStatusCode, totalProviderAttemptCount);
        } catch (Exception ignored) {
            traceSuppressed("llm.responses.gatewayReceipt.parse", ignored);
            return null;
        }
    }

    private static String canonicalGatewayProvider(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9][a-z0-9_-]{0,39}") ? normalized : null;
    }

    record GatewayProviderReceipt(String provider, int statusCode, int totalProviderAttemptCount) {
    }

    private Map<String, Object> requestAttemptOptionEnvelope() {
        Map<String, Object> ownedOptions = new LinkedHashMap<>();
        ownedOptions.put("temperature", null);
        ownedOptions.put("topP", null);
        ownedOptions.put("maxOutputTokens", maxOutputTokens);
        ownedOptions.put("timeoutMs", Math.max(MIN_RESPONSES_TIMEOUT_MS, timeoutMs));
        ownedOptions.put("maxRetries", 0);
        ownedOptions.put("fallbackEnabled", false);
        ownedOptions.put("fallbackKey", null);
        if (reasoningEffort != null) {
            ownedOptions.put("reasoningEffort", reasoningEffort);
        }
        return ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                "openai", modelName, "openai_responses", ownedOptions);
    }

    private void recordDisabledAttempt(List<ChatMessage> messages, String localUxMessage) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        ModelRuntimeHealthTracker.RequestAttemptRoute route = requestAttemptRoute == null
                ? modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                        "openai_responses", modelName, responsesUrl, "openai_responses")
                : requestAttemptRoute;
        modelRuntimeHealthTracker.expectedFailureAttemptEvidence(
                        requestAttemptRole,
                        route,
                        requestAttemptOptionEnvelope())
                .record(messages, localUxMessage);
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String exactSha256(String value) {
        if (value == null) {
            return "hash:unknown";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) {
                hex.append(String.format("%02x", valueByte));
            }
            return "sha256:" + hex;
        } catch (Exception failure) {
            traceSuppressed("llm.responses.evidence.sha256", failure);
            return "hash:unknown";
        }
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Integer nonNegativeInt(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        long candidate = number.longValue();
        return candidate >= 0L && candidate <= Integer.MAX_VALUE ? (int) candidate : null;
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        try {
            TraceStore.put(stage + ".suppressed", true);
            TraceStore.put(stage + ".errorType", failure == null ? "unknown"
                    : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown"));
        } catch (RuntimeException traceFailure) {
            log.debug("OpenAI /responses suppressed trace skipped stage={} errorType={}",
                    SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                    SafeRedactor.traceLabelOrFallback(traceFailure.getClass().getSimpleName(), "unknown"));
        }
    }

    private static void traceInvalidResponsesJson(String json) {
        TraceStore.put("llm.responses.parse.failed", true);
        TraceStore.put("llm.responses.parse.reason", "invalid_response_json");
        TraceStore.put("llm.responses.parse.bodyPresent", StringUtils.hasText(json));
        TraceStore.put("llm.responses.parse.bodyHash", SafeRedactor.hashValue(json));
        TraceStore.put("llm.responses.parse.bodyLength", json == null ? 0 : json.length());
        log.debug("OpenAI /responses parse skipped: reason={} bodyHash={}",
                "invalid_response_json",
                SafeRedactor.hashValue(json));
    }

    @Override
    public String toString() {
        return "OpenAiResponsesChatModel(modelHash=" + com.example.lms.trace.SafeRedactor.hashValue(modelName) + ")";
    }
}
