package com.example.lms.infra.upstash;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class UpstashIncrExpireContractTest {
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>();
    private UpstashRedisClient client(String response) {
        var builder=WebClient.builder().exchangeFunction(request->{
            calls.incrementAndGet();path.set(request.url().getPath());
            assertEquals("POST", request.method().name());
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(response).build());
        });
        var client=new UpstashRedisClient(builder);
        ReflectionTestUtils.setField(client,"url","https://upstash.example.invalid");
        ReflectionTestUtils.setField(client,"token","synthetic-offline-credential");
        return client;
    }
    @Test void incrementAndExpiryUseOneTransaction() {
        assertEquals(7L,client("[{\"result\":7},{\"result\":1}]").incrExpire("fixture",Duration.ofSeconds(10)).block());
        assertEquals("/multi-exec",path.get());assertEquals(1,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={
        "[{\"result\":7},{\"result\":0}]", "[{\"result\":7},{\"error\":\"ERR fixture\"}]",
        "[{\"result\":7}]", "[{\"result\":7},null]", "[]", "null", "{\"error\":\"ERR fixture\"}", "invalid",
        "[{\"result\":7},{\"result\":true}]", "[{\"result\":7},{\"result\":1.5}]",
        "[{\"result\":7.2},{\"result\":1}]", "[{\"result\":null},{\"result\":1}]",
        "[{\"result\":\"9223372036854775808\"},{\"result\":1}]"
    })
    void anyInvalidCommandResultFailsClosedWithoutRetry(String response) {
        assertEquals(Long.MAX_VALUE,client(response).incrExpire("fixture",Duration.ofSeconds(10)).block());
        assertEquals(1,calls.get());
    }
    @Test void getAndSetRemainPipelined() {
        assertEquals("value",client("[{\"result\":\"value\"}]").get("fixture").block());
        assertEquals("/pipeline",path.get());
        assertEquals(true,client("[{\"result\":\"OK\"}]").setEx("fixture","value",Duration.ofSeconds(10)).block());
        assertEquals("/pipeline",path.get());
    }
    @Test void disabledHasNoExchange() {
        var client=client("[]");ReflectionTestUtils.setField(client,"token","changeme");
        assertEquals(0L,client.incrExpire("fixture",Duration.ofSeconds(10)).block());assertEquals(0,calls.get());
    }
}
