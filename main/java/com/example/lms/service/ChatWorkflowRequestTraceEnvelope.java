package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.service.chat.ChatRunExecutionContext;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ChatWorkflowRequestTraceEnvelope {
    private static final String PREFETCH = "chat.workflow.controllerPrefetch.internal";
    private static final Set<String> COUNT_FIELDS = Set.of("rawSize", "rawCount", "parsedCount", "returnedCount",
            "afterBlockedCount", "afterStrictCount", "formattedBeforeDedupCount", "afterDedupCount", "afterFilterCount");
    private static final Set<String> FAILURE_CLASSES = Set.of("NONE", "OK", "UNKNOWN", "TRUE_ZERO", "FILTER_ZERO",
            "AUTH_OR_CONFIG", "TIMEOUT_OR_BUDGET", "PROVIDER_ERROR", "BREAKER_OR_COOLDOWN", "LOCAL_ADMISSION",
            "CANCELLED_NEUTRAL", "RATE_LIMIT", "RATE_LIMIT_DAILY", "RATE_LIMIT_INSTANT", "PARSE_ERROR");

    private static final Set<String> BRAVE_OUTCOMES = Set.of("OK", "DISABLED", "COOLDOWN", "RATE_LIMIT_LOCAL",
            "HTTP_429", "HTTP_503", "HTTP_ERROR", "EXCEPTION", "UNKNOWN");
    private static final Set<String> MASKABLE_FAILURES = Set.of("AUTH_OR_CONFIG", "TIMEOUT_OR_BUDGET", "PROVIDER_ERROR",
            "RATE_LIMIT", "RATE_LIMIT_DAILY", "RATE_LIMIT_INSTANT", "PARSE_ERROR");

    record ControllerPrefetch(ChatRunExecutionContext run, String ownerHash, String requestHash,
                              String traceHash, Map<String, List<Map<String, Object>>> rows) { }

    /** Called only at the controller's completed, freshly cleared prefetch boundary. */
    static void captureControllerPrefetch(ChatRequestDto req, long startedAtEpochMs, String retrievalExecutionId) {
        TraceStore.putInternal(PREFETCH, null);
        var run = ChatRunExecutionContext.current();
        long now = System.currentTimeMillis();
        if (req == null || run == null || !run.belongsToSession(req.getSessionId()) || !run.permitsEmission()
                || req.getVerifiedRequestOwnerHash() == null || !hashed(retrievalExecutionId) || startedAtEpochMs <= 0 || startedAtEpochMs > now) return;
        String requestHash = correlationHash(org.slf4j.MDC.get("x-request-id"));
        String traceHash = correlationHash(currentTraceId());
        if (requestHash == null || traceHash == null || !traceHash.equals(TraceStore.get("trace.id"))) return;
        var rows = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
        for (String provider : List.of("naver", "brave")) {
            String key = provider.equals("naver") ? "web.naver.filter.runs" : "web.brave.attempt.runs";
            if (!(TraceStore.get(key) instanceof List<?> source)) continue;
            var accepted = new java.util.ArrayList<Map<String, Object>>();
            for (Object item : source) {
                if (accepted.size() == 32) break;
                if (!(item instanceof Map<?, ?> row) || !provider.equals(row.get("provider"))
                        || !retrievalExecutionId.equals(row.get("retrievalExecutionId"))
                        || !Boolean.TRUE.equals(row.get("clientAttemptObserved"))
                        || !Boolean.TRUE.equals(row.get("providerReceiptObserved"))
                        || Boolean.TRUE.equals(row.get("cacheHit"))
                        || !(row.get("httpStatus") instanceof Number status) || status.intValue() < 100 || status.intValue() > 599
                        || !(row.get("startedAtEpochMs") instanceof Number start) || start.longValue() < startedAtEpochMs
                        || !(row.get("finishedAtEpochMs") instanceof Number end) || end.longValue() < start.longValue() || end.longValue() > now
                        || !hashed(row.get("searchExecutionId")) || !hashed(row.get("providerAttemptId"))) continue;
                var safe = new java.util.LinkedHashMap<String, Object>();
                safe.put("provider", provider);
                safe.put("clientAttemptObserved", true); safe.put("providerReceiptObserved", true); safe.put("cacheHit", false);
                safe.put("httpStatus", status.intValue());
                safe.put("startedAtEpochMs", start.longValue()); safe.put("finishedAtEpochMs", end.longValue());
                for (String id : List.of("searchExecutionId", "providerAttemptId", "retrievalExecutionId", "queryHash"))
                    if (hashed(row.get(id))) safe.put(id, row.get(id));
                Object failure = row.get("failureClass");
                safe.put("failureClass", failure instanceof String label && FAILURE_CLASSES.contains(label) ? label
                        : provider.equals("brave") && "OK".equals(row.get("outcome")) ? "OK" : "UNKNOWN");
                if (provider.equals("brave") && row.get("outcome") instanceof String outcome && BRAVE_OUTCOMES.contains(outcome))
                    safe.put("outcome", outcome);
                for (String count : COUNT_FIELDS) {
                    Object value = row.get(count);
                    if (value instanceof Number number && number.longValue() >= 0 && number.longValue() <= 1_000_000)
                        safe.put(count, number.intValue());
                    else if ("unknown".equals(value)) safe.put(count, "unknown");
                }
                if (row.get("recoveredAt") instanceof String time) {
                    try { safe.put("recoveredAt", java.time.Instant.parse(time).toString()); }
                    catch (RuntimeException invalidTime) { /* no unverified recovery timestamp */ }
                }
                accepted.add(Map.copyOf(safe));
            }
            if (!accepted.isEmpty()) rows.put(key, List.copyOf(accepted));
        }
        var naverRows = rows.get("web.naver.filter.runs");
        var braveRows = rows.getOrDefault("web.brave.attempt.runs", List.of());
        if (naverRows != null && TraceStore.get("web.naver.masked.runs") instanceof List<?> masks) {
            rows.put("web.naver.filter.runs", naverRows.stream().map(row -> {
                if (!MASKABLE_FAILURES.contains(row.get("failureClass"))) return row;
                boolean successfulBrave = braveRows.stream().anyMatch(brave ->
                        row.get("searchExecutionId").equals(brave.get("searchExecutionId"))
                        && "OK".equals(brave.get("outcome"))
                        && brave.get("httpStatus") instanceof Number status && status.intValue() >= 200 && status.intValue() < 300
                        && brave.get("afterFilterCount") instanceof Number count && count.intValue() > 0);
                if (!successfulBrave) return row;
                boolean sameMask = masks.stream().limit(32).anyMatch(item -> item instanceof Map<?, ?> mask
                        && row.get("providerAttemptId").equals(mask.get("providerAttemptId"))
                        && row.get("searchExecutionId").equals(mask.get("searchExecutionId"))
                        && "brave".equals(mask.get("maskedBy")));
                if (!sameMask) return row;
                var masked = new java.util.LinkedHashMap<>(row);
                masked.put("maskedBy", "brave");
                return Map.copyOf(masked);
            }).toList());
        }
        if (!rows.isEmpty()) TraceStore.putInternal(PREFETCH,
                new ControllerPrefetch(run, req.getVerifiedRequestOwnerHash(), requestHash, traceHash, Map.copyOf(rows)));
    }

    static ControllerPrefetch takeControllerPrefetch(ChatRequestDto req) {
        Object value = TraceStore.get(PREFETCH);
        return value instanceof ControllerPrefetch prefetch && matches(req, prefetch) ? prefetch : null;
    }

    static void restoreControllerPrefetch(ChatRequestDto req, ControllerPrefetch prefetch) {
        if (prefetch == null || !matches(req, prefetch) || !prefetch.traceHash().equals(TraceStore.get("trace.id"))) return;
        prefetch.rows().forEach(TraceStore::put);
    }

    private static boolean matches(ChatRequestDto req, ControllerPrefetch prefetch) {
        var run = ChatRunExecutionContext.current();
        return req != null && run != null && run.sameRun(prefetch.run()) && run.belongsToSession(req.getSessionId())
                && run.permitsEmission() && prefetch.ownerHash().equals(req.getVerifiedRequestOwnerHash())
                && prefetch.requestHash().equals(correlationHash(org.slf4j.MDC.get("x-request-id")))
                && prefetch.traceHash().equals(correlationHash(currentTraceId()));
    }

    private static boolean hashed(Object value) { return value instanceof String id && id.matches("hash:[a-f0-9]{12}"); }
    private static String correlationHash(String id) { return id == null || id.isBlank() ? null : SafeRedactor.hashValueOrPreserve(id); }
    private static String currentTraceId() {
        for (String name : List.of("traceId", "trace", "x-request-id")) {
            String id = org.slf4j.MDC.get(name);
            if (id != null && !id.isBlank()) return id;
        }
        return null;
    }

    private ChatWorkflowRequestTraceEnvelope() {
    }

    static void rehydrateRequestCorrelation(String rawCorrelationId) {
        if (rawCorrelationId == null || rawCorrelationId.isBlank()) {
            return;
        }
        String correlationHash = SafeRedactor.hashValue(rawCorrelationId);
        if (correlationHash == null || correlationHash.isBlank()) {
            return;
        }
        TraceStore.put("trace.id", correlationHash);
        TraceStore.put("x-request-id", correlationHash);
        TraceStore.put("requestId", correlationHash);
        TraceStore.put("traceId", correlationHash);
    }

    static void seed(ChatRequestDto req, String sessionKey) {
        try {
            TraceStore.put("trace.runId", String.format(
                    "chat:%s:%d", SafeRedactor.hashValue(sessionKey), System.nanoTime()));

            String requestSid = null;
            try {
                requestSid = org.slf4j.MDC.get("sid");
                if (requestSid != null && !requestSid.isBlank()
                        && sessionKey != null && !requestSid.equals(sessionKey)) {
                    TraceStore.putIfAbsent("req.sid", SafeRedactor.hashValue(requestSid));
                    if (org.slf4j.MDC.get("requestSid") == null) {
                        org.slf4j.MDC.put("requestSid", requestSid);
                    }
                }
            } catch (Throwable failure) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.requestSid", failure);
            }

            try {
                if (req.getSessionId() != null) {
                    TraceStore.put("chatSessionHash",
                            SafeRedactor.hashValue(String.valueOf(req.getSessionId())));
                    if (org.slf4j.MDC.get("chatSessionId") == null) {
                        org.slf4j.MDC.put("chatSessionId", String.valueOf(req.getSessionId()));
                    }
                }
            } catch (Throwable failure) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.chatSessionId", failure);
            }

            String traceId = null;
            try {
                traceId = org.slf4j.MDC.get("traceId");
                if (traceId == null || traceId.isBlank()) {
                    traceId = org.slf4j.MDC.get("trace");
                }
                if (traceId == null || traceId.isBlank()) {
                    traceId = org.slf4j.MDC.get("x-request-id");
                }
            } catch (Throwable failure) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.mdcTraceId", failure);
            }
            if (traceId == null || traceId.isBlank()) {
                traceId = java.util.UUID.randomUUID().toString();
            }
            rehydrateRequestCorrelation(traceId);

            if (sessionKey != null && !sessionKey.isBlank()) {
                TraceStore.put("sid", SafeRedactor.hashValue(sessionKey));
                try {
                    org.slf4j.MDC.put("sid", sessionKey);
                    org.slf4j.MDC.put("sessionId", sessionKey);
                } catch (Throwable failure) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.mdcSession", failure);
                }
            }

            try {
                java.util.Map<String, Object> breadcrumb = new java.util.LinkedHashMap<>();
                breadcrumb.put("conversationSidHash", SafeRedactor.hashValue(sessionKey));
                breadcrumb.put("requestSidHash", SafeRedactor.hashValue(requestSid));
                breadcrumb.put("chatSessionHash",
                        SafeRedactor.hashValue(String.valueOf(req.getSessionId())));
                breadcrumb.put("traceIdHash", SafeRedactor.hashValue(traceId));
                ai.abandonware.nova.orch.trace.OrchEventEmitter.breadcrumb(
                        "conversation.breadcrumb.seed",
                        "Seeded conversation breadcrumb in MDC/TraceStore",
                        "ChatWorkflow.continueChat",
                        breadcrumb);
            } catch (Throwable failure) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.breadcrumb", failure);
            }
        } catch (Exception failure) {
            ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.envelope", failure);
        }
    }
}
