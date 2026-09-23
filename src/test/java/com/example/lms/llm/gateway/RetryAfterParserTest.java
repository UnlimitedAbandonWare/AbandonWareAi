package com.example.lms.llm.gateway;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryAfterParserTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void parsesDeltaSeconds() {
        assertEquals(Duration.ofSeconds(12), RetryAfterParser.parse("12").orElseThrow());
    }

    @Test
    void parsesRfc1123DateWithoutNegativeDurations() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-31T00:00:00Z"), ZoneOffset.UTC);

        assertEquals(Duration.ofSeconds(5),
                RetryAfterParser.parse("Sun, 31 May 2026 00:00:05 GMT", clock).orElseThrow());
        assertEquals(Duration.ZERO,
                RetryAfterParser.parse("Sun, 31 May 2026 00:00:00 GMT", clock).orElseThrow());
    }

    @Test
    void ignoresInvalidHeaders() {
        assertTrue(RetryAfterParser.parse("not a retry header").isEmpty());
    }

    @Test
    void invalidRetryAfterFormsLeaveRedactedTraceBreadcrumbs() {
        assertTrue(RetryAfterParser.parse("not a retry header").isEmpty());

        assertEquals(true, TraceStore.get("llm.gateway.retryAfter.suppressed.deltaSeconds"));
        assertEquals("invalid_number",
                TraceStore.get("llm.gateway.retryAfter.suppressed.deltaSeconds.errorType"));
        assertEquals(true, TraceStore.get("llm.gateway.retryAfter.suppressed.httpDate"));
        assertEquals("invalid_date",
                TraceStore.get("llm.gateway.retryAfter.suppressed.httpDate.errorType"));
    }

    @Test
    void retryAfterParserDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/gateway/RetryAfterParser.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Retry-After parsing fallbacks need explicit return paths instead of exact empty catch bodies");
    }
}
