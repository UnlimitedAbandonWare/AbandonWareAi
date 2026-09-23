package com.example.lms.infra.upstash;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class UpstashRedisTransportFocusedTest {
    private UpstashRedisClient client(Mono<ClientResponse> reply, AtomicInteger attempts) {
        var client = new UpstashRedisClient(WebClient.builder().exchangeFunction(request -> {
            attempts.incrementAndGet();
            return reply;
        }));
        ReflectionTestUtils.setField(client, "url", "https://synthetic.example.invalid");
        ReflectionTestUtils.setField(client, "token", "synthetic-local-credential");
        return client;
    }

    private Mono<ClientResponse> reply(int status, String body) {
        return Mono.just(ClientResponse.create(HttpStatusCode.valueOf(status))
                .header("Content-Type", "application/json").body(body).build());
    }

    @Test void upstreamFaultsAreCategorizedWithoutRetryOrRawErrorExposure() {
        Object[][] cases = {
                {401, "PRIVATE_AUTH_BODY", "authentication_failed"},
                {429, "PRIVATE_QUOTA_BODY", "provider_rate_limited"},
                {503, "PRIVATE_UPSTREAM_BODY", "upstream_http_error"},
                {200, "[{\"error\":\"NOPERM PRIVATE_PERMISSION_BODY\"}]", "permission_denied"},
                {200, "[{\"error\":\"WRONGPASS PRIVATE_AUTH_BODY\"}]", "authentication_failed"},
                {200, "[{\"error\":\"ERR PRIVATE_COMMAND_BODY\"}]", "upstream_command_error"},
                {200, "[{\"result\":\"PRIVATE_INVALID_BODY\"}]", "invalid_response"}
        };
        for (Object[] c : cases) {
            TraceStore.clear();
            try {
                var attempts = new AtomicInteger();
                var client = client(reply((int)c[0], (String)c[1]), attempts);
                var reason = new AtomicReference<Object>();
                var failure = assertThrows(IllegalStateException.class, () -> client
                        .eval("return {1,0}", List.of(), List.of())
                        .doOnError(error -> reason.set(TraceStore.get("upstash.redis.admission.reasonCode"))).block());
                assertEquals("redis_admission_unavailable", failure.getMessage());
                assertNull(failure.getCause());
                assertEquals(c[2], reason.get());
                assertEquals(1, attempts.get());
                assertFalse(String.valueOf(TraceStore.getAll()).contains("PRIVATE_"));
                assertFalse(String.valueOf(TraceStore.getAll()).contains("synthetic-local-credential"));
            } finally { TraceStore.clear(); }
        }
    }

    @Test void timeoutMakesOnlyOneAttemptAndKeepsOutcomeUnavailable() {
        var attempts = new AtomicInteger();
        var client = client(Mono.never(), attempts);
        var reason = new AtomicReference<Object>();
        var failure = assertThrows(IllegalStateException.class, () -> client.eval("return {1,0}", List.of(), List.of())
                .doOnError(error -> reason.set(TraceStore.get("upstash.redis.admission.reasonCode")))
                .block(Duration.ofSeconds(4)));
        assertEquals("redis_admission_unavailable", failure.getMessage());
        assertEquals("timeout", reason.get());
        assertEquals(1, attempts.get());
    }

    @Test void invalidOrCredentialBearingUrlsNeverSendTheToken() {
        for (String url : List.of("[https://example.invalid](https://example.invalid)",
                "https://user:password@example.invalid", "https://example.invalid?token=private",
                "https://example.invalid#fragment", "http://example.invalid", "\"https://example.invalid\"")) {
            var attempts = new AtomicInteger();
            var client = client(reply(200, "[{\"result\":[1,0]}]"), attempts);
            ReflectionTestUtils.setField(client, "url", url);
            assertFalse(client.enabled(), "invalid endpoint must remain disabled");
            assertThrows(IllegalStateException.class, () -> client.eval("return {1,0}", List.of(), List.of()).block());
            assertEquals(0, attempts.get());
        }
    }

    @Test void failedExpiryInMultiCommandReplyCannotReportCounterSuccess() {
        var attempts = new AtomicInteger();
        var client = client(reply(200, "[{\"result\":1},{\"error\":\"NOPERM PRIVATE_EXPIRY_BODY\"}]"), attempts);
        assertEquals(Long.MAX_VALUE, client.incrExpire("synthetic-key", Duration.ofSeconds(10)).block());
        assertEquals(1, attempts.get());
    }
}
