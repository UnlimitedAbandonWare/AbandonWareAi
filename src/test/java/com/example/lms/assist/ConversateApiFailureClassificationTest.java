package com.example.lms.assist;

import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayException;
import dev.langchain4j.exception.HttpException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConversateApiFailureClassificationTest {
    @AfterEach void clearTrace() {
        com.example.lms.search.TraceStore.clear();
        Thread.interrupted();
    }

    @Test void gatewayAuthenticationFailureKeepsItsCategory() {
        assertFailure(new LlmGatewayException("synthetic", LlmFailureClass.AUTH_MISSING), "GENERATION_DENIED");
    }

    @Test void gatewayRateLimitKeepsItsCategory() {
        assertFailure(new LlmGatewayException("synthetic", LlmFailureClass.RATE_LIMIT_COOLDOWN), "GENERATION_RATE_LIMITED");
    }

    @Test void gatewayMissingModelKeepsItsCategory() {
        assertFailure(new LlmGatewayException("synthetic", LlmFailureClass.MODEL_MISSING), "MODEL_NOT_FOUND");
    }

    @Test void compatibleApiHttpFailuresKeepStatusWithoutExposingBody() {
        assertAll(
                () -> assertFailure(new HttpException(401, "synthetic-private-body"), "GENERATION_DENIED"),
                () -> assertFailure(new HttpException(429, "synthetic-private-body"), "GENERATION_RATE_LIMITED"),
                () -> assertFailure(new HttpException(504, "synthetic-private-body"), "GENERATION_TIMEOUT"));
    }

    @Test void socketDeadlineKeepsTimeoutCategory() {
        assertFailure(new IllegalStateException(new java.net.SocketTimeoutException()), "GENERATION_TIMEOUT");
    }

    @Test void explicitEmptyAndCancellationKeepTheirExistingMeaning() {
        assertFailure(new LlmGatewayException("synthetic", LlmFailureClass.PROVIDER_ERROR, "blank_response"), "GENERATION_EMPTY");
        assertFailure(new java.util.concurrent.CancellationException("synthetic"), "CANCELLED");
    }

    @Test void unknownFailureRemainsUnavailableWithoutAnotherCall() {
        assertFailure(new IllegalStateException("synthetic-private-body"), "GENERATION_UNAVAILABLE");
    }

    private static void assertFailure(RuntimeException failure, String expected) {
        var calls = new AtomicInteger();
        var generator = new ConversateLocalCardGenerator((messages, schema) -> {
            calls.incrementAndGet();
            throw failure;
        }, 1000);
        var result = generator.suggest("다음 질문을 추천해 주세요", List.of("합성 문맥"), 1000);
        assertEquals(1, calls.get(), "classification must not replay a failed provider call");
        assertEquals(1, result.attempts());
        assertNotEquals("SHOW", result.card().decision());
        assertFalse(result.toString().contains("synthetic-private-body"));
        assertFalse(com.example.lms.search.TraceStore.getAll().toString().contains("synthetic-private-body"));
        assertEquals(expected, result.reason());
    }
}
