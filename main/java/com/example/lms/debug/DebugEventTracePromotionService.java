package com.example.lms.debug;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Promotes selected request-local TraceStore breadcrumbs into DebugEvent rows.
 *
 * <p>TraceStore remains the request-internal diagnostic surface. DebugEvent is
 * the operator-console unit, so this bridge only emits low-cardinality,
 * redacted summaries.</p>
 */
@Service
public class DebugEventTracePromotionService {

    private static final Logger log = LoggerFactory.getLogger(DebugEventTracePromotionService.class);
    private static final List<String> EXTERNAL_LANES =
            List.of("supabase", "superpowers", "computer-use", "browser");
    private static final Set<String> QUERY_REWRITE_SUB_MODEL_IDS =
            Set.of("definition-model", "alias-model", "relation-model");
    private static final Set<String> QUERY_REWRITE_BRANCH_AXES =
            Set.of("definition", "alias", "relation");
    private static final Map<String, String> FAULT_MASK_COUNTERS = Map.of(
            "reactor.onErrorDropped.count", "reactor_on_error_dropped",
            "ctx.debugPort.suppressed.count", "debug_port_suppressed",
            "ctx.propagation.missing.count", "context_propagation_missing");
    private static final Set<String> STAGE_BOUNDARY_STAGES = Set.of(
            "request", "orchestration", "search", "prompt", "llm", "sse", "verification");
    private static final Set<String> STAGE_BOUNDARY_FAILURE_CLASSES = Set.of(
            "context-missing", "fallback", "catch", "provider-disabled", "timeout", "rate-limit",
            "zero-result", "after-filter-starvation", "silent-failure");
    private static final Set<String> VERIFICATION_JUDGE_LANES = Set.of(
            "fact_status_classifier", "claim_verifier", "both");
    private static final int MAX_SUPPRESSED_EVENTS = 8;

    private final DebugEventStore debugEventStore;

    public DebugEventTracePromotionService(DebugEventStore debugEventStore) {
        this.debugEventStore = debugEventStore;
    }

    public static void seedRequestedExternalEvidenceLanes(String text) {
        List<String> lanes = detectRequestedExternalEvidenceLanes(text);
        if (lanes.isEmpty()) {
            return;
        }
        try {
            TraceStore.put("externalEvidence.mode", "external_evidence");
            TraceStore.put("externalEvidence.source", "chat_request_tags");
            TraceStore.put("externalEvidence.executionThread", false);
            TraceStore.put("externalEvidence.readOnly", true);
            TraceStore.put("externalEvidence.mutationAllowed", false);
            TraceStore.put("externalEvidence.requestedLanes", lanes);
            TraceStore.put("externalEvidence.requestedLaneCount", lanes.size());
            for (String lane : lanes) {
                TraceStore.put(lane + ".evidenceNeeded", "external_evidence_lane");
                TraceStore.put(lane + ".readOnly", true);
                TraceStore.put(lane + ".mutationAllowed", false);
            }
        } catch (RuntimeException ignore) {
            traceSuppressed("seed.externalEvidence", ignore);
        }
    }

    static List<String> detectRequestedExternalEvidenceLanes(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> lanes = new LinkedHashSet<>();
        if (lower.contains("@supabase")) {
            lanes.add("supabase");
        }
        if (lower.contains("@superpowers")) {
            lanes.add("superpowers");
        }
        if (lower.contains("@computer-use")
                || lower.contains("@computer")
                || lower.contains("@\uCEF4\uD4E8\uD130")) {
            lanes.add("computer-use");
        }
        if (lower.contains("@browser") || lower.contains("@\uBE0C\uB77C\uC6B0\uC800")) {
            lanes.add("browser");
        }
        return List.copyOf(lanes);
    }

    public void promoteChatTrace(String phase, Map<String, Object> traceMeta, String where) {
        if (debugEventStore == null || traceMeta == null || traceMeta.isEmpty()) {
            return;
        }
        promoteExternalEvidence(phase, traceMeta, where);
        promoteQueryRewriteSuperTokens(phase, traceMeta, where);
        promoteLocalLlmOperatorAction(phase, traceMeta, where);
        promoteStageBoundaryBreadcrumbs(phase, traceMeta, where);
        promoteSuppressedStages(phase, traceMeta, where);
        promoteFaultMaskCounters(phase, traceMeta, where);
    }

