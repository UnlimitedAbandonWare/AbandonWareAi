package com.example.lms.api;

import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class ChatTraceMetaMessageRestorer {

    private static final Logger log = LoggerFactory.getLogger(ChatTraceMetaMessageRestorer.class);
    private static final String TRACE_META_PREFIX = "?TRACE?";
    private static final String TRACE_META_PREFIX_B64 = "?TRACE64?";
    private static final String TRACE_SNAPSHOT_META_PREFIX = "?TRACESNAP?";
    private static final int MAX_TRACE_META_B64_CHARS = 64_000;
    private static final String DURABLE_ENVELOPE_VERSION = "v1";
    private static final int MAX_DURABLE_PROJECTION_BYTES = 2_048;
    private static final int MAX_DURABLE_PROJECTION_B64_CHARS = 2_732;
    private static final int MAX_DURABLE_PROJECTION_FIELDS = 16;
    private static final Pattern SAFE_LABEL = Pattern.compile("[A-Za-z0-9_.:-]{1,80}");
    private static final Pattern SAFE_HASH = Pattern.compile("hash:[0-9a-f]{12}");
    private static final Set<String> LABEL_FIELDS = Set.of(
            "reason",
            "method",
            "uiTraceHtmlKind",
            "harmonyDecision",
            "harmonyReason",
            "traceMemoryStage",
            "traceMemoryReason");
    private static final Set<String> COUNT_FIELDS = Set.of(
            "traceEntryCount",
            "uiTraceHtmlLength");
    private static final Set<String> BOOLEAN_FIELDS = Set.of("hasMlBreadcrumbs");

    private ChatTraceMetaMessageRestorer() {
    }

    static Optional<ChatApiController.MessageDto> restore(
            Long turnId,
            String content,
            LocalDateTime timestamp,
            boolean exposeTrace) {
        if (content == null || !exposeTrace) {
            return Optional.empty();
        }
        if (content.startsWith(TRACE_SNAPSHOT_META_PREFIX)) {
            String pointer = content.substring(TRACE_SNAPSHOT_META_PREFIX.length()).trim();
            int firstDelimiter = pointer.indexOf('|');
            String snapshotId = firstDelimiter < 0 ? pointer : pointer.substring(0, firstDelimiter);
            if (!isSafeTraceSnapshotId(snapshotId)) {
                log.debug("[AWX][trace] trace snapshot pointer skipped reason=invalid_id messageId={}", turnId);
                return Optional.empty();
            }
            if (firstDelimiter < 0) {
                return Optional.of(new ChatApiController.MessageDto(
                        turnId,
                        "system",
                        traceSnapshotCard(snapshotId, Map.of()),
                        timestamp));
            }
            Map<String, String> durableProjection = parseDurableProjection(pointer, firstDelimiter, turnId);
            return Optional.of(new ChatApiController.MessageDto(
                    turnId,
                    "system",
                    traceSnapshotCard(snapshotId, durableProjection),
                    timestamp));
        }
        if (content.startsWith(TRACE_META_PREFIX)) {
            String html = content.substring(TRACE_META_PREFIX.length()).trim();
            return Optional.of(new ChatApiController.MessageDto(turnId, "system",
                    "traceHtml=" + SafeRedactor.diagnosticText("traceHtml", html, 12000), timestamp));
        }
        if (!content.startsWith(TRACE_META_PREFIX_B64)) {
            return Optional.empty();
        }

        String b64 = content.substring(TRACE_META_PREFIX_B64.length()).trim();
        if (b64.length() > MAX_TRACE_META_B64_CHARS) {
            log.warn("[AWX][trace] Trace64 payload skipped reason=too_large messageId={} chars={}", turnId, b64.length());
            return Optional.empty();
        }
        try {
            String html = new String(java.util.Base64.getDecoder().decode(b64),
                    java.nio.charset.StandardCharsets.UTF_8);
            return Optional.of(new ChatApiController.MessageDto(turnId, "system",
                    "traceHtml=" + SafeRedactor.diagnosticText("traceHtml", html, 12000), timestamp));
        } catch (IllegalArgumentException e) {
            log.debug("[AWX][trace] Trace64 payload skipped reason=invalid_base64 messageId={}", turnId);
            return Optional.empty();
        }
    }

    static boolean isSafeTraceSnapshotId(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank() || snapshotId.length() > 160) {
            return false;
        }
        for (int i = 0; i < snapshotId.length(); i++) {
            char ch = snapshotId.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '_' || ch == '.' || ch == ':';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> parseDurableProjection(String pointer, int firstDelimiter, Long turnId) {
        int secondDelimiter = pointer.indexOf('|', firstDelimiter + 1);
        if (secondDelimiter < 0
                || pointer.indexOf('|', secondDelimiter + 1) >= 0
                || !DURABLE_ENVELOPE_VERSION.equals(pointer.substring(firstDelimiter + 1, secondDelimiter))) {
            traceSnapshotEnvelopeSkipped(turnId, "invalid_envelope");
            return Map.of();
        }
        String encoded = pointer.substring(secondDelimiter + 1);
        if (encoded.isBlank() || encoded.length() > MAX_DURABLE_PROJECTION_B64_CHARS) {
            traceSnapshotEnvelopeSkipped(turnId, "invalid_size");
            return Map.of();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (decoded.length == 0 || decoded.length > MAX_DURABLE_PROJECTION_BYTES) {
                traceSnapshotEnvelopeSkipped(turnId, "invalid_size");
                return Map.of();
            }
            String text = new String(decoded, StandardCharsets.UTF_8);
            if (text.indexOf('\r') >= 0 || text.indexOf('\uFFFD') >= 0) {
                traceSnapshotEnvelopeSkipped(turnId, "invalid_encoding");
                return Map.of();
            }
            Map<String, String> projection = new LinkedHashMap<>();
            for (String line : text.split("\\n", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                if (projection.size() >= MAX_DURABLE_PROJECTION_FIELDS) {
                    traceSnapshotEnvelopeSkipped(turnId, "too_many_fields");
                    return Map.of();
                }
                int equals = line.indexOf('=');
                if (equals <= 0 || line.indexOf('=', equals + 1) >= 0) {
                    traceSnapshotEnvelopeSkipped(turnId, "invalid_field");
                    return Map.of();
                }
                String key = line.substring(0, equals);
                String value = line.substring(equals + 1);
                if (projection.containsKey(key) || !isValidDurableField(key, value)) {
                    traceSnapshotEnvelopeSkipped(turnId, "invalid_field");
                    return Map.of();
                }
                projection.put(key, value);
            }
            if (!"durable_fallback".equals(projection.get("storageMode"))
                    || !projection.containsKey("reason")
                    || !projection.containsKey("method")
                    || !projection.containsKey("pathHash")) {
                traceSnapshotEnvelopeSkipped(turnId, "missing_required_field");
                return Map.of();
            }
            return Map.copyOf(projection);
        } catch (IllegalArgumentException e) {
            traceSnapshotEnvelopeSkipped(turnId, "invalid_base64");
            return Map.of();
        }
    }

    private static boolean isValidDurableField(String key, String value) {
        if ("storageMode".equals(key)) {
            return "durable_fallback".equals(value);
        }
        if ("pathHash".equals(key)) {
            return "none".equals(value) || SAFE_HASH.matcher(value).matches();
        }
        if (LABEL_FIELDS.contains(key)) {
            return SAFE_LABEL.matcher(value).matches() || SAFE_HASH.matcher(value).matches();
        }
        if (COUNT_FIELDS.contains(key)) {
            try {
                long parsed = Long.parseLong(value);
                return parsed >= 0L && parsed <= 1_000_000L;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return BOOLEAN_FIELDS.contains(key) && ("true".equals(value) || "false".equals(value));
    }

    private static void traceSnapshotEnvelopeSkipped(Long turnId, String reason) {
        log.debug("[AWX][trace] trace snapshot fallback skipped reason={} messageId={}", reason, turnId);
    }

    private static String traceSnapshotCard(String snapshotId, Map<String, String> durableProjection) {
        String safeId = escapeHtmlAttr(snapshotId);
        String hrefId = java.net.URLEncoder.encode(snapshotId, java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder card = new StringBuilder(512);
        card.append("<div class=\"search-trace trace-snapshot-card\" data-trace-snapshot-id=\"")
                .append(safeId)
                .append("\">")
                .append("<strong>Trace snapshot</strong>")
                .append("<a href=\"/api/diagnostics/trace/snapshots/")
                .append(hrefId)
                .append("/html\" target=\"_blank\" rel=\"noopener noreferrer\">Open live trace snapshot</a>");
        if (durableProjection != null && !durableProjection.isEmpty()) {
            card.append("<div class=\"trace-snapshot-fallback\" data-storage-mode=\"durable_fallback\">");
            durableProjection.forEach((key, value) -> card.append("<span data-trace-field=\"")
                    .append(escapeHtmlAttr(key))
                    .append("\">")
                    .append(escapeHtmlAttr(key))
                    .append('=')
                    .append(escapeHtmlAttr(value))
                    .append("</span>"));
            card.append("</div>");
        }
        return card.append("</div>").toString();
    }

    private static String escapeHtmlAttr(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
