package com.example.lms.agent.context;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class ExternalAgentEvidenceReader {
    private static final Logger log = LoggerFactory.getLogger(ExternalAgentEvidenceReader.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OK = "OK";
    private static final String WARN = "WARN";
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{1,63}");
    private static final List<String> EXTERNAL_EVIDENCE_LANES =
            List.of("supabase", "superpowers", "computer-use", "browser");

    private ExternalAgentEvidenceReader() {
    }

    static Map<String, Object> goalNextAuto(String path) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", "goal-next-auto");
        row.put("readOnly", true);
        row.put("mutationAllowed", false);
        Path summaryPath = Path.of(firstNonBlank(path, "var/codex-smoke/goal-next-auto/goal-next-auto.summary.json"));
        if (!Files.isRegularFile(summaryPath)) {
            row.put("status", WARN);
            row.put("decision", "evidence_needed");
            row.put("evidenceNeeded", "goal_next_auto_summary_missing");
            row.put("nextAction", "run_goal_next_auto");
            return row;
        }
        try {
            JsonNode root = readJson(summaryPath);
            boolean ok = root.path("ok").asBoolean(false);
            JsonNode gate = root.path("externalInputGate");
            JsonNode desktop = root.path("desktopControlLoop");
            JsonNode supabaseSmoke = root.path("supabaseSmoke");
            JsonNode supabaseApply = root.path("supabaseApply");
            JsonNode externalApply = root.path("externalApply");
            String decision = label(root.path("decision").asText("evidence_needed"), null);
            String failureClass = label(root.path("failureClassification").asText(decision), null);
            row.put("status", ok ? OK : WARN);
            row.put("decision", decision == null ? "evidence_needed" : decision);
            row.put("evidenceNeeded", ok ? null : firstNonBlank(failureClass, "evidence_needed"));
            row.put("nextAction", firstNonBlank(action(root.path("firstAction").asText("")), ok ? "none" : "inspect_goal_next_auto"));
            row.put("nextActionSource", firstNonBlank(action(root.path("firstActionSource").asText("")), "goal_next_auto"));
            row.put("nextActionCount", Math.max(0, root.path("nextActionCount").asInt(0)));
            row.put("nextActionSources", firstNonBlank(jsonLabelList(root.path("nextActionSources")), "none"));
            row.put("topActions", firstNonBlank(topActions(root.path("topActions")), "none"));
            row.put("externalInputGateStatus", firstNonBlank(label(gate.path("status").asText(""), null), "unknown"));
            row.put("externalInputMutationAllowed", gate.path("mutationAllowed").asBoolean(false));
            String generatedAt = firstNonBlank(action(root.path("generatedAt").asText("")), "unknown");
            int ageMinutes = ageMinutes(generatedAt);
            row.put("generatedAt", generatedAt);
            row.put("summaryGeneratedAt", generatedAt);
            row.put("ageMinutes", ageMinutes);
            row.put("staleAfterMinutes", 60);
            row.put("stale", ageMinutes >= 60);
            String summaryPathText = summaryPath.toAbsolutePath().normalize().toString();
            row.put("summaryPathHash", SafeRedactor.hashValue(summaryPathText));
            row.put("summaryPathLength", summaryPathText.length());
            row.put("summaryFileUpdatedAt", action(Files.getLastModifiedTime(summaryPath).toInstant().toString()));
            row.put("repeatCount", Math.max(0, gate.path("repeatCount").asInt(0)));
            row.put("localPatchJustified", gate.path("localPatchJustified").asBoolean(false));
            row.put("localReady", desktop.path("localReady").asBoolean(false));
            row.put("externalEvidenceComplete", desktop.path("externalEvidenceComplete").asBoolean(false));
            putExitAndSupabase(row, root, supabaseSmoke, supabaseApply, externalApply);
            putUiSmoke(row, root.path("computerUse"), root.path("browserUse"));
            return row;
        } catch (IOException | RuntimeException ex) {
            traceSuppressed("goal_next_auto_summary", ex);
            row.put("status", WARN);
            row.put("decision", "evidence_needed");
            row.put("evidenceNeeded", "goal_next_auto_summary_unreadable");
            row.put("nextAction", "rerun_goal_next_auto");
            return row;
        }
    }

    static Map<String, Object> noether(String path) {
        Map<String, Object> row = base("noether");
        Path statusPath = Path.of(firstNonBlank(path, "var/codex-smoke/noether-subagent-status.json"));
        if (!Files.isRegularFile(statusPath)) {
            row.put("status", WARN);
            row.put("waiting", true);
            row.put("responded", false);
            row.put("evidenceNeeded", "noether_status_missing");
            row.put("nextAction", "wait_for_noether_redacted_status");
            return row;
        }
        try {
            JsonNode root = readJson(statusPath);
            int secretHits = Math.max(0, root.path("secretHits").asInt(0))
                    + Math.max(0, root.path("rawSecretPatternHits").asInt(0));
            boolean ok = root.path("ok").asBoolean(false);
            boolean responded = root.path("responded").asBoolean(false);
            row.put("status", ok && responded && secretHits == 0 ? OK : WARN);
            putAgentIdentity(row, text(root, "agentId"));
            row.put("agentName", label(text(root, "agentName"), "noether"));
            row.put("waiting", root.path("waiting").asBoolean(true));
            row.put("responded", responded);
            row.put("lastMessageKind", label(text(root, "lastMessageKind"), responded ? "response_seen" : "pending"));
            row.put("secretHits", secretHits);
            row.put("evidenceNeeded", secretHits > 0 ? "noether_status_secret_pattern_hits"
                    : (ok && responded ? "ready" : firstNonBlank(label(text(root, "evidenceNeeded"), ""), "noether_response_pending")));
            row.put("nextAction", firstNonBlank(label(text(root, "nextAction"), ""), responded ? "wait_for_new_redacted_evidence" : "wait_for_noether_response"));
            return row;
        } catch (IOException | RuntimeException ex) {
            traceSuppressed("noether_status", ex);
            row.put("status", WARN);
            row.put("waiting", true);
            row.put("responded", false);
            row.put("evidenceNeeded", "noether_status_unreadable");
            row.put("nextAction", "rewrite_noether_redacted_status");
            return row;
        }
    }

    static Map<String, Object> supabaseDetails(String path) {
        Map<String, Object> row = new LinkedHashMap<>();
        putSupabaseDefaults(row);
        Path summaryPath = Path.of(firstNonBlank(path, "var/codex-smoke/goal-next-auto/goal-next-auto.summary.json"));
        if (!Files.isRegularFile(summaryPath)) {
            return row;
        }
        try {
            JsonNode root = readJson(summaryPath);
            JsonNode supabaseSmoke = root.path("supabaseSmoke");
            JsonNode supabaseApply = root.path("supabaseApply");
            row.put("mcpDecision", firstNonBlank(label(supabaseSmoke.path("mcpDecision").asText(""), null), "unknown"));
            row.put("evidenceNeededCount", Math.max(
                    firstInt(supabaseApply, "evidenceNeededCount"),
                    firstInt(supabaseSmoke, "evidenceNeededCount")));
            row.put("requiredEnvNames", firstNonBlank(jsonEnvNameList(supabaseApply.path("requiredEnvNames")), "none"));
            row.put("requiredMcpTools", firstNonBlank(jsonLabelList(supabaseApply.path("requiredMcpTools")), "none"));
            row.put("requiredResultNames", firstNonBlank(jsonLabelList(supabaseApply.path("requiredResultNames")), "none"));
            return row;
        } catch (IOException | RuntimeException ex) {
            traceSuppressed("supabase_details", ex);
            return row;
        }
    }

    static void mergeRequestedLanes(List<Map<String, Object>> rows, Map<String, Object> trace) {
        List<String> lanes = requestedLanes(trace == null ? null : trace.get("externalEvidence.requestedLanes"));
        if (lanes.isEmpty()) {
            return;
        }
        String lanePolicy = firstNonBlank(labelValue(trace.get("externalEvidence.mode")), "external_evidence");
        String sourceStage = firstNonBlank(labelValue(trace.get("externalEvidence.source")), "trace");
        for (String lane : lanes) {
            Map<String, Object> row = findService(rows, lane);
            if (row == null) {
                rows.add(requestedLane(lane, trace, lanePolicy, sourceStage));
            } else {
                putExternalBoundary(row, lanePolicy, sourceStage);
            }
        }
    }

    private static Map<String, Object> requestedLane(String lane,
                                                     Map<String, Object> trace,
                                                     String lanePolicy,
                                                     String sourceStage) {
        String evidenceNeeded = firstNonBlank(labelValue(trace.get(lane + ".evidenceNeeded")),
                firstNonBlank(labelValue(trace.get(lane + ".disabledReason")), "external_evidence_lane"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", lane);
        row.put("status", "ready".equals(evidenceNeeded) ? OK : WARN);
        row.put("evidenceScope", "external-evidence-lane");
        row.put("evidenceNeeded", evidenceNeeded);
        row.put("nextAction", "ready".equals(evidenceNeeded)
                ? "external_evidence_lane_current"
                : "collect_" + lane.replace('-', '_') + "_external_evidence");
        putExternalBoundary(row, lanePolicy, sourceStage);
        return row;
    }

    private static void putExternalBoundary(Map<String, Object> row, String lanePolicy, String sourceStage) {
        row.put("readOnly", true);
        row.put("mutationAllowed", false);
        row.put("executionThread", false);
        row.put("lanePolicy", firstNonBlank(lanePolicy, "external_evidence"));
        row.put("sourceStage", firstNonBlank(sourceStage, "trace"));
    }

    private static List<String> requestedLanes(Object requested) {
        List<String> lanes = new ArrayList<>();
        if (requested instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addRequestedLane(lanes, item);
            }
        } else {
            addRequestedLane(lanes, requested);
        }
        return List.copyOf(lanes);
    }

    private static void addRequestedLane(List<String> lanes, Object value) {
        String lane = labelValue(value);
        if (lane != null && EXTERNAL_EVIDENCE_LANES.contains(lane) && !lanes.contains(lane)) {
            lanes.add(lane);
        }
    }

    private static Map<String, Object> findService(List<Map<String, Object>> rows, String service) {
        if (rows == null || service == null) {
            return null;
        }
        for (Map<String, Object> row : rows) {
            if (row != null && service.equals(String.valueOf(row.get("service")))) {
                return row;
            }
        }
        return null;
    }

    private static Map<String, Object> base(String service) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", service);
        row.put("readOnly", true);
        row.put("mutationAllowed", false);
        row.put("evidenceScope", "subagent-response-proof");
        return row;
    }

    private static void putAgentIdentity(Map<String, Object> row, String agentId) {
        String id = agentId == null ? "" : agentId.trim();
        row.put("agentIdHash", firstNonBlank(SafeRedactor.hashValue(id), "none"));
        row.put("agentIdLength", id.length());
    }

    private static void putExitAndSupabase(Map<String, Object> row,
                                           JsonNode root,
                                           JsonNode supabaseSmoke,
                                           JsonNode supabaseApply,
                                           JsonNode externalApply) {
        int supabaseExit = Math.max(0, root.path("supabaseSmokeExit").asInt(0));
        int externalExit = Math.max(0, root.path("externalApplyExit").asInt(0));
        int sourceExit = Math.max(0, root.path("sourceHealthExit").asInt(0));
        int auditExit = Math.max(0, root.path("completionAuditExit").asInt(0));
        row.put("supabaseSmokeExit", supabaseExit);
        row.put("externalApplyExit", externalExit);
        row.put("sourceHealthExit", sourceExit);
        row.put("completionAuditExit", auditExit);
        row.put("exitSummary", "supabase=" + supabaseExit + " external=" + externalExit
                + " source=" + sourceExit + " audit=" + auditExit);
        row.put("supabaseProjectScopeStatus", firstNonBlank(label(supabaseSmoke.path("projectScopeStatus").asText(""), null), "unknown"));
        row.put("supabaseMcpDecision", firstNonBlank(label(supabaseSmoke.path("mcpDecision").asText(""), null), "unknown"));
        row.put("supabaseEvidenceNeededCount", Math.max(
                firstInt(supabaseApply, "evidenceNeededCount"), firstInt(supabaseSmoke, "evidenceNeededCount")));
        row.put("supabaseRequiredEnvNames", firstNonBlank(jsonEnvNameList(supabaseApply.path("requiredEnvNames")), "none"));
        row.put("supabaseRequiredMcpTools", firstNonBlank(jsonLabelList(supabaseApply.path("requiredMcpTools")), "none"));
        row.put("supabaseRequiredResultNames", firstNonBlank(jsonLabelList(supabaseApply.path("requiredResultNames")), "none"));
        row.put("externalEvidenceNeededCount", firstInt(externalApply, "evidenceNeededCount"));
        row.put("externalRequiredRoles", firstNonBlank(jsonLabelList(externalApply.path("requiredRoles")), "none"));
        row.put("externalRequiredProducerEvidenceFiles", firstNonBlank(jsonActionList(externalApply.path("requiredProducerEvidenceFiles")), "none"));
        row.put("externalRequiredPatchDropSidecars", firstNonBlank(jsonActionList(externalApply.path("requiredPatchDropSidecars")), "none"));
        JsonNode sourceIsolation = externalApply.path("requiredSourceIsolation");
        row.put("externalRequiredSourceIsolationGuard", firstNonBlank(action(sourceIsolation.path("guard").asText("")), "unknown"));
        row.put("externalRequiredSourceRootKind", firstNonBlank(action(sourceIsolation.path("sourceRootKind").asText("")), "unknown"));
        row.put("externalRequiredDirectCanonicalSourceEdit", sourceIsolation.path("directCanonicalSourceEdit").asBoolean(false));
        row.put("externalCopiedEvidenceCount", firstInt(externalApply, "copiedEvidenceCount"));
        row.put("externalCopiedHandoffCount", firstInt(externalApply, "copiedHandoffCount"));
    }

    private static void putUiSmoke(Map<String, Object> row, JsonNode computerUse, JsonNode browserUse) {
        row.put("computerUseDecision", firstNonBlank(label(computerUse.path("decision").asText(""), null), "unknown"));
        row.put("computerUseReachable", computerUse.path("reachable").asBoolean(false));
        row.put("computerUseStale", computerUse.path("stale").asBoolean(false));
        row.put("computerUseAppCount", firstInt(computerUse, "appCount"));
        row.put("computerUseWindowCount", firstInt(computerUse, "targetableWindowCount", "windowCount"));
        row.put("computerUseNextAction", firstNonBlank(action(computerUse.path("nextAction").asText("")), "none"));
        row.put("computerUseSecretHits", firstInt(computerUse, "secretHits", "rawSecretPatternHits"));
        row.put("browserUseDecision", firstNonBlank(label(browserUse.path("decision").asText(""), null), "unknown"));
        row.put("browserUseReachable", browserUse.path("reachable").asBoolean(false));
        row.put("browserUseStale", browserUse.path("stale").asBoolean(false));
        row.put("browserUseScreenshotCaptured", browserUse.path("screenshotCaptured").asBoolean(false));
        row.put("browserUseTargetContentVisible", browserUse.path("targetContentVisible").asBoolean(false));
        row.put("browserUseStatusClass", firstNonBlank(label(browserUse.path("statusClass").asText(""), null), "unknown"));
        row.put("browserUseSurface", firstNonBlank(label(browserUse.path("browserSurface").asText(""), null), "unknown"));
        row.put("browserUseEvidenceNeeded", firstNonBlank(label(browserUse.path("evidenceNeeded").asText(""), null), "none"));
        row.put("browserUseNextAction", firstNonBlank(action(browserUse.path("nextAction").asText("")), "none"));
        row.put("browserUseSecretHits", firstInt(browserUse, "secretHits", "rawSecretPatternHits"));
    }

    private static void putSupabaseDefaults(Map<String, Object> row) {
        row.put("mcpDecision", "unknown");
        row.put("evidenceNeededCount", 0);
        row.put("requiredEnvNames", "none");
        row.put("requiredMcpTools", "none");
        row.put("requiredResultNames", "none");
    }

    private static int ageMinutes(String generatedAt) {
        try {
            Instant then = Instant.parse(generatedAt);
            long minutes = Duration.between(then, Instant.now()).toMinutes();
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, minutes));
        } catch (RuntimeException ex) {
            traceSuppressed("goal_next_auto_generated_at", ex);
            return 0;
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static JsonNode readJson(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return JSON.readTree(text.startsWith("\uFEFF") ? text.substring(1) : text);
    }

    private static String label(String value, String fallback) {
        String label = SafeRedactor.traceLabel(value);
        return firstNonBlank(label == null ? null : label.toLowerCase(Locale.ROOT), fallback);
    }

    private static String labelValue(Object value) {
        return value == null ? null : label(String.valueOf(value), null);
    }

    private static String action(String value) {
        String label = SafeRedactor.traceLabel(value);
        return label == null ? null : label;
    }

    private static String jsonLabelList(JsonNode node) {
        return jsonList(node, false);
    }

    private static String jsonActionList(JsonNode node) {
        return jsonList(node, true);
    }

    private static String jsonEnvNameList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode item : node) {
            String value = envName(item.asText(""));
            if (value == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(value);
        }
        return out.toString();
    }

    private static String envName(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (ENV_NAME.matcher(trimmed).matches()) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return label(trimmed, null);
    }

    private static String jsonList(JsonNode node, boolean keepCase) {
        if (node == null || !node.isArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode item : node) {
            String value = keepCase ? action(item.asText("")) : label(item.asText(""), null);
            if (value == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(value);
        }
        return out.toString();
    }

    private static String topActions(JsonNode node) {
        if (node == null || !node.isArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int added = 0;
        for (JsonNode item : node) {
            String source = label(item.path("source").asText(""), null);
            String action = actionSummary(item.path("action").asText(""));
            if (source == null || action == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(source).append(':').append(action);
            if (++added >= 5) {
                break;
            }
        }
        return out.toString();
    }

    private static String actionSummary(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.indexOf(':') >= 0 || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            return "path_value_redacted";
        }
        return action(value);
    }

    private static int firstInt(JsonNode root, String... fieldNames) {
        if (root == null || fieldNames == null) {
            return 0;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = root.path(fieldName);
            if (value.isNumber()) {
                return value.asInt(0);
            }
        }
        return 0;
    }

    private static void traceSuppressed(String stage, Throwable ex) {
        String safeStage = firstNonBlank(SafeRedactor.traceLabelOrFallback(stage, null), "unknown");
        String errorType = firstNonBlank(
                SafeRedactor.traceLabelOrFallback(ex == null ? null : ex.getClass().getSimpleName(), null),
                "unknown");
        try {
            TraceStore.put("externalEvidence.reader.suppressed.stage", safeStage);
            TraceStore.put("externalEvidence.reader.suppressed.errorType", errorType);
            TraceStore.put("externalEvidence.reader.suppressed." + safeStage, true);
            TraceStore.put("externalEvidence.reader.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException ignored) {
            log.warn("[AWX][external-evidence] traceStoreWriteSuppressed stage={} errorType={}",
                    safeStage, errorType);
            // Last-resort fail-soft path; external evidence readers must not break the health console.
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
