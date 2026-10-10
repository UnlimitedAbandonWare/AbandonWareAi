package com.example.lms.api;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Read-only export boundary. Never invokes retrieval, generation, job claims or memory writes. */
@Component
public final class ChatConversationExportSupport {
    static final String SCHEMA = "awx.conversation-context.v1";
    static final String USUM = "⎔USUM⎔";
    static final int PAGE = 200, MAX_SESSIONS = 10, MAX_ROWS = 10_000;
    static final int ROW_BYTES = 1 << 20, JSON_BYTES = 16 << 20, CACHE_BYTES = 64 << 20;
    // Conservative reservations include UTF-16 rows, masking copies, DTO, UTF-8 output and copies.
    // These budgets bound export allocations, not the application's JVM heap.
    static final long CAPTURE_WORK = 192L << 20, ZIP_WORK = 48L << 20, WORK_BUDGET = 256L << 20;
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final TraceSnapshotStore traces;
    private final DebugEventStore events;
    private final ChatRunRegistry runs;
    private final Clock clock;
    private final Semaphore captureSlot = new Semaphore(1), zipSlot = new Semaphore(1);
    private final LinkedHashMap<String, Retained> cache = new LinkedHashMap<>();
    private long retainedBytes, workingBytes;

    @Autowired
    public ChatConversationExportSupport(DataSource dataSource, ObjectMapper mapper,
            ObjectProvider<TraceSnapshotStore> traces, ObjectProvider<DebugEventStore> events,
            ObjectProvider<ChatRunRegistry> runs) {
        this(dataSource, mapper, traces.getIfAvailable(), events.getIfAvailable(), runs.getIfAvailable(), Clock.systemUTC());
    }

    ChatConversationExportSupport(DataSource dataSource, ObjectMapper mapper, TraceSnapshotStore traces,
            DebugEventStore events, ChatRunRegistry runs, Clock clock) {
        this.dataSource = dataSource; this.mapper = mapper; this.traces = traces;
        this.events = events; this.runs = runs; this.clock = clock;
    }

