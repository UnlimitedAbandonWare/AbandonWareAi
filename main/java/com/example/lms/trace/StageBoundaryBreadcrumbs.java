package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits low-cardinality breadcrumbs at fragile request pipeline boundaries.
 *
 * <p>The rows intentionally carry only stage, failure class, reason code, counts,
 * and hashes. Raw queries/prompts/provider bodies must stay out of TraceStore
 * snapshots because diagnostics, SSE, and HTML surfaces can render them.</p>
 */
public final class StageBoundaryBreadcrumbs {
    private static final Logger LOG = LoggerFactory.getLogger(StageBoundaryBreadcrumbs.class);
    private static final List<String> PROVIDERS = List.of("naver", "brave", "serpapi", "tavily");
    private static final List<String> QUERY_KEYS = List.of("query", "rawQuery", "user.query", "q");
    private static final List<String> RISKY_RAW_KEYS = List.of(
            "query", "rawQuery", "user.query", "q", "rawPrompt",
            "prompt.raw", "llm.client.rawPrompt", "llm.rawPrompt", "llm.client.rawModel");
    private static final List<String> STAGES = List.of(
            "request", "orchestration", "search", "prompt", "llm", "verification", "sse");

    private StageBoundaryBreadcrumbs() {
    }

    public record BoundaryBreadcrumb(String phase, String stage, String failureClass, String reasonCode,
                                     Map<String, Object> data) {
    }

    public static List<BoundaryBreadcrumb> recordFromCurrentTrace(String phase) {
        try {
            return record(phase, TraceStore.getAll());
        } catch (RuntimeException error) {
            traceSuppressed("recordFromCurrentTrace", error);
            return List.of();
        }
    }

    static List<BoundaryBreadcrumb> record(String phase, Map<String, Object> traceMeta) {
        if (traceMeta == null || traceMeta.isEmpty()) {
            return List.of();
        }
        String safePhase = safeLabel(phase, "unknown");
        List<BoundaryBreadcrumb> rows = new ArrayList<>();
        addIfPresent(rows, request(traceMeta, safePhase));
        addIfPresent(rows, orchestration(traceMeta, safePhase));
        addIfPresent(rows, search(traceMeta, safePhase));
        addIfPresent(rows, prompt(traceMeta, safePhase));
        addIfPresent(rows, llm(traceMeta, safePhase));
        addIfPresent(rows, verification(traceMeta, safePhase));
        addIfPresent(rows, sse(traceMeta, safePhase));
        if (rows.isEmpty()) {
            return List.of();
        }
        sanitizeRiskyTraceKeys(traceMeta);
        TraceStore.put("stageBoundary.count", rows.size());
        TraceStore.put("stageBoundary.lastStage", rows.get(rows.size() - 1).stage());
        TraceStore.put("stageBoundary.lastFailureClass", rows.get(rows.size() - 1).failureClass());
        for (BoundaryBreadcrumb row : rows) {
            String stepKey = "mla.breadcrumb.step." + row.stage();
            Object previous = TraceStore.get(stepKey);
            TraceStore.put(stepKey, row.data());
            if (sameBoundaryObservation(previous, row.data())) {
                continue;
            }
            TraceStore.append("ml.breadcrumbs.v1", aggregateRow(row));
            TraceLogger.emit("stage_boundary_breadcrumb", row.stage(), row.data());
        }
        return List.copyOf(rows);
    }

    private static boolean sameBoundaryObservation(Object previous, Map<String, Object> current) {
        if (!(previous instanceof Map<?, ?> previousMap)) {
            return false;
        }
        return boundaryIdentity(previousMap).equals(boundaryIdentity(current));
    }