    public void promoteStageBoundaryBreadcrumbsOnly(
            String phase, Map<String, Object> traceMeta, String where) {
        if (debugEventStore == null || traceMeta == null || traceMeta.isEmpty()) {
            return;
        }
        promoteStageBoundaryBreadcrumbs(phase, traceMeta, where);
    }

    private void promoteExternalEvidence(String phase, Map<String, Object> traceMeta, String where) {
        List<String> lanes = externalLanes(traceMeta);
        if (lanes.isEmpty()) {
            return;
        }

        List<Map<String, Object>> laneRows = new ArrayList<>();
        boolean warn = false;
        for (String lane : lanes) {
            String evidenceNeeded = firstNonBlank(
                    safeLabel(traceMeta.get(lane + ".evidenceNeeded")),
                    safeLabel(traceMeta.get(lane + ".disabledReason")),
                    safeLabel(traceMeta.get(lane + ".projectRefMissing")));
            boolean laneWarn = evidenceNeeded != null;
            warn = warn || laneWarn;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lane", lane);
            row.put("status", laneWarn ? "WARN" : "OBSERVED");
            row.put("reason", evidenceNeeded);
            row.put("readOnly", true);
            row.put("mutationAllowed", false);
            row.put("executionThread", false);
            laneRows.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", safePhase(phase));
        data.put("lanePolicy", "external_evidence");
        data.put("failureClass", warn ? "external_evidence_lane" : "external_evidence_observed");
        data.put("sourceStage", firstNonBlank(safeLabel(traceMeta.get("externalEvidence.source")), "trace"));
        data.put("executionThread", false);
        data.put("readOnly", true);
        data.put("mutationAllowed", false);
        data.put("laneCount", lanes.size());
        data.put("lanes", laneRows);

        debugEventStore.emit(
                DebugProbeType.EXTERNAL_EVIDENCE,
                warn ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                "chat.externalEvidence." + safePhase(phase),
                "[AWX][external] External evidence lane observed",
                where,
                data,
                null);
    }

    private void promoteQueryRewriteSuperTokens(String phase, Map<String, Object> traceMeta, String where) {
        int branchCount = intValue(traceMeta.get("queryTransformer.subQueries.superTokens.branchCount"), 0);
        int tokenCount = intValue(traceMeta.get("queryTransformer.subQueries.superTokens.tokenCount"), 0);
        int subModelCount = intValue(traceMeta.get("queryTransformer.subQueries.superTokens.subModelCount"), 0);
        int branchTitleCount = intValue(traceMeta.get("queryTransformer.subQueries.superTokens.branchTitleCount"), 0);
        int refinedCount = intValue(traceMeta.get("queryTransformer.subQueries.refined.count"), 0);
        int paddedCount = intValue(traceMeta.get("queryTransformer.subQueries.refined.paddedCount"), 0);
        int outputAxisCount = intValue(traceMeta.get("queryTransformer.subQueries.coverage.axisCount"), 0);
        int outputCoveredAxisCount = intValue(traceMeta.get("queryTransformer.subQueries.coverage.coveredAxisCount"), 0);
        int outputMissingAxisCount = intValue(traceMeta.get("queryTransformer.subQueries.coverage.missingAxisCount"), 0);
        boolean outputCoverageComplete = truthy(traceMeta.get("queryTransformer.subQueries.coverage.complete"));
        if (!truthy(traceMeta.get("queryTransformer.subQueries.superTokens.enabled"))
                && branchCount <= 0
                && tokenCount <= 0
                && subModelCount <= 0
                && branchTitleCount <= 0
                && !truthy(traceMeta.get("queryTransformer.subQueries.refined"))
                && refinedCount <= 0
                && paddedCount <= 0
                && outputAxisCount <= 0
                && outputCoveredAxisCount <= 0
                && !outputCoverageComplete
                && !truthy(traceMeta.get("queryTransformer.subQueries.fallback"))) {
            return;
        }

        List<String> subModelIds = queryRewriteSubModelIds(
                traceMeta.get("queryTransformer.subQueries.superTokens.subModelIds"));
        List<String> branchAxes = queryRewriteBranchAxes(
                traceMeta.get("queryTransformer.subQueries.superTokens.axes"));
        List<String> branchTitleHashes = queryRewriteBranchTitleHashes(
                traceMeta.get("queryTransformer.subQueries.superTokens.branchTitleHashes"));
        List<Integer> branchTitleLengths = queryRewriteIntList(
                traceMeta.get("queryTransformer.subQueries.superTokens.branchTitleLengths"));
        List<Integer> branchTitleTermCounts = queryRewriteIntList(
                traceMeta.get("queryTransformer.subQueries.superTokens.branchTitleTermCounts"));
        List<String> branchQueryHashes = queryRewriteBranchTitleHashes(
                traceMeta.get("queryTransformer.subQueries.superTokens.branchQueryHashes"));
        List<Integer> branchQueryLengths = queryRewriteIntList(
                traceMeta.get("queryTransformer.subQueries.superTokens.branchQueryLengths"));
        List<Integer> branchQueryTermCounts = queryRewriteIntList(
                traceMeta.get("queryTransformer.subQueries.superTokens.branchQueryTermCounts"));
        List<String> outputCoveredAxes = queryRewriteBranchAxes(
                traceMeta.get("queryTransformer.subQueries.coverage.coveredAxes"));
        int subModelAssignmentCount = Math.max(subModelIds.size(),
                intValue(traceMeta.get("queryTransformer.subQueries.superTokens.subModelAssignmentCount"), 0));
        int branchAxisCount = Math.max(branchAxes.size(),
                intValue(traceMeta.get("queryTransformer.subQueries.superTokens.axisCount"), 0));
        int branchTitleHashCount = Math.max(branchTitleHashes.size(),
                intValue(traceMeta.get("queryTransformer.subQueries.superTokens.branchTitleHashCount"), 0));
        List<Map<String, Object>> branchTitleMetadata = queryRewriteBranchTitleMetadata(
                branchAxes, branchTitleHashes, branchTitleLengths, branchTitleTermCounts);
        List<Map<String, Object>> branchQueryMetadata = queryRewriteBranchQueryMetadata(
                branchAxes, subModelIds, branchQueryHashes, branchQueryLengths, branchQueryTermCounts);
        int coveredAxisCount = Math.max(outputCoveredAxes.size(), outputCoveredAxisCount);
        boolean coverageComplete = truthy(traceMeta.get("queryTransformer.subQueries.superTokens.coverageComplete"));
        boolean branchTitleCoverageComplete = truthy(
                traceMeta.get("queryTransformer.subQueries.superTokens.branchTitleCoverageComplete"));
        boolean branchQueryCoverageComplete = truthy(
                traceMeta.get("queryTransformer.subQueries.superTokens.branchQueryCoverageComplete"));
        boolean titlePresent = truthy(traceMeta.get("queryTransformer.subQueries.superTokens.titlePresent"));
        String titleHash = safeLabel(traceMeta.get("queryTransformer.subQueries.superTokens.titleHash12"));
        int titleTokenCount = intValue(traceMeta.get("queryTransformer.subQueries.superTokens.titleTokenCount"), 0);
        int titleLength = intValue(traceMeta.get("queryTransformer.subQueries.superTokens.titleLength"), 0);
        boolean incompleteCoverage = outputAxisCount > 0
                && (outputMissingAxisCount > 0 || coveredAxisCount < outputAxisCount || !outputCoverageComplete);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", safePhase(phase));
        data.put("phaseStage", safePhase(phase));
        data.put("stage", "query_rewrite");
        data.put("failureClass", incompleteCoverage
                ? "query_rewrite.super_tokens.incomplete_coverage"
                : tokenCount > 0 ? "query_rewrite.super_tokens" : "query_rewrite");
        data.put("branchCount", Math.max(0, branchCount));
        data.put("superCount", Math.max(0, tokenCount));
        data.put("subModelCount", Math.max(Math.max(0, subModelCount), subModelIds.size()));
        data.put("subModelAssignmentCount", Math.max(0, subModelAssignmentCount));
        data.put("refinedCount", Math.max(0, refinedCount));
        data.put("paddedCount", Math.max(0, paddedCount));
        if (!subModelIds.isEmpty()) {
            data.put("subModelIds", subModelIds);
        }
        data.put("branchAxisCount", Math.max(0, branchAxisCount));
        if (!branchAxes.isEmpty()) {
            data.put("branchAxes", branchAxes);
        }
        data.put("branchTitleCount", Math.max(0, branchTitleCount));
        data.put("branchTitleHashCount", Math.max(0, branchTitleHashCount));
        if (!branchTitleHashes.isEmpty()) {
            data.put("branchTitleHashes", branchTitleHashes);
        }
        data.put("branchTitleMetadataCount", branchTitleMetadata.size());
        if (!branchTitleMetadata.isEmpty()) {
            data.put("branchTitleMetadata", branchTitleMetadata);
        }
        data.put("branchQueryMetadataCount", branchQueryMetadata.size());
        if (!branchQueryMetadata.isEmpty()) {
            data.put("branchQueryMetadata", branchQueryMetadata);
        }
        data.put("branchQueryCoverageComplete", branchQueryCoverageComplete);
        data.put("titlePresent", titlePresent);
        data.put("titleHash12", titleHash);
        data.put("titleTermCount", Math.max(0, titleTokenCount));
        data.put("titleLength", Math.max(0, titleLength));
        data.put("branchTitleCoverageComplete", branchTitleCoverageComplete);
        data.put("coverageComplete", coverageComplete);
        data.put("outputAxisCount", Math.max(0, outputAxisCount));
        data.put("coveredAxisCount", Math.max(0, coveredAxisCount));
        data.put("missingAxisCount", Math.max(0, outputMissingAxisCount));
        data.put("outputCoverageComplete", outputCoverageComplete);
        if (!outputCoveredAxes.isEmpty()) {
            data.put("coveredAxes", outputCoveredAxes);
        }
        data.put("promotedFromTraceStore", true);

        debugEventStore.emit(
                DebugProbeType.QUERY_TRANSFORMER,
                incompleteCoverage ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                "chat.queryRewrite.superTokens." + safePhase(phase),
                incompleteCoverage
                        ? "[AWX][query-rewrite] Super-token query rewrite incomplete coverage"
                        : "[AWX][query-rewrite] Super-token query rewrite observed",
                where,
                data,
                null);
    }

    private void promoteLocalLlmOperatorAction(String phase, Map<String, Object> traceMeta, String where) {
        boolean triggered = truthy(traceMeta.get("llm.localSmoke.operatorAction.triggered"));
        String triggerReason = firstNonBlank(
                safeLabel(traceMeta.get("llm.localSmoke.operatorAction.triggerReason")),
                "none");
        String failureClass = firstNonBlank(
                safeLabel(traceMeta.get("llm.localSmoke.operatorAction.failureClass")),
                "none");
        String nextAction = firstNonBlank(
                safeLabel(traceMeta.get("llm.localSmoke.operatorAction.nextAction")),
                "monitor_local_llm_route");
        int actionScore = Math.max(0, intValue(traceMeta.get("llm.localSmoke.operatorAction.actionScore"), 0));
        int scoreDelta = Math.max(0, intValue(traceMeta.get("llm.localSmoke.operatorAction.scoreDelta"), 0));
        int negativeSignalCount = Math.max(0,
                intValue(traceMeta.get("llm.localSmoke.operatorAction.negativeSignalCount"), 0));

        if (!triggered
                && actionScore <= 0
                && scoreDelta <= 0
                && "none".equals(failureClass)
                && "monitor_local_llm_route".equals(nextAction)) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", safePhase(phase));
        data.put("phaseStage", safePhase(phase));
        data.put("stage", "local_llm_operator_action");
        data.put("failureClass", failureClass);
        data.put("triggerReason", triggerReason);
        data.put("nextAction", nextAction);
        data.put("operatorActionNext", nextAction);
        data.put("localLlmNextAction", nextAction);
        data.put("localLlmTriggerReason", triggerReason);
        data.put("actionScore", actionScore);
        data.put("scoreDelta", scoreDelta);
        data.put("negativeSignalCount", negativeSignalCount);
        data.put("promotedFromTraceStore", true);

        debugEventStore.emit(
                DebugProbeType.MODEL_GUARD,
                triggered || !"none".equals(failureClass) ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                "chat.localLlm.operatorAction." + safePhase(phase),
                "[AWX][llm] Local LLM operator action observed",
                where,
                data,
                null);
    }

    private void promoteStageBoundaryBreadcrumbs(String phase, Map<String, Object> traceMeta, String where) {
        int emitted = 0;
        for (Map.Entry<String, Object> entry : traceMeta.entrySet()) {
            if (emitted >= MAX_SUPPRESSED_EVENTS) {
                return;
            }
            String key = entry.getKey();
            if (key == null || !key.startsWith("mla.breadcrumb.step.") || !(entry.getValue() instanceof Map<?, ?> row)) {
                continue;
            }
            String keyStage = safeLabel(key.substring("mla.breadcrumb.step.".length()));
            String rowStage = safeLabel(row.get("stage"));
            if (!STAGE_BOUNDARY_STAGES.contains(keyStage)
                    || (rowStage != null && !keyStage.equals(rowStage))) {
                continue;
            }
            String boundaryStage = keyStage;
            String failureClass = safeLabel(row.get("failureClass"));
            if (failureClass == null || !STAGE_BOUNDARY_FAILURE_CLASSES.contains(failureClass)) {
                continue;
            }
            String reasonCode = firstNonBlank(safeLabel(row.get("reasonCode")), failureClass);
            if ("verification".equals(boundaryStage)
                    && !isCoherentVerificationFailSoft(row, failureClass, reasonCode)) {
                continue;
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("phase", safePhase(phase));
            data.put("phaseStage", safePhase(phase));
            data.put("stage", "stage_boundary");
            data.put("boundaryStage", boundaryStage);
            data.put("failureClass", failureClass);
            data.put("reasonCode", reasonCode);
            putIfPresent(data, "provider", safeLabel(row.get("provider")));
            putIfPresent(data, "queryHash12", safeHash12(row.get("queryHash12")));
            putIfPositive(data, "queryLength", row.get("queryLength"));
            putIfPositive(data, "returnedCount", row.get("returnedCount"));
            putIfPositive(data, "afterFilterCount", row.get("afterFilterCount"));
            putIfPositive(data, "contextCount", row.get("contextCount"));
            if ("verification".equals(boundaryStage)) {
                data.put("layer", "verification.judge");
                putVerificationFields(data, row);
            }
            data.put("promotedFromTraceStore", true);
            String promotionMarker = boundaryPromotionMarker(data);
            if (boundaryAlreadyPromoted(traceMeta, promotionMarker)) {
                continue;
            }

            debugEventStore.emit(
                    probeForBoundaryStage(boundaryStage),
                    DebugEventLevel.WARN,
                    "chat.stageBoundary." + safePhase(phase) + "." + SafeRedactor.hash12(boundaryStage + ":" + failureClass),
                    "[AWX][trace] Stage-boundary breadcrumb promoted from TraceStore",
                    where,
                    data,
                    null);
            markBoundaryPromoted(promotionMarker);
            emitted++;
        }
    }

    private static DebugProbeType probeForBoundaryStage(String boundaryStage) {
        return switch (safePhase(boundaryStage)) {
            case "request" -> DebugProbeType.CONTEXT_PROPAGATION;
            case "orchestration" -> DebugProbeType.ORCHESTRATION;
            case "search" -> DebugProbeType.WEB_SEARCH;
            case "prompt" -> DebugProbeType.PROMPT;
            case "llm" -> DebugProbeType.MODEL_GUARD;
            default -> DebugProbeType.GENERIC;
        };
    }

    private static String boundaryPromotionMarker(Map<String, Object> data) {
        Map<String, Object> projection = new LinkedHashMap<>();
        for (String key : List.of(
                "boundaryStage", "failureClass", "reasonCode", "provider", "queryHash12",
                "queryLength", "returnedCount", "afterFilterCount", "contextCount", "status",
                "judgeLane", "judgeFailSoftLaneCount", "judgeCallAttempted", "verificationOutcomeKnown", "redacted")) {
            if (data.containsKey(key)) {
                projection.put(key, data.get(key));
            }
        }
        String stage = safePhase(String.valueOf(projection.get("boundaryStage")));
        return "stageBoundary.promoted." + stage + "." + SafeRedactor.hash12(projection.toString());
    }

    private static void putVerificationFields(Map<String, Object> data, Map<?, ?> row) {
        if ("fail_soft".equals(safeLabel(row.get("status")))) {
            data.put("status", "fail_soft");
        }
        String judgeLane = safeLabel(row.get("judgeLane"));
        if (judgeLane != null && VERIFICATION_JUDGE_LANES.contains(judgeLane)) {
            data.put("judgeLane", judgeLane);
        }
        int judgeFailSoftLaneCount = intValue(row.get("judgeFailSoftLaneCount"), -1);
        if (judgeFailSoftLaneCount >= 1 && judgeFailSoftLaneCount <= 2) {
            data.put("judgeFailSoftLaneCount", judgeFailSoftLaneCount);
        }
        if (row.get("judgeCallAttempted") instanceof Boolean attempted) {
            data.put("judgeCallAttempted", attempted);
        }
        if (Boolean.FALSE.equals(row.get("verificationOutcomeKnown"))) {
            data.put("verificationOutcomeKnown", false);
        }
        if (Boolean.TRUE.equals(row.get("redacted"))) {
            data.put("redacted", true);
        }
    }

    private static boolean isCoherentVerificationFailSoft(
            Map<?, ?> row, String failureClass, String reasonCode) {
        if (!"fail_soft".equals(safeLabel(row.get("status")))
                || !Boolean.FALSE.equals(row.get("verificationOutcomeKnown"))
                || !Boolean.TRUE.equals(row.get("redacted"))) {
            return false;
        }
        String judgeLane = safeLabel(row.get("judgeLane"));
        int laneCount = intValue(row.get("judgeFailSoftLaneCount"), -1);
        if (judgeLane == null || !VERIFICATION_JUDGE_LANES.contains(judgeLane)
                || ("both".equals(judgeLane) ? laneCount != 2 : laneCount != 1)) {
            return false;
        }
        return switch (reasonCode) {
            case "judge_call_failed" -> "catch".equals(failureClass)
                    && Boolean.TRUE.equals(row.get("judgeCallAttempted"));
            case "judge_model_unavailable" -> "provider-disabled".equals(failureClass)
                    && Boolean.FALSE.equals(row.get("judgeCallAttempted"));
            case "judge_fail_soft" -> "fallback".equals(failureClass)
                    && !row.containsKey("judgeCallAttempted");
            default -> false;
        };
    }

    private static boolean boundaryAlreadyPromoted(Map<String, Object> traceMeta, String marker) {
        if (truthy(traceMeta.get(marker))) {
            return true;
        }
        try {
            return truthy(TraceStore.get(marker));
        } catch (RuntimeException ignored) {
            log.debug("[AWX][trace] stage-boundary promotion marker read suppressed");
            return false;
        }
    }

    private static void markBoundaryPromoted(String marker) {
        try {
            TraceStore.put(marker, true);
        } catch (RuntimeException ignored) {
            log.debug("[AWX][trace] stage-boundary promotion marker write suppressed");
        }
    }

    private void promoteSuppressedStages(String phase, Map<String, Object> traceMeta, String where) {
        int emitted = 0;
        Set<String> emittedKeys = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : traceMeta.entrySet()) {
            if (emitted >= MAX_SUPPRESSED_EVENTS) {
                return;
            }
            String key = entry.getKey();
            if (key == null || !key.endsWith(".suppressed.stage")) {
                continue;
            }
            String prefix = key.substring(0, key.length() - ".stage".length());
            String stage = safeLabel(entry.getValue());
            String errorType = firstNonBlank(safeLabel(traceMeta.get(prefix + ".errorType")), "unknown");
            String eventKey = prefix + ":" + (stage == null ? "unknown" : stage);
            emittedKeys.add(eventKey);
            emitSuppressedDebugEvent(phase, prefix, stage, errorType, where);
            emitted++;
        }

        for (Map.Entry<String, Object> entry : traceMeta.entrySet()) {
            if (emitted >= MAX_SUPPRESSED_EVENTS) {
                return;
            }
            String key = entry.getKey();
            if (key == null
                    || key.endsWith(".errorType")
                    || key.endsWith(".stage")
                    || FAULT_MASK_COUNTERS.containsKey(key)
                    || !truthy(entry.getValue())) {
                continue;
            }
            int marker = key.indexOf(".suppressed.");
            if (marker < 0) {
                continue;
            }
            String suppressedPrefix = key.substring(0, marker + ".suppressed".length());
            String stage = safeLabel(key.substring(marker + ".suppressed.".length()));
            if (stage == null) {
                continue;
            }
            String eventKey = suppressedPrefix + ":" + stage;
            if (emittedKeys.contains(eventKey)) {
                continue;
            }
            String errorType = firstNonBlank(
                    safeLabel(traceMeta.get(key + ".errorType")),
                    safeLabel(traceMeta.get(suppressedPrefix + ".errorType")),
                    "unknown");
            emitSuppressedDebugEvent(phase, suppressedPrefix, stage, errorType, where);
            emittedKeys.add(eventKey);
            emitted++;
        }
    }

    private void promoteFaultMaskCounters(String phase, Map<String, Object> traceMeta, String where) {
        for (Map.Entry<String, String> counter : FAULT_MASK_COUNTERS.entrySet()) {
            String key = counter.getKey();
            Object value = traceMeta.get(key);
            if (!truthy(value)) {
                continue;
            }
            int count = Math.max(1, intValue(value, 1));
            String errorType = faultMaskCounterErrorType(key, traceMeta);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("phase", safePhase(phase));
            data.put("stage", counter.getValue());
            data.put("traceSignal", key);
            data.put("failureClass", counter.getValue());
            data.put("exceptionType", errorType);
            data.put("count", count);
            data.put("promotedFromTraceStore", true);
            if ("reactor.onErrorDropped.count".equals(key)) {
                data.put("cancelCount", Math.max(0, intValue(traceMeta.get("reactor.onErrorDropped.cancel.count"), 0)));
                data.put("bodyReleasedCount",
                        Math.max(0, intValue(traceMeta.get("reactor.onErrorDropped.bodyReleased.count"), 0)));
            }

            debugEventStore.emit(
                    DebugProbeType.FAULT_MASK,
                    DebugEventLevel.WARN,
                    "chat.traceCounter." + SafeRedactor.hash12(key + ":" + count + ":" + errorType),
                    "[AWX][trace] Fail-soft counter promoted from TraceStore",
                    where,
                    data,
                    null);
        }
    }

    private static String faultMaskCounterErrorType(String key, Map<String, Object> traceMeta) {
        String prefix = key.endsWith(".count") ? key.substring(0, key.length() - ".count".length()) : key;
        return firstNonBlank(
                safeLabel(traceMeta.get(key + ".errorType")),
                safeLabel(traceMeta.get(prefix + ".errorType")),
                safeLabel(traceMeta.get(prefix + ".last")),
                safeLabel(traceMeta.get("reactor.onErrorDropped.last")),
                "unknown");
    }

    private void emitSuppressedDebugEvent(String phase, String traceKeyPrefix, String stage, String errorType,
            String where) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", safePhase(phase));
        data.put("stage", "trace_suppressed");
        data.put("traceSignal", traceKeyPrefix + ".stage");
        data.put("traceKeyPrefix", SafeRedactor.traceLabelOrFallback(traceKeyPrefix, "unknown"));
        data.put("suppressedStage", stage == null ? "unknown" : stage);
        data.put("exceptionType", errorType);
        data.put("failureClass", errorType);
        data.put("promotedFromTraceStore", true);

        debugEventStore.emit(
                DebugProbeType.FAULT_MASK,
                DebugEventLevel.WARN,
                "chat.traceSuppressed." + SafeRedactor.hash12(traceKeyPrefix + ":" + stage + ":" + errorType),
                "[AWX][trace] Fail-soft suppression promoted from TraceStore",
                where,
                data,
                null);
    }