    public record Selection(List<String> sessionIds, String currentSessionId) { }
    public record Actor(String username, String ownerKey) {
        boolean named() { return username != null && !username.isBlank()
                && !"anonymousUser".equalsIgnoreCase(username) && !"anonymous".equalsIgnoreCase(username); }
        boolean exactKey() { return ownerKey != null && !ownerKey.isBlank()
                && !ownerKey.startsWith("ipua:") && !ownerKey.startsWith("system:"); }
        String identity() {
            if (!named() && !exactKey()) throw fail(403, "OWNER_UNAVAILABLE");
            return AttachmentOwnerIdentity.forActor(named() ? username : null, exactKey() ? ownerKey : null).hash();
        }
    }
    private record Owned(long id, String title, String namespace, long epoch) { }
    private record Row(long id, String role, String content, String createdAt) { }
    private record Pointer(long messageId, ChatTraceMetaMessageRestorer.SnapshotPointer value) { }
    private record Retained(String owner, List<Long> sessions, long expiresAt, byte[] json, Map<String, Object> metadata) { }
    static final class ExportFailure extends RuntimeException {
        final int status; final String reason;
        ExportFailure(int status, String reason) { super(reason); this.status = status; this.reason = reason; }
    }
    private static ExportFailure fail(int status, String reason) { return new ExportFailure(status, reason); }
    private static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw fail(400, "INVALID_SELECTION");
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) { throw fail(400, "INVALID_SELECTION"); }
    }

    public Map<String, Object> options(Actor actor, String before, int limit) {
        actor.identity();
        int size = Math.max(1, Math.min(100, limit));
        long cursor = before == null || before.isBlank() ? Long.MAX_VALUE : id(before);
        try (Connection c = dataSource.getConnection(); PreparedStatement p = prepare(c,
                "SELECT s.id,s.title FROM chat_session s LEFT JOIN administrators a ON s.admin_id=a.id "
                + "WHERE s.id<? AND ((s.admin_id IS NOT NULL AND a.username=?) "
                + "OR (s.admin_id IS NULL AND s.owner_key=?)) ORDER BY s.id DESC LIMIT ?",
                cursor, actor.named() ? actor.username : null, actor.exactKey() ? actor.ownerKey : null, size + 1)) {
            List<Map<String, Object>> items = new ArrayList<>();
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) items.add(Map.of("sessionId", Long.toString(r.getLong(1)), "title", text(r.getString(2))));
            }
            boolean more = items.size() > size;
            if (more) items.remove(items.size() - 1);
            return Map.of("items", items, "hasMore", more, "nextCursor",
                    more ? items.get(items.size() - 1).get("sessionId") : "", "scope", "own_only");
        } catch (SQLException failure) { throw fail(503, "EXPORT_STORAGE_UNAVAILABLE"); }
    }

    public Map<String, Object> capture(Actor actor, Selection selection) {
        String owner = actor.identity();
        if (selection == null || selection.sessionIds == null || selection.sessionIds.isEmpty()
                || selection.sessionIds.size() > MAX_SESSIONS) throw fail(400, "INVALID_SELECTION");
        List<Long> ids = selection.sessionIds.stream().map(ChatConversationExportSupport::id).distinct().toList();
        String current = selection.currentSessionId;
        if (current != null && !ids.contains(id(current))) throw fail(400, "INVALID_SELECTION");
        reserve(captureSlot, CAPTURE_WORK);
        try {
            Instant start = clock.instant();
            String exportId = UUID.randomUUID().toString();
            List<Map<String, Object>> sessions = new ArrayList<>();
            List<Map<String, Object>> fences = new ArrayList<>();
            long[] budget = {0, 0, 0}; // rows, raw/projected bytes and event rows, across the selection
            // A private connection avoids changing any enclosing application transaction.
            try (Connection c = dataSource.getConnection()) {
                c.setReadOnly(true);
                c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                c.setAutoCommit(false);
                try {
                    List<Owned> owned = new ArrayList<>();
                    // Validate ALL selected sessions before any message, pointer, event or attachment read.
                    for (Long sid : ids) owned.add(authorize(c, actor, sid));
                    for (Owned session : owned) {
                        Map<String, Object> result = readSession(c, session, budget);
                        sessions.add(result);
                        fences.add(Map.of("sessionId", Long.toString(session.id), "highWatermark", result.get("highWatermark")));
                    }
                } finally { c.rollback(); }
            }
            long publicCount = sessions.stream().mapToLong(s -> ((List<?>) s.get("messages")).size()).sum();
            String status = publicCount == 0 ? "empty" : "partial";
            Instant end = clock.instant();
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("currentSessionId", current);
            snapshot.put("selectedSessionIds", ids.stream().map(String::valueOf).toList());
            snapshot.put("fences", fences); snapshot.put("captureStartedAt", start.toString());
            snapshot.put("captureEndedAt", end.toString());
            snapshot.put("consistency", "serializable_db_fence_non_atomic_diagnostics");
            snapshot.put("contentUpdateDetection", "serializable_transaction");
            snapshot.put("messageComplete", true); snapshot.put("diagnosticCompleteness", "partial");
            snapshot.put("completeWithinFence", true);
            snapshot.put("messageCompleteScope", "captured_db_fence");
            snapshot.put("latestTurnCoverage", sessions.stream().map(s -> s.get("latestTurnCoverage").toString())
                    .min(Comparator.comparingInt(coverage -> switch (coverage) {
                        case "in_progress" -> 0;
                        case "awaiting_assistant" -> 1;
                        case "unknown" -> 2;
                        default -> 3;
                    })).orElse("unknown"));
            snapshot.put("exportStatus", status);
            snapshot.put("reasons", publicCount == 0 ? List.of("no_retained_messages")
                    : List.of("historical_raw_prompt_run_and_retrieval_not_fully_retained"));
            snapshot.put("limits", Map.of("selectedSessions", MAX_SESSIONS, "pageRows", PAGE,
                    "totalRows", MAX_ROWS, "messageBytes", ROW_BYTES, "jsonBytes", JSON_BYTES,
                    "ttlSeconds", 300, "globalSnapshots", 4, "ownerSnapshots", 2,
                    "retainedBytes", CACHE_BYTES));
            Map<String, Object> context = Map.of("schemaVersion", SCHEMA, "exportId", exportId,
                    "exportedAt", end.toString(), "snapshot", snapshot, "sessions", sessions,
                    "exclusions", List.of("system_and_developer_bodies", "hidden_reasoning", "credentials_and_owner_identity",
                            "raw_application_logs", "unbound_global_ledger_and_derived_memory"));
            byte[] json = encode(context, JSON_BYTES);
            Map<String, Object> metadata = Map.of("exportId", exportId, "schemaVersion", SCHEMA,
                    "exportedAt", end.toString(), "expiresAt", end.plusSeconds(300).toString(),
                    "snapshot", snapshot, "sha256", sha(json), "bytes", json.length);
            retain(exportId, new Retained(owner, ids, clock.millis() + 300_000, json, metadata));
            return metadata;
        } catch (SQLException failure) { throw fail(503, "EXPORT_STORAGE_UNAVAILABLE"); }
        finally { release(captureSlot, CAPTURE_WORK); }
    }

    public ResponseEntity<byte[]> download(Actor actor, String exportId, String format) {
        String owner = actor.identity();
        if (!"json".equals(format) && !"zip".equals(format)) throw fail(400, "INVALID_FORMAT");
        Retained retained;
        synchronized (cache) {
            expire(); retained = cache.get(exportId);
            if (retained == null || !retained.owner.equals(owner)) throw fail(410, "EXPORT_EXPIRED");
        }
        try (Connection c = dataSource.getConnection()) {
            for (long sid : retained.sessions) authorize(c, actor, sid);
        } catch (SQLException failure) { throw fail(503, "EXPORT_STORAGE_UNAVAILABLE"); }
        byte[] body = retained.json;
        if ("zip".equals(format)) {
            reserve(zipSlot, ZIP_WORK);
            try {
                Map<String, Object> manifest = Map.of("schemaVersion", SCHEMA, "exportId", exportId,
                        "context", retained.metadata, "files", Map.of("context.json", Map.of("sha256", sha(body), "bytes", body.length)));
                BoundedOutput out = new BoundedOutput(JSON_BYTES + (1 << 20));
                try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                    entry(zip, "context.json", body);
                    entry(zip, "manifest.json", encode(manifest, 64 << 10));
                    entry(zip, "README.txt", ("Own retained conversations only. context.json is byte-identical to the JSON download.\n"
                            + "Partial sources and retention limits are declared in context.json. No generation or upload occurred.\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                body = out.toByteArray();
            } catch (IOException failure) { throw fail(503, "EXPORT_ENCODING_FAILED"); }
            finally { release(zipSlot, ZIP_WORK); }
        }
        return ResponseEntity.ok().contentType("json".equals(format)
                ? new MediaType("application", "json", StandardCharsets.UTF_8) : MediaType.parseMediaType("application/zip"))
                .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment; filename=\"conversation-context-" + exportId + "." + format + "\"")
                .header("X-Export-Id", exportId).body(body);
    }

    private Owned authorize(Connection c, Actor actor, long sid) throws SQLException {
        try (PreparedStatement p = prepare(c, "SELECT s.title,s.owner_key,s.admin_id,a.username,LENGTH(s.session_meta) "
                + "FROM chat_session s LEFT JOIN administrators a ON s.admin_id=a.id WHERE s.id=?", sid);
                ResultSet r = p.executeQuery()) {
            if (!r.next()) throw fail(404, "SESSION_UNAVAILABLE");
            String key = r.getString(2), username = r.getString(4);
            Object admin = r.getObject(3);
            if (admin != null ? !actor.named() || !Objects.equals(username, actor.username)
                    : !actor.exactKey() || !Objects.equals(key, actor.ownerKey)) throw fail(404, "SESSION_UNAVAILABLE");
            if (r.getLong(5) > ROW_BYTES / 2) throw fail(413, "EXPORT_TOO_LARGE");
            String namespace = admin != null ? AttachmentOwnerIdentity.forAdministrator(username).hash()
                    : AttachmentOwnerIdentity.forAnonymous(key).hash();
            long epoch = 1;
            try (PreparedStatement meta = prepare(c, "SELECT session_meta FROM chat_session WHERE id=?", sid);
                    ResultSet m = meta.executeQuery()) {
                if (!m.next()) throw fail(409, "SNAPSHOT_CHANGED");
                String content = bounded(m.getCharacterStream(1), ROW_BYTES);
                if (content != null && !content.isBlank()) {
                    try {
                        var node = mapper.readTree(content).get("generalGraphConsentEpoch");
                        if (node != null) epoch = node.isIntegralNumber() && node.canConvertToLong() ? node.longValue() : -1;
                    } catch (IOException invalid) { epoch = -1; }
                }
            }
            return new Owned(sid, text(r.getString(1)), namespace, epoch);
        }
    }

    private Map<String, Object> readSession(Connection c, Owned session, long[] budget) throws SQLException {
        long fence, count;
        try (PreparedStatement p = prepare(c, "SELECT COALESCE(MAX(id),0),COUNT(*) FROM chat_message WHERE session_id=?", session.id);
                ResultSet r = p.executeQuery()) { r.next(); fence = r.getLong(1); count = r.getLong(2); }
        if (count > MAX_ROWS - budget[0]) throw fail(413, "EXPORT_TOO_LARGE");
        List<Row> rows = new ArrayList<>();
        long cursor = 0, excluded = 0;
        while (cursor < fence) {
            List<Long> page = new ArrayList<>();
            // Read lengths BEFORE materializing LOBs. Irrelevant system bodies are never read.
            try (PreparedStatement p = prepare(c, "SELECT id,role,LENGTH(content),CASE WHEN role IN ('user','assistant') "
                    + "OR (role='system' AND (content LIKE ? OR content LIKE ?)) THEN 1 ELSE 0 END "
                    + "FROM chat_message WHERE session_id=? AND id>? AND id<=? ORDER BY id ASC LIMIT ?",
                    "?TRACESNAP?%", USUM + "%", session.id, cursor, fence, PAGE); ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    if (r.getInt(4) == 1 && r.getLong(3) > ROW_BYTES / 2) throw fail(413, "EXPORT_TOO_LARGE");
                    page.add(r.getLong(1));
                }
            }
            if (page.isEmpty()) throw fail(409, "SNAPSHOT_CHANGED");
            for (long mid : page) {
                if (mid <= cursor) throw fail(409, "SNAPSHOT_CHANGED");
                try (PreparedStatement p = prepare(c, "SELECT role,created_at,CASE WHEN role IN ('user','assistant') "
                        + "OR (role='system' AND (content LIKE ? OR content LIKE ?)) THEN content ELSE NULL END "
                        + "FROM chat_message WHERE session_id=? AND id=?", "?TRACESNAP?%", USUM + "%", session.id, mid);
                        ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw fail(409, "SNAPSHOT_CHANGED");
                    String content = bounded(r.getCharacterStream(3), ROW_BYTES);
                    if (content != null) {
                        budget[1] += content.getBytes(StandardCharsets.UTF_8).length;
                        if (budget[1] > JSON_BYTES) throw fail(413, "EXPORT_TOO_LARGE");
                    }
                    rows.add(new Row(mid, r.getString(1), content, Objects.toString(r.getTimestamp(2), "")));
                }
                cursor = mid; budget[0]++;
            }
        }
        if (rows.size() != count) throw fail(409, "SNAPSHOT_CHANGED");
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<Long, Row> byId = new LinkedHashMap<>();
        List<Pointer> pointers = new ArrayList<>();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Row row : rows) {
            byId.put(row.id, row);
            if ("user".equals(row.role) || "assistant".equals(row.role)) {
                messages.add(Map.of("messageId", Long.toString(row.id), "role", row.role,
                        "createdAt", row.createdAt, "content", text(row.content)));
            } else {
                excluded++;
                if ("system".equals(row.role)) {
                    ChatTraceMetaMessageRestorer.parseSnapshotPointer(row.content, row.id)
                            .ifPresent(pointer -> pointers.add(new Pointer(row.id, pointer)));
                    if (row.content != null && row.content.startsWith(USUM)) summaries.add(summary(row));
                }
            }
        }
        List<Map<String, Object>> turns = new ArrayList<>();
        Set<Long> bound = new HashSet<>();
        for (Pointer pointer : pointers) {
            var value = pointer.value;
            Long assistant = value.assistantMessageId();
            Row target = assistant == null ? null : byId.get(assistant);
            boolean conflict = pointers.stream().filter(p -> Objects.equals(p.value.assistantMessageId(), assistant)
                    || p.value.snapshotId().equals(value.snapshotId())).count() != 1;
            if (target == null || !"assistant".equals(target.role) || conflict) {
                turns.add(Map.of("tracePointerMessageId", Long.toString(pointer.messageId),
                        "traceSnapshotId", value.snapshotId(), "status", "unavailable", "reason",
                        conflict ? "conflicting_pointer" : assistant == null ? "legacy_unbound" : "assistant_not_in_session"));
                continue;
            }
            bound.add(assistant);
            Map<String, Object> turn = turn(session, pointer, budget);
            budget[1] += encode(turn, JSON_BYTES).length;
            if (budget[1] > JSON_BYTES) throw fail(413, "EXPORT_TOO_LARGE");
            turns.add(turn);
        }
        for (Row row : rows) if ("assistant".equals(row.role) && !bound.contains(row.id)) {
            turns.add(Map.of("assistantMessageId", Long.toString(row.id), "trace", source("unavailable", "no_exact_pointer", "durable_db")));
        }
        linkReceipts(c, session, byId, summaries);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", Long.toString(session.id)); result.put("title", session.title);
        result.put("highWatermark", Long.toString(fence)); result.put("countAtCapture", count);
        result.put("countExported", rows.size()); result.put("lastCursor", Long.toString(cursor));
        result.put("excludedSystemCount", excluded); result.put("messages", messages); result.put("turns", turns);
        result.put("understanding", summaries); result.put("attachments", attachments(c, session));
        Boolean running = runs == null ? null : runs.isRunning(session.id);
        result.put("inProgress", running == null ? "unknown" : running);
        result.put("runStateSampledAt", clock.instant().toString());
        boolean awaitingAssistant = !messages.isEmpty()
                && "user".equals(messages.get(messages.size() - 1).get("role"));
        result.put("latestTurnCoverage", Boolean.TRUE.equals(running) ? "in_progress"
                : awaitingAssistant ? "awaiting_assistant"
                : running == null ? "unknown" : "persisted_within_fence");
        result.put("sessionDerivedData", source("excluded", "ownership_or_exact_link_unproven", "derived_stores"));
        return result;
    }

    private Map<String, Object> turn(Owned session, Pointer pointer, long[] budget) {
        var value = pointer.value;
        Map<String, Object> diagnostics = new LinkedHashMap<>(value.diagnostics());
        var snapshot = traces == null ? null : traces.get(value.snapshotId()).orElse(null);
        // Authorize the numeric DB session first; accept only its two exact producer formats.
        Set<String> sessionHashes = Set.of(SafeRedactor.hashValue(Long.toString(session.id)),
                SafeRedactor.hashValue("chat-" + session.id));
        boolean sameSession = snapshot != null && snapshot.sessionId() != null && sessionHashes.contains(snapshot.sessionId())
                && (snapshot.sid() == null || snapshot.sessionId().equals(snapshot.sid()));
        String ringReason = snapshot == null ? "ring_expired_or_restarted"
                : sameSession ? "safe_typed_projection_only" : "session_hash_missing_or_mismatch";
        List<String> conflicts = new ArrayList<>();
        if (sameSession) {
            // Reuse the envelope's exact typed projector and decoder; do not serialize raw trace maps.
            Map<String, String> projected = ChatTraceMetaMessageRestorer.projectDiagnostics(snapshot.trace());
            for (var e : projected.entrySet()) {
                String envelope = "storageMode=durable_fallback\nreason=retained\nmethod=GET\npathHash=none\nassistantMessageId="
                        + value.assistantMessageId() + "\n" + e.getKey() + "=" + e.getValue() + "\n";
                String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
                var decoded = ChatTraceMetaMessageRestorer.parseSnapshotPointer("?TRACESNAP?" + value.snapshotId() + "|v3|" + encoded, pointer.messageId);
                if (decoded.isPresent()) decoded.get().diagnostics().forEach((key, val) -> {
                    if (diagnostics.containsKey(key) && !Objects.equals(diagnostics.get(key), val)) conflicts.add(key);
                    else diagnostics.putIfAbsent(key, val);
                });
            }
        }
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("assistantMessageId", Long.toString(value.assistantMessageId())); turn.put("userMessageId", null);
        turn.put("tracePointerMessageId", Long.toString(pointer.messageId)); turn.put("traceSnapshotId", value.snapshotId());
        turn.put("trace", Map.of("status", diagnostics.isEmpty() ? "unavailable" : "partial",
                "reason", "durable_projection_with_optional_exact_ring", "diagnostics", diagnostics,
                "ring", source(sameSession ? "partial" : "unavailable", ringReason, "process_ring"), "conflicts", conflicts));
        turn.put("events", sameSession ? correlatedEvents(snapshot, snapshot.sessionId(), budget) : source("unavailable", ringReason, "process_ring"));
        turn.put("run", source("unavailable", "historical_exact_run_not_recorded", "process_registry"));
        turn.put("evidence", source("partial", "saved_answer_sources_only_raw_retrieval_not_retained", "assistant_body"));
        turn.put("prompt", source("partial", "typed_metadata_only_delivery_unknown", "durable_pointer"));
        return turn;
    }

    private Map<String, Object> correlatedEvents(TraceSnapshotStore.TraceSnapshot snapshot, String sessionHash, long[] budget) {
        String request = hash(snapshot.requestId()), trace = hash(snapshot.traceId());
        if (events == null || (request == null && trace == null)) return source("unavailable", "correlation_or_store_unavailable", "process_ring");
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>(); String after = null, reason = "bounded_current_ring";
        int rejected = 0;
        for (int pageCount = 0; pageCount < 20; pageCount++) {
            int remaining = (int) Math.min(PAGE, 4000 - budget[2]);
            if (remaining <= 0) { reason = "event_capture_limit"; break; }
            var page = events.page(request, trace, after, remaining);
            if (page.items().size() > remaining) throw fail(503, "EXPORT_DIAGNOSTICS_INVALID");
            budget[2] += page.items().size();
            if ("evicted".equals(page.cursorStatus())) { reason = "cursor_evicted"; break; }
            for (var event : page.items()) {
                if (!sessionHash.equals(event.sid())) { rejected++; continue; }
                if (seen.add(event.id())) result.add(Map.of("id", label(event.id()), "ts", event.ts().toString(),
                        "level", event.level().name(), "probe", event.probe().name(), "fingerprint", label(event.fingerprint()),
                        "messageHash", Objects.toString(SafeRedactor.hashValue(event.message()), "none")));
            }
            if (!page.hasMore()) break;
            if (page.nextId() == null || page.nextId().equals(after)) { reason = "cursor_not_advanced"; break; }
            after = page.nextId();
            if (pageCount == 19) reason = budget[2] >= 4000 ? "event_capture_limit" : "event_page_limit";
        }
        return Map.of("status", result.isEmpty() ? "unavailable" : "partial", "reason", reason,
                "storageScope", "process_ring", "historyComplete", false, "items", result, "unboundExcludedCount", rejected);
    }

    private Map<String, Object> summary(Row row) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("usumMessageId", Long.toString(row.id)); summary.put("status", "partial");
        summary.put("reason", "legacy_usum_unbound"); summary.put("storageScope", "durable_session");
        summary.put("unboundToTurn", true); summary.put("historyComplete", false);
        List<Map<String, String>> citations = new ArrayList<>();
        try {
            var root = mapper.readTree(row.content.substring(USUM.length()));
            var values = root.path("citations");
            if (values.isArray()) for (var v : values) {
                if (citations.size() == 100) { summary.put("reason", "citation_limit"); break; }
                String url = safeUrl(v.path("url").asText());
                if (url != null) citations.add(Map.of("url", url, "title", text(v.path("title").asText())));
            }
        } catch (IOException invalid) { summary.put("reason", "invalid_usum_json"); }
        summary.put("citations", citations); return summary;
    }

    private void linkReceipts(Connection c, Owned session, Map<Long, Row> rows, List<Map<String, Object>> summaries) throws SQLException {
        try (PreparedStatement p = prepare(c, "SELECT user_message_id,user_revision,assistant_message_id,assistant_revision,"
                + "usum_message_id,result_sha256,consent_epoch FROM awx_understanding_receipts "
                + "WHERE owner_namespace=? AND session_id=? AND channel='GENERAL' AND kind='UNDERSTANDING' "
                + "AND receipt_state='PERSISTED' ORDER BY usum_message_id LIMIT ?", session.namespace, session.id, MAX_ROWS + 1);
                ResultSet r = p.executeQuery()) {
            Set<Long> linked = new HashSet<>();
            int count = 0;
            while (r.next()) {
                if (++count > MAX_ROWS) throw fail(413, "EXPORT_TOO_LARGE");
                Row user = rows.get(r.getLong(1)), assistant = rows.get(r.getLong(3)), usum = rows.get(r.getLong(5));
                if (user == null || assistant == null || usum == null || !"user".equals(user.role)
                        || !"assistant".equals(assistant.role) || !"system".equals(usum.role) || usum.content == null
                        || !usum.content.startsWith(USUM) || revision(user) != r.getLong(2) || revision(assistant) != r.getLong(4)
                        || session.epoch <= 0 || session.epoch != r.getLong(7)
                        || !sha(usum.content.substring(USUM.length()).getBytes(StandardCharsets.UTF_8)).equals(r.getString(6))) continue;
                for (var summary : summaries) if (Long.toString(usum.id).equals(summary.get("usumMessageId"))) {
                    if (!linked.add(usum.id)) { summary.remove("userMessageId"); summary.remove("assistantMessageId");
                        summary.put("reason", "conflicting_receipts"); summary.put("unboundToTurn", true); }
                    else { summary.put("userMessageId", Long.toString(user.id)); summary.put("assistantMessageId", Long.toString(assistant.id));
                        summary.put("reason", "validated_receipt_exact_link"); summary.put("unboundToTurn", false); }
                }
            }
        } catch (SQLException failure) {
            if (!missingTable(failure)) throw failure;
            for (var summary : summaries) summary.put("reason", "receipt_store_unavailable_legacy_unbound");
        }
    }

    private Map<String, Object> attachments(Connection c, Owned session) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        String after = ""; long now = clock.millis();
        try {
            long count;
            try (PreparedStatement p = prepare(c, "SELECT COUNT(*) FROM attachment_source WHERE owner_namespace=? AND session_id=? "
                    + "AND channel='GENERAL' AND tombstone=FALSE AND (expires_at<=0 OR expires_at>?)",
                    session.namespace, Long.toString(session.id), now); ResultSet r = p.executeQuery()) { r.next(); count = r.getLong(1); }
            if (count > 1000) throw fail(413, "EXPORT_TOO_LARGE");
            while (result.size() < count) {
                int previous = result.size();
                try (PreparedStatement p = prepare(c, "SELECT id,content_sha256,source_revision,parser_version,size_bytes,retained_at,expires_at,"
                        + "text_state,graph_state,vector_state,consent_epoch FROM attachment_source WHERE owner_namespace=? AND session_id=? "
                        + "AND channel='GENERAL' AND tombstone=FALSE AND (expires_at<=0 OR expires_at>?) AND id>? ORDER BY id LIMIT ?",
                        session.namespace, Long.toString(session.id), now, after, PAGE); ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        after = r.getString(1);
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("sourceId", label(after)); item.put("contentSha256", digestLabel(r.getString(2)));
                        item.put("sourceRevision", Long.toString(r.getLong(3))); item.put("parserVersion", label(r.getString(4)));
                        item.put("sizeBytes", r.getLong(5)); item.put("retainedAt", r.getLong(6)); item.put("expiresAt", r.getLong(7));
                        item.put("textState", label(r.getString(8))); item.put("graphState", label(r.getString(9)));
                        item.put("vectorState", label(r.getString(10))); item.put("unboundToTurn", true);
                        item.put("status", r.getLong(11) == session.epoch && r.getLong(3) > 0 ? "partial" : "excluded");
                        item.put("reason", r.getLong(11) == session.epoch && r.getLong(3) > 0
                                ? "session_owned_metadata_not_retrieval_proof" : "consent_or_revision_mismatch");
                        result.add(item);
                    }
                }
                if (result.size() == previous || result.size() > count) throw fail(409, "SNAPSHOT_CHANGED");
            }
            return Map.of("status", "partial", "reason", "safe_metadata_only_raw_units_excluded", "storageScope", "durable_session",
                    "historyComplete", true, "countAtCapture", count, "countExported", result.size(), "items", result);
        } catch (SQLException failure) {
            if (!missingTable(failure)) throw failure;
            return source("unavailable", "attachment_store_not_configured", "durable_db");
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement p = c.prepareStatement(sql);
        try { p.setQueryTimeout(30); for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]); return p; }
        catch (SQLException failure) { p.close(); throw failure; }
    }
    private static String bounded(Reader reader, int maxBytes) {
        if (reader == null) return null;
        try (reader) {
            StringBuilder value = new StringBuilder(); char[] buffer = new char[4096]; int count;
            while ((count = reader.read(buffer)) != -1) {
                if (value.length() + count > maxBytes / 2) throw fail(413, "EXPORT_TOO_LARGE");
                value.append(buffer, 0, count);
            }
            String result = value.toString();
            if (result.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw fail(413, "EXPORT_TOO_LARGE");
            return result;
        } catch (IOException failure) { throw fail(503, "EXPORT_STORAGE_UNAVAILABLE"); }
    }
    private byte[] encode(Object value, int maxBytes) {
        try { BoundedOutput out = new BoundedOutput(maxBytes); mapper.writeValue(out, value); return out.toByteArray(); }
        catch (IOException failure) {
            Throwable cause = failure;
            while (cause != null) { if (cause instanceof ExportFailure limit) throw limit; cause = cause.getCause(); }
            throw fail(503, "EXPORT_ENCODING_FAILED");
        }
    }
    private static final class BoundedOutput extends ByteArrayOutputStream {
        final int max;
        BoundedOutput(int max) { this.max = max; }
        @Override public synchronized void write(int b) { check(1); super.write(b); }
        @Override public synchronized void write(byte[] b, int off, int len) { check(len); super.write(b, off, len); }
        private void check(int added) { if (added > max - count) throw fail(413, "EXPORT_TOO_LARGE"); }
    }
    private static void entry(ZipOutputStream zip, String name, byte[] body) throws IOException {
        ZipEntry entry = new ZipEntry(name); entry.setTime(0); zip.putNextEntry(entry); zip.write(body); zip.closeEntry();
    }
    private void reserve(Semaphore slot, long bytes) {
        if (!slot.tryAcquire()) throw fail(503, "EXPORT_BUSY");
        synchronized (cache) {
            if (workingBytes + bytes > WORK_BUDGET) { slot.release(); throw fail(503, "EXPORT_BUSY"); }
            workingBytes += bytes;
        }
    }
    private void release(Semaphore slot, long bytes) { synchronized (cache) { workingBytes -= bytes; } slot.release(); }
    private void retain(String id, Retained value) {
        synchronized (cache) {
            expire();
            while (cache.values().stream().filter(v -> v.owner.equals(value.owner)).count() >= 2) {
                remove(cache.entrySet().stream().filter(e -> e.getValue().owner.equals(value.owner)).findFirst().orElseThrow().getKey());
            }
            while (cache.size() >= 4 || retainedBytes + value.json.length > CACHE_BYTES) remove(cache.keySet().iterator().next());
            cache.put(id, value); retainedBytes += value.json.length;
        }
    }
    private void expire() { new ArrayList<>(cache.keySet()).forEach(k -> { if (cache.get(k).expiresAt <= clock.millis()) remove(k); }); }
    private void remove(String id) { retainedBytes -= cache.remove(id).json.length; }
    long workingBytes() { synchronized (cache) { return workingBytes; } }
    long retainedBytes() { synchronized (cache) { expire(); return retainedBytes; } }
    private static boolean missingTable(SQLException failure) { return "42S02".equals(failure.getSQLState()) || "42P01".equals(failure.getSQLState()); }
    private static Map<String, Object> source(String status, String reason, String storage) {
        return Map.of("status", status, "reason", reason, "storageScope", storage, "historyComplete", false);
    }
    private static String text(String value) {
        String masked = Objects.toString(SafeRedactor.redact(value), "");
        // Transcript fields can contain user-pasted headers and capability identifiers too.
        return masked.replaceAll("(?i)(cookie|set-cookie|authorization|x-owner-key|ownerKey|runToken|runId|sessionToken)(\\s*[:=]\\s*)[^\\r\\n,;]+", "$1$2[redacted]");
    }
    private static String hash(String value) { return value != null && value.matches("hash:[0-9a-f]{12}") ? value : null; }
    private static String digestLabel(String value) { return value != null && value.matches("[0-9a-f]{64}") ? value : "unknown"; }
    private static String label(String value) { return value != null && value.matches("[A-Za-z0-9_.:-]{1,128}") ? value : "unknown"; }
    private static String safeUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) return null;
            return new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (Exception invalid) { return null; }
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static long revision(Row row) {
        byte[] digest = HexFormat.of().parseHex(sha((row.role + "\u0000" + row.content).getBytes(StandardCharsets.UTF_8)));
        long value = ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE; return value == 0 ? 1 : value;
    }
}
