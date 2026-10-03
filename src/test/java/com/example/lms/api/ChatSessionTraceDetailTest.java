package com.example.lms.api;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.SettingsService;
import com.example.lms.service.trace.TraceHtmlBuilder;
import com.example.lms.trace.TraceSnapshotStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatSessionTraceDetailTest {
    private static String pointer(String snapshot, String assistant, String value, String version) {
        String text = "storageMode=durable_fallback\nassistantMessageId=" + assistant
                + "\nreason=chat.trace_html.final\nmethod=SSE\npathHash=none\n" + value;
        return "?TRACESNAP?" + snapshot + "|" + version + "|"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
    private static ChatMessage message(long id, String role, String content) {
        return ChatMessage.builder().id(id).role(role).content(content)
                .createdAt(LocalDateTime.of(2026, 9, 27, 0, 0).plusSeconds(id)).build();
    }
    private static class Fixture {
        final ChatHistoryService history = mock(ChatHistoryService.class);
        final TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        final ChatApiController controller = mock(ChatApiController.class, CALLS_REAL_METHODS);
        final com.example.lms.web.ClientOwnerKeyResolver owner;
        final ChatSession session;
        Fixture(List<ChatMessage> messages) {
            owner = mock(com.example.lms.web.ClientOwnerKeyResolver.class);
            when(owner.ownerKey()).thenReturn("synthetic-owner");
            session = ChatSession.builder().id(7L).title("synthetic").ownerKey("synthetic-owner")
                    .messages(messages).build();
            when(history.getSessionWithMessages(7L)).thenReturn(session);
            when(store.get(anyString())).thenReturn(Optional.empty());
            SettingsService settings = mock(SettingsService.class);
            when(settings.getAllSettings()).thenReturn(Map.of());
            ReflectionTestUtils.setField(controller, "historyService", history);
            ReflectionTestUtils.setField(controller, "ownerKeyResolver", owner);
            ReflectionTestUtils.setField(controller, "settingsService", settings);
            ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
            ReflectionTestUtils.setField(controller, "traceHtmlBuilder", new TraceHtmlBuilder(null));
            ReflectionTestUtils.setField(controller, "traceSnapshotStore", store);
        }
        org.springframework.security.core.Authentication auth(boolean admin) {
            return new UsernamePasswordAuthenticationToken("operator", "unused",
                    admin ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN")) : List.of());
        }
    }
    private static List<ChatMessage> messages(String pointer) {
        return List.of(message(1, "user", "synthetic"), message(2, "assistant", "answer"),
                message(3, "system", pointer));
    }
    @Test void exactOwnedPointerRestoresDetailsAfterRingLossWithoutWrites() {
        Fixture f = new Fixture(messages(pointer("snap-owned", "2",
                "diag.queryTransformer.bypassed=b:true\ndiag.queryTransformer.reason=s:retrieval_off_direct\n"
                + "diag.prompt.webCount=n:0\n", "v3")));
        var response = f.controller.getSessionTraceHtml(7L, "snap-owned", f.auth(true), "html");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("durable_projection", response.getHeaders().getFirst("X-Trace-Storage"));
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        String html = (String) response.getBody();
        assertTrue(html.contains("Query Transformation"));
        assertTrue(html.contains("retrieval_off_direct"));
        assertTrue(html.contains("prompt.webCount"));
        assertFalse(html.contains("synthetic-owner"));
        verify(f.history).getSessionWithMessages(7L);
        verifyNoMoreInteractions(f.history);
        var detail = ChatSessionDetailResponseBuilder.build(f.session, null, new ObjectMapper(), Map.of(), true,
                org.slf4j.LoggerFactory.getLogger(getClass())).getBody();
        assertEquals("7", detail.turnTraces().get(0).fields().get("sessionId"));
        assertTrue(detail.turnTraces().get(0).fields().size() <= 16);
    }
    @Test void existingTraceExposureRequiresBothOwnerAndOperator() {
        Fixture f = new Fixture(messages(pointer("snap-owned", "2", "diag.prompt.webCount=n:0\n", "v3")));
        assertEquals(404, f.controller.getSessionTraceHtml(7L, "snap-owned", f.auth(false), "html").getStatusCode().value());
        when(f.owner.ownerKey()).thenReturn("foreign-owner");
        assertEquals(404, f.controller.getSessionTraceHtml(7L, "snap-owned", f.auth(true), "html").getStatusCode().value());
        assertEquals(403, f.controller.getSessionTraceHtml(7L, "snap-owned", f.auth(false), "html").getStatusCode().value());
        verifyNoInteractions(f.store);
    }
    @Test void wrongSnapshotAssistantRoleSessionAndConflictingPointersAreRejected() {
        String good = pointer("snap-owned", "2", "diag.prompt.webCount=n:0\n", "v3");
        Fixture f = new Fixture(messages(good));
        assertEquals(404, f.controller.getSessionTraceHtml(7L, "other", f.auth(true), "html").getStatusCode().value());
        assertEquals(404, f.controller.getSessionTraceHtml(8L, "snap-owned", f.auth(true), "html").getStatusCode().value());
        for (String assistant : List.of("1", "99")) {
            Fixture wrong = new Fixture(messages(pointer("snap-owned", assistant, "diag.prompt.webCount=n:0\n", "v3")));
            assertEquals(404, wrong.controller.getSessionTraceHtml(7L, "snap-owned", wrong.auth(true), "html").getStatusCode().value());
        }
        Fixture conflict = new Fixture(List.of(message(1,"user","q"), message(2,"assistant","a"),
                message(3,"system",good), message(4,"system",pointer("snap-owned","2","diag.prompt.webCount=n:1\n","v3"))));
        assertEquals(404, conflict.controller.getSessionTraceHtml(7L, "snap-owned", conflict.auth(true), "html").getStatusCode().value());
        verifyNoInteractions(conflict.store);
    }
    @Test void boundLegacyRingHitStillWorksButExpiredLegacyDoesNotInventDetail() {
        Fixture f = new Fixture(messages(pointer("snap-owned", "2", "", "v2")));
        assertEquals(404, f.controller.getSessionTraceHtml(7L, "snap-owned", f.auth(true), "html").getStatusCode().value());
        TraceSnapshotStore.TraceSnapshot snapshot = mock(TraceSnapshotStore.TraceSnapshot.class);
        when(snapshot.html()).thenReturn("<details class=\"search-trace\"><summary>ring</summary></details>");
        when(f.store.get("snap-owned")).thenReturn(Optional.of(snapshot));
        var response = f.controller.getSessionTraceHtml(7L, "snap-owned", f.auth(true), "html");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("ring", response.getHeaders().getFirst("X-Trace-Storage"));
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) out.put(entry.getName(), zip.readAllBytes());
        }
        return out;
    }
    @Test void bundleAfterRestartPreservesTypedEvidenceAndDeclaresMissingSources() throws Exception {
        Fixture f = new Fixture(messages(pointer("snap-owned", "2", "diag.prompt.webCount=n:0\n", "v3")));
        var response = f.controller.getSessionTraceHtml(7L, "snap-owned", f.auth(true), "bundle");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/zip", response.getHeaders().getContentType().toString());
        var entries = unzip((byte[]) response.getBody());
        assertEquals(Set.of("manifest.json", "summary.json", "trace.json", "README.txt"), entries.keySet());
        ObjectMapper mapper = new ObjectMapper();
        var manifest = mapper.readTree(entries.get("manifest.json"));
        assertEquals("awx.answer-trace-bundle.v1", manifest.path("schema").asText());
        assertEquals("ring_expired_or_restarted", manifest.path("sources").path("events").path("reason").asText());
        assertEquals("unavailable", manifest.path("sources").path("logs").path("status").asText());
        assertEquals(0, mapper.readTree(entries.get("trace.json")).path("prompt.webCount").asInt(-1));
        for (var entry : entries.entrySet()) {
            if ("manifest.json".equals(entry.getKey())) continue;
            String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(entry.getValue()));
            assertEquals(sha, manifest.path("checksumsSha256").path(entry.getKey()).asText());
        }
        assertEquals(404, f.controller.getSessionTraceHtml(7L, "snap-owned", f.auth(false), "bundle").getStatusCode().value());
        verify(f.history, times(2)).getSessionWithMessages(7L);
        verifyNoMoreInteractions(f.history);
    }
    @Test void bundleUsesOnlyExactCorrelatedRingEventsAndNeverDuplicatesFileMirrors() throws Exception {
        Fixture f = new Fixture(messages(pointer("snap-owned", "2", "diag.prompt.webCount=n:0\n", "v3")));
        var snapshot = mock(TraceSnapshotStore.TraceSnapshot.class);
        when(snapshot.requestId()).thenReturn(com.example.lms.trace.SafeRedactor.hashValue("synthetic-bundle-request"));
        when(snapshot.traceId()).thenReturn(com.example.lms.trace.SafeRedactor.hashValue("synthetic-bundle-trace"));
        when(f.store.get("snap-owned")).thenReturn(Optional.of(snapshot));
        var events = new com.example.lms.debug.DebugEventStore();
        ReflectionTestUtils.setField(events, "ndjsonEnabled", false);
        ReflectionTestUtils.setField(f.controller, "traceBundleEvents", events);
        try {
            org.slf4j.MDC.put("requestId", "synthetic-bundle-request");
            org.slf4j.MDC.put("traceId", "synthetic-bundle-trace");
            events.emit(com.example.lms.debug.DebugProbeType.ORCHESTRATION,
                    com.example.lms.debug.DebugEventLevel.WARN, "synthetic", "synthetic-private-payload",
                    Map.of("rawQuery", "synthetic-private-payload"), null);
            org.slf4j.MDC.put("requestId", "foreign-request");
            events.emit(com.example.lms.debug.DebugProbeType.ORCHESTRATION,
                    com.example.lms.debug.DebugEventLevel.INFO, "foreign", "foreign-event", Map.of(), null);
        } finally { org.slf4j.MDC.clear(); }
        var response = f.controller.getSessionTraceHtml(7L, "snap-owned", f.auth(true), "bundle");
        var entries = unzip((byte[]) response.getBody());
        String ndjson = new String(entries.get("events.ndjson"), StandardCharsets.UTF_8);
        assertEquals(1, ndjson.lines().count());
        assertFalse(ndjson.contains("synthetic-private-payload"));
        assertFalse(ndjson.contains("foreign-event"));
        assertFalse(entries.containsKey("logs.ndjson"));
        var manifest = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertFalse(manifest.path("sources").path("events").path("historyComplete").asBoolean(true));
        assertEquals(1, manifest.path("sources").path("events").path("count").asInt());
        assertEquals("available", manifest.path("sources").path("events").path("status").asText());
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue("synthetic-bundle-request"),
                manifest.path("requestIdHash").asText());
        assertEquals(2, events.list(10).size());
    }
    @Test void legacyBundleMarksAbsentDetailsInsteadOfCreatingAnEmptySuccessFile() throws Exception {
        Fixture f = new Fixture(messages(pointer("snap-owned", "2", "", "v2")));
        var response = f.controller.getSessionTraceHtml(7L, "snap-owned", f.auth(true), "bundle");
        var entries = unzip((byte[]) response.getBody());
        assertFalse(entries.containsKey("trace.json"));
        assertEquals("legacy_summary_only", new ObjectMapper().readTree(entries.get("manifest.json"))
                .path("sources").path("trace").path("reason").asText());
    }
}
