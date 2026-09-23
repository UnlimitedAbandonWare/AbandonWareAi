package com.example.lms.service.rag.langgraph;

import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

final class RagGraphControlPolicy {

    private RagGraphControlPolicy() {
    }

    enum SafetyMode {
        STRIKE,
        STRICT,
        RECOVERY,
        RELAXED,
        NORMAL
    }

    enum RetrievalPosture {
        LOCKDOWN,
        EVIDENCE_SHARPEN,
        REPAIR,
        BROADEN,
        NORMAL
    }

    enum FailureAction {
        FINALIZE,
        STRICT_VERIFY,
        REPAIR,
        FAIL_SOFT_FINALIZE
    }

    record Decision(
            SafetyMode safetyMode,
            RetrievalPosture retrievalPosture,
            FailureAction failureAction,
            String reasonCode,
            List<Map<String, Object>> transitionTrace,
            String policyRuleId,
            double controlScore,
            double transitionScore,
            boolean promotionEligible,
            List<String> promotionBlockers) implements Serializable {

        Decision(SafetyMode safetyMode,
                 RetrievalPosture retrievalPosture,
                 FailureAction failureAction,
                 String reasonCode,
                 List<Map<String, Object>> transitionTrace) {
            this(
                    safetyMode,
                    retrievalPosture,
                    failureAction,
                    reasonCode,
                    transitionTrace,
                    "rule.legacy",
                    defaultControlScore(safetyMode),
                    defaultControlScore(safetyMode),
                    defaultControlScore(safetyMode) >= 0.70d && failureAction == FailureAction.FINALIZE,
                    List.of());
        }

        Decision {
            safetyMode = safetyMode == null ? SafetyMode.NORMAL : safetyMode;
            retrievalPosture = retrievalPosture == null ? RetrievalPosture.NORMAL : retrievalPosture;
            failureAction = failureAction == null ? FailureAction.FINALIZE : failureAction;
            reasonCode = cleanReason(reasonCode, "normal");
            transitionTrace = immutableTrace(transitionTrace);
            policyRuleId = cleanReason(policyRuleId, "rule.unknown");
            controlScore = clamp01(controlScore);
            transitionScore = clamp01(transitionScore);
            promotionBlockers = immutableStrings(promotionBlockers);
            promotionEligible = promotionEligible && transitionScore >= 0.70d && promotionBlockers.isEmpty();
        }

        Decision withAction(FailureAction nextAction, String reason, String node) {
            FailureAction action = nextAction == null ? FailureAction.FINALIZE : nextAction;
            String safeReason = cleanReason(reason, reasonCode);
            double nextScore = RagGraphControlPolicy.transitionScore(action, safeReason, transitionScore);
            List<String> blockers = mergeBlockers(promotionBlockers, blockersFor(action, safeReason, nextScore));
            return new Decision(
                    safetyMode,
                    retrievalPosture,
                    action,
                    safeReason,
                    appendTrace(transitionTrace, node, action, safeReason, policyRuleId,
                            nextScore - transitionScore, nextScore),
                    policyRuleId,
                    controlScore,
                    nextScore,
                    action == FailureAction.FINALIZE,
                    blockers);
        }
    }

    private record PolicyRule(
            String id,
            int priority,
            double score,
            SafetyMode mode,
            RetrievalPosture posture,
            String reason,
            BiPredicate<QueryRequest, Map<String, Object>> predicate) {
    }

    private static final List<PolicyRule> DECISION_RULES = List.of(
            new PolicyRule("strike.signal", 10, 0.15d, SafetyMode.STRIKE, RetrievalPosture.LOCKDOWN,
                    "strike_signal", RagGraphControlPolicy::hasStrikeSignal),
            new PolicyRule("strict.signal", 20, 0.45d, SafetyMode.STRICT, RetrievalPosture.EVIDENCE_SHARPEN,
                    "strict_signal", RagGraphControlPolicy::hasStrictSignal),
            new PolicyRule("recovery.signal", 30, 0.50d, SafetyMode.RECOVERY, RetrievalPosture.REPAIR,
                    "recovery_signal", (request, debug) -> hasRecoverySignal(debug)),
            new PolicyRule("relaxed.signal", 40, 0.72d, SafetyMode.RELAXED, RetrievalPosture.BROADEN,
                    "relaxed_signal", RagGraphControlPolicy::hasRelaxedSignal),
            new PolicyRule("normal.default", 100, 0.86d, SafetyMode.NORMAL, RetrievalPosture.NORMAL,
                    "normal", (request, debug) -> true)
    );

