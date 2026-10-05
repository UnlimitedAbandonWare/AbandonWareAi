package com.example.lms.api;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class ChatTraceBundleResponseBuilder {
    private ChatTraceBundleResponseBuilder() { }

    static ResponseEntity<?> build(ChatTraceMetaMessageRestorer.SnapshotPointer pointer,
            LocalDateTime capturedAt, TraceSnapshotStore.TraceSnapshot snapshot,
            ObjectMapper objectMapper, DebugEventStore traceBundleEvents,
            Supplier<String> buildVersion, Consumer<String> logSuppressed) {
        try {
            Map<String, byte[]> files = new java.util.LinkedHashMap<>();
            Map<String, Object> sources = new java.util.LinkedHashMap<>();
            files.put("summary.json", objectMapper.writeValueAsBytes(pointer.projection()));
            if (!pointer.diagnostics().isEmpty()) files.put("trace.json", objectMapper.writeValueAsBytes(pointer.diagnostics()));
            sources.put("summary", Map.of("status", "available", "source", "chat_system_pointer"));
            sources.put("trace", Map.of("status", pointer.diagnostics().isEmpty() ? "unavailable" : "available",
                    "reason", pointer.diagnostics().isEmpty() ? "legacy_summary_only" : "safe_projection_only",
                    "retainedFieldCount", pointer.diagnostics().size()));
            // TraceSnapshotStore already hashes these IDs; hashing again breaks the exact join.
            String requestHash = snapshot != null && snapshot.requestId() != null
                    && snapshot.requestId().matches("hash:[0-9a-f]{12}") ? snapshot.requestId() : null;
            String traceHash = snapshot != null && snapshot.traceId() != null
                    && snapshot.traceId().matches("hash:[0-9a-f]{12}") ? snapshot.traceId() : null;
            if (traceBundleEvents != null && requestHash != null) {
                var page = traceBundleEvents.page(requestHash, traceHash, null, 200);
                StringBuilder events = new StringBuilder();
                for (var event : page.items()) {
                    // One event ID per store event; file mirrors are never collected again.
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", event.id());
                    row.put("ts", event.ts().toString());
                    row.put("level", event.level().name());
                    row.put("probe", event.probe().name());
                    row.put("fingerprint", event.fingerprint());
                    row.put("requestIdHash", event.requestId());
                    row.put("traceIdHash", event.traceId());
                    row.put("messageHash", SafeRedactor.hashValue(event.message()));
                    events.append(objectMapper.writeValueAsString(row)).append('\n');
                }
                if (!page.items().isEmpty()) files.put("events.ndjson", events.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                sources.put("events", Map.of("status", page.items().isEmpty() ? "unavailable" : "available",
                        "reason", page.items().isEmpty() ? "not_in_retained_ring" : "bounded_current_ring",
                        "count", page.items().size(), "truncated", page.hasMore(), "historyComplete", false));
            } else {
                sources.put("events", Map.of("status", "unavailable",
                        "reason", snapshot == null ? "ring_expired_or_restarted" : "correlation_or_store_unavailable"));
            }
            sources.put("logs", Map.of("status", "unavailable",
                    "reason", "raw_application_logs_not_collected",
                    "structuredFailurePatterns", "included_in_events_when_retained"));
            files.put("README.txt", ("This answer only. Summary and typed trace fields use the existing chat store.\n"
                    + "Events, when present, are bounded correlated ring metadata; they are not durable log history.\n"
                    + "Missing sources are declared in manifest.json. No raw prompt, query, log, path or secret is exported.\n"
                    + "Opening or exporting does not run models, retrieval or memory writes.\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Map<String, Object> checksums = new java.util.LinkedHashMap<>();
            for (var file : files.entrySet()) checksums.put(file.getKey(),
                    java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(file.getValue())));
            Map<String, Object> manifest = new java.util.LinkedHashMap<>();
            manifest.put("schema", "awx.answer-trace-bundle.v1");
            manifest.put("redactionVersion", "typed-pointer-v3");
            manifest.put("build", Optional.ofNullable(buildVersion.get()).orElse("not_observed"));
            manifest.put("snapshotId", pointer.snapshotId());
            manifest.put("assistantMessageId", pointer.assistantMessageId());
            manifest.put("capturedAt", capturedAt == null ? "not_observed" : capturedAt.toString());
            manifest.put("exportedAt", java.time.Instant.now().toString());
            manifest.put("terminalReason", pointer.projection().getOrDefault("reason", "not_observed"));
            manifest.put("ringScope", "current_process_only");
            manifest.put("durableScope", "existing_chat_store");
            manifest.put("sources", sources);
            manifest.put("checksumsSha256", checksums);
            if (requestHash != null) manifest.put("requestIdHash", requestHash);
            if (traceHash != null) manifest.put("traceIdHash", traceHash);
            files.put("manifest.json", objectMapper.writeValueAsBytes(manifest));
            if (files.values().stream().mapToInt(bytes -> bytes.length).sum() > 262_144)
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
            var out = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(out)) {
                for (var file : files.entrySet()) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(file.getKey()));
                    zip.write(file.getValue());
                    zip.closeEntry();
                }
            }
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                    .header("Cache-Control", "no-store")
                    .header("Content-Disposition", "attachment; filename=\"answer-trace-bundle.zip\"")
                    .body(out.toByteArray());
        } catch (Exception ignored) {
            logSuppressed.accept("chat.traceBundle");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

}