    private static Map<String, Object> boundaryIdentity(Map<?, ?> data) {
        Map<String, Object> identity = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!"phase".equals(name) && !"phaseStage".equals(name)) {
                identity.put(name, value);
            }
        });
        return identity;
    }

    private static BoundaryBreadcrumb request(Map<String, Object> meta, String phase) {
        if (truthy(meta.get("ctx.propagation.missing"))
                || number(meta.get("ctx.propagation.missing.count"), 0) > 0
                || truthy(meta.get("request.contextMissing"))) {
            Map<String, Object> data = baseData(phase, "request", "context-missing", "context_missing");
            putCount(data, "missingContextCount", meta.get("ctx.propagation.missing.count"));
            return new BoundaryBreadcrumb(phase, "request", "context-missing", "context_missing", data);
        }
        return null;
    }

    private static BoundaryBreadcrumb orchestration(Map<String, Object> meta, String phase) {
        String reason = firstNonBlank(
                truthy(meta.get("queryTransformer.bypassed")) ? "query_transformer_bypassed" : null,
                safeReason(meta.get("queryTransformer.reason"), null),
                safeReason(meta.get("queryTransformer.fallback.reason"), null),
                safeReason(meta.get("rag.orchestrator.suppressed.stage"), null),
                safeReason(meta.get("chat.stream.signal.suppressed.stage"), null));
        if (reason == null) {
            return null;
        }
        String failureClass = reason.contains("bypass") || reason.contains("fallback") ? "fallback" : "catch";
        Map<String, Object> data = baseData(phase, "orchestration", failureClass, reason);
        return new BoundaryBreadcrumb(phase, "orchestration", failureClass, reason, data);
    }

    private static BoundaryBreadcrumb search(Map<String, Object> meta, String phase) {
        String failureClass = null;
        String reasonCode = null;
        String provider = null;
        for (String candidate : PROVIDERS) {
            String prefix = "web." + candidate + ".";
            if (truthy(meta.get(prefix + "providerDisabled")) || truthy(meta.get(prefix + "skipped"))) {
                failureClass = "provider-disabled";
                reasonCode = firstNonBlank(
                        safeReason(meta.get(prefix + "disabledReasonCanonical"), null),
                        safeReason(meta.get(prefix + "disabledReason"), null),
                        safeReason(meta.get(prefix + "skipped.reason"), null),
                        "provider-disabled");
                provider = candidate;
                break;
            }
            if (truthy(meta.get(prefix + "timeout")) || truthy(meta.get(prefix + "timedOut"))) {
                failureClass = "timeout";
                reasonCode = "timeout";
                provider = candidate;
                break;
            }
            if (truthy(meta.get(prefix + "rateLimited")) || truthy(meta.get(prefix + "rate-limit"))
                    || "429".equals(String.valueOf(meta.get(prefix + "httpStatus")))) {
                failureClass = "rate-limit";
                reasonCode = "rate-limit";
                provider = candidate;
                break;
            }
        }

        int returned = firstCount(meta, "webSearch.returnedCount", "web.returnedCount", "returnedCount", "outCount");
        int afterFilter = firstCount(meta, "webSearch.afterFilterCount", "web.afterFilterCount",
                "afterFilterCount", "stageCountsSelectedFromOut");
        if (failureClass == null && returned == 0 && hasAny(meta, "webSearch.returnedCount", "web.returnedCount",
                "returnedCount", "outCount")) {
            failureClass = "zero-result";
            reasonCode = "zero_result";
        }
        if (failureClass == null && returned > 0 && afterFilter == 0
                && hasAny(meta, "webSearch.afterFilterCount", "web.afterFilterCount", "afterFilterCount",
                "stageCountsSelectedFromOut")) {
            failureClass = "after-filter-starvation";
            reasonCode = "after_filter_starvation";
        }
        if (failureClass == null && firstNonBlank(safeReason(meta.get("starvationFallback.trigger"), null),
                safeReason(meta.get("rag.starvation.trigger"), null)) != null) {
            failureClass = "after-filter-starvation";
            reasonCode = firstNonBlank(
                    safeReason(meta.get("starvationFallback.trigger"), null),
                    safeReason(meta.get("rag.starvation.trigger"), null),
                    "starvation_fallback");
        }
        if (failureClass == null) {
            return null;
        }

        Map<String, Object> data = baseData(phase, "search", failureClass, firstNonBlank(reasonCode, failureClass));
        if (provider != null) {
            data.put("provider", provider);
        }
        putCount(data, "returnedCount", returned);
        putCount(data, "afterFilterCount", afterFilter);
        putQuerySummary(data, meta);
        return new BoundaryBreadcrumb(phase, "search", failureClass, firstNonBlank(reasonCode, failureClass), data);
    }

    private static BoundaryBreadcrumb prompt(Map<String, Object> meta, String phase) {
        int context = firstCount(meta, "finalContextCount", "prompt.context.count", "final.context.count");
        if (context == 0 && hasAny(meta, "finalContextCount", "prompt.context.count", "final.context.count")) {
            Map<String, Object> data = baseData(phase, "prompt", "context-missing", "context_missing");
            data.put("contextCount", 0);
            putQuerySummary(data, meta);
            return new BoundaryBreadcrumb(phase, "prompt", "context-missing", "context_missing", data);
        }
        if (truthy(meta.get("prompt.contextMissing"))) {
            Map<String, Object> data = baseData(phase, "prompt", "context-missing", "context_missing");
            putQuerySummary(data, meta);
            return new BoundaryBreadcrumb(phase, "prompt", "context-missing", "context_missing", data);
        }
        return null;
    }

    private static BoundaryBreadcrumb llm(Map<String, Object> meta, String phase) {
        String failureClass = null;
        String reasonCode = null;
        if (truthy(meta.get("llm.fastBailTimeout")) || truthy(meta.get("llm.timeout"))) {
            failureClass = "timeout";
            reasonCode = "timeout";
        } else if (truthy(meta.get("llmrouter.api.providerDisabled"))
                || safeReason(meta.get("llmrouter.api.disabledReason"), null) != null) {
            failureClass = "provider-disabled";
            reasonCode = firstNonBlank(safeReason(meta.get("llmrouter.api.disabledReason"), null), "provider-disabled");
        } else if (truthy(meta.get("llm.client.failed")) || truthy(meta.get("llm.call.failed"))
                || safeReason(meta.get("llm.client.errorType"), null) != null
                || safeReason(meta.get("llm.error.code"), null) != null) {
            failureClass = "catch";
            reasonCode = firstNonBlank(
                    safeReason(meta.get("llm.client.errorType"), null),
                    safeReason(meta.get("llm.error.code"), null),
                    "llm_catch");
        } else if (truthy(meta.get("llm.client.blank")) || truthy(meta.get("llm.call.blank"))
                || truthy(meta.get("llm.output.blank"))) {
            failureClass = "zero-result";
            reasonCode = "blank_response";
        }
        if (failureClass == null) {
            return null;
        }
        Map<String, Object> data = baseData(phase, "llm", failureClass, reasonCode);
        putQuerySummary(data, meta);
        return new BoundaryBreadcrumb(phase, "llm", failureClass, reasonCode, data);
    }

    private static BoundaryBreadcrumb verification(Map<String, Object> meta, String phase) {
        String factKey = "factStatusClassifier.judge.disabledReason";
        String claimKey = "claimVerifier.judge.disabledReason";
        boolean factObserved = hasTextValue(meta, factKey);
        boolean claimObserved = hasTextValue(meta, claimKey);
        if (!factObserved && !claimObserved) {
            return null;
        }

        String factReason = factObserved ? safeReason(meta.get(factKey), null) : null;
        String claimReason = claimObserved ? safeReason(meta.get(claimKey), null) : null;
        boolean callFailed = "judge_call_failed".equals(factReason)
                || "judge_call_failed".equals(claimReason);
        boolean allObservedReasonsUnavailable = (!factObserved || "judge_model_unavailable".equals(factReason))
                && (!claimObserved || "judge_model_unavailable".equals(claimReason));
        String failureClass = callFailed ? "catch" : allObservedReasonsUnavailable ? "provider-disabled" : "fallback";
        String reasonCode = callFailed
                ? "judge_call_failed"
                : allObservedReasonsUnavailable ? "judge_model_unavailable" : "judge_fail_soft";
        String judgeLane = factObserved && claimObserved
                ? "both"
                : factObserved ? "fact_status_classifier" : "claim_verifier";

        Map<String, Object> data = baseData(phase, "verification", failureClass, reasonCode);
        data.put("status", "fail_soft");
        data.put("judgeLane", judgeLane);
        data.put("judgeFailSoftLaneCount", (factObserved ? 1 : 0) + (claimObserved ? 1 : 0));
        if (callFailed) {
            data.put("judgeCallAttempted", true);
        } else if (allObservedReasonsUnavailable) {
            data.put("judgeCallAttempted", false);
        }
        data.put("verificationOutcomeKnown", false);
        return new BoundaryBreadcrumb(phase, "verification", failureClass, reasonCode, data);
    }

    private static BoundaryBreadcrumb sse(Map<String, Object> meta, String phase) {
        String reason = firstNonBlank(
                truthy(meta.get("chatApi.emptyFinalText")) ? "empty_final_text" : null,
                safeReason(meta.get("chat.stream.signal.suppressed.stage"), null));
        if (reason == null) {
            return null;
        }
        String failureClass = "empty_final_text".equals(reason) ? "fallback" : "catch";
        Map<String, Object> data = baseData(phase, "sse", failureClass, reason);
        return new BoundaryBreadcrumb(phase, "sse", failureClass, reason, data);
    }

    private static Map<String, Object> aggregateRow(BoundaryBreadcrumb breadcrumb) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", 1);
        row.put("seq", TraceStore.nextSequence("ml.breadcrumbs.v1"));
        row.put("ts", Instant.now().toString());
        row.put("component", "StageBoundaryBreadcrumbs");
        row.put("rules", "stage_boundary");
        row.put("decision", "stage_boundary_observed");
        row.put("requestId", SafeRedactor.hashValue(firstNonBlank(MDC.get("x-request-id"), MDC.get("traceId"))));
        row.put("sessionId", SafeRedactor.hashValue(firstNonBlank(MDC.get("sid"), MDC.get("sessionId"))));
        row.put("data", breadcrumb.data());
        return row;
    }

    private static Map<String, Object> baseData(String phase, String stage, String failureClass, String reasonCode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", safeLabel(phase, "unknown"));
        data.put("phaseStage", safeLabel(phase, "unknown"));
        data.put("stage", stage);
        data.put("failureClass", failureClass);
        data.put("reasonCode", safeReason(reasonCode, failureClass));
        data.put("redacted", true);
        return data;
    }

    private static void putQuerySummary(Map<String, Object> data, Map<String, Object> meta) {
        for (String key : QUERY_KEYS) {
            String query = rawString(meta.get(key));
            if (query == null) {
                continue;
            }
            data.put("queryRedacted", true);
            data.put("queryHash12", SafeRedactor.hash12(query));
            data.put("queryLength", query.length());
            return;
        }
        for (String key : QUERY_KEYS) {
            String queryHash12 = safeStoredHash12(meta.get(key + ".hash12"));
            int queryLength = number(meta.get(key + ".length"), -1);
            if (queryHash12 == null && queryLength < 0) {
                continue;
            }
            data.put("queryRedacted", true);
            if (queryHash12 != null) {
                data.put("queryHash12", queryHash12);
            }
            putCount(data, "queryLength", queryLength);
            return;
        }
    }

    private static void sanitizeRiskyTraceKeys(Map<String, Object> meta) {
        for (String key : RISKY_RAW_KEYS) {
            String raw = rawString(meta.get(key));
            if (raw == null) {
                continue;
            }
            TraceStore.put(key + ".hash12", SafeRedactor.hash12(raw));
            TraceStore.put(key + ".length", raw.length());
            TraceStore.put(key, null);
        }
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            String key = entry.getKey();
            if (key == null || !(entry.getValue() instanceof String raw)) {
                continue;
            }
            if (RISKY_RAW_KEYS.contains(key)) {
                continue;
            }
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (!SafeRedactor.isRestrictedKey(key)
                    && !lower.contains("reason")
                    && !lower.contains("error")
                    && !lower.contains("exception")) {
                continue;
            }
            Object safe = SafeRedactor.diagnosticValue(key, raw, 256);
            if (safe != null && !String.valueOf(safe).equals(raw)) {
                TraceStore.put(key, safe);
            }
        }
    }

    private static void putCount(Map<String, Object> data, String key, Object value) {
        int count = number(value, -1);
        if (count >= 0) {
            data.put(key, count);
        }
    }

    private static int firstCount(Map<String, Object> meta, String... keys) {
        if (keys == null) {
            return 0;
        }
        for (String key : keys) {
            if (key != null && meta.containsKey(key)) {
                Object value = meta.get(key);
                if (value instanceof Map<?, ?> map) {
                    return map.values().stream().mapToInt(v -> number(v, 0)).sum();
                }
                return number(value, 0);
            }
        }
        return 0;
    }

    private static boolean hasAny(Map<String, Object> meta, String... keys) {
        if (keys == null) {
            return false;
        }
        for (String key : keys) {
            if (key != null && meta.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTextValue(Map<String, Object> meta, String key) {
        if (meta == null || key == null || !meta.containsKey(key)) {
            return false;
        }
        Object value = meta.get(key);
        return value != null && !String.valueOf(value).isBlank();
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.longValue() != 0L;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value).trim()));
        } catch (RuntimeException error) {
            traceSuppressed("number_parse", error);
            return fallback;
        }
    }

    private static String safeReason(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String label = SafeRedactor.traceFlagDetail(value);
        if (label == null || label.isBlank() || "present".equals(label)) {
            return fallback;
        }
        return label;
    }

    private static String safeLabel(Object value, String fallback) {
        return firstNonBlank(SafeRedactor.traceLabelOrFallback(value, null), fallback);
    }

    private static String rawString(Object value) {
        if (!(value instanceof CharSequence sequence)) {
            return null;
        }
        String text = sequence.toString();
        return text.isBlank() ? null : text;
    }

    private static String safeStoredHash12(Object value) {
        String hash = rawString(value);
        if (hash == null || !hash.matches("(?i)[0-9a-f]{1,12}")) {
            return null;
        }
        return hash.toLowerCase(java.util.Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static void addIfPresent(List<BoundaryBreadcrumb> rows, BoundaryBreadcrumb row) {
        if (row == null || row.stage() == null || !STAGES.contains(row.stage())) {
            return;
        }
        rows.add(row);
    }

    private static void traceSuppressed(String stage, RuntimeException error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        try {
            TraceStore.put("stageBoundary.suppressed.stage", safeStage);
            TraceStore.put("stageBoundary.suppressed.errorType", errorType);
            TraceStore.put("stageBoundary.suppressed." + safeStage, true);
            TraceStore.put("stageBoundary.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException ignored) {
            TraceLogger.emit("stage_boundary_suppressed_write_failed", safeStage, Map.of(
                    "traceStoreWriteFailed", true,
                    "suppressedStage", safeStage,
                    "errorType", errorType,
                    "traceStoreErrorType", ignored.getClass().getSimpleName()));
            LOG.warn("[AWX][stage-boundary] traceStoreWriteSuppressed stage={} errorType={} traceStoreErrorType={}",
                    safeStage,
                    errorType,
                    ignored.getClass().getSimpleName());
            // Last-resort fail-soft path; breadcrumbs must never break chat requests.
        }
    }
}
