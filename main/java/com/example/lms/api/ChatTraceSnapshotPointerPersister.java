package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class ChatTraceSnapshotPointerPersister {

    private static final String TRACE_SNAPSHOT_META_PREFIX = "?TRACESNAP?";
    private static final String DURABLE_ENVELOPE_VERSION = "v2";
    private static final int MAX_DURABLE_PROJECTION_BYTES = 2_048;

    private ChatTraceSnapshotPointerPersister() {
    }

    static Long persist(
            Long sessionId,
            Long assistantMessageId,
            String reason,
            String method,
            String path,
            Map<String, Object> traceMeta,
            String traceHtml,
            TraceSnapshotStore traceSnapshotStore,
            ChatHistoryService historyService,
            Logger log) {
        return persist(sessionId, assistantMessageId, reason, method, path, traceMeta, traceHtml,
                traceSnapshotStore, historyService, log, true);
    }

    static Long persist(
            Long sessionId, Long assistantMessageId, String reason, String method, String path,
            Map<String, Object> traceMeta, String traceHtml, TraceSnapshotStore traceSnapshotStore,
            ChatHistoryService historyService, Logger log, boolean renderHtml) {
        return persist(sessionId, assistantMessageId, reason, method, path, traceMeta, traceHtml,
                traceSnapshotStore, historyService, log, renderHtml, java.util.List.of());
    }

    static Long persist(
            Long sessionId, Long assistantMessageId, String reason, String method, String path,
            Map<String, Object> traceMeta, String traceHtml, TraceSnapshotStore traceSnapshotStore,
            ChatHistoryService historyService, Logger log, boolean renderHtml,
            java.util.List<com.example.lms.dto.RagEvidenceMetadata> evidence) {
        if (sessionId == null || assistantMessageId == null || assistantMessageId <= 0L
                || traceSnapshotStore == null) {
            return null;
        }
        boolean missingTraceHtml = traceHtml == null || traceHtml.isBlank();
        boolean durableProjection = (!renderHtml && traceMeta != null && !traceMeta.isEmpty())
                || (missingTraceHtml && evidence != null && !evidence.isEmpty());
        boolean metadataOnlyTraceMemory = renderHtml && isTraceMemorySnapshot(traceMeta) && missingTraceHtml;
        boolean metadataOnlyHarmony = renderHtml && isAgentVisibleHarmony(traceMeta) && missingTraceHtml;
        String snapshotHtml = metadataOnlyTraceMemory
                ? metadataOnlyTraceMemoryTraceHtml(traceMeta)
                : (metadataOnlyHarmony ? metadataOnlyHarmonyTraceHtml(traceMeta) : traceHtml);
        if (!durableProjection && (snapshotHtml == null || snapshotHtml.isBlank())) {
            return null;
        }
        try {
            Map<String, Object> snapMeta = new LinkedHashMap<>(traceMeta == null ? Map.of() : traceMeta);
            snapMeta.putIfAbsent("ui.traceHtml.kind", metadataOnlyTraceMemory ? "traceMemoryMetadataOnly"
                    : (metadataOnlyHarmony ? "chatHarmonyMetadataOnly" : "splitPanel"));
            snapMeta.putIfAbsent("ui.traceHtml.length", snapshotHtml == null ? 0 : snapshotHtml.length());
            if (durableProjection) {
                snapMeta.put("ui.traceHtml.kind", "durableProjection");
                snapMeta.put("ui.traceHtml.length", 0);
            }
            if (metadataOnlyTraceMemory || metadataOnlyHarmony) {
                snapMeta.putIfAbsent("ui.traceHtml.synthetic", true);
            }
            String snapshotId = durableProjection
                    ? traceSnapshotStore.captureCustom(reason, method, path, null, null, snapMeta, null, false)
                    : traceSnapshotStore.captureCustom(reason, method, path, null, null, snapMeta, snapshotHtml);
            if (!ChatTraceMetaMessageRestorer.isSafeTraceSnapshotId(snapshotId)) {
                return null;
            }
            return historyService.appendMessageReturningId(
                    sessionId,
                    "system",
                    durablePointer(snapshotId, assistantMessageId, reason, method, path, snapMeta, evidence));
        } catch (Exception e) {
            String safeErrorType = errorType(e);
            TraceStore.put("chat.traceSnapshotPointer.suppressed.stage", "persist");
            TraceStore.put("chat.traceSnapshotPointer.suppressed.errorType", safeErrorType);
            TraceStore.put("chat.traceSnapshotPointer.suppressed.persist", true);
            TraceStore.put("chat.traceSnapshotPointer.suppressed.persist.errorType", safeErrorType);
            log.debug("[AWX][trace] snapshot pointer skipped reason={} errorType={}",
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"), safeErrorType);
            return null;
        }
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    private static String durablePointer(
            String snapshotId,
            Long assistantMessageId,
            String reason,
            String method,
            String path,
            Map<String, Object> traceMeta,
            java.util.List<com.example.lms.dto.RagEvidenceMetadata> evidence) {
        StringBuilder projection = new StringBuilder(512);
        appendProjection(projection, "storageMode", "durable_fallback");
        if (assistantMessageId != null && assistantMessageId > 0L) {
            appendProjection(projection, "assistantMessageId", assistantMessageId);
        }
        appendProjection(projection, "reason", safeProjectionLabel(reason, "unknown"));
        appendProjection(projection, "method", safeProjectionLabel(method, "unknown"));
        appendProjection(projection, "pathHash", safeHash(path));
        appendProjection(projection, "traceEntryCount", boundedCount(traceMeta == null ? 0 : traceMeta.size()));
        appendProjection(projection, "hasMlBreadcrumbs", hasMlBreadcrumbs(traceMeta));
        appendProjection(projection, "uiTraceHtmlKind",
                safeProjectionLabel(traceMeta == null ? null : traceMeta.get("ui.traceHtml.kind"), "unknown"));
        appendProjection(projection, "uiTraceHtmlLength",
                boundedCount(traceMeta == null ? null : traceMeta.get("ui.traceHtml.length")));
        appendOptionalProjectionLabel(projection, "harmonyDecision", traceMeta,
                "chat.harmony.postprocess.decision");
        appendOptionalProjectionLabel(projection, "harmonyReason", traceMeta,
                "chat.harmony.postprocess.reason");
        appendOptionalProjectionLabel(projection, "traceMemoryStage", traceMeta,
                "traceMemory.checkpoint.stage");
        appendOptionalProjectionLabel(projection, "traceMemoryReason", traceMeta,
                "traceMemory.trigger.reason");

        Map<String, String> diagnostics = ChatTraceMetaMessageRestorer.projectDiagnostics(traceMeta);
        String publicEvidence = ChatTraceMetaMessageRestorer.encodePublicEvidence(evidence);
        boolean detail = !diagnostics.isEmpty() || publicEvidence != null;
        String version = detail ? "v3" : DURABLE_ENVELOPE_VERSION;
        if (publicEvidence != null) appendProjection(projection, "publicEvidence", publicEvidence);
        diagnostics.forEach((key, value) -> {
            String field = key + "=" + value + "\n";
            if (publicEvidence == null || (projection.toString() + field).getBytes(StandardCharsets.UTF_8).length
                    <= ChatTraceMetaMessageRestorer.MAX_DETAIL_BYTES) projection.append(field);
        });
        byte[] bytes = projection.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > (!detail ? MAX_DURABLE_PROJECTION_BYTES
                : ChatTraceMetaMessageRestorer.MAX_DETAIL_BYTES)) {
            version = DURABLE_ENVELOPE_VERSION;
            String minimal = "storageMode=durable_fallback\n"
                    + (assistantMessageId != null && assistantMessageId > 0L
                    ? "assistantMessageId=" + assistantMessageId + "\n" : "")
                    + "reason=" + safeProjectionLabel(reason, "unknown") + "\n"
                    + "method=" + safeProjectionLabel(method, "unknown") + "\n"
                    + "pathHash=" + safeHash(path) + "\n";
            bytes = minimal.getBytes(StandardCharsets.UTF_8);
        }
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return TRACE_SNAPSHOT_META_PREFIX + snapshotId + "|" + version + "|" + encoded;
    }

    private static void appendProjection(StringBuilder out, String key, Object value) {
        out.append(key).append('=').append(value).append('\n');
    }

    private static void appendOptionalProjectionLabel(
            StringBuilder out,
            String projectionKey,
            Map<String, Object> traceMeta,
            String sourceKey) {
        if (traceMeta == null || !traceMeta.containsKey(sourceKey)) {
            return;
        }
        appendProjection(out, projectionKey, safeProjectionLabel(traceMeta.get(sourceKey), "unknown"));
    }

    private static String safeProjectionLabel(Object value, String fallback) {
        return SafeRedactor.traceLabelOrFallback(value, fallback);
    }

    private static String safeHash(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "none" : hash;
    }

    private static long boundedCount(Object value) {
        long count;
        if (value instanceof Number number) {
            count = number.longValue();
        } else {
            try {
                count = value == null ? 0L : Long.parseLong(String.valueOf(value));
            } catch (RuntimeException ignored) {
                count = 0L;
            }
        }
        return Math.max(0L, Math.min(1_000_000L, count));
    }

    private static boolean hasMlBreadcrumbs(Map<String, Object> traceMeta) {
        if (traceMeta == null || traceMeta.isEmpty()) {
            return false;
        }
        for (String key : traceMeta.keySet()) {
            if (key != null && (key.startsWith("ml.") || key.startsWith("orch."))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAgentVisibleHarmony(Map<String, Object> traceMeta) {
        if (traceMeta == null) {
            return false;
        }
        Object value = traceMeta.get("chat.harmony.postprocess.agentVisible");
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean isTraceMemorySnapshot(Map<String, Object> traceMeta) {
        if (traceMeta == null || traceMeta.isEmpty()) {
            return false;
        }
        if (traceMeta.containsKey("traceMemory.fingerprint.current")
                || traceMeta.containsKey("traceMemory.checkpoint.stage")
                || traceMeta.containsKey("traceMemory.triggered")) {
            return true;
        }
        for (String key : traceMeta.keySet()) {
            if (key != null && key.startsWith("traceMemory.")) {
                return true;
            }
        }
        return false;
    }

    private static String metadataOnlyTraceMemoryTraceHtml(Map<String, Object> traceMeta) {
        return "<section data-trace=\"trace-memory\" data-kind=\"metadata-only\">"
                + "<h3>Trace Memory Checkpoint</h3>"
                + "<dl>"
                + item("Checkpoint stage", traceMeta.get("traceMemory.checkpoint.stage"))
                + item("Checkpoint phase", traceMeta.get("traceMemory.checkpoint.phase"))
                + item("Checkpoint history", traceMeta.get("traceMemory.checkpoint.historySize"))
                + item("Triggered", traceMeta.get("traceMemory.triggered"))
                + item("Reason", traceMeta.get("traceMemory.trigger.reason"))
                + item("Failure class", traceMeta.get("traceMemory.recovery.failureClass"))
                + item("Recovery action", traceMeta.get("traceMemory.recovery.action"))
                + item("Recovery route", traceMeta.get("traceMemory.recovery.route"))
                + item("Quarantine", traceMeta.get("traceMemory.recovery.quarantine"))
                + item("Suspect isolated", traceMeta.get("traceMemory.suspectPayload.isolated"))
                + item("Error break risk", traceMeta.get("traceMemory.errorBreak.risk"))
                + item("CFVM offered", traceMeta.get("traceMemory.cfvm.offered"))
                + item("Supabase shadow count", traceMeta.get("traceMemory.rawSnapshot.supabaseShadowCount"))
                + item("Delta changed", traceMeta.get("traceMemory.delta.changed"))
                + item("Delta changed count", traceMeta.get("traceMemory.delta.changedCount"))
                + item("Dropped breadcrumbs", traceMeta.get("traceMemory.delta.droppedBreadcrumbCount"))
                + "</dl>"
                + "</section>";
    }

    private static String metadataOnlyHarmonyTraceHtml(Map<String, Object> traceMeta) {
        return "<section data-trace=\"chat-harmony\" data-kind=\"metadata-only\">"
                + "<h3>Chat Harmony Trace</h3>"
                + "<dl>"
                + item("Decision", traceMeta.get("chat.harmony.postprocess.decision"))
                + item("Reason", traceMeta.get("chat.harmony.postprocess.reason"))
                + item("Weighted score", traceMeta.get("chat.harmony.postprocess.weightedScore"))
                + item("Evidence count", traceMeta.get("chat.harmony.postprocess.evidenceCount"))
                + item("Next debug action", traceMeta.get("debug.ai.metrics.nextAction"))
                + item("Next debug reason", traceMeta.get("debug.ai.metrics.nextReason"))
                + "</dl>"
                + "</section>";
    }

    private static String item(String label, Object value) {
        return "<dt>" + escape(label) + "</dt><dd>" + escape(safeMetaValue(value)) + "</dd>";
    }

    private static String safeMetaValue(Object value) {
        if (value == null) {
            return "unknown";
        }
        String safe = SafeRedactor.traceLabelOrFallback(String.valueOf(value), "unknown");
        if (safe.length() > 80) {
            return safe.substring(0, 80);
        }
        return safe;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
