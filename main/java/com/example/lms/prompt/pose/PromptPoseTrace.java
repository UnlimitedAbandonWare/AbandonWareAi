package com.example.lms.prompt.pose;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PromptPoseTrace {

    private static final Logger LOG = LoggerFactory.getLogger(PromptPoseTrace.class);

    public static final String ENABLED = "promptPose.enabled";
    public static final String ROUTE = "promptPose.route";
    public static final String ARM = "promptPose.arm";
    public static final String QUERY_HASH12 = "promptPose.queryHash12";
    public static final String SELFASK_COUNT = "promptPose.selfAskCount";
    public static final String QUERY_BURST_CAP = "promptPose.queryBurstCap";
    public static final String LANE_WEIGHTS = "promptPose.laneWeights";
    public static final String SKIP_REASON = "promptPose.skipReason";
    public static final String FAILURE_CLASS = "promptPose.failureClass";
    public static final String RAW_INCLUDED = "promptPose.rawIncluded";
    public static final String SELFASK_TEMPERATURE = "promptPose.selfAskTemperature";
    public static final String QUERY_BURST_SEED_HASHES = "promptPose.queryBurstSeedHashes";
    public static final String REWARD_ARM = "promptPose.reward.arm";
    public static final String REWARD_TILE_KEY = "promptPose.reward.tileKey";
    public static final String REWARD_VALUE = "promptPose.reward.value";
    public static final String APPLICATION_ENABLED = "promptPose.application.enabled";
    public static final String APPLICATION_APPLIED = "promptPose.application.applied";
    public static final String APPLICATION_INTENT_SLOT = "promptPose.application.intentSlot";
    public static final String APPLICATION_EVIDENCE_SLOT = "promptPose.application.evidenceSlot";
    public static final String APPLICATION_FAILURE_SLOT = "promptPose.application.failureSlot";
    public static final String APPLICATION_FEEDBACK_SLOT = "promptPose.application.feedbackSlot";
    public static final String APPLICATION_FEEDBACK_TILE = "promptPose.application.feedbackTile";
    public static final String APPLICATION_DECISION_HASH12 = "promptPose.application.decisionHash12";
    public static final String APPLICATION_REASON = "promptPose.application.reason";
    public static final String APPLICATION_QUERY_BURST_MAX = "promptPose.application.queryBurstMax";
    public static final String APPLICATION_SELFASK_COUNT = "promptPose.application.selfAskCount";
    public static final String APPLICATION_ANSWER_TEMPERATURE = "promptPose.application.answerTemperature";
    public static final String APPLICATION_SELFASK_TEMPERATURE = "promptPose.application.selfAskTemperature";
    public static final String APPLICATION_MIN_CITATIONS = "promptPose.application.minCitations";
    public static final String APPLICATION_LANE_WEIGHTS = "promptPose.application.laneWeights";
    public static final String APPLICATION_CALL_RATIOS = "promptPose.application.callRatios";
    public static final String APPLICATION_TIMEBOX_RATIOS = "promptPose.application.timeboxRatios";
    public static final String APPLICATION_RISK_PENALTY_LAMBDA = "promptPose.application.riskPenaltyLambda";
    public static final String APPLICATION_MIN_LANE_COVERAGE = "promptPose.application.minLaneCoverage";
    public static final String APPLICATION_FEEDBACK_MEAN = "promptPose.application.feedbackMean";
    public static final String APPLICATION_FEEDBACK_COUNT = "promptPose.application.feedbackCount";
    public static final String APPLICATION_COMPRESSION_MODE = "promptPose.application.compressionMode";
    private static final List<String> PROMPT_POSE_KEYS = List.of(
            ENABLED,
            ROUTE,
            ARM,
            QUERY_HASH12,
            SELFASK_COUNT,
            QUERY_BURST_CAP,
            LANE_WEIGHTS,
            SKIP_REASON,
            FAILURE_CLASS,
            RAW_INCLUDED,
            SELFASK_TEMPERATURE,
            QUERY_BURST_SEED_HASHES,
            REWARD_ARM,
            REWARD_TILE_KEY,
            REWARD_VALUE);
    private static final List<String> APPLICATION_KEYS = List.of(
            APPLICATION_ENABLED,
            APPLICATION_APPLIED,
            APPLICATION_INTENT_SLOT,
            APPLICATION_EVIDENCE_SLOT,
            APPLICATION_FAILURE_SLOT,
            APPLICATION_FEEDBACK_SLOT,
            APPLICATION_FEEDBACK_TILE,
            APPLICATION_DECISION_HASH12,
            APPLICATION_REASON,
            APPLICATION_QUERY_BURST_MAX,
            APPLICATION_SELFASK_COUNT,
            APPLICATION_ANSWER_TEMPERATURE,
            APPLICATION_SELFASK_TEMPERATURE,
            APPLICATION_MIN_CITATIONS,
            APPLICATION_LANE_WEIGHTS,
            APPLICATION_CALL_RATIOS,
            APPLICATION_TIMEBOX_RATIOS,
            APPLICATION_RISK_PENALTY_LAMBDA,
            APPLICATION_MIN_LANE_COVERAGE,
            APPLICATION_FEEDBACK_MEAN,
            APPLICATION_FEEDBACK_COUNT,
            APPLICATION_COMPRESSION_MODE);

    private PromptPoseTrace() {
    }

    public static void writePlan(PromptPosePlan plan, PromptPoseInputSanitizer.SanitizedInput input) {
        if (plan == null) {
            writeDisabled("empty_plan", input);
            return;
        }
        clearPromptPoseKeys();
        put(ENABLED, plan.enabled());
        put(ROUTE, plan.routeModel());
        put(ARM, plan.arm().name());
        put(QUERY_HASH12, input == null ? null : input.queryHash12());
        put(SELFASK_COUNT, Math.max(0, plan.selfAskCount()));
        put(QUERY_BURST_CAP, Math.max(0, plan.queryBurstMax()));
        put(LANE_WEIGHTS, safeLaneWeights(plan.laneWeights()));
        put(SKIP_REASON, plan.reasonCode());
        put(FAILURE_CLASS, failureClass(plan.reasonCode()));
        put(RAW_INCLUDED, false);
        if (Double.isFinite(plan.selfAskTemperature()) && plan.selfAskTemperature() > 0.0d) {
            put(SELFASK_TEMPERATURE, round4(plan.selfAskTemperature()));
        }
        List<String> seedHashes = seedHashes(plan.queryBurstSeeds());
        if (!seedHashes.isEmpty()) {
            put(QUERY_BURST_SEED_HASHES, seedHashes);
        }
    }

    public static void writeDisabled(String reason, PromptPoseInputSanitizer.SanitizedInput input) {
        clearPromptPoseKeys();
        put(ENABLED, false);
        put(ARM, PromptPoseArm.NO_DRAFT.name());
        put(QUERY_HASH12, input == null ? null : input.queryHash12());
        put(SKIP_REASON, reason);
        put(FAILURE_CLASS, failureClass(reason));
        put(RAW_INCLUDED, false);
    }

    public static void writeApplicationDecision(
            PromptPoseApplicationDecision decision,
            PromptPoseInputSanitizer.SanitizedInput input) {
        clearApplicationKeys();
        if (decision == null) {
            put(APPLICATION_ENABLED, false);
            put(APPLICATION_APPLIED, false);
            put(APPLICATION_REASON, "empty_decision");
            put(RAW_INCLUDED, false);
            put(QUERY_HASH12, input == null ? null : input.queryHash12());
            return;
        }
        put(APPLICATION_ENABLED, decision.enabled());
        put(APPLICATION_APPLIED, decision.enabled());
        put(APPLICATION_INTENT_SLOT, decision.intentSlot());
        put(APPLICATION_EVIDENCE_SLOT, decision.evidenceSlot());
        put(APPLICATION_FAILURE_SLOT, decision.failureSlot());
        put(APPLICATION_FEEDBACK_SLOT, decision.feedbackSlot());
        put(APPLICATION_FEEDBACK_TILE, decision.feedbackTile());
        put(APPLICATION_DECISION_HASH12, decision.decisionHash12());
        put(APPLICATION_REASON, decision.reasonCode());
        put(APPLICATION_QUERY_BURST_MAX, decision.queryBurstMax());
        put(APPLICATION_SELFASK_COUNT, decision.selfAskCount());
        put(APPLICATION_ANSWER_TEMPERATURE, round4(decision.answerTemperature()));
        put(APPLICATION_SELFASK_TEMPERATURE, round4(decision.selfAskTemperature()));
        put(APPLICATION_MIN_CITATIONS, decision.minCitations());
        put(APPLICATION_LANE_WEIGHTS, safeLaneWeights(decision.laneWeights()));
        put(APPLICATION_CALL_RATIOS, safeLaneWeights(decision.callBudgetRatios()));
        put(APPLICATION_TIMEBOX_RATIOS, safeLaneWeights(decision.timeboxRatios()));
        put(APPLICATION_RISK_PENALTY_LAMBDA, round4(decision.riskPenaltyLambda()));
        put(APPLICATION_MIN_LANE_COVERAGE, decision.minLaneCoverage());
        put(APPLICATION_FEEDBACK_MEAN, round4(decision.feedbackMean()));
        put(APPLICATION_FEEDBACK_COUNT, decision.feedbackCount());
        put(APPLICATION_COMPRESSION_MODE, decision.compressionMode());
        put(QUERY_HASH12, input == null ? null : input.queryHash12());
        put(RAW_INCLUDED, false);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Double> laneWeights() {
        if (!hasActivePlan()) {
            return Map.of();
        }
        Object raw = TraceStore.get(LANE_WEIGHTS);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e == null || e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String lane = String.valueOf(e.getKey()).trim().toUpperCase(Locale.ROOT);
            if (!List.of("BQ", "ER", "RC").contains(lane)) {
                continue;
            }
            Double value = asDouble(e.getValue());
            if (value != null) {
                out.put(lane, Math.max(0.25d, Math.min(2.50d, value)));
            }
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    public static Double selfAskTemperature() {
        if (!hasActivePlan()) {
            return null;
        }
        return asDouble(TraceStore.get(SELFASK_TEMPERATURE));
    }

    public static Integer selfAskCount() {
        if (!hasActivePlan()) {
            return null;
        }
        Integer count = positiveInt(TraceStore.get(SELFASK_COUNT));
        return count == null ? null : Math.max(1, Math.min(3, count));
    }

    public static Integer queryBurstCap() {
        if (!hasActivePlan()) {
            return null;
        }
        return positiveInt(TraceStore.get(QUERY_BURST_CAP));
    }

    public static String arm() {
        String arm = TraceStore.getString(ARM);
        return arm == null || arm.isBlank() ? null : SafeRedactor.traceLabelOrFallback(arm, "unknown");
    }

    @SuppressWarnings("unchecked")
    public static List<String> queryBurstSeedHashes() {
        if (!hasActivePlan()) {
            return List.of();
        }
        Object raw = TraceStore.get(QUERY_BURST_SEED_HASHES);
        if (raw instanceof List<?> list) {
            ArrayList<String> out = new ArrayList<>();
            for (Object item : list) {
                String s = item == null ? "" : String.valueOf(item).trim();
                if (!s.isBlank()) {
                    out.add(s);
                }
            }
            return List.copyOf(out);
        }
        return List.of();
    }

    private static boolean hasActivePlan() {
        Object enabled = TraceStore.get(ENABLED);
        boolean isEnabled = Boolean.TRUE.equals(enabled)
                || (enabled instanceof String s && "true".equalsIgnoreCase(s.trim()));
        if (!isEnabled) {
            return false;
        }
        String arm = arm();
        return arm != null && !PromptPoseArm.NO_DRAFT.name().equalsIgnoreCase(arm);
    }

    private static void clearPromptPoseKeys() {
        for (String key : PROMPT_POSE_KEYS) {
            put(key, null);
        }
    }

    private static void clearApplicationKeys() {
        for (String key : APPLICATION_KEYS) {
            put(key, null);
        }
    }

    private static Map<String, Double> safeLaneWeights(Map<String, Double> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            if (e == null || e.getKey() == null || e.getValue() == null) {
                continue;
            }
            double value = e.getValue();
            if (!Double.isFinite(value)) {
                traceSkipped("double_parse", new NumberFormatException("non-finite"));
                continue;
            }
            out.put(e.getKey(), round4(value));
        }
        return Map.copyOf(out);
    }

    private static List<String> seedHashes(List<String> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (String seed : seeds) {
            String hash = SafeRedactor.hash12(seed);
            if (hash != null && !hash.isBlank()) {
                out.add(hash);
            }
        }
        return List.copyOf(out);
    }

    private static String failureClass(String reason) {
        if (reason == null || reason.isBlank() || "ok".equalsIgnoreCase(reason)) {
            return "none";
        }
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("timeout")) {
            return "timeout";
        }
        if (r.contains("429") || r.contains("rate")) {
            return "rate-limit";
        }
        if (r.contains("disabled") || r.contains("missing") || r.contains("route")) {
            return "provider-disabled";
        }
        if (r.contains("parse") || r.contains("json")) {
            return "parse";
        }
        if (r.contains("private") || r.contains("redaction")) {
            return "privacy-block";
        }
        return "other";
    }

    private static void put(String key, Object value) {
        try {
            TraceStore.put(key, safeTraceValue(value));
        } catch (Throwable t) {
            traceSkipped("trace_put", t);
        }
    }

    private static Object safeTraceValue(Object value) {
        if (value instanceof String s) {
            return SafeRedactor.traceLabelOrFallback(s, "");
        }
        return value;
    }

    private static Double asDouble(Object raw) {
        if (raw instanceof Number n) {
            double value = n.doubleValue();
            if (!Double.isFinite(value)) {
                traceSkipped("double_parse", new NumberFormatException("non-finite"));
                return null;
            }
            return value;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                double value = Double.parseDouble(s.trim());
                if (!Double.isFinite(value)) {
                    throw new NumberFormatException("non-finite");
                }
                return value;
            } catch (NumberFormatException e) {
                traceSkipped("double_parse", e);
                return null;
            }
        }
        return null;
    }

    private static Integer positiveInt(Object raw) {
        if (raw instanceof Number n) {
            int v = n.intValue();
            return v > 0 ? v : null;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                int v = Integer.parseInt(s.trim());
                return v > 0 ? v : null;
            } catch (NumberFormatException e) {
                traceSkipped("int_parse", e);
                return null;
            }
        }
        return null;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static void traceSkipped(String stage, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        String safeErrorType = SafeRedactor.traceLabelOrFallback(errorType, "unknown");
        LOG.debug("[AWX][prompt][pose] trace skipped stage={} errorType={}",
                safeStage,
                safeErrorType);
    }
}
