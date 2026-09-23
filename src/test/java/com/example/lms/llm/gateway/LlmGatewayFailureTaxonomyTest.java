package com.example.lms.llm.gateway;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmGatewayFailureTaxonomyTest {

    private final LlmGatewayFailureClassifier classifier = new LlmGatewayFailureClassifier();

    @Test
    void nullFailureIsNoFailure() {
        assertEquals(LlmFailureClass.NONE, classifier.classify(null));
    }

    @Test
    void typedGatewayFailureKeepsItsCategory() {
        assertEquals(
                LlmFailureClass.CONTEXT_TOO_SMALL,
                classifier.classify(new LlmGatewayException(
                        "bounded",
                        LlmFailureClass.CONTEXT_TOO_SMALL,
                        "context_limit_exceeded")));
    }

    @Test
    void gpuIsLostSignatureIsHardDeviceLoss() {
        assertEquals(
                LlmFailureClass.GPU_DEVICE_LOST,
                classifier.classify(new RuntimeException("GPU is lost")));
    }

    @Test
    void fallenOffBusSignatureIsHardDeviceLoss() {
        assertEquals(
                LlmFailureClass.GPU_DEVICE_LOST,
                classifier.classify(new RuntimeException("device has fallen off the bus")));
    }

    @Test
    void nvmlLostEnumIsHardDeviceLoss() {
        assertEquals(
                LlmFailureClass.GPU_DEVICE_LOST,
                classifier.classify(new RuntimeException("NVML_ERROR_GPU_IS_LOST")));
    }

    @Test
    void connectionRefusedIsBackendDown() {
        assertEquals(
                LlmFailureClass.HEALTH_DOWN,
                classifier.classify(new ConnectException("connection refused")));
    }

    @Test
    void missingModelIsModelScopedFailure() {
        assertEquals(
                LlmFailureClass.MODEL_MISSING,
                classifier.classify(httpFailure(404, "model missing")));
    }

    @Test
    void overloadIsBackendDown() {
        assertEquals(
                LlmFailureClass.HEALTH_DOWN,
                classifier.classify(httpFailure(503, "overloaded")));
    }

    @Test
    void rateLimitGetsCooldownCategory() {
        assertEquals(
                LlmFailureClass.RATE_LIMIT_COOLDOWN,
                classifier.classify(httpFailure(429, "too many requests")));
    }

    @Test
    void authFailureIsNotCollapsedIntoBadRequest() {
        assertEquals(
                LlmFailureClass.AUTH_MISSING,
                classifier.classify(httpFailure(401, "unauthorized")));
    }

    @Test
    void socketTimeoutIsSoftTimeout() {
        assertEquals(
                LlmFailureClass.TIMEOUT_SOFT,
                classifier.classify(new SocketTimeoutException("read timed out")));
    }

    @Test
    void cancellationIsNeutral() {
        assertEquals(
                LlmFailureClass.CANCELLED_NEUTRAL,
                classifier.classify(new CancellationException("stop")));
    }

    @Test
    void openCircuitUsesDedicatedCategory() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("taxonomy-open");
        assertEquals(
                LlmFailureClass.SOFT_CIRCUIT_OPEN,
                classifier.classify(CallNotPermittedException.createCallNotPermittedException(breaker)));
    }

    @Test
    void badRequestIsNonReplayableButDoesNotDamageRouteHealth() {
        LlmFailureClass failureClass = classifier.classify(httpFailure(400, "invalid payload"));

        assertEquals(LlmFailureClass.BAD_REQUEST, failureClass);
        assertFalse(failureClass.routeBlocking());
        assertFalse(failureClass.hardBreakerFailure());
    }

    private static WebClientResponseException httpFailure(int status, String body) {
        return WebClientResponseException.create(
                status,
                "stub",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
