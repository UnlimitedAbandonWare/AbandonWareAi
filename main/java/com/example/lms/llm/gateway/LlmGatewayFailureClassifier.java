package com.example.lms.llm.gateway;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import dev.langchain4j.exception.HttpException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

@Component
public class LlmGatewayFailureClassifier {

    private static final int MAX_CAUSE_DEPTH = 20;
    private static final int MAX_ERROR_BODY_CHARS = 16_384;
    /** Same spend/quota codes as ConversateApiCueService: body `error.code`, any HTTP status. */
    static final Set<String> QUOTA_ERROR_CODES = Set.of(
            "insufficient_quota",
            "credit_balance_exhausted",
            "spend_limit_exceeded",
            "blocked_api_access");
    private static final ObjectReader ERROR_READER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** A same-request retry must preserve these gateway decisions across outer layers. */
    public static boolean hasNonReplayableReason(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            if (current instanceof LlmResponseTerminalException) return true;
            if (current instanceof LlmGatewayException gatewayFailure) {
                String reason = gatewayFailure.reasonCode();
                if ("failover_exhausted".equals(reason)
                        || "tool_side_effect_started".equals(reason)
                        || "stream_error_after_partial".equals(reason)
                        || "timeout_after_partial".equals(reason)
                        || "context_limit_exceeded".equals(reason)
                        || "capability_mismatch".equals(reason)
                        || QUOTA_ERROR_CODES.contains(reason)
                        || "route_disabled".equals(reason)
                        || "responses_tools_unsupported".equals(reason)
                        || "responses_content_unsupported".equals(reason)
                        || "route_mapping_unconfirmed".equals(reason)) {
                    return true;
                }
            }
            if (current instanceof HttpException http
                    && hasQuotaErrorCode(http.statusCode(), http.getMessage())) {
                return true;
            }
            if (current instanceof WebClientResponseException web
                    && hasQuotaErrorCode(web.getRawStatusCode(), web.getResponseBodyAsString())) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return false;
    }

    private static boolean hasQuotaErrorCode(int status, String body) {
        if (body == null || body.isBlank() || body.length() > MAX_ERROR_BODY_CHARS) {
            return false;
        }
        try {
            JsonNode error = ERROR_READER.readTree(body);
            JsonNode code = error.path("error").path("code");
            return code.isTextual() && QUOTA_ERROR_CODES.contains(code.textValue());
        } catch (JsonProcessingException malformed) {
            // Unconfirmed/malformed provider output keeps the existing generic HTTP policy.
            // Neither the body nor the parser exception is retained or logged.
            return false;
        }
    }

    public LlmFailureClass classify(Throwable failure) {
        if (failure == null) {
            return LlmFailureClass.NONE;
        }
        Throwable t = failure;
        int depth = 0;
        while (t != null && depth++ < MAX_CAUSE_DEPTH) {
            LlmFailureClass direct = classifyOne(t);
            if (direct != LlmFailureClass.UNKNOWN) {
                return direct;
            }
            Throwable next = t.getCause();
            if (next == t) {
                break;
            }
            t = next;
        }
        return classifyMessage(failure.toString());
    }

    public static boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            if (current instanceof CancellationException || current instanceof InterruptedException) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    public LlmFailureClass classifyEmptyAfterException(Throwable failure) {
        LlmFailureClass classified = classify(failure);
        return classified == LlmFailureClass.NONE ? LlmFailureClass.UNKNOWN : classified;
    }