    static Decision decide(QueryRequest request, Map<String, Object> debug) {
        PolicyRule matched = DECISION_RULES.get(DECISION_RULES.size() - 1);
        for (PolicyRule rule : DECISION_RULES) {
            if (rule.predicate().test(request, debug == null ? Map.of() : debug)) {
                matched = rule;
                break;
            }
        }

        double score = clamp01(matched.score());
        return new Decision(
                matched.mode(),
                matched.posture(),
                FailureAction.FINALIZE,
                matched.reason(),
                List.of(traceRow("decide_control", FailureAction.FINALIZE, matched.reason(),
                        matched.id(), 0.0d, score)),
                matched.id(),
                score,
                score,
                true,
                blockersFor(FailureAction.FINALIZE, matched.reason(), score));
    }

    static QueryRequest applyPolicy(Decision decision, QueryRequest request) {
        QueryRequest safe = request == null ? new QueryRequest() : request;
        Decision d = decision == null ? decide(safe, Map.of()) : decision;
        switch (d.safetyMode()) {
            case STRIKE -> {
                safe.enableSelfAsk = false;
                safe.aggressive = false;
                safe.deepResearch = false;
                safe.whitelistOnly = true;
                safe.enableBiEncoder = true;
                safe.enableOnnx = true;
                safe.topK = clampTopK(safe.topK, 4, 8);
            }
            case STRICT -> {
                safe.enableSelfAsk = false;
                safe.aggressive = false;
                safe.deepResearch = false;
                safe.whitelistOnly = true;
                safe.enableBiEncoder = true;
                safe.enableOnnx = true;
                safe.topK = Math.max(4, safe.topK);
            }
            case RECOVERY -> {
                safe.enableBiEncoder = true;
                safe.enableOnnx = true;
                if (safe.vectorTopK == null) {
                    safe.vectorTopK = Math.max(8, safe.topK);
                }
                if (safe.kgTopK == null) {
                    safe.kgTopK = Math.max(3, safe.topK / 3);
                }
            }
            case RELAXED -> {
                safe.enableSelfAsk = true;
                safe.topK = Math.max(10, safe.topK);
                if (safe.webTopK == null) {
                    safe.webTopK = Math.max(10, safe.topK);
                }
                if (safe.vectorTopK == null) {
                    safe.vectorTopK = Math.max(6, safe.topK / 2);
                }
                if (safe.kgTopK == null) {
                    safe.kgTopK = Math.max(3, safe.topK / 3);
                }
            }
            case NORMAL -> {
                // Preserve request defaults.
            }
        }
        return safe;
    }

    static Decision afterQuality(Decision previous,
                                 QueryRequest request,
                                 QueryResponse response,
                                 String failureReason,
                                 String qualityReason,
                                 int resultCount) {
        Decision base = previous == null ? decide(request, response == null ? Map.of() : response.debug) : previous;
        String reason = firstNonBlank(qualityReason, failureReason, base.reasonCode());

        if (isFailSoftOnly(reason, response)) {
            return base.withAction(FailureAction.FAIL_SOFT_FINALIZE, reason, "quality_gate");
        }
        if ((base.safetyMode() == SafetyMode.STRIKE || base.safetyMode() == SafetyMode.STRICT)
                && !strictEvidencePass(response, resultCount)) {
            return base.withAction(FailureAction.STRICT_VERIFY, firstNonBlank(reason, "strict_verify_required"),
                    "quality_gate");
        }
        if (isRepairable(reason, resultCount)) {
            return base.withAction(FailureAction.REPAIR, reason, "quality_gate");
        }
        return base.withAction(FailureAction.FINALIZE, firstNonBlank(reason, "quality_pass"), "quality_gate");
    }