    private static List<String> externalLanes(Map<String, Object> traceMeta) {
        Set<String> lanes = new LinkedHashSet<>();
        Object requested = traceMeta.get("externalEvidence.requestedLanes");
        if (requested instanceof Collection<?> collection) {
            for (Object item : collection) {
                String lane = normalizeLane(item);
                if (lane != null) {
                    lanes.add(lane);
                }
            }
        } else {
            String lane = normalizeLane(requested);
            if (lane != null) {
                lanes.add(lane);
            }
        }
        for (String key : traceMeta.keySet()) {
            if (key == null) {
                continue;
            }
            for (String lane : EXTERNAL_LANES) {
                if (key.startsWith(lane + ".")) {
                    lanes.add(lane);
                }
            }
        }
        return List.copyOf(lanes);
    }

    private static String normalizeLane(Object value) {
        String lane = safeLabel(value);
        if (lane == null) {
            return null;
        }
        return EXTERNAL_LANES.contains(lane) ? lane : null;
    }

    private static List<String> queryRewriteSubModelIds(Object value) {
        Set<String> ids = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addQueryRewriteSubModelId(ids, item);
            }
        } else {
            addQueryRewriteSubModelId(ids, value);
        }
        return List.copyOf(ids);
    }

    private static void addQueryRewriteSubModelId(Set<String> ids, Object value) {
        String id = safeLabel(value);
        if (id != null && QUERY_REWRITE_SUB_MODEL_IDS.contains(id)) {
            ids.add(id);
        }
    }

    private static List<String> queryRewriteBranchAxes(Object value) {
        Set<String> axes = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addQueryRewriteBranchAxis(axes, item);
            }
        } else {
            addQueryRewriteBranchAxis(axes, value);
        }
        return List.copyOf(axes);
    }

    private static void addQueryRewriteBranchAxis(Set<String> axes, Object value) {
        String axis = safeLabel(value);
        if (axis != null && QUERY_REWRITE_BRANCH_AXES.contains(axis)) {
            axes.add(axis);
        }
    }

    private static List<String> queryRewriteBranchTitleHashes(Object value) {
        Set<String> hashes = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addQueryRewriteBranchTitleHash(hashes, item);
            }
        } else {
            addQueryRewriteBranchTitleHash(hashes, value);
        }
        return List.copyOf(hashes);
    }

    private static void addQueryRewriteBranchTitleHash(Set<String> hashes, Object value) {
        String hash = safeLabel(value);
        if (hash != null && hash.matches("[a-f0-9]{12}")) {
            hashes.add(hash);
        }
    }

    private static List<Integer> queryRewriteIntList(Object value) {
        List<Integer> values = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                values.add(Math.max(0, intValue(item, 0)));
            }
        } else if (value != null) {
            values.add(Math.max(0, intValue(value, 0)));
        }
        return List.copyOf(values);
    }

    private static List<Map<String, Object>> queryRewriteBranchTitleMetadata(
            List<String> axes,
            List<String> hashes,
            List<Integer> lengths,
            List<Integer> termCounts) {
        int limit = Math.min(Math.min(axes.size(), hashes.size()), Math.min(lengths.size(), termCounts.size()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String axis = axes.get(i);
            String hash = hashes.get(i);
            int length = lengths.get(i);
            int termCount = termCounts.get(i);
            if (!QUERY_REWRITE_BRANCH_AXES.contains(axis)
                    || hash == null
                    || !hash.matches("[a-f0-9]{12}")
                    || length <= 0
                    || termCount <= 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("axis", axis);
            row.put("hash12", hash);
            row.put("len", length);
            row.put("termCount", termCount);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static List<Map<String, Object>> queryRewriteBranchQueryMetadata(
            List<String> axes,
            List<String> subModelIds,
            List<String> hashes,
            List<Integer> lengths,
            List<Integer> termCounts) {
        int limit = Math.min(
                Math.min(axes.size(), subModelIds.size()),
                Math.min(hashes.size(), Math.min(lengths.size(), termCounts.size())));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String axis = axes.get(i);
            String subModelId = subModelIds.get(i);
            String hash = hashes.get(i);
            int length = lengths.get(i);
            int termCount = termCounts.get(i);
            if (!QUERY_REWRITE_BRANCH_AXES.contains(axis)
                    || !QUERY_REWRITE_SUB_MODEL_IDS.contains(subModelId)
                    || hash == null
                    || !hash.matches("[a-f0-9]{12}")
                    || length <= 0
                    || termCount <= 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("axis", axis);
            row.put("model", subModelId);
            row.put("hash12", hash);
            row.put("len", length);
            row.put("termCount", termCount);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private static void putIfPositive(Map<String, Object> data, String key, Object value) {
        int count = intValue(value, -1);
        if (count >= 0) {
            data.put(key, count);
        }
    }

    private static String safeHash12(Object value) {
        String hash = safeLabel(value);
        return hash != null && hash.matches("[a-f0-9]{12}") ? hash : null;
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

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException ignored) {
            traceSuppressed("intValue", ignored);
            return fallback;
        }
    }

    private static String safePhase(String phase) {
        return firstNonBlank(SafeRedactor.traceLabelOrFallback(phase, null), "unknown");
    }

    private static String safeLabel(Object value) {
        if (value == null) {
            return null;
        }
        return SafeRedactor.traceLabelOrFallback(String.valueOf(value), null);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void traceSuppressed(String stage, RuntimeException error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        try {
            TraceStore.put("debugEvent.promote.suppressed.stage", safeStage);
            TraceStore.put("debugEvent.promote.suppressed.errorType", errorType);
        } catch (RuntimeException ignored) {
            log.warn("[AWX][debug-event] traceStoreWriteSuppressed stage={} errorType={}", safeStage, errorType);
            // Last-resort fail-soft path; do not break chat requests for debug metadata.
        }
    }
}
