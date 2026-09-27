package com.example.lms.web;

import com.example.lms.trace.SafeRedactor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChatUiHeartbeatPayload {

    private static final long MAX_AGE_MS = 30_000L;
    private static final List<String> CHAT_HARMONY_FIELDS = List.of(
            "status",
            "latestReason",
            "latestDegraded",
            "latestAgentVisible",
            "latestDebugAction",
            "latestDebugReason",
            "latestTraceRoute");
    private static final List<String> OPTIONAL_LANE_FIELDS = List.of(
            "lane",
            "status",
            "reason",
            "nextAction");
    private static final int MAX_OPTIONAL_LANES = 8;
    private static final List<String> TRACE_MEMORY_FIELDS = List.of(
            "status",
            "latestStage",
            "latestPhase",
            "latestVirtualCheckpointKey",
            "latestVirtualCheckpointStage",
            "latestVirtualCheckpointPhase",
            "latestCheckpointHistorySize",
            "latestFingerprintChanged",
            "latestTriggered",
            "latestRecoveryAction",
            "latestRecoveryRoute",
            "latestRecoveryRouteDecision",
            "latestQuarantine",
            "suspectPayloadIsolated",
            "latestRisk",
            "latestCfvmOffered",
            "latestCfvmPatternId",
            "latestTraceRoute",
            "latestCheckpointJsonRoute");

    private ChatUiHeartbeatPayload() {
    }

    static Map<String, Object> from(Map<String, Object> pipelineHealth) {
        Object rawStatus = pipelineHealth == null ? null : pipelineHealth.get("status");
        String status = rawStatus == null ? "" : String.valueOf(rawStatus).trim();
        Map<String, Object> chatHarmony = diagnosticBlock(
                pipelineHealth, "chatHarmony", CHAT_HARMONY_FIELDS);
        Map<String, Object> traceMemory = diagnosticBlock(
                pipelineHealth, "traceMemory", TRACE_MEMORY_FIELDS);
        List<Map<String, Object>> optionalLanes = diagnosticLanes(pipelineHealth);
        if ("OK".equalsIgnoreCase(status)) {
            return projection("OK", "ready", "none", 0L, chatHarmony, traceMemory, optionalLanes);
        }
        if (!status.isBlank()) {
            return projection("WARN", "degraded", "retry_later", 0L, chatHarmony, traceMemory, optionalLanes);
        }
        return unavailable();
    }

    static Map<String, Object> disabled(String ignoredReason) {
        return unavailable();
    }

    static Map<String, Object> withAge(Map<String, Object> base, long ageMs) {
        Map<String, Object> source = base == null ? unavailable() : base;
        String statusBand = "OK".equals(source.get("statusBand")) ? "OK" : "WARN";
        String reasonCode = "OK".equals(statusBand) ? "ready" : fixedWarnReason(source.get("reasonCode"));
        String nextAction = "OK".equals(statusBand) ? "none" : fixedWarnAction(source.get("nextAction"));
        Map<String, Object> chatHarmony = diagnosticBlock(source, "chatHarmony", CHAT_HARMONY_FIELDS);
        Map<String, Object> traceMemory = diagnosticBlock(source, "traceMemory", TRACE_MEMORY_FIELDS);
        List<Map<String, Object>> optionalLanes = diagnosticLanes(source);
        return projection(
                statusBand,
                reasonCode,
                nextAction,
                Math.max(0L, Math.min(MAX_AGE_MS, ageMs)),
                chatHarmony,
                traceMemory,
                optionalLanes);
    }

    private static Map<String, Object> unavailable() {
        return projection("WARN", "heartbeat_unavailable", "retry_heartbeat", 0L,
                Map.of(), Map.of(), List.of());
    }

    private static String fixedWarnReason(Object value) {
        return "degraded".equals(value) ? "degraded" : "heartbeat_unavailable";
    }

    private static String fixedWarnAction(Object value) {
        return "retry_later".equals(value) ? "retry_later" : "retry_heartbeat";
    }

    private static Map<String, Object> projection(
            String statusBand,
            String reasonCode,
            String nextAction,
            long ageMs,
            Map<String, Object> chatHarmony,
            Map<String, Object> traceMemory,
            List<Map<String, Object>> optionalLanes
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusBand", statusBand);
        out.put("reasonCode", reasonCode);
        out.put("nextAction", nextAction);
        out.put("ageMs", ageMs);
        if (chatHarmony != null && !chatHarmony.isEmpty()) {
            out.put("chatHarmony", chatHarmony);
        }
        if (traceMemory != null && !traceMemory.isEmpty()) {
            out.put("traceMemory", traceMemory);
        }
        if (optionalLanes != null && !optionalLanes.isEmpty()) {
            out.put("optionalLanes", optionalLanes);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 선택 레인(예: agentDbContext)은 allowlist 필드만 투영한다. core 판정에는
     * 전혀 관여하지 않는 정보성 블록이다.
     */
    private static List<Map<String, Object>> diagnosticLanes(Map<String, Object> source) {
        Object rawLanes = source == null ? null : source.get("optionalLanes");
        if (!(rawLanes instanceof List<?> lanes) || lanes.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object lane : lanes) {
            if (out.size() >= MAX_OPTIONAL_LANES) {
                break;
            }
            if (!(lane instanceof Map<?, ?> laneMap)) {
                continue;
            }
            Map<String, Object> projected = new LinkedHashMap<>();
            for (String field : OPTIONAL_LANE_FIELDS) {
                Object safeValue = safeDiagnosticScalar(field, laneMap.get(field));
                if (safeValue != null) {
                    projected.put(field, safeValue);
                }
            }
            if (!projected.isEmpty()) {
                out.add(Collections.unmodifiableMap(projected));
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, Object> diagnosticBlock(
            Map<String, Object> source,
            String blockName,
            List<String> allowedFields
    ) {
        Object rawBlock = source == null ? null : source.get(blockName);
        if (!(rawBlock instanceof Map<?, ?> block)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String field : allowedFields) {
            Object safeValue = safeDiagnosticScalar(field, block.get(field));
            if (safeValue != null) {
                out.put(field, safeValue);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Object safeDiagnosticScalar(String field, Object value) {
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            return null;
        }
        if (value instanceof Float number && !Float.isFinite(number)) {
            return null;
        }
        if (value instanceof Number) {
            return value;
        }
        if (!(value instanceof String text)) {
            return null;
        }
        if ("latestTraceRoute".equals(field) || "latestCheckpointJsonRoute".equals(field)) {
            return fixedDiagnosticRoute(text);
        }
        String label = SafeRedactor.traceLabel(text);
        return label == null || label.isBlank() ? null : label;
    }

    private static String fixedDiagnosticRoute(String value) {
        String route = value == null ? "" : value.trim();
        if (route.length() > 160
                || !route.startsWith("/api/diagnostics/trace/")
                || !route.matches("[A-Za-z0-9_./:-]+")) {
            return null;
        }
        return route;
    }
}
