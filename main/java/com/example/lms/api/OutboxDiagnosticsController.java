package com.example.lms.api;

// MERGE_HOOK:PROJ_AGENT::OUTBOX_DIAGNOSTICS_ENDPOINT_V1

import ai.abandonware.nova.orch.storage.DegradedStorage;
import ai.abandonware.nova.orch.storage.DegradedStorageWithAck;
import ai.abandonware.nova.orch.storage.PendingMemoryEvent;
import com.example.lms.trace.SafeRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple monitoring surface for the degraded memory outbox.
 *
 * <p>Intended for internal diagnostics / dashboards. Security is expected to be enforced
 * at the API gateway / ingress layer.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/nova/outbox")
@PreAuthorize("hasRole('ADMIN')")
public class OutboxDiagnosticsController {

    private final ObjectProvider<DegradedStorage> storageProvider;

    public OutboxDiagnosticsController(ObjectProvider<DegradedStorage> storageProvider) {
        this.storageProvider = storageProvider;
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        DegradedStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "reason", "No DegradedStorage bean present"
            ));
        }

        if (storage instanceof DegradedStorageWithAck ack) {
            return ResponseEntity.ok(safeStats(ack.stats()));
        }

        return ResponseEntity.ok(Map.of(
                "enabled", true,
                "type", storage.getClass().getName(),
                "note", "Storage does not implement DegradedStorageWithAck; only basic drain is available"
        ));
    }

    @PostMapping("/sweep")
    public ResponseEntity<?> sweep() {
        DegradedStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return ResponseEntity.ok(Map.of(
                    "ok", false,
                    "reason", "No DegradedStorage bean present"
            ));
        }

        if (storage instanceof DegradedStorageWithAck ack) {
            return ResponseEntity.ok(safeSweep(ack.sweep()));
        }

        return ResponseEntity.ok(Map.of(
                "ok", false,
                "type", storage.getClass().getName(),
                "note", "Storage does not implement DegradedStorageWithAck; sweep is unavailable"
        ));
    }

    // MERGE_HOOK:PROJ_AGENT::OUTBOX_PEEK_ENDPOINT_V1
    @GetMapping("/peek")
    public ResponseEntity<?> peek(
            @RequestParam(defaultValue = "pending") String state,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "256") int maxSnippetChars
    ) {
        DegradedStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "reason", "No DegradedStorage bean present"
            ));
        }

        if (storage instanceof DegradedStorageWithAck ack) {
            // keep this endpoint reasonably bounded
            int safeLimit = Math.max(0, Math.min(limit, 200));
            int safeSnippet = Math.max(0, Math.min(maxSnippetChars, 4096));
            return ResponseEntity.ok(safePeek(ack.peek(state, safeLimit, safeSnippet)));
        }

        return ResponseEntity.ok(Map.of(
                "enabled", true,
                "type", storage.getClass().getName(),
                "note", "Storage does not implement DegradedStorageWithAck; peek is unavailable"
        ));
    }

    private static Map<String, Object> safeStats(DegradedStorageWithAck.OutboxStats stats) {
        if (stats == null) {
            return Map.of("enabled", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", stats.enabled());
        out.put("mode", SafeRedactor.traceLabelOrFallback(stats.mode(), ""));
        out.put("pathHash", SafeRedactor.hashValue(stats.path()));
        out.put("pathLength", stats.path() == null ? 0 : stats.path().length());
        out.put("pendingCount", stats.pendingCount());
        out.put("inflightCount", stats.inflightCount());
        out.put("totalBytes", stats.totalBytes());
        out.put("oldestCreatedAt", stats.oldestCreatedAt());
        out.put("newestCreatedAt", stats.newestCreatedAt());
        out.put("maxFiles", stats.maxFiles());
        out.put("maxBytes", stats.maxBytes());
        out.put("ttlSeconds", stats.ttlSeconds());
        out.put("inflightStaleSeconds", stats.inflightStaleSeconds());
        out.put("ackTotal", stats.ackTotal());
        out.put("nackTotal", stats.nackTotal());
        out.put("releaseTotal", stats.releaseTotal());
        out.put("droppedExpiredTotal", stats.droppedExpiredTotal());
        out.put("droppedByLimitTotal", stats.droppedByLimitTotal());
        out.put("parseErrorTotal", stats.parseErrorTotal());
        out.put("lastSweepEpochMs", stats.lastSweepEpochMs());
        return out;
    }

    private static Map<String, Object> safeSweep(DegradedStorageWithAck.OutboxSweepResult sweep) {
        if (sweep == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sweptAtEpochMs", sweep.sweptAtEpochMs());
        out.put("removedExpired", sweep.removedExpired());
        out.put("removedByMaxFiles", sweep.removedByMaxFiles());
        out.put("removedByMaxBytes", sweep.removedByMaxBytes());
        out.put("recoveredInflight", sweep.recoveredInflight());
        out.put("bytesBefore", sweep.bytesBefore());
        out.put("bytesAfter", sweep.bytesAfter());
        return out;
    }

    private static List<Map<String, Object>> safePeek(List<DegradedStorageWithAck.OutboxPeekItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (DegradedStorageWithAck.OutboxPeekItem item : items) {
            if (item == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tokenHash", SafeRedactor.hashValue(item.token()));
            row.put("tokenLength", item.token() == null ? 0 : item.token().length());
            row.put("state", SafeRedactor.traceLabelOrFallback(item.state(), ""));
            row.put("attemptCount", item.attemptCount());
            row.put("createdAt", item.createdAt());
            row.put("lastAttemptAt", item.lastAttemptAt());
            row.put("sizeBytes", item.sizeBytes());
            String lastError = item.lastError();
            row.put("lastErrorPresent", lastError != null && !lastError.isBlank());
            row.put("lastErrorHash", SafeRedactor.hashValue(lastError));
            row.put("lastErrorLength", lastError == null ? 0 : lastError.length());
            row.put("event", safeEvent(item.event()));
            row.put("meta", safeMeta(item.meta()));
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> safeEvent(PendingMemoryEvent event) {
        if (event == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionHash", SafeRedactor.hashValue(event.sessionKey()));
        out.put("sessionLength", event.sessionKey() == null ? 0 : event.sessionKey().length());
        out.put("contextHash", SafeRedactor.hashValue(event.contextKey()));
        out.put("contextLength", event.contextKey() == null ? 0 : event.contextKey().length());
        out.put("userQueryHash", safeHashToken(event.userQueryHash()));
        out.put("answerSnippetHash", SafeRedactor.hashValue(event.answerSnippet()));
        out.put("answerSnippetLength", event.answerSnippet() == null ? 0 : event.answerSnippet().length());
        out.put("occurredAt", event.occurredAt());
        out.put("sizeBytes", event.sizeBytes());
        out.put("reason", SafeRedactor.traceLabelOrFallback(event.reason(), ""));
        return out;
    }

    private static Map<String, Object> safeMeta(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            String key = SafeRedactor.traceLabelOrFallback(entry.getKey(), "field");
            out.put(key, SafeRedactor.diagnosticValue("outbox.meta." + key, entry.getValue()));
        }
        return out;
    }

    private static String safeHashToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.matches("(?i)[a-f0-9]{8,64}")) {
            return trimmed.toLowerCase(java.util.Locale.ROOT);
        }
        if (trimmed.startsWith("hash:")) {
            return SafeRedactor.traceLabelOrFallback(trimmed, "");
        }
        return SafeRedactor.hashValue(trimmed);
    }
}
