package com.abandonware.ai.agent.trace;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import ai.abandonware.nova.orch.trace.OrchTrace;
import com.abandonware.ai.agent.orchestrator.recovery.RecoveryAction;
import com.abandonware.ai.agent.orchestrator.recovery.Verdict;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redacted timeline memory for agent tool calls and judgment decisions.
 *
 * <p>The canonical event list is {@code orch.events.v1}; this helper only adds
 * compact breadcrumbs and CFVM-like raw-tile summaries, never raw inputs,
 * prompts, queries, or tool payloads.
 */
public final class AgentBreadcrumbMemory {
    private static final String TIMELINE_KEY = OrchTrace.TRACE_KEY_EVENTS_V1;

    private AgentBreadcrumbMemory() {
    }

    public static void toolInvocation(String toolId,
                                      boolean adminAuthorized,
                                      boolean scopesSatisfied,
                                      String status,
                                      long startedMillis,
                                      long elapsedMs,
                                      String failReason,
                                      Throwable error,
                                      Map<String, Object> input,
                                      Map<String, Object> output) {
        toolInvocation(toolId, adminAuthorized, scopesSatisfied, status, startedMillis, elapsedMs,
                failReason, error, input, output, null);
    }

    public static void toolInvocation(String toolId,
                                      boolean adminAuthorized,
                                      boolean scopesSatisfied,
                                      String status,
                                      long startedMillis,
                                      long elapsedMs,
                                      String failReason,
                                      Throwable error,
                                      Map<String, Object> input,
                                      Map<String, Object> output,
                                      FailurePatternMemoryService memory) {
        String safeStatus = SafeRedactor.traceLabelOrFallback(status, "unknown");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolId", SafeRedactor.traceLabelOrFallback(toolId, "unknown"));
        data.put("status", safeStatus);
        data.put("adminAuthorized", adminAuthorized);
        data.put("hasConsent", adminAuthorized || scopesSatisfied);
        data.put("scopesSatisfied", scopesSatisfied);
        data.put("start", Math.max(0L, startedMillis));
        data.put("durationMs", Math.max(0L, elapsedMs));
        data.put("durationBucket", durationBucket(elapsedMs));
        data.put("inputKeyCount", sizeOf(input));
        data.put("outputKeyCount", sizeOf(output));
        if (failReason != null && !failReason.isBlank()) {
            data.put("failureClass", SafeRedactor.traceLabelOrFallback(failReason, "unknown"));
        }
        if (error != null) {
            data.put("errorType", SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown"));
        }
        data.put("rawTile", rawTile("agent_tool_raw_tile", data));
        recall(memory, "agent_tool_breadcrumb", String.valueOf(data.get("toolId")), safeStatus, data);
        append("agent.tool", "invoke", stepFor(safeStatus), data);
        remember(memory,
                "agent_tool_breadcrumb",
                String.valueOf(data.get("toolId")),
                safeStatus,
                safeStatus,
                stepFor(safeStatus),
                data);
    }

    public static void judgment(String flowId,
                                Verdict verdict,
                                RecoveryAction action,
                                int round,
                                long elapsedMs,
                                Map<String, Object> state,
                                Map<String, Object> patch) {
        judgment(flowId, verdict, action, round, elapsedMs, state, patch, null);
    }

    public static void judgment(String flowId,
                                Verdict verdict,
                                RecoveryAction action,
                                int round,
                                long elapsedMs,
                                Map<String, Object> state,
                                Map<String, Object> patch,
                                FailurePatternMemoryService memory) {
        Verdict safeVerdict = verdict == null ? Verdict.accept(1.0d, "accepted") : verdict;
        String safeFlowId = SafeRedactor.traceLabelOrFallback(flowId, "default.v1");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flow", safeFlowId);
        data.put("decision", safeVerdict.decision().name());
        data.put("failureClass", safeVerdict.failureClass().name());
        data.put("confidenceBucket", confidenceBucket(safeVerdict.confidence()));
        data.put("action", action == null ? "ACCEPT" : action.name());
        data.put("round", Math.max(0, round));
        data.put("durationMs", Math.max(0L, elapsedMs));
        data.put("durationBucket", durationBucket(elapsedMs));
        data.put("stateKeyCount", sizeOf(state));
        data.put("patchKeyCount", sizeOf(patch));
        if (safeVerdict.reason() != null && !safeVerdict.reason().isBlank()) {
            data.put("reasonCode", SafeRedactor.traceLabelOrFallback(safeVerdict.reason(), "unknown"));
            data.put("reasonHash", SafeRedactor.hashValue(safeVerdict.reason()));
        }
        data.put("rawTile", rawTile("agent_judgment_raw_tile", data));
        recall(memory, "agent_judgment_breadcrumb", safeFlowId, safeVerdict.failureClass().name(), data);
        append("agent.judgment", action == null ? "accept" : "recovery", action == null ? "ok" : "decision", data);
        remember(memory,
                "agent_judgment_breadcrumb",
                safeFlowId,
                safeVerdict.failureClass().name(),
                action == null ? "ACCEPT" : action.name(),
                safeVerdict.decision().name(),
                data);
    }

    private static void append(String kind, String phase, String step, Map<String, Object> data) {
        try {
            Map<String, Object> event = OrchTrace.newEvent(kind, phase, step, data);
            OrchTrace.appendEvent(event);
            Object seq = event.get("seq");
            TraceStore.put("agent.breadcrumb.timelineKey", TIMELINE_KEY);
            TraceStore.put("agent.breadcrumb.lastKind", kind);
            TraceStore.put("agent.breadcrumb.lastPhase", phase);
            TraceStore.put("agent.breadcrumb.lastStep", step);
            if (seq != null) {
                TraceStore.put("agent.breadcrumb.lastSeq", seq);
                MDC.put("agent.breadcrumb.seq", String.valueOf(seq));
            }
            MDC.put("agent.breadcrumb.kind", kind);
        } catch (RuntimeException ex) {
            TraceStore.put("agent.breadcrumb.suppressed", true);
            TraceStore.put("agent.breadcrumb.suppressed.stage", "append");
            TraceStore.put("agent.breadcrumb.suppressed.errorType",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
        }
    }

    private static Map<String, Object> rawTile(String kind, Map<String, Object> data) {
        String signature = kind + "|"
                + data.getOrDefault("toolId", "")
                + "|" + data.getOrDefault("flow", "")
                + "|" + data.getOrDefault("status", "")
                + "|" + data.getOrDefault("decision", "")
                + "|" + data.getOrDefault("failureClass", "")
                + "|" + data.getOrDefault("durationBucket", "")
                + "|" + data.getOrDefault("inputKeyCount", "")
                + "|" + data.getOrDefault("outputKeyCount", "")
                + "|" + data.getOrDefault("round", "");
        String patternId = SafeRedactor.hashValue(signature);
        Map<String, Object> tile = new LinkedHashMap<>();
        tile.put("kind", kind);
        tile.put("patternId", patternId);
        tile.put("signatureLength", signature.length());
        tile.put("rawPayloadStored", false);
        TraceStore.put("cfvm.agentBreadcrumb.rawTile.created", true);
        TraceStore.put("cfvm.agentBreadcrumb.rawTile.kind", kind);
        TraceStore.put("cfvm.agentBreadcrumb.rawTile.patternId", patternId);
        TraceStore.put("cfvm.agentBreadcrumb.rawTile.rawPayloadStored", false);
        return tile;
    }

    private static void recall(FailurePatternMemoryService memory,
                               String kind,
                               String hotspot,
                               String failureClass,
                               Map<String, Object> data) {
        if (memory == null) {
            return;
        }
        try {
            Map<String, Object> result = memory.recall(Map.of(
                    "kind", kind,
                    "source", "agent_breadcrumb",
                    "failureClass", failureClass,
                    "hotspot", hotspot,
                    "intent", kind + "|" + hotspot + "|" + failureClass,
                    "limit", 3));
            int matchCount = number(result, "matchCount", null);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("matchCount", matchCount);
            String recommendedAction = SafeRedactor.traceLabelOrFallback(
                    String.valueOf(result.getOrDefault("recommendedAction", "")), "");
            if (!recommendedAction.isBlank()) {
                summary.put("recommendedAction", recommendedAction);
            }
            Object matches = result.get("matches");
            if (matches instanceof java.util.List<?> rows && !rows.isEmpty()
                    && rows.get(0) instanceof Map<?, ?> top) {
                Object score = top.get("score");
                if (score instanceof Number number) {
                    summary.put("topScore", Math.max(0, number.intValue()));
                }
                if (top.get("patternId") != null) {
                    summary.put("topPatternId", SafeRedactor.traceLabelOrFallback(
                            String.valueOf(top.get("patternId")), "unknown"));
                }
            }
            data.put("memoryRecall", summary);
            TraceStore.put("agent.breadcrumb.memory.recall.kind", kind);
            TraceStore.put("agent.breadcrumb.memory.recall.matchCount", matchCount);
            TraceStore.put("agent.breadcrumb.memory.recall.recommendedAction",
                    summary.getOrDefault("recommendedAction", ""));
            TraceStore.put("agent.breadcrumb.memory.recall.topScore", summary.getOrDefault("topScore", 0));
            TraceStore.put("agent.breadcrumb.memory.recall.topPatternId", summary.getOrDefault("topPatternId", ""));
        } catch (RuntimeException ex) {
            data.put("memoryRecall", Map.of("matchCount", 0, "skipReason", "memory_recall_exception"));
            TraceStore.put("agent.breadcrumb.memory.recall.matchCount", 0);
            TraceStore.put("agent.breadcrumb.memory.recall.skipReason", "memory_recall_exception");
            TraceStore.put("agent.breadcrumb.memory.recall.errorType",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
        }
    }

    private static void remember(FailurePatternMemoryService memory,
                                 String kind,
                                 String hotspot,
                                 String failureClass,
                                 String patchAction,
                                 String decision,
                                 Map<String, Object> data) {
        if (memory == null) {
            return;
        }
        try {
            Object rawTile = data == null ? null : data.get("rawTile");
            String patternId = "";
            if (rawTile instanceof Map<?, ?> tile && tile.get("patternId") != null) {
                patternId = String.valueOf(tile.get("patternId"));
            }
            Map<String, Object> matrix = new LinkedHashMap<>();
            matrix.put("failurePatternKind", failureClass);
            matrix.put("m1", number(data, "inputKeyCount", "stateKeyCount"));
            matrix.put("m2", number(data, "outputKeyCount", "patchKeyCount"));
            matrix.put("m3", number(data, "round", null));
            Map<String, Object> result = memory.record(Map.of(
                    "kind", kind,
                    "source", "agent_breadcrumb",
                    "failureClass", failureClass,
                    "hotspot", hotspot,
                    "intent", kind + "|" + hotspot + "|" + failureClass,
                    "evidence", "timeline=" + TIMELINE_KEY
                            + "|pattern=" + patternId
                            + "|duration=" + data.getOrDefault("durationBucket", ""),
                    "patchAction", patchAction,
                    "decision", decision,
                    "matrix", matrix));
            boolean recorded = Boolean.TRUE.equals(result.get("recorded"));
            TraceStore.put("agent.breadcrumb.memory.stored", recorded);
            TraceStore.put("agent.breadcrumb.memory.kind", kind);
            TraceStore.put("agent.breadcrumb.memory.skipReason", recorded ? "" : "memory_record_failed");
            if (result.get("patternId") != null) {
                TraceStore.put("agent.breadcrumb.memory.patternId", result.get("patternId"));
            }
        } catch (RuntimeException ex) {
            TraceStore.put("agent.breadcrumb.memory.stored", false);
            TraceStore.put("agent.breadcrumb.memory.skipReason", "memory_record_exception");
            TraceStore.put("agent.breadcrumb.memory.errorType",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
        }
    }

    private static int number(Map<String, Object> data, String primaryKey, String secondaryKey) {
        Object value = data == null ? null : data.get(primaryKey);
        if (!(value instanceof Number) && secondaryKey != null) {
            value = data == null ? null : data.get(secondaryKey);
        }
        return value instanceof Number n && Double.isFinite(n.doubleValue()) ? Math.max(0, n.intValue()) : 0;
    }

    private static int sizeOf(Map<String, Object> map) {
        return map == null ? 0 : map.size();
    }

    private static String stepFor(String status) {
        String s = status == null ? "" : status.trim();
        if ("OK".equalsIgnoreCase(s)) {
            return "ok";
        }
        if (s.contains("REJECTED") || s.contains("DENIED")) {
            return "denied";
        }
        return "error";
    }

    private static String durationBucket(long elapsedMs) {
        if (elapsedMs < 50L) {
            return "lt_50ms";
        }
        if (elapsedMs < 250L) {
            return "lt_250ms";
        }
        if (elapsedMs < 1000L) {
            return "lt_1s";
        }
        if (elapsedMs < 5000L) {
            return "lt_5s";
        }
        return "gte_5s";
    }

    private static String confidenceBucket(double confidence) {
        if (confidence >= 0.8d) {
            return "high";
        }
        if (confidence >= 0.5d) {
            return "medium";
        }
        return "low";
    }
}
