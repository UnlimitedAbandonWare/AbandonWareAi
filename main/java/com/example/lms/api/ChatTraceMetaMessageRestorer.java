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
    private static final String DURABLE_ENVELOPE_VERSION_V1 = "v1";
    private static final String DURABLE_ENVELOPE_VERSION_V2 = "v2";
    private static final String DURABLE_ENVELOPE_VERSION_V3 = "v3";
    static final int MAX_DETAIL_BYTES = 8_192;
    private static final int MAX_DETAIL_FIELDS = 96;
    private static final Set<String> DETAIL_FLAGS = Set.of(
            "queryTransformer.bypassed", "qtx.stagePolicy.enabled", "qtx.stagePolicy.clamped",
            "qtx.suppressed.minLiveBudget", "qtx.timeoutMs.cappedByMinLiveBudget",
            "keywordSelection.cacheSeeded", "keywordSelection.noiseEscape",
            "keywordSelection.qtxGate.softAllow.used", "keywordSelection.qtxGate.softAllow.oneShotAttempted",
            "embed.normalizeApplied", "embed.matryoshka.sliced", "embed.failover.used",
            "vector.fp.bypassed", "vector.federated.timeout",
            "orch.strike", "orch.compression", "orch.bypass", "orch.auxDegraded", "orch.auxHardDown",
            "orch.webRateLimited", "orch.auxLlmDown", "orch.highRisk",
            "prompt.historyRendered", "prompt.lastAssistantRendered", "prompt.memoryPresent",
            "prompt.agentDebugEvidence.chatHarmony.applied", "prompt.builder.required.enforced",
            "prompt.context.refiner.activated", "prompt.context.refiner.failSoft", "prompt.learningDegraded",
            "llm.output.blank", "attachment.bind.attempted", "attachment.bind.applied",
            "rag.evidence.promotion.evidenceGatePassed", "rag.evidence.promotion.citationGateMinPassed",
            "finalAnswer.releaseAllowed", "finalAnswer.evidenceScopeBound");
    private static final Set<String> DETAIL_COUNTS = Set.of(
            "queryTransformer.bypassed.queryLength", "qtx.minLiveBudgetMs",
            "qtx.timeoutMs.before", "qtx.timeoutMs.after", "qtx.constraints.rejectedCount",
            "keywordSelection.maxMust", "keywordSelection.fallback.must.count",
            "keywordSelection.fallback.should.count", "keywordSelection.fallback.mustLimit",
            "embed.actualDim", "embed.sourceDim", "embed.targetDim", "embed.providerActualDim",
            "vector.fp.dropped", "vector.fp.wantLength",
            "prompt.webCount", "prompt.ragCount", "prompt.localDocsCount", "prompt.citableEvidenceCount",
            "prompt.memoryLen", "prompt.context.composer.input.ragCount", "prompt.localDocsRenderedCount",
            "prompt.ctx.len", "prompt.instr.len", "llm.call.approxInputTokens",
            "llm.output.contentLength", "memory.session.tokenEstimate", "fallbackCount",
            "attachment.bind.count", "attachment.sessionFilter.allowedCount",
            "rag.evidence.promotion.candidateCount", "rag.evidence.promotion.citableLocatorCount",
            "rag.evidence.promotion.promotedCount");
    private static final Set<String> DETAIL_NUMBERS = Set.of("prompt.context.refiner.phi", "orch.irregularity");
    private static final Set<String> DETAIL_LABELS = Set.of(
            "queryTransformer.reason", "qtx.bypass.reason", "qtx.constraints.reason",
            "keywordSelection.mode", "keywordSelection.reason", "keywordSelection.cacheSeeded.reason",
            "keywordSelection.fallback.intent", "embed.sliceMethod", "embed.sliceReason",
            "embed.failover.stage", "vector.fp.blockedReason", "vector.federated.cancelMode",
            "orch.mode", "orch.reason", "llm.output.doneReason", "llm.route",
            "observedProvider", "routeId", "fallbackReason", "observedReason",
            "attachment.bind.reason", "rag.evidence.promotion.disabledReason",
            "finalAnswer.releaseReason", "finalAnswer.evidenceReleaseState", "finalAnswer.retrievalExecution");
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
            Optional<SnapshotPointer> pointer = parseSnapshotPointer(content, turnId);
            if (pointer.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ChatApiController.MessageDto(
                    turnId,
                    "system",
                    traceSnapshotCard(pointer.get().snapshotId(), pointer.get().projection()),
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

    record SnapshotPointer(String snapshotId, Map<String, String> projection,
                           Long assistantMessageId, boolean legacyFallbackAllowed,
                           Map<String, Object> diagnostics) {
    }

    static Optional<SnapshotPointer> parseSnapshotPointer(String content, Long messageId) {
        if (content == null || !content.startsWith(TRACE_SNAPSHOT_META_PREFIX)) {
            return Optional.empty();
        }
        String pointer = content.substring(TRACE_SNAPSHOT_META_PREFIX.length()).trim();
        int firstDelimiter = pointer.indexOf('|');
        String snapshotId = firstDelimiter < 0 ? pointer : pointer.substring(0, firstDelimiter);
        if (!isSafeTraceSnapshotId(snapshotId)) {
            log.debug("[AWX][trace] trace snapshot pointer skipped reason=invalid_id messageId={}", messageId);
            return Optional.empty();
        }
        Map<String, String> projection = firstDelimiter < 0
                ? Map.of()
                : parseDurableProjection(pointer, firstDelimiter, messageId);
        int secondDelimiter = firstDelimiter < 0 ? -1 : pointer.indexOf('|', firstDelimiter + 1);
        String version = secondDelimiter < 0 ? "" : pointer.substring(firstDelimiter + 1, secondDelimiter);
        Long assistantMessageId = null;
        if (projection.containsKey("assistantMessageId")) {
            assistantMessageId = Long.parseLong(projection.get("assistantMessageId"));
        }
        boolean legacyFallbackAllowed = firstDelimiter < 0
                || (DURABLE_ENVELOPE_VERSION_V1.equals(version) && !projection.isEmpty());
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        Map<String, String> summary = new LinkedHashMap<>();
        projection.forEach((key, value) -> {
            if (key.startsWith("diag.")) diagnostics.put(key.substring(5), decodeDiagnostic(key.substring(5), value));
            else summary.put(key, value);
        });
        return Optional.of(new SnapshotPointer(snapshotId, Map.copyOf(summary), assistantMessageId,
                legacyFallbackAllowed, Map.copyOf(diagnostics)));
    }

    static Map<String, String> projectDiagnostics(Map<String, Object> source) {
        Map<String, String> out = new LinkedHashMap<>();
        if (source == null) return out;
        source.keySet().stream().filter(java.util.Objects::nonNull).sorted().forEach(key -> {
            Object safe = SafeRedactor.diagnosticValue(key, source.get(key));
            String encoded = encodeDiagnostic(key, safe);
            if (encoded != null && out.size() < 80) out.put("diag." + key, encoded);
        });
        return out;
    }

    private static String encodeDiagnostic(String key, Object value) {
        if ("observedModel".equals(key) && value instanceof String model
                && model.matches("[A-Za-z0-9][A-Za-z0-9._:/+@-]{0,199}")) return "s:" + model;
        if (DETAIL_FLAGS.contains(key) && value instanceof Boolean) return "b:" + value;
        if (DETAIL_COUNTS.contains(key) && (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) {
            long number = ((Number) value).longValue();
            return number >= 0 && number <= 1_000_000 ? "n:" + number : null;
        }
        if (DETAIL_NUMBERS.contains(key) && (value instanceof Float || value instanceof Double)) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) && Math.abs(number) <= 1_000_000 ? "f:" + number : null;
        }
        if (DETAIL_LABELS.contains(key) && value instanceof String s
                && (SAFE_LABEL.matcher(s).matches() || SAFE_HASH.matcher(s).matches())
                && s.equals(SafeRedactor.traceLabel(s))) return "s:" + s;
        return null;
    }

    private static Object decodeDiagnostic(String key, String encoded) {
        if (encoded == null || encoded.length() < 3 || encoded.length() > ("observedModel".equals(key) ? 202 : 82)) return null;
        try {
            String value = encoded.substring(2);
            Object decoded = switch (encoded.substring(0, 2)) {
                case "b:" -> "true".equals(value) ? Boolean.TRUE : "false".equals(value) ? Boolean.FALSE : null;
                case "n:" -> Long.valueOf(value);
                case "f:" -> Double.valueOf(value);
                case "s:" -> value;
                default -> null;
            };
            return decoded != null && encoded.equals(encodeDiagnostic(key, decoded)) ? decoded : null;
        } catch (IllegalArgumentException invalid) {
            return null;
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
        String version = secondDelimiter < 0 ? "" : pointer.substring(firstDelimiter + 1, secondDelimiter);
        if (secondDelimiter < 0
                || pointer.indexOf('|', secondDelimiter + 1) >= 0
                || !(DURABLE_ENVELOPE_VERSION_V1.equals(version)
                || DURABLE_ENVELOPE_VERSION_V2.equals(version)
                || DURABLE_ENVELOPE_VERSION_V3.equals(version))) {
            traceSnapshotEnvelopeSkipped(turnId, "invalid_envelope");
            return Map.of();
        }
        String encoded = pointer.substring(secondDelimiter + 1);
        boolean detail = DURABLE_ENVELOPE_VERSION_V3.equals(version);
        if (encoded.isBlank() || encoded.length() > (detail ? 10_924 : MAX_DURABLE_PROJECTION_B64_CHARS)) {
            traceSnapshotEnvelopeSkipped(turnId, "invalid_size");
            return Map.of();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (decoded.length == 0 || decoded.length > (detail ? MAX_DETAIL_BYTES : MAX_DURABLE_PROJECTION_BYTES)) {
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
                if (projection.size() >= (detail ? MAX_DETAIL_FIELDS : MAX_DURABLE_PROJECTION_FIELDS)) {
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
                if (projection.containsKey(key) || !isValidDurableField(key, value, version)) {
                    traceSnapshotEnvelopeSkipped(turnId, "invalid_field");
                    return Map.of();
                }
                projection.put(key, value);
            }
            if (!"durable_fallback".equals(projection.get("storageMode"))
                    || !projection.containsKey("reason")
                    || !projection.containsKey("method")
                    || !projection.containsKey("pathHash")
                    || ((DURABLE_ENVELOPE_VERSION_V2.equals(version) || detail)
                    && !projection.containsKey("assistantMessageId"))) {
                traceSnapshotEnvelopeSkipped(turnId, "missing_required_field");
                return Map.of();
            }
            return Map.copyOf(projection);
        } catch (IllegalArgumentException e) {
            traceSnapshotEnvelopeSkipped(turnId, "invalid_base64");
            return Map.of();
        }
    }

    private static boolean isValidDurableField(String key, String value, String version) {
        if (key.startsWith("diag.")) {
            return DURABLE_ENVELOPE_VERSION_V3.equals(version) && decodeDiagnostic(key.substring(5), value) != null;
        }
        if ("assistantMessageId".equals(key)) {
            if (!DURABLE_ENVELOPE_VERSION_V2.equals(version) && !DURABLE_ENVELOPE_VERSION_V3.equals(version)) return false;
            try {
                return Long.parseLong(value) > 0L;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
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
