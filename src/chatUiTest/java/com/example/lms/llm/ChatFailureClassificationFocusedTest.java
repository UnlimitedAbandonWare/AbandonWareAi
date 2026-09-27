package com.example.lms.llm;

import com.example.lms.llm.gateway.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class ChatFailureClassificationFocusedTest {
    private static Throwable http(int status, String body) {
        return WebClientResponseException.create(status, "synthetic", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
    private static void failure(String expected, Throwable upstream) {
        var result = ModelSelectionException.failure(upstream);
        assertEquals(expected, result.code());
        assertEquals(expected, result.getMessage());
        assertNull(result.getCause(), "Upstream private bodies must not escape");
    }
    @Test void typedFailuresRetainTheirRecoveryMeaning() {
        String blobPath = String.join(Character.toString(92), "E:", "models", "blobs", "sha256-synthetic");
        failure("local_model_store_unavailable",
                new IllegalStateException("read " + blobPath + ": A device which does not exist was specified."));
        failure("backend_timeout", new SocketTimeoutException("private sentinel"));
        failure("backend_unavailable", new ConnectException("private sentinel"));
        failure("request_cancelled", new CancellationException("private sentinel"));
        failure("local_capacity_exceeded", new LlmGatewayException("private", LlmFailureClass.VRAM_OOM));
        failure("provider_unauthorized", http(401, "{}"));
        failure("rate_limited", http(429, "{}"));
        failure("model_unavailable", http(404, "{}"));
        failure("model_request_invalid", http(400, "{}"));
    }
    @Test void wrappedSelectionAndTimeoutArePreserved() {
        failure("protocol_unsupported", new RuntimeException("outer", new ModelSelectionException("protocol_unsupported")));
        failure("backend_timeout", new RuntimeException("outer", new SocketTimeoutException()));
    }
    @Test void quotaIsNotTreatedAsRetryableRateLimiting() {
        for (int status : new int[]{400,402,429,503}) {
            failure("quota_exceeded", http(status, "{\"error\":{\"code\":\"insufficient_quota\"}}"));
        }
        failure("quota_exceeded", new LlmGatewayException("private", LlmFailureClass.RATE_LIMIT_COOLDOWN, "spend_limit_exceeded"));
        failure("rate_limited", http(429, "{\"error\":{\"message\":\"insufficient_quota\"}}"));
    }
    @Test void storeReadErrorsAreDistinctFromMissingModelsAndGenericBadRequests() {
        for (int status : new int[]{400,500}) {
            failure("local_model_store_unavailable", http(status,
                    "{\"error\":\"couldn't open model file: read X:/models/blobs/sha256-fixture: A device which does not exist was specified.\"}"));
        }
        failure("model_unavailable", http(404, "{\"error\":\"model fixture not found\"}"));
        failure("model_request_invalid", http(400, "{\"error\":\"invalid option\"}"));
        failure("backend_unavailable", http(500, "{\"error\":\"unknown failure\"}"));
    }
    @Test void unknownFailuresDoNotClaimTheModelIsMissing() {
        failure("backend_unavailable", new IllegalStateException("private sentinel"));
    }
}