    static Decision afterStrictVerify(Decision previous, QueryResponse response) {
        Decision base = previous == null ? decide(null, response == null ? Map.of() : response.debug) : previous;
        int resultCount = response == null || response.results == null ? 0 : response.results.size();
        if (strictEvidencePass(response, resultCount)) {
            return base.withAction(FailureAction.FINALIZE, "strict_verify_pass", "strict_verify");
        }
        return base.withAction(FailureAction.FAIL_SOFT_FINALIZE, "strict_verify_failed", "strict_verify");
    }

    static String route(Decision decision) {
        FailureAction action = decision == null ? FailureAction.FINALIZE : decision.failureAction();
        return switch (action) {
            case STRICT_VERIFY -> "strict_verify";
            case REPAIR -> "repair";
            case FAIL_SOFT_FINALIZE -> "fail_soft_finalize";
            case FINALIZE -> "finalize";
        };
    }

    static void writeDebug(Map<String, Object> debug, Decision decision) {
        if (debug == null || decision == null) {
            return;
        }
        debug.put("langgraph.control.mode", decision.safetyMode().name());
        debug.put("langgraph.control.retrievalPosture", decision.retrievalPosture().name());
        debug.put("langgraph.control.failureAction", decision.failureAction().name());
        debug.put("langgraph.control.reasonCode", decision.reasonCode());
        debug.put("langgraph.control.policyRuleId", decision.policyRuleId());
        debug.put("langgraph.control.score", decision.controlScore());
        debug.put("langgraph.control.transitionScore", decision.transitionScore());
        debug.put("langgraph.control.promotionEligible", decision.promotionEligible());
        debug.put("langgraph.control.promotionBlockers", decision.promotionBlockers());
        debug.put("langgraph.transitionTrace", decision.transitionTrace());
    }

    static Map<String, Object> stateDebug(Decision decision) {
        Map<String, Object> out = new LinkedHashMap<>();
        writeDebug(out, decision);
        return out;
    }

    private static boolean hasStrikeSignal(QueryRequest request, Map<String, Object> debug) {
        String haystack = signalText(request, debug);
        return haystack.contains("strike") || haystack.contains("bypass") || bool(debug, "strikeMode")
                || bool(debug, "nightmareMode");
    }

    private static boolean hasStrictSignal(QueryRequest request, Map<String, Object> debug) {
        String haystack = signalText(request, debug);
        return haystack.contains("strict") || bool(debug, "strictMode")
                || (request != null && request.whitelistOnly);
    }

    private static boolean hasRecoverySignal(Map<String, Object> debug) {
        String action = normalized(firstNonBlank(
                value(debug, "rag.control.last.action"),
                value(debug, "langgraph.control.failureAction"),
                value(debug, "retrieval.recovery")));
        return action.contains("recovery") || action.contains("repair") || bool(debug, "rag.control.recovery.requested");
    }

    private static boolean hasRelaxedSignal(QueryRequest request, Map<String, Object> debug) {
        String haystack = signalText(request, debug);
        return haystack.contains("relaxed")
                || haystack.contains("brave")
                || haystack.contains("free")
                || haystack.contains("wild")
                || (request != null && (request.aggressive || request.deepResearch));
    }

    private static String signalText(QueryRequest request, Map<String, Object> debug) {
        StringBuilder sb = new StringBuilder();
        if (request != null) {
            append(sb, request.jamminiMode);
            append(sb, request.memoryProfile);
            append(sb, request.planId);
        }
        append(sb, value(debug, "langgraph.control.mode"));
        append(sb, value(debug, "rag.control.last.action"));
        return normalized(sb.toString());
    }

