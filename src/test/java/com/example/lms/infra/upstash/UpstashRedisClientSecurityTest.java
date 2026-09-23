package com.example.lms.infra.upstash;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstashRedisClientSecurityTest {

    @Test
    void placeholderTokenDisablesClient() {
        UpstashRedisClient client = new UpstashRedisClient(WebClient.builder());
        ReflectionTestUtils.setField(client, "url", "https://upstash.example.invalid");
        ReflectionTestUtils.setField(client, "token", "changeme");

        assertFalse(client.enabled());
    }

    @Test
    void redisNumericResponseParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/infra/upstash/UpstashRedisClient.java"));

        int parse = source.indexOf("Long.parseLong(v)");
        assertTrue(parse >= 0, "Upstash Redis increment parser should remain present");
        int start = source.lastIndexOf("try {", parse);
        int end = source.indexOf("})", parse);
        assertTrue(start >= 0 && end > parse, "parser try block should be found");
        String parser = source.substring(start, end);
        assertTrue(parser.contains("catch (NumberFormatException"),
                "Redis numeric response parser should only catch NumberFormatException");
        assertFalse(parser.contains("catch (Exception"),
                "Redis numeric response parser must not swallow all Exception");
        assertFalse(parser.contains("catch (Throwable"),
                "Redis numeric response parser must not swallow Throwable");
    }

    @Test
    void redisNumericParseFallbackLeavesRedactedTraceBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/infra/upstash/UpstashRedisClient.java"));

        assertTrue(source.contains("traceSuppressed(\"incrParse\", e)"),
                "Redis numeric parse fallback should leave a redacted breadcrumb");
        assertTrue(source.contains("TraceStore.put(\"upstash.redis.suppressed.\" + safeStage, true)"),
                "Redis suppressed fallbacks should use the Upstash TraceStore namespace");
    }

    @Test
    void redisNumericParseFallbackLeavesAggregateBreadcrumbWithoutRawValue() {
        TraceStore.clear();
        try {
            WebClient.Builder builder = WebClient.builder()
                    .exchangeFunction(request -> Mono.just(ClientResponse
                            .create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("[{\"result\":\"ownerToken=raw-secret\"},{\"result\":1}]")
                            .build()));
            UpstashRedisClient client = new UpstashRedisClient(builder);
            ReflectionTestUtils.setField(client, "url", "https://upstash.example.invalid");
            ReflectionTestUtils.setField(client, "token", "upstash-token-present");

            Long result = client.incrExpire("rate-limit:key", Duration.ofSeconds(10)).block();

            assertEquals(Long.MAX_VALUE, result);
            assertEquals("incrParse", TraceStore.get("upstash.redis.suppressed.stage"));
            assertEquals("NumberFormatException", TraceStore.get("upstash.redis.suppressed.errorType"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("ownerToken=raw-secret"), trace);
            assertFalse(trace.contains("upstash-token-present"), trace);
        } finally {
            TraceStore.clear();
        }
    }
}