    private static LlmFailureClass classifyOne(Throwable t) {
        if (t instanceof LlmGatewayException gatewayFailure) {
            return gatewayFailure.failureClass();
        }
        if (t instanceof CancellationException) {
            return LlmFailureClass.CANCELLED_NEUTRAL;
        }
        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return LlmFailureClass.CANCELLED_NEUTRAL;
        }
        if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
            return LlmFailureClass.TIMEOUT_SOFT;
        }
        if (t instanceof CallNotPermittedException) {
            return LlmFailureClass.SOFT_CIRCUIT_OPEN;
        }
        if (t instanceof ConnectException || t instanceof UnknownHostException) {
            return LlmFailureClass.HEALTH_DOWN;
        }
        if (t instanceof HttpException httpException) {
            return classifyHttpStatus(httpException.statusCode(), httpException.getMessage());
        }
        if (t instanceof WebClientResponseException wcre) {
            return classifyHttpStatus(wcre.getRawStatusCode(), wcre.getResponseBodyAsString());
        }
        return classifyMessage(t.getMessage());
    }

    private static LlmFailureClass classifyHttpStatus(int status, String responseText) {
        if (hasQuotaErrorCode(status, responseText)) {
            return LlmFailureClass.RATE_LIMIT_COOLDOWN;
        }
        if (status == 401 || status == 403) {
            return LlmFailureClass.AUTH_MISSING;
        }
        if (status == 404) {
            return LlmFailureClass.MODEL_MISSING;
        }
        if (status == 429) {
            return LlmFailureClass.RATE_LIMIT_COOLDOWN;
        }
        if (status == 408 || status == 504) {
            return LlmFailureClass.TIMEOUT_SOFT;
        }
        if (status >= 500) {
            LlmFailureClass bodyClass = classifyMessage(responseText);
            if (bodyClass == LlmFailureClass.GPU_DEVICE_LOST
                    || bodyClass == LlmFailureClass.VRAM_OOM) {
                return bodyClass;
            }
            return LlmFailureClass.HEALTH_DOWN;
        }
        if (status >= 400) {
            LlmFailureClass bodyClass = classifyMessage(responseText);
            return bodyClass == LlmFailureClass.UNKNOWN
                    ? LlmFailureClass.BAD_REQUEST
                    : bodyClass;
        }
        return LlmFailureClass.UNKNOWN;
    }

    private static LlmFailureClass classifyMessage(String message) {
        if (message == null || message.isBlank()) {
            return LlmFailureClass.UNKNOWN;
        }
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("cancel")) {
            return LlmFailureClass.CANCELLED_NEUTRAL;
        }
        if (m.contains("rate limit") || m.contains("too many requests") || m.contains("http_429") || m.contains("429")) {
            return LlmFailureClass.RATE_LIMIT_COOLDOWN;
        }
        if (m.contains("timed out") || m.contains("timeout")) {
            return LlmFailureClass.TIMEOUT_SOFT;
        }
        if ((m.contains("circuit") || m.contains("breaker")) && (m.contains("open") || m.contains("not permitted"))) {
            return LlmFailureClass.SOFT_CIRCUIT_OPEN;
        }
        if (m.contains("unauthorized") || m.contains("forbidden") || m.contains("api key") || m.contains("auth_missing")
                || m.contains("missing key") || m.contains("owner token")) {
            return LlmFailureClass.AUTH_MISSING;
        }
        if (m.contains("model_not_found") || m.contains("model not found") || m.contains("not found")) {
            return LlmFailureClass.MODEL_MISSING;
        }
        if ((m.contains("main_gpu") && m.contains("available devices: 0"))
                || m.contains("gpu is lost")
                || m.contains("fallen off the bus")
                || m.contains("nvml_error_gpu_is_lost")) {
            return LlmFailureClass.GPU_DEVICE_LOST;
        }
        if (m.contains("oom") || m.contains("out of memory") || m.contains("vram")) {
            return LlmFailureClass.VRAM_OOM;
        }
        if (m.contains("connection refused") || m.contains("unknownhost") || m.contains("health_down")) {
            return LlmFailureClass.HEALTH_DOWN;
        }
        if (m.contains("cuda error") || m.contains("cuda_error") || m.contains("cuda runtime")
                || m.contains("device-side assert") || m.contains("illegal memory access")) {
            return LlmFailureClass.PROVIDER_ERROR;
        }
        if (m.contains("stream")) {
            return LlmFailureClass.STREAM_ERROR;
        }
        if (m.contains("response_model_mismatch")) {
            return LlmFailureClass.PROVIDER_ERROR;
        }
        if (m.contains("response_model_unverified")) {
            return LlmFailureClass.RESPONSE_MODEL_UNVERIFIED;
        }
        return LlmFailureClass.UNKNOWN;
    }
}
