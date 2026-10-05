package com.example.lms.api;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatTraceBundleResponseBuilderTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final LocalDateTime CAPTURED = LocalDateTime.of(2026, 10, 4, 23, 59);
    private static final String REQUEST = "hash:111111111111";
    private static final String TRACE = "hash:222222222222";
    private static final int LIMIT = 262_144;

    private static class Fixture {
        final ChatApiController controller = mock(ChatApiController.class, CALLS_REAL_METHODS);
        ObjectMapper mapper = new ObjectMapper();
        DebugEventStore events;
        TraceSnapshotStore.TraceSnapshot snapshot;
        ChatTraceMetaMessageRestorer.SnapshotPointer pointer = pointer("");

        ResponseEntity<?> call() {
            ReflectionTestUtils.setField(controller, "objectMapper", mapper);
            ReflectionTestUtils.setField(controller, "traceBundleEvents", events);
            try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                clock.when(Instant::now).thenReturn(NOW);
                return ChatTraceBundleResponseBuilder.build(pointer, CAPTURED, snapshot, mapper, events, () -> controller.getClass().getPackage().getImplementationVersion(), stage -> { });
            }
        }

        void correlate() {
            snapshot = mock(TraceSnapshotStore.TraceSnapshot.class);
            when(snapshot.requestId()).thenReturn(REQUEST);
            when(snapshot.traceId()).thenReturn(TRACE);
        }
    }

    private static ChatTraceMetaMessageRestorer.SnapshotPointer pointer(String padding) {
        Map<String, String> projection = new LinkedHashMap<>();
        projection.put("reason", "chat.trace_html.final");
        projection.put("padding", padding);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("prompt.webCount", 0);
        diagnostics.put("queryTransformer.reason", "synthetic");
        return new ChatTraceMetaMessageRestorer.SnapshotPointer("snap-synthetic", projection, 2L, false, diagnostics);
    }

    private static Map<String, byte[]> unzip(ResponseEntity<?> response) throws Exception {
        assertEquals(200, response.getStatusCode().value());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream((byte[]) response.getBody()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Test
    void normalBundlePreservesHeadersEntryBytesAndJsonKeyOrder() throws Exception {
        Fixture f = new Fixture();
        var response = f.call();
        assertEquals("application/zip", response.getHeaders().getContentType().toString());
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals("attachment; filename=\"answer-trace-bundle.zip\"",
                response.getHeaders().getFirst("Content-Disposition"));
        var entries = unzip(response);
        assertEquals(List.of("summary.json", "trace.json", "README.txt", "manifest.json"),
                new ArrayList<>(entries.keySet()));
        assertArrayEquals(f.mapper.writeValueAsBytes(f.pointer.projection()), entries.get("summary.json"));
        assertArrayEquals(f.mapper.writeValueAsBytes(f.pointer.diagnostics()), entries.get("trace.json"));
        JsonNode manifest = f.mapper.readTree(entries.get("manifest.json"));
        assertEquals(List.of("schema", "redactionVersion", "build", "snapshotId", "assistantMessageId",
                "capturedAt", "exportedAt", "terminalReason", "ringScope", "durableScope", "sources",
                "checksumsSha256"), keys(manifest));
        assertEquals("awx.answer-trace-bundle.v1", manifest.path("schema").asText());
        assertEquals("typed-pointer-v3", manifest.path("redactionVersion").asText());
        assertEquals(NOW.toString(), manifest.path("exportedAt").asText());
        assertEquals(List.of("summary", "trace", "events", "logs"), keys(manifest.path("sources")));
        for (var entry : entries.entrySet()) {
            if (!"manifest.json".equals(entry.getKey())) {
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue())),
                        manifest.path("checksumsSha256").path(entry.getKey()).asText());
            }
        }
    }

    private static Fixture paddedTo(int targetBytes) throws Exception {
        Fixture f = new Fixture();
        int initialBytes = unzip(f.call()).values().stream().mapToInt(value -> value.length).sum();
        f.pointer = pointer("A".repeat(targetBytes - initialBytes));
        return f;
    }

    @Test
    void exactly262144UncompressedBytesIncludingManifestAreAccepted() throws Exception {
        var entries = unzip(paddedTo(LIMIT).call());
        assertEquals(LIMIT, entries.values().stream().mapToInt(value -> value.length).sum());
    }

    @Test
    void oneByteOver262144IsRejectedWithoutSuccessHeaders() throws Exception {
        var response = paddedTo(LIMIT + 1).call();
        assertEquals(413, response.getStatusCode().value());
        assertNull(response.getBody());
        assertTrue(response.getHeaders().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void realRingPageCaps201CorrelatedEventsAt200AndMarksTruncation() throws Exception {
        Fixture f = new Fixture();
        f.correlate();
        f.events = spy(new DebugEventStore());
        ReflectionTestUtils.setField(f.events, "ndjsonEnabled", false);
        Deque<DebugEvent> ring = (Deque<DebugEvent>) ReflectionTestUtils.getField(f.events, "ring");
        for (int i = 0; i < 201; i++) {
            ring.addLast(new DebugEvent("event-" + i, NOW, NOW.toEpochMilli(), DebugEventLevel.WARN,
                    DebugProbeType.ORCHESTRATION, "fingerprint-" + i, "synthetic-event", null,
                    TRACE, REQUEST, "test", null, Map.of(), null, null));
        }
        var entries = unzip(f.call());
        assertEquals(List.of("summary.json", "trace.json", "events.ndjson", "README.txt", "manifest.json"),
                new ArrayList<>(entries.keySet()));
        String ndjson = new String(entries.get("events.ndjson"), StandardCharsets.UTF_8);
        assertEquals(200L, ndjson.lines().count());
        JsonNode row = f.mapper.readTree(ndjson.lines().findFirst().orElseThrow());
        assertEquals(List.of("id", "ts", "level", "probe", "fingerprint", "requestIdHash", "traceIdHash",
                "messageHash"), keys(row));
        assertEquals(REQUEST, row.path("requestIdHash").asText());
        assertEquals(TRACE, row.path("traceIdHash").asText());
        assertEquals(SafeRedactor.hashValue("synthetic-event"), row.path("messageHash").asText());
        var events = f.mapper.readTree(entries.get("manifest.json")).path("sources").path("events");
        assertEquals(200, events.path("count").asInt());
        assertTrue(events.path("truncated").asBoolean());
        assertFalse(events.path("historyComplete").asBoolean());
        verify(f.events).page(REQUEST, TRACE, null, 200);
    }

    @Test
    void alreadyHashedCorrelationIdsAreForwardedAndPublishedUnchanged() throws Exception {
        Fixture f = new Fixture();
        f.correlate();
        f.events = mock(DebugEventStore.class);
        when(f.events.page(REQUEST, TRACE, null, 200))
                .thenReturn(new DebugEventStore.EventPage(List.of(), null, false, "none"));
        JsonNode manifest = f.mapper.readTree(unzip(f.call()).get("manifest.json"));
        assertEquals(REQUEST, manifest.path("requestIdHash").asText());
        assertEquals(TRACE, manifest.path("traceIdHash").asText());
        verify(f.events).page(REQUEST, TRACE, null, 200);
        verifyNoMoreInteractions(f.events);
    }

    @Test
    void unhashedIdsDoNotCreateCorrelationOrCollectEvents() throws Exception {
        Fixture f = new Fixture();
        f.correlate();
        when(f.snapshot.requestId()).thenReturn("synthetic-request");
        when(f.snapshot.traceId()).thenReturn("synthetic-trace");
        f.events = mock(DebugEventStore.class);
        JsonNode manifest = f.mapper.readTree(unzip(f.call()).get("manifest.json"));
        assertFalse(manifest.has("requestIdHash"));
        assertFalse(manifest.has("traceIdHash"));
        assertEquals("correlation_or_store_unavailable", manifest.path("sources").path("events").path("reason").asText());
        verifyNoInteractions(f.events);
    }

    @Test
    void serializationFailureReturns500WithoutSuccessHeaders() throws Exception {
        Fixture f = new Fixture();
        f.mapper = mock(ObjectMapper.class);
        when(f.mapper.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("synthetic") { });
        var response = f.call();
        assertEquals(500, response.getStatusCode().value());
        assertNull(response.getBody());
        assertTrue(response.getHeaders().isEmpty());
    }

    @Test
    void absentDiagnosticsKeepLegacyFileListAndReason() throws Exception {
        Fixture f = new Fixture();
        f.pointer = new ChatTraceMetaMessageRestorer.SnapshotPointer("snap-synthetic", f.pointer.projection(), 2L, true, Map.of());
        var entries = unzip(f.call());
        assertEquals(List.of("summary.json", "README.txt", "manifest.json"), new ArrayList<>(entries.keySet()));
        var trace = f.mapper.readTree(entries.get("manifest.json")).path("sources").path("trace");
        assertEquals("unavailable", trace.path("status").asText());
        assertEquals("legacy_summary_only", trace.path("reason").asText());
    }

    @Test
    void recordsStableArchiveBytesWithOnlyZipClockFieldsNormalized() throws Exception {
        Fixture f = new Fixture();
        // Map.of iteration order varies by JVM; explicitly configure this mapper
        // for the before/after byte probe. Default key order is tested above.
        f.mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var response = f.call();
        assertEquals(4, unzip(response).size());
        byte[] archive = ((byte[]) response.getBody()).clone();
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int end = archive.length - 22;
        assertEquals(0x06054b50, buffer.getInt(end));
        int directory = buffer.getInt(end + 16);
        int count = Short.toUnsignedInt(buffer.getShort(end + 10));
        assertEquals(4, count);
        for (int i = 0; i < count; i++) {
            assertEquals(0x02014b50, buffer.getInt(directory));
            int local = buffer.getInt(directory + 42);
            assertEquals(0x04034b50, buffer.getInt(local));
            // No extended timestamp extras are present for these ordinary dates.
            assertEquals(0, Short.toUnsignedInt(buffer.getShort(directory + 30)));
            assertEquals(0, Short.toUnsignedInt(buffer.getShort(local + 28)));
            Arrays.fill(archive, directory + 12, directory + 16, (byte) 0);
            Arrays.fill(archive, local + 10, local + 14, (byte) 0);
            directory += 46 + Short.toUnsignedInt(buffer.getShort(directory + 28))
                    + Short.toUnsignedInt(buffer.getShort(directory + 30))
                    + Short.toUnsignedInt(buffer.getShort(directory + 32));
        }
        assertEquals(end, directory);
        System.out.println("BUNDLE_CHARACTERIZATION_SHA256="
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)));
    }
}
