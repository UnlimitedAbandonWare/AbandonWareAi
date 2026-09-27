package com.example.lms.debug;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugEventRedactionTest {

    @AfterEach
    void clearMdc() {
        TraceStore.clear();
        MDC.clear();
    }

    @Test
    void topLevelCorrelationAndPayloadAreRedacted() {
        DebugEventStore store = enabledDebugEventStore();
        MDC.put("sid", "session-raw-debug");
        MDC.put("traceId", "trace-raw-debug");
        MDC.put("x-request-id", "request-raw-debug");

        store.emit(
                DebugProbeType.GENERIC,
                DebugEventLevel.INFO,
                "debug.redaction.fixed",
                "fixed diagnostic message",
                "DebugEventRedactionTest",
                Map.of(
                        "query", "private query should not remain",
                        "snippet", "<script>alert('x')</script>",
                        "provider", "naver"),
                null);

        List<DebugEvent> events = store.list(1);
        DebugEvent event = events.get(0);

        assertEquals(SafeRedactor.hashValue("session-raw-debug"), event.sid());
        assertEquals(SafeRedactor.hashValue("trace-raw-debug"), event.traceId());
        assertEquals(SafeRedactor.hashValue("request-raw-debug"), event.requestId());
        assertEquals("naver", event.data().get("provider"));
        assertInstanceOf(Map.class, event.data().get("query"));
        assertInstanceOf(Map.class, event.data().get("snippet"));

        String dump = event.toString();
        assertFalse(dump.contains("private query should not remain"));
        assertFalse(dump.contains("<script>"));
        assertFalse(dump.contains("session-raw-debug"));
    }

    @Test
    void unclassifiedStringPayloadsUseDiagnosticSummary() {
        DebugEventStore store = enabledDebugEventStore();
        String rawDetail = "private user question should not remain in debug event text";

        store.emit(
                DebugProbeType.GENERIC,
                DebugEventLevel.INFO,
                "debug.redaction.unclassified",
                "fixed diagnostic message",
                "DebugEventRedactionTest",
                Map.of(
                        "provider", "naver",
                        "detail", rawDetail,
                        "nested", Map.of("note", rawDetail)),
                null);

        DebugEvent event = store.list(1).get(0);

        assertEquals("naver", event.data().get("provider"));
        assertInstanceOf(Map.class, event.data().get("detail"));
        assertInstanceOf(Map.class, ((Map<?, ?>) event.data().get("nested")).get("note"));
        assertFalse(event.toString().contains(rawDetail));
    }

    @Test
    void payloadKeysAreLabelsNotRawText() {
        DebugEventStore store = enabledDebugEventStore();
        String rawKey = "private payload key " + com.example.lms.test.SecretFixtures.openAiKey() + "";
        String nestedRawKey = "nested ownerToken=secret";

        store.emit(
                DebugProbeType.GENERIC,
                DebugEventLevel.INFO,
                "debug.redaction.keys",
                "fixed diagnostic message",
                "DebugEventRedactionTest",
                Map.of(
                        "provider", "naver",
                        rawKey, "provider-disabled",
                        "nested", Map.of(nestedRawKey, "provider-disabled")),
                null);

        DebugEvent event = store.list(1).get(0);
        String dump = event.toString();

        assertEquals("naver", event.data().get("provider"));
        assertTrue(event.data().keySet().stream().anyMatch(k -> String.valueOf(k).startsWith("hash:")), dump);
        assertFalse(dump.contains(rawKey));
        assertFalse(dump.contains(nestedRawKey));
        assertFalse(dump.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertFalse(dump.contains("ownerToken"));
    }

    @Test
    void arbitraryObjectPayloadsUseDiagnosticSummary() {
        DebugEventStore store = enabledDebugEventStore();
        String rawDetail = "private object question should not remain in debug event text";
        Object objectWithRawToString = new Object() {
            @Override
            public String toString() {
                return rawDetail;
            }
        };

        store.emit(
                DebugProbeType.GENERIC,
                DebugEventLevel.INFO,
                "debug.redaction.object",
                "fixed diagnostic message",
                "DebugEventRedactionTest",
                Map.of(
                        "provider", "naver",
                        "detail", objectWithRawToString),
                null);

        DebugEvent event = store.list(1).get(0);

        assertEquals("naver", event.data().get("provider"));
        assertInstanceOf(Map.class, event.data().get("detail"));
        assertFalse(event.toString().contains(rawDetail));
    }

    @Test
    void aggregateSummaryOriginalMessageIsRedacted() throws Exception {
        DebugEventStore store = enabledDebugEventStore();
        ReflectionTestUtils.setField(store, "maxPerWindow", 1L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 1L);
        String openAiKey = "sk-1234567890abcdef";
        String bearer = "Bearer " + "abcdefghijklmnopqrstuvwxyz";
        String rawMessage = "summary message " + openAiKey + " " + bearer;

        store.emit(
                DebugProbeType.GENERIC,
                DebugEventLevel.INFO,
                "debug.redaction.summary",
                rawMessage,
                Map.of("provider", "naver"),
                null);
        Thread.sleep(5L);
        store.emit(
                DebugProbeType.GENERIC,
                DebugEventLevel.INFO,
                "debug.redaction.summary",
                rawMessage,
                Map.of("provider", "naver"),
                null);

        DebugEvent summary = store.list(2).stream()
                .filter(e -> e.message().startsWith("[rate-limit]"))
                .findFirst()
                .orElseThrow();

        Object originalMessage = summary.data().get("originalMessage");
        String dump = summary.toString() + "\n" + originalMessage;
        assertInstanceOf(Map.class, originalMessage);
        assertEquals(SafeRedactor.hash12(SafeRedactor.safeMessage(rawMessage, 2048)),
                ((Map<?, ?>) originalMessage).get("hash12"));
        assertFalse(dump.contains(openAiKey));
        assertFalse(dump.contains(bearer));
        assertFalse(dump.contains("summary message"));
    }

    @Test
    void sanitizerSuppressedTraceIncludesSafeStageAndErrorType() throws Exception {
        String rawStage = "debugEventSanitizer.array " + com.example.lms.test.SecretFixtures.openAiKey();
        Method method = DebugEventSanitizer.class.getDeclaredMethod("traceSuppressed", String.class, Throwable.class);
        method.setAccessible(true);

        method.invoke(null, rawStage, new IllegalStateException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

        Object safeStage = TraceStore.get("debugEvent.sanitizer.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("debugEvent.sanitizer.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("debugEvent.sanitizer.suppressed.errorType"));
        assertEquals("IllegalStateException",
                TraceStore.get("debugEvent.sanitizer.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }

    @Test
    void storeSuppressedTraceIncludesSafeStageAndErrorType() throws Exception {
        String rawStage = "debugEventStore.logJson " + com.example.lms.test.SecretFixtures.openAiKey();
        Method method = DebugEventStore.class.getDeclaredMethod("traceSuppressed", String.class, Throwable.class);
        method.setAccessible(true);

        method.invoke(null, rawStage, new IllegalArgumentException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

        Object safeStage = TraceStore.get("debugEvent.store.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("debugEvent.store.suppressed." + safeStage));
        assertEquals("IllegalArgumentException", TraceStore.get("debugEvent.store.suppressed.errorType"));
        assertEquals("IllegalArgumentException",
                TraceStore.get("debugEvent.store.suppressed." + safeStage + ".errorType"));
        assertEquals(1L, TraceStore.get("debugEvent.store.suppressed.count"));
        assertEquals(1L, TraceStore.get("debugEvent.store.suppressed." + safeStage + ".count"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }

    @Test
    void internalFailSoftLogsDoNotRenderThrowableToString() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/debug/DebugEventStore.java"));

        assertFalse(source.contains("LOG.debug(\"Failed to serialize DebugEvent: {}\", e.toString())"));
        assertFalse(source.contains("LOG.debug(\"Failed to mirror DebugEvent NDJSON: {}\", e.toString())"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(source.contains(
                "LOG.debug(\"Failed to serialize DebugEvent. errorHash={} errorLength={}\""));
        assertTrue(source.contains(
                "LOG.debug(\"Failed to mirror DebugEvent NDJSON. errorHash={} errorLength={}\""));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "DebugEventStore fail-soft paths need fixed-stage breadcrumbs instead of exact empty catches");
        assertTrue(source.contains("traceSuppressed(\"debugEventStore.objectMapper\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"debugEventStore.stackTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"debugEventStore.traceContext.sid\", traceError);"));
        assertTrue(source.contains("traceSuppressed(\"debugEventStore.traceContext.traceId\", traceError);"));
        assertTrue(source.contains("TraceStore.put(\"debugEvent.store.suppressed.stage\", safeStage);"));
        assertTrue(source.contains("TraceStore.put(\"debugEvent.store.suppressed.errorType\", errorType);"));
        assertTrue(source.contains("TraceStore.put(\"debugEvent.store.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains(
                "TraceStore.put(\"debugEvent.store.suppressed.\" + safeStage + \".errorType\", errorType);"));
        assertTrue(source.contains("TraceStore.inc(\"debugEvent.store.suppressed.count\");"));
        assertTrue(source.contains("LOG.debug(\"DebugEvent suppressed stage={} errorHash={} errorLength={}"));

        String sanitizer = Files.readString(Path.of("main/java/com/example/lms/debug/DebugEventSanitizer.java"));
        assertTrue(sanitizer.contains("traceSuppressed(\"debugEventSanitizer.array\", ignore);"));
        assertTrue(sanitizer.contains("TraceStore.put(\"debugEvent.sanitizer.suppressed.stage\", safeStage);"));
        assertTrue(sanitizer.contains("TraceStore.put(\"debugEvent.sanitizer.suppressed.errorType\", errorType);"));
        assertTrue(sanitizer.contains("TraceStore.put(\"debugEvent.sanitizer.suppressed.\" + safeStage, true);"));
        assertTrue(sanitizer.contains(
                "TraceStore.put(\"debugEvent.sanitizer.suppressed.\" + safeStage + \".errorType\", errorType);"));
    }

    private static DebugEventStore enabledDebugEventStore() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 15_000L);
        return store;
    }
}
