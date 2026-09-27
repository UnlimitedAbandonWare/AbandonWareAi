package com.example.lms.llm.gateway;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmGatewayFailureClassifierTest {

    private final LlmGatewayFailureClassifier classifier = new LlmGatewayFailureClassifier();

    @Test
    void operatorOffReasonIsTerminalWithoutMakingEveryGpuOrDisabledFailureTerminal() {
        Throwable off = new LlmGatewayException("synthetic operator OFF", LlmFailureClass.DISABLED, "route_disabled");
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(off));
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(new IllegalStateException("outer", off)));
        assertFalse(LlmGatewayFailureClassifier.hasNonReplayableReason(
                new LlmGatewayException("ordinary disabled provider", LlmFailureClass.DISABLED)));
        assertFalse(LlmGatewayFailureClassifier.hasNonReplayableReason(
                new LlmGatewayException("device failure", LlmFailureClass.GPU_DEVICE_LOST, "gpu_device_lost")));
    }

    @Test
    void structuredQuotaIsTerminalAcrossHttpTypesAndWrappedCauses() {
        for (String prefix : List.of("free_limit_reached", "free_tier_requires_payment",
                "key_daily_cap", "insufficient_credits")) {
            String body = "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"" + prefix + ": synthetic limit\"}}";
            Throwable http = new dev.langchain4j.exception.HttpException(429, body);
            Throwable web = WebClientResponseException.create(429, "Too Many Requests",
                    HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            for (Throwable failure : List.of(http, web,
                    new RuntimeException("outer", http), new RuntimeException("outer", web))) {
                assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(failure), prefix);
                assertFalse(com.example.lms.llm.LlmErrorClassifier.classify(failure).retryable(), prefix);
                assertEquals(LlmFailureClass.RATE_LIMIT_COOLDOWN, classifier.classify(failure));
            }
        }
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(new LlmGatewayException(
                "categorical quota failure", LlmFailureClass.RATE_LIMIT_COOLDOWN, "insufficient_quota")));
    }

    @Test
    void providerSpendLimitCodesAreTerminalRegardlessOfHttpStatus() {
        for (String code : LlmGatewayFailureClassifier.QUOTA_ERROR_CODES) {
            String body = "{\"error\":{\"code\":\"" + code + "\"}}";
            for (int status : List.of(400, 402, 429, 503)) {
                Throwable http = new dev.langchain4j.exception.HttpException(status, body);
                Throwable web = WebClientResponseException.create(status, "stub",
                        HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
                for (Throwable failure : List.of(http, web, new RuntimeException("outer", http))) {
                    assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(failure), code + "/" + status);
                    assertFalse(com.example.lms.llm.LlmErrorClassifier.classify(failure).retryable(), code + "/" + status);
                }
                assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(new LlmGatewayException(
                        "categorical quota failure", LlmFailureClass.RATE_LIMIT_COOLDOWN, code)), code);
            }
        }
    }

    @Test
    void quotaLookalikesRemainUnconfirmedWithoutAnExactStructured429Code() {
        List<String> bodies = List.of("", "quota exceeded", "{malformed",
                "{\"error\":{\"message\":\"insufficient_quota\"}}",
                "{\"code\":\"insufficient_quota\"}",
                "{\"error\":{\"code\":\"unavailable_route\"}}",
                "{\"error\":{\"code\":\"gateway_overloaded\"}}",
                "{\"error\":{\"code\":\"INSUFFICIENT_QUOTA\"}}",
                "{\"error\":{\"code\":\"insufficient_quota_extra\"}}",
                "{\"error\":{\"code\":[\"insufficient_quota\"]}}",
                "{\"error\":{\"code\":\"insufficient_quota\"}} trailing",
                "{\"error\":{\"code\":\"insufficient_quota\"}} {}",
                "{\"error\":{\"code\":\"unavailable_route\",\"code\":\"insufficient_quota\"}}",
                "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"" + "x".repeat(20_000) + "\"}}");
        for (int index = 0; index < bodies.size(); index++) {
            String body = bodies.get(index);
            Throwable http = new dev.langchain4j.exception.HttpException(429, body);
            Throwable web = WebClientResponseException.create(429, "Too Many Requests",
                    HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            assertFalse(LlmGatewayFailureClassifier.hasNonReplayableReason(http), "fixture=" + index);
            assertFalse(LlmGatewayFailureClassifier.hasNonReplayableReason(web), "fixture=" + index);
        }
        String quota = "{\"error\":{\"code\":\"insufficient_quota\"}}";
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(
                new dev.langchain4j.exception.HttpException(503, quota)));
        assertFalse(LlmGatewayFailureClassifier.hasNonReplayableReason(new RuntimeException(quota)));
    }

    @Test
    void quotaCauseWalkRetainsItsDepthAndCycleBounds() {
        Throwable quota = new dev.langchain4j.exception.HttpException(
                429, "{\"error\":{\"code\":\"insufficient_quota\"}}");
        Throwable atBoundary = quota;
        for (int depth = 0; depth < 19; depth++) atBoundary = new RuntimeException("layer", atBoundary);
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(atBoundary));
        assertFalse(LlmGatewayFailureClassifier.hasNonReplayableReason(new RuntimeException("outside", atBoundary)));
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertFalse(LlmGatewayFailureClassifier.hasNonReplayableReason(first));
    }

    @Test
    void classifiesRateLimitAndAuthWithoutHardBreakerForNeutralCancel() {
        assertEquals(LlmFailureClass.RATE_LIMIT_COOLDOWN,
                classifier.classify(WebClientResponseException.create(
                        429, "Too Many Requests", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));
        assertEquals(LlmFailureClass.AUTH_MISSING,
                classifier.classify(WebClientResponseException.create(
                        401, "Unauthorized", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));
        assertEquals(LlmFailureClass.CANCELLED_NEUTRAL,
                classifier.classify(new CancellationException("cancelled by caller")));
    }

    @Test
    void classifiesGatewayTimeoutAsSoftTimeout() {
        assertEquals(LlmFailureClass.TIMEOUT_SOFT,
                classifier.classify(WebClientResponseException.create(
                        504, "Gateway Timeout", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));
    }

    @Test
    void classifiesOpenCircuitBreakerAsSoftCircuitOpen() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("llm-gateway-test");
        CallNotPermittedException failure = CallNotPermittedException.createCallNotPermittedException(breaker);

        assertEquals(LlmFailureClass.SOFT_CIRCUIT_OPEN, classifier.classify(failure));
        assertEquals(LlmFailureClass.SOFT_CIRCUIT_OPEN,
                classifier.classify(new RuntimeException("circuit breaker is OPEN for llm gateway")));
    }

    @Test
    void classifiesRouteBlockingMessages() {
        assertEquals(LlmFailureClass.MODEL_MISSING, classifier.classify(new RuntimeException("model not found")));
        assertEquals(LlmFailureClass.VRAM_OOM, classifier.classify(new RuntimeException("CUDA VRAM OOM")));
        assertEquals(LlmFailureClass.TIMEOUT_SOFT, classifier.classify(new RuntimeException("request timeout")));
    }

    @Test
    void hardGpuDeviceLossSignatureIsNotCollapsedIntoGenericHealthDown() {
        WebClientResponseException failure = serverError(
                "runner failed: main_gpu unavailable because available devices: 0");

        assertEquals("GPU_DEVICE_LOST", classifier.classify(failure).name());
    }

    @Test
    void genericServerErrorDoesNotBecomeGpuDeviceLossWithoutSignature() {
        WebClientResponseException failure = serverError("internal server error");

        assertEquals(LlmFailureClass.HEALTH_DOWN, classifier.classify(failure));
    }

    @Test
    void vramOomServerErrorDoesNotQuarantineThePhysicalDevice() {
        WebClientResponseException failure = serverError("CUDA VRAM out of memory");

        assertEquals(LlmFailureClass.VRAM_OOM, classifier.classify(failure));
    }

    @Test
    void detectsTypedCancellationWithoutMessageHeuristicsOrInterruptMutation() {
        Thread.interrupted();
        try {
            assertTrue(LlmGatewayFailureClassifier.isCancellation(
                    new RuntimeException("outer",
                            new IllegalStateException("middle", new InterruptedException()))));
            assertTrue(LlmGatewayFailureClassifier.isCancellation(
                    new RuntimeException("outer", new CancellationException())));
            assertFalse(LlmGatewayFailureClassifier.isCancellation(
                    new RuntimeException("request cancelled by remote status")));
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void causeWalkIsBoundedBeforeAPathologicalDeepCancellationCause() {
        Throwable failure = new InterruptedException("too deep");
        for (int depth = 0; depth < 21; depth++) {
            failure = new RuntimeException("layer-" + depth, failure);
        }

        assertFalse(LlmGatewayFailureClassifier.isCancellation(failure));
        assertEquals(LlmFailureClass.UNKNOWN, classifier.classify(failure));
    }

    @Test
    void nestedInterruptedClassificationRemainsNeutralAndRestoresInterrupt() {
        Thread.interrupted();
        try {
            assertEquals(LlmFailureClass.CANCELLED_NEUTRAL,
                    classifier.classify(new RuntimeException(new InterruptedException())));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static WebClientResponseException serverError(String body) {
        return WebClientResponseException.create(
                500,
                "Internal Server Error",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