    private static boolean strictEvidencePass(QueryResponse response, int resultCount) {
        if (response == null || resultCount < 2) {
            return false;
        }
        Set<String> sources = new LinkedHashSet<>();
        if (response.results != null) {
            for (Doc doc : response.results) {
                if (doc != null && doc.source != null && !doc.source.isBlank()) {
                    sources.add(doc.source.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        int debugDistinct = intValue(response.debug == null ? null : response.debug.get("rag.eval.distinctSourceCount"));
        int distinctSources = Math.max(sources.size(), debugDistinct);
        return distinctSources >= 2 || resultCount >= 3;
    }

    private static boolean isRepairable(String reason, int resultCount) {
        String r = normalized(reason);
        return resultCount == 0
                || r.equals("empty_results")
                || r.equals("zero_result")
                || r.equals("zero_results")
                || r.equals("after_filter_starvation")
                || r.equals("after-filter-starvation")
                || r.equals("retrieval_starvation")
                || r.equals("weak_evidence")
                || r.equals("insufficient_citations")
                || r.equals("kg_starvation")
                || r.equals("kg_final_drop")
                || r.equals("kg_neo4j_degraded")
                || r.equals("source_collapse")
                || r.equals("stage_drop_high");
    }

    private static boolean isFailSoftOnly(String reason, QueryResponse response) {
        String providerSignal = providerSignal(response);
        if (!providerSignal.isBlank()) {
            return true;
        }
        String r = normalized(reason);
        return r.equals("provider_disabled")
                || r.equals("provider-disabled")
                || r.equals("missing_key")
                || r.equals("missing-key")
                || r.equals("missing_key_or_unauthorized")
                || r.equals("missing-key-or-unauthorized")
                || r.equals("missing_dependency")
                || r.equals("missing-dependency")
                || r.equals("rate_limit")
                || r.equals("rate-limit")
                || r.equals("timeout")
                || r.startsWith("retrieve_error");
    }

    private static String providerSignal(QueryResponse response) {
        if (response == null || response.debug == null) {
            return "";
        }
        Object raw = response.debug.get("rag.eval.providerDisabledSignals");
        if (raw == null) {
            return "";
        }
        return String.valueOf(raw).isBlank() ? "" : "provider_disabled";
    }

    private static List<Map<String, Object>> appendTrace(List<Map<String, Object>> trace,
                                                          String node,
                                                          FailureAction action,
                                                          String reason) {
        return appendTrace(trace, node, action, reason, "rule.unknown", 0.0d, 1.0d);
    }

    private static List<Map<String, Object>> appendTrace(List<Map<String, Object>> trace,
                                                          String node,
                                                          FailureAction action,
                                                          String reason,
                                                          String ruleId,
                                                          double scoreDelta,
                                                          double scoreAfter) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (trace != null) {
            rows.addAll(trace);
        }
        rows.add(traceRow(node, action, reason, ruleId, scoreDelta, scoreAfter));
        return immutableTrace(rows);
    }

    private static Map<String, Object> traceRow(String node, FailureAction action, String reason) {
        return traceRow(node, action, reason, "rule.unknown", 0.0d, 1.0d);
    }

    private static Map<String, Object> traceRow(String node,
                                                FailureAction action,
                                                String reason,
                                                String ruleId,
                                                double scoreDelta,
                                                double scoreAfter) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("node", cleanReason(node, "unknown"));
        row.put("action", (action == null ? FailureAction.FINALIZE : action).name());
        row.put("reasonCode", cleanReason(reason, "normal"));
        row.put("ruleId", cleanReason(ruleId, "rule.unknown"));
        row.put("scoreDelta", round3Signed(scoreDelta));
        row.put("scoreAfter", round3(scoreAfter));
        return Map.copyOf(row);
    }

    private static List<Map<String, Object>> immutableTrace(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry != null && entry.getKey() != null && entry.getValue() != null) {
                    safe.put(entry.getKey(), cleanTraceValue(entry.getValue(), entry.getKey()));
                }
            }
            if (!safe.isEmpty()) {
                out.add(Map.copyOf(safe));
            }
        }
        return List.copyOf(out);
    }

    private static int clampTopK(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String cleanReason(String value, String fallback) {
        String out = value == null ? "" : value.trim();
        if (out.isBlank()) {
            out = fallback == null || fallback.isBlank() ? "normal" : fallback.trim();
        }
        return SafeRedactor.traceLabelOrFallback(out, "normal");
    }

    private static Object cleanTraceValue(Object value, String fallback) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return SafeRedactor.traceLabelOrFallback(value, fallback == null || fallback.isBlank() ? "field" : fallback);
    }

    private static double defaultControlScore(SafetyMode mode) {
        if (mode == null) {
            return 0.86d;
        }
        return switch (mode) {
            case STRIKE -> 0.15d;
            case STRICT -> 0.45d;
            case RECOVERY -> 0.50d;
            case RELAXED -> 0.72d;
            case NORMAL -> 0.86d;
        };
    }

    private static double transitionScore(FailureAction action, String reason, double currentScore) {
        double score = currentScore;
        if (action == FailureAction.STRICT_VERIFY) {
            score -= 0.18d;
        } else if (action == FailureAction.REPAIR) {
            score -= 0.24d;
        } else if (action == FailureAction.FAIL_SOFT_FINALIZE) {
            score -= 0.45d;
        } else if (action == FailureAction.FINALIZE && isPositiveReason(reason)) {
            score += 0.04d;
        }
        String normalized = normalized(reason);
        if (normalized.startsWith("graph_invoke") || normalized.contains("invoke_fallback")) {
            score -= 0.55d;
        }
        if (normalized.contains("empty_results") || normalized.contains("starvation")) {
            score -= 0.10d;
        }
        return clamp01(score);
    }

    private static boolean isPositiveReason(String reason) {
        String r = normalized(reason);
        return r.equals("quality_pass")
                || r.equals("strict_verify_pass")
                || r.equals("repair_ok")
                || r.equals("normal");
    }

    private static List<String> blockersFor(FailureAction action, String reason, double score) {
        List<String> blockers = new ArrayList<>();
        String r = normalized(reason);
        if (r.startsWith("graph_invoke") || r.contains("invoke_fallback")) {
            blockers.add("invoke_fallback");
        }
        if (action == FailureAction.STRICT_VERIFY) {
            blockers.add("strict_verify_pending");
        } else if (action == FailureAction.REPAIR) {
            blockers.add("repair_pending");
        } else if (action == FailureAction.FAIL_SOFT_FINALIZE) {
            blockers.add("fail_soft_finalize");
        }
        if (score < 0.70d) {
            blockers.add("low_transition_score");
        }
        return immutableStrings(blockers);
    }

    private static List<String> mergeBlockers(List<String> current, List<String> next) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (current != null) {
            out.addAll(current);
        }
        if (next != null) {
            out.addAll(next);
        }
        return List.copyOf(out);
    }

    private static List<String> immutableStrings(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : raw) {
            String safe = cleanReason(value, "");
            if (!safe.isBlank()) {
                out.add(safe);
            }
        }
        return List.copyOf(out);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double round3(double value) {
        return Math.round(clamp01(value) * 1000.0d) / 1000.0d;
    }

    private static double round3Signed(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.round(Math.max(-1.0d, Math.min(1.0d, value)) * 1000.0d) / 1000.0d;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String value(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return false;
        }
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        String s = normalized(String.valueOf(value));
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    private static int intValue(Object raw) {
        if (raw instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) {
                return n.intValue();
            }
            TraceStore.put("langgraph.control.suppressed.intValue", true);
            TraceStore.put("langgraph.control.suppressed.intValue.errorType", "invalid_number");
            return 0;
        }
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ignore) {
            TraceStore.put("langgraph.control.suppressed.intValue", true); TraceStore.put("langgraph.control.suppressed.intValue.errorType", "invalid_number"); return 0;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static void append(StringBuilder sb, String value) {
        if (sb == null || value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(value);
    }
}
