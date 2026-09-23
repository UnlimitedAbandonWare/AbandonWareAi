package com.example.lms.telemetry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingSseEventPublisherRedactionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void debugPayloadIsRedactedBeforeLogging() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingSseEventPublisher.class);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        try {
            String openAiKey = "sk-1234567890abcdef";
            String bearer = "Bearer " + "abcdefghijklmnopqrstuvwxyz";

            new LoggingSseEventPublisher().emit("secret", "payload " + openAiKey + " " + bearer);

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logged.contains("SSE[secret]"));
            assertFalse(logged.contains(openAiKey));
            assertFalse(logged.contains(bearer));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    @Test
    void emittedPayloadIsAvailableOnRedactedReplayStream() {
        LoggingSseEventPublisher publisher = new LoggingSseEventPublisher();

        publisher.emit("ORDER_DECISION", Map.of(
                "stage", "rerank",
                "apiKey", "sk-1234567890abcdef",
                "query", "sensitive raw query"));

        Map<String, Object> event = publisher.asStream().blockFirst(Duration.ofSeconds(1));
        assertNotNull(event);
        assertEquals("ORDER_DECISION", event.get("type"));
        String serialized = String.valueOf(event);
        assertTrue(serialized.contains("rerank"));
        assertFalse(serialized.contains("sk-1234567890abcdef"));
        assertFalse(serialized.contains("sensitive raw query"));
    }

    @Test
    void eventTypeIsTreatedAsLabelNotRawText() {
        LoggingSseEventPublisher publisher = new LoggingSseEventPublisher();
        String rawType = "private event type sk-1234567890abcdef";

        publisher.emit(rawType, Map.of("stage", "rerank"));

        Map<String, Object> event = publisher.asStream().blockFirst(Duration.ofSeconds(1));
        assertNotNull(event);
        String serialized = String.valueOf(event);
        assertFalse(serialized.contains(rawType));
        assertFalse(serialized.contains("private event type"));
        assertFalse(serialized.contains("sk-1234567890abcdef"));
        assertTrue(String.valueOf(event.get("type")).startsWith("hash:"), serialized);
    }

    @Test
    void emitAppendsSanitizedMlaBreadcrumbForTelemetryEvent() {
        LoggingSseEventPublisher publisher = new LoggingSseEventPublisher();
        TraceStore.put("requestId", "raw-request-id-123");
        TraceStore.put("sessionId", "raw-session-id-123");
        TraceStore.put("plan.id", "safe_plan.v1");
        TraceStore.put("cfvm.jb.score", 0.75d);
        TraceStore.put("cfvm.cb.score", 0.25d);
        TraceStore.put("artplate.selected", "AP1_AUTH_WEB");
        TraceStore.put("llm.router.arm", "gpt-5-mini");

        publisher.emit("MOE_ROUTE", Map.of(
                "query", "sensitive raw query",
                "apiKey", "sk-1234567890abcdef"));

        List<?> breadcrumbs = assertInstanceOf(List.class, TraceStore.get("ml.breadcrumbs.v1"));
        Map<?, ?> row = assertInstanceOf(Map.class, breadcrumbs.get(0));
        assertEquals("LoggingSseEventPublisher", row.get("component"));
        assertEquals("sse_emit", row.get("decision"));
        assertEquals(SafeRedactor.hashValue("raw-request-id-123"), row.get("requestId"));
        assertEquals(SafeRedactor.hashValue("raw-session-id-123"), row.get("sessionId"));
        Map<?, ?> data = assertInstanceOf(Map.class, row.get("data"));
        assertEquals("MOE_ROUTE", data.get("eventType"));
        assertEquals("safe_plan.v1", data.get("planId"));
        assertEquals(0.75d, data.get("jb"));
        assertEquals(0.25d, data.get("cb"));
        assertEquals("AP1_AUTH_WEB", data.get("plateId"));
        assertEquals("gpt-5-mini", data.get("llmArm"));
        String serialized = String.valueOf(row);
        assertFalse(serialized.contains("sensitive raw query"));
        assertFalse(serialized.contains("sk-1234567890abcdef"));
    }

    @Test
    void sessionAwareEmitAddsOnlyHashedSessionIdToReplayEvent() {
        LoggingSseEventPublisher publisher = new LoggingSseEventPublisher();
        String rawSessionId = "session-private-123";

        publisher.emit("SESSION_EVENT", Map.of("stage", "breadcrumb"), rawSessionId);

        Map<String, Object> event = publisher.asStream(rawSessionId).blockFirst(Duration.ofSeconds(1));
        assertNotNull(event);
        assertEquals("SESSION_EVENT", event.get("type"));
        assertEquals(SafeRedactor.hashValue(rawSessionId), event.get("sessionId"));
        assertFalse(String.valueOf(event).contains(rawSessionId));
    }

    @Test
    void constructorHonorsReplayLimit() {
        LoggingSseEventPublisher publisher = new LoggingSseEventPublisher(2);

        publisher.emit("FIRST", Map.of("n", 1));
        publisher.emit("SECOND", Map.of("n", 2));
        publisher.emit("THIRD", Map.of("n", 3));

        List<Map<String, Object>> events = publisher.asStream()
                .take(2)
                .collectList()
                .block(Duration.ofSeconds(1));
        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("SECOND", events.get(0).get("type"));
        assertEquals("THIRD", events.get(1).get("type"));
    }

    @Test
    void defaultFallbackPublisherRedactsDebugPayloadBeforeLogging() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultSseEventPublisher.class);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        try {
            String secret = "sk-1234567890abcdef";
            String rawQuery = "private fallback query";

            new DefaultSseEventPublisher().emit("fallback-secret", Map.of(
                    "apiKey", secret,
                    "query", rawQuery));

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logged.contains("SSE[fallback-secret]"));
            assertFalse(logged.contains(secret));
            assertFalse(logged.contains(rawQuery));
            assertTrue(logged.contains("hash12"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }
}
