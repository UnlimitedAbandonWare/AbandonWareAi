package com.example.lms.search.probe;

import ai.abandonware.nova.orch.failpattern.FailurePatternKind;
import ai.abandonware.nova.orch.failpattern.FailurePatternMatch;
import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Projects indirect probe signals into a compact causal readiness trigger.
 *
 * <p>The service reuses existing TraceStore, failure-pattern, and blackbox
 * signals. It only emits low-cardinality labels, counts, booleans, bounded
 * scores, and a goal hash.</p>
 */
@Service
public class CausalProbeTriggerService {

    private static final int MIN_SAMPLE_COUNT = 3;
    private static final int MIN_AGREEING_AXES = 2;
    private static final double MIN_CONFIDENCE = 0.65d;
    private static final String PREFIX = "causalProbe.";

    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;
    private final ObjectProvider<RagFailureBlackboxService> blackboxServiceProvider;
    private final ObjectProvider<FailurePatternOrchestrator> failurePatternProvider;

    public CausalProbeTriggerService(
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            ObjectProvider<RagFailureBlackboxService> blackboxServiceProvider,
            ObjectProvider<FailurePatternOrchestrator> failurePatternProvider) {
        this.debugEventStoreProvider = debugEventStoreProvider;
        this.blackboxServiceProvider = blackboxServiceProvider;
        this.failurePatternProvider = failurePatternProvider;
    }

    public Decision projectCurrentTrace(String goal, String where) {
        Map<String, Object> trace = snapshotTrace();
        RagFailureBlackboxService.Snapshot blackbox = currentBlackbox(where, trace);
        List<FailurePatternMatch> recent = recentFailurePatterns();
        return projectCurrentTrace(goal, where, blackbox, recent);
    }

    public Decision projectCurrentTrace(String goal, String where, ProbeConstraints constraints) {
        Map<String, Object> trace = snapshotTrace();
        RagFailureBlackboxService.Snapshot blackbox = currentBlackbox(where, trace);
        List<FailurePatternMatch> recent = recentFailurePatterns();
        return projectCurrentTrace(goal, where, blackbox, recent, constraints);
    }

    public Decision projectCurrentTrace(
            String goal,
            String where,
            RagFailureBlackboxService.Snapshot blackbox,
            List<FailurePatternMatch> recentFailures) {
        return projectCurrentTrace(goal, where, blackbox, recentFailures, null);
    }

    public Decision projectCurrentTrace(
            String goal,
            String where,
            RagFailureBlackboxService.Snapshot blackbox,
            List<FailurePatternMatch> recentFailures,
            ProbeConstraints constraints) {
        Map<String, Object> trace = snapshotTrace();
        Decision decision = evaluate(goal, trace, blackbox, recentFailures, constraints);
        writeTrace(goal, where, decision);
        emitDebug(goal, where, decision);
        return decision;
    }

    static Decision evaluate(
            String goal,
            Map<String, Object> trace,
            RagFailureBlackboxService.Snapshot blackbox,
            List<FailurePatternMatch> recentFailures) {
        return evaluate(goal, trace, blackbox, recentFailures, null);
    }

    static Decision evaluate(
            String goal,
            Map<String, Object> trace,
            RagFailureBlackboxService.Snapshot blackbox,
            List<FailurePatternMatch> recentFailures,
            ProbeConstraints constraints) {
        Map<String, Object> safeTrace = trace == null ? Map.of() : trace;
        RagFailureBlackboxService.Snapshot snapshot = blackbox == null
                ? RagFailureBlackboxService.analyze(safeTrace)
                : blackbox;
        Map<String, Set<String>> axes = new LinkedHashMap<>();
        addBlackboxAxis(axes, snapshot);
        addFailurePatternAxis(axes, safeTrace, recentFailures);
        addProviderAxis(axes, safeTrace);
        addNeedleAxis(axes, safeTrace);
        addRetrievalAxis(axes, safeTrace);

        String dominant = chooseDominantFailure(axes, snapshot);
        int agreeingAxes = axes.getOrDefault(dominant, Set.of()).size();
        long sampleCount = sampleCount(safeTrace, axes);
        double confidence = confidence(snapshot, agreeingAxes);
        String hotspot = dominant.equals(safeLabel(snapshot.dominantFailure(), "none"))
                ? safeLabel(snapshot.hotspot(), "none")
                : hotspotFor(dominant);

        String triggerReason = triggerReason(sampleCount, agreeingAxes, confidence);
        boolean ready = "axis_agreement".equals(triggerReason);
        String proposedPatchCandidate = patchCandidate(dominant, snapshot);
        String proposedAction = ready && !"provider_disabled".equals(dominant)
                ? "source_patch_candidate"
                : "observe_only";
        PolicyOutcome policy = applyConstraints(proposedPatchCandidate, proposedAction, constraints);

        return new Decision(
                sampleCount,
                ready,
                triggerReason,
                dominant,
                hotspot,
                round4(confidence),
                policy.patchCandidate(),
                policy.action(),
                agreeingAxes,
                policy.decision(),
                constraintHash(constraints));
    }

    private static PolicyOutcome applyConstraints(
            String patchCandidate,
            String action,
            ProbeConstraints constraints) {
        if (!"source_patch_candidate".equals(action)) {
            return new PolicyOutcome(patchCandidate, action, "not_applicable");
        }
        if (constraints == null) {
            return new PolicyOutcome("observe_only", "observe_only", "constraints_missing");
        }
        if (constraints.stopRequested()) {
            return new PolicyOutcome("observe_only", "observe_only", "operator_stop");
        }
        if (constraints.forbiddenActions().contains(action)) {
            return new PolicyOutcome("observe_only", "observe_only", "action_forbidden");
        }
        if (!constraints.allowedPatchCandidates().contains(patchCandidate)) {
            return new PolicyOutcome("observe_only", "observe_only", "candidate_out_of_scope");
        }
        return new PolicyOutcome(patchCandidate, action, "allowed");
    }

    private static void addBlackboxAxis(Map<String, Set<String>> axes, RagFailureBlackboxService.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        String failure = safeLabel(snapshot.dominantFailure(), "none");
        if (!"none".equals(failure)) {
            addAxis(axes, failure, "blackbox");
        }
    }

    private static void addFailurePatternAxis(
            Map<String, Set<String>> axes,
            Map<String, Object> trace,
            List<FailurePatternMatch> recentFailures) {
        if (recentFailures != null) {
            for (FailurePatternMatch match : recentFailures) {
                String failure = failureForKind(match == null ? null : match.kind());
                if (!"none".equals(failure)) {
                    addAxis(axes, failure, "failure_pattern");
                }
            }
        }
        String recovery = safeLabel(asString(trace.get("failpattern.searchRecovery.reason")), "");
        if (recovery.contains("after_filter")) {
            addAxis(axes, "after_filter_starvation", "failure_pattern");
        } else if (recovery.contains("zero_result")) {
            addAxis(axes, "web_starvation", "failure_pattern");
        } else if (recovery.contains("evidence")) {
            addAxis(axes, "evidence_gate", "failure_pattern");
        }
        String starvation = safeLabel(asString(trace.get("failpattern.web.starvation.kind")), "");
        if (starvation.contains("web_starvation")) {
            addAxis(axes, "after_filter_starvation", "failure_pattern");
        }
    }

    private static void addProviderAxis(Map<String, Set<String>> axes, Map<String, Object> trace) {
        if (truthyAny(trace, "web.naver.providerDisabled", "web.brave.providerDisabled",
                "web.serpapi.providerDisabled", "web.tavily.providerDisabled")
                || listLikePresent(trace.get("providerDisabledSignals"))
                || listLikePresent(trace.get("rag.eval.providerDisabledSignals"))) {
            addAxis(axes, "provider_disabled", "provider_state");
        }
        if (afterFilterStarved(trace)) {
            addAxis(axes, "after_filter_starvation", "provider_state");
        }
        if (truthyAny(trace, "web.naver.zeroResults", "web.brave.zeroResults",
                "web.serpapi.zeroResults", "web.tavily.zeroResults", "webSearch.zeroResults", "rag.zeroResults")
                || listLikePresent(trace.get("rag.eval.zeroResultSignals"))) {
            addAxis(axes, "web_starvation", "provider_state");
        }
        if (truthyAny(trace, "web.timeout", "web.naver.timeout", "web.brave.timeout",
                "web.serpapi.timeout", "web.tavily.timeout") || positive(trace, "web.await.events.timeout.count")) {
            addAxis(axes, "timeout", "provider_state");
        }
        if (positive(trace, "web.failsoft.rateLimitBackoff.skipped.cooldown.count")
                || positive(trace, "web.await.events.rateLimit.count")) {
            addAxis(axes, "rate_limit", "provider_state");
        }
    }

    private static void addNeedleAxis(Map<String, Set<String>> axes, Map<String, Object> trace) {
        double delta = maxDouble(trace,
                "probe.needle.contribution.qualityDelta",
                "needle.qualityDelta",
                "NeedleContribution.qualityDelta");
        if (delta < -0.05d) {
            addAxis(axes, "evidence_gate", "needle_contribution");
        }
    }

    private static void addRetrievalAxis(Map<String, Set<String>> axes, Map<String, Object> trace) {
        if (afterFilterStarved(trace)) {
            addAxis(axes, "after_filter_starvation", "retrieval_counts");
        }
        if (zeroPresent(trace, "outCount") || zeroPresent(trace, "retrieval.outCount")
                || zeroPresent(trace, "rag.eval.resultCount")) {
            addAxis(axes, "web_starvation", "retrieval_counts");
        }
        if (truthyAny(trace, "finalSigmoidGate.failed", "gate.final.failed", "citation.gate.failed")
                || positive(trace, "citation.gate.failed.count")) {
            addAxis(axes, "evidence_gate", "retrieval_counts");
        }
    }

    private static void addAxis(Map<String, Set<String>> axes, String failure, String axis) {
        String safeFailure = safeLabel(failure, "none");
        String safeAxis = safeLabel(axis, "unknown");
        if ("none".equals(safeFailure)) {
            return;
        }
        axes.computeIfAbsent(safeFailure, ignored -> new LinkedHashSet<>()).add(safeAxis);
    }

    private static String chooseDominantFailure(
            Map<String, Set<String>> axes,
            RagFailureBlackboxService.Snapshot snapshot) {
        String blackboxFailure = snapshot == null ? "none" : safeLabel(snapshot.dominantFailure(), "none");
        if (!"none".equals(blackboxFailure) && axes.getOrDefault(blackboxFailure, Set.of()).size() >= MIN_AGREEING_AXES) {
            return blackboxFailure;
        }
        return axes.entrySet().stream()
                .max(Comparator
                        .comparingInt((Map.Entry<String, Set<String>> entry) -> entry.getValue().size())
                        .thenComparing(entry -> priority(entry.getKey())))
                .map(Map.Entry::getKey)
                .orElse(blackboxFailure);
    }

    private static String triggerReason(long sampleCount, int agreeingAxes, double confidence) {
        if (sampleCount < MIN_SAMPLE_COUNT) {
            return "sample_count_below_threshold";
        }
        if (agreeingAxes < MIN_AGREEING_AXES) {
            return "axis_agreement_below_threshold";
        }
        if (confidence < MIN_CONFIDENCE) {
            return "confidence_below_threshold";
        }
        return "axis_agreement";
    }

    private static String patchCandidate(String failure, RagFailureBlackboxService.Snapshot snapshot) {
        String dominant = safeLabel(failure, "none");
        if ("provider_disabled".equals(dominant)) {
            return "observe_provider_disabled";
        }
        if (snapshot != null && dominant.equals(safeLabel(snapshot.dominantFailure(), "none"))) {
            String action = safeLabel(snapshot.restoreAction(), "");
            if (!action.isBlank() && !"none".equals(action) && !"observe_only".equals(action)) {
                return action;
            }
        }
        return switch (dominant) {
            case "after_filter_starvation", "web_starvation" -> "anchor_compression_topup";
            case "timeout", "rate_limit", "cancelled" -> "cooldown_reorder";
            case "evidence_gate" -> "evidence_gate_strict";
            case "missing_future" -> "web_await_bypass";
            case "context_contamination" -> "vector_quarantine";
            case "model_required" -> "llm_route_degrade";
            default -> "observe_only";
        };
    }

    private static String hotspotFor(String failure) {
        return switch (safeLabel(failure, "none")) {
            case "after_filter_starvation" -> "web_filter";
            case "web_starvation", "timeout", "rate_limit", "cancelled" -> "web";
            case "provider_disabled" -> "provider";
            case "evidence_gate" -> "final_gate";
            case "context_contamination" -> "vector";
            case "missing_future" -> "web_await";
            default -> "none";
        };
    }

    private static int priority(String failure) {
        return switch (safeLabel(failure, "")) {
            case "provider_disabled" -> 90;
            case "after_filter_starvation" -> 75;
            case "web_starvation" -> 70;
            case "timeout", "rate_limit", "cancelled" -> 60;
            case "evidence_gate" -> 55;
            default -> 10;
        };
    }

    private static String failureForKind(FailurePatternKind kind) {
        if (kind == null) {
            return "none";
        }
        return switch (kind) {
            case SEARCH_AFTER_FILTER_STARVATION, WEB_STARVATION -> "after_filter_starvation";
            case SEARCH_ZERO_RESULT -> "web_starvation";
            case EVIDENCE_INSUFFICIENT -> "evidence_gate";
            case NAVER_TRACE_TIMEOUT -> "timeout";
            case CIRCUIT_OPEN -> "rate_limit";
            case DISAMBIG_FALLBACK -> "missing_future";
        };
    }

    private static long sampleCount(Map<String, Object> trace, Map<String, Set<String>> axes) {
        long explicit = maxLong(trace,
                PREFIX + "sampleCount",
                "probe.sampleCount",
                "rag.probe.sampleCount",
                "search.probe.sampleCount",
                "needle.probe.sampleCount",
                "retrieval.sampleCount",
                "tracePool.size");
        if (explicit > 0) {
            return explicit;
        }
        long listSignals = listSize(trace.get("rag.eval.providerDisabledSignals"))
                + listSize(trace.get("rag.eval.zeroResultSignals"))
                + listSize(trace.get("rag.eval.afterFilterStarvationSignals"));
        long axisSignals = axes.values().stream().mapToLong(Set::size).sum();
        return Math.max(listSignals, axisSignals);
    }

    private static double confidence(RagFailureBlackboxService.Snapshot snapshot, int agreeingAxes) {
        double base = 0.0d;
        if (snapshot != null) {
            base = Math.max(base, snapshot.confidence());
            base = Math.max(base, Math.min(snapshot.riskScore(), snapshot.priorityScore()));
        }
        if (agreeingAxes >= MIN_AGREEING_AXES) {
            base = Math.max(base, 0.65d + (Math.min(3, agreeingAxes - MIN_AGREEING_AXES) * 0.05d));
        }
        return clamp01(base);
    }

    private void writeTrace(String goal, String where, Decision decision) {
        if (decision == null) {
            return;
        }
        try {
            TraceStore.put(PREFIX + "goalHash", goalHash(goal));
            TraceStore.put(PREFIX + "sampleCount", decision.sampleCount());
            TraceStore.put(PREFIX + "evidenceReady", decision.evidenceReady());
            TraceStore.put(PREFIX + "triggerReason", safeLabel(decision.triggerReason(), "unknown"));
            TraceStore.put(PREFIX + "dominantFailure", safeLabel(decision.dominantFailure(), "none"));
            TraceStore.put(PREFIX + "hotspot", safeLabel(decision.hotspot(), "none"));
            TraceStore.put(PREFIX + "confidence", decision.confidence());
            TraceStore.put(PREFIX + "patchCandidate", safeLabel(decision.patchCandidate(), "observe_only"));
            TraceStore.put(PREFIX + "action", safeLabel(decision.action(), "observe_only"));
            TraceStore.put(PREFIX + "policyDecision", safeLabel(decision.policyDecision(), "unknown"));
            TraceStore.put(PREFIX + "constraintHash", safeLabel(decision.constraintHash(), "none"));
            TraceStore.put(PREFIX + "decisionAuthority", "probe_only");
            TraceStore.put(PREFIX + "verificationGatePassed", false);
            TraceStore.put(PREFIX + "axisCount", Math.max(0, decision.axisCount()));
            TraceStore.put(PREFIX + "where", safeLabel(where, "unknown"));
        } catch (Throwable t) {
            traceSuppressed("write_trace", t);
        }
    }

    private void emitDebug(String goal, String where, Decision decision) {
        if (decision == null || !decision.evidenceReady()) {
            return;
        }
        DebugEventStore store = debugEventStoreProvider == null ? null : debugEventStoreProvider.getIfAvailable();
        if (store == null) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sampleCount", decision.sampleCount());
            data.put("failureClass", safeLabel(decision.dominantFailure(), "none"));
            data.put("hotspot", safeLabel(decision.hotspot(), "none"));
            data.put("confidence", decision.confidence());
            data.put("patchCandidate", safeLabel(decision.patchCandidate(), "observe_only"));
            data.put("action", safeLabel(decision.action(), "observe_only"));
            data.put("policyDecision", safeLabel(decision.policyDecision(), "unknown"));
            data.put("decisionAuthority", "probe_only");
            data.put("verificationGatePassed", false);
            data.put("constraintHash", safeLabel(decision.constraintHash(), "none"));
            data.put("goalHash", goalHash(goal));
            data.put("triggerReason", safeLabel(decision.triggerReason(), "unknown"));
            data.put("axisCount", Math.max(0, decision.axisCount()));
            store.emit(
                    DebugProbeType.ORCHESTRATION,
                    "observe_only".equals(decision.action()) ? DebugEventLevel.INFO : DebugEventLevel.WARN,
                    "causal_probe:" + safeLabel(decision.dominantFailure(), "none")
                            + ":" + safeLabel(decision.action(), "observe_only"),
                    "[AWX][causalProbe] causal_evidence_ready",
                    safeLabel(where, "unknown"),
                    data,
                    null);
        } catch (Throwable t) {
            traceSuppressed("debug_emit", t);
        }
    }

    private RagFailureBlackboxService.Snapshot currentBlackbox(String where, Map<String, Object> fallbackTrace) {
        try {
            RagFailureBlackboxService service =
                    blackboxServiceProvider == null ? null : blackboxServiceProvider.getIfAvailable();
            if (service != null) {
                return service.currentOrRefresh("causalProbe." + safeLabel(where, "unknown"));
            }
        } catch (Throwable t) {
            traceSuppressed("blackbox_snapshot", t);
        }
        return RagFailureBlackboxService.analyze(fallbackTrace == null ? Map.of() : fallbackTrace);
    }

    private List<FailurePatternMatch> recentFailurePatterns() {
        try {
            FailurePatternOrchestrator orchestrator =
                    failurePatternProvider == null ? null : failurePatternProvider.getIfAvailable();
            if (orchestrator == null) {
                return List.of();
            }
            long since = System.currentTimeMillis() - 120_000L;
            return orchestrator.recentMatchesSince(since, null);
        } catch (Throwable t) {
            traceSuppressed("failure_pattern_recent", t);
            return List.of();
        }
    }

    private static Map<String, Object> snapshotTrace() {
        try {
            return TraceStore.getAll();
        } catch (Throwable t) {
            traceSuppressed("snapshot_trace", t);
            return Map.of();
        }
    }

    private static String goalHash(String goal) {
        String hash = SafeRedactor.hashValue(goal);
        if (hash != null) {
            return hash;
        }
        for (String key : List.of(
                PREFIX + "goalHash",
                "rag.eval.queryFingerprint",
                "queryFingerprint",
                "queryHash",
                "nightmare.finalRescue.queryHash",
                "retrieval.vectorFallback.queryHash12")) {
            Object value = TraceStore.get(key);
            hash = SafeRedactor.hashValue(value == null ? null : String.valueOf(value));
            if (hash != null) {
                return hash;
            }
        }
        return "none";
    }

    private static boolean afterFilterStarved(Map<String, Object> trace) {
        return positive(trace, "web.naver.returnedCount") && zeroPresent(trace, "web.naver.afterFilterCount")
                || positive(trace, "web.naver.filter.rawCount") && zeroPresent(trace, "web.naver.afterFilterCount")
                || positive(trace, "web.brave.returnedCount") && zeroPresent(trace, "web.brave.afterFilterCount")
                || positive(trace, "web.serpapi.returnedCount") && zeroPresent(trace, "web.serpapi.afterFilterCount")
                || positive(trace, "web.tavily.returnedCount") && zeroPresent(trace, "web.tavily.afterFilterCount")
                || positive(trace, "webSearch.returnedCount") && zeroPresent(trace, "webSearch.afterFilterCount")
                || positive(trace, "rag.returnedCount") && zeroPresent(trace, "rag.afterFilterCount")
                || positive(trace, "web.failsoft.starvationFallback.count")
                || positive(trace, "starvationFallback.count")
                || listLikePresent(trace.get("rag.eval.afterFilterStarvationSignals"))
                || truthyAny(trace, "web.failsoft.starvationFallback.used", "starvationFallback.used");
    }

    private static boolean truthyAny(Map<String, Object> trace, String... keys) {
        if (trace == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (truthy(trace.get(key))) {
                return true;
            }
        }
        return false;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return Double.isFinite(n.doubleValue()) && n.doubleValue() != 0.0d;
        }
        String s = value == null ? "" : String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
    }

    private static boolean positive(Map<String, Object> trace, String key) {
        return trace != null && toLong(trace.get(key)) > 0L;
    }

    private static boolean zeroPresent(Map<String, Object> trace, String key) {
        return trace != null && trace.containsKey(key) && toLong(trace.get(key)) <= 0L;
    }

    private static long maxLong(Map<String, Object> trace, String... keys) {
        long max = 0L;
        if (trace == null || keys == null) {
            return max;
        }
        for (String key : keys) {
            max = Math.max(max, toLong(trace.get(key)));
        }
        return max;
    }

    private static double maxDouble(Map<String, Object> trace, String... keys) {
        double max = 0.0d;
        if (trace == null || keys == null) {
            return max;
        }
        for (String key : keys) {
            max = Math.max(max, toDouble(trace.get(key), 0.0d));
        }
        return max;
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? Math.max(0L, n.longValue()) : 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            traceSuppressed("long_parse", e);
            return 0L;
        }
    }

    private static double toDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : fallback;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            traceSuppressed("double_parse", e);
            return fallback;
        }
    }

    private static boolean listLikePresent(Object value) {
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        String s = value == null ? "" : String.valueOf(value).trim();
        return !s.isBlank() && !"[]".equals(s) && !"{}".equals(s) && !"null".equalsIgnoreCase(s);
    }

    private static long listSize(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return listLikePresent(value) ? 1L : 0L;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safeLabel(String value, String fallback) {
        String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (raw.isBlank()) {
            raw = fallback == null ? "" : fallback.trim().toLowerCase(Locale.ROOT);
        }
        raw = raw.replaceAll("[^a-z0-9_.:-]+", "_");
        if (raw.length() > 96) {
            raw = raw.substring(0, 96);
        }
        return raw.isBlank() ? "none" : raw;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double round4(double value) {
        return Math.round(clamp01(value) * 10_000.0d) / 10_000.0d;
    }

    private static String constraintHash(ProbeConstraints constraints) {
        if (constraints == null) {
            return "none";
        }
        List<String> allowed = new ArrayList<>(constraints.allowedPatchCandidates());
        List<String> forbidden = new ArrayList<>(constraints.forbiddenActions());
        allowed.sort(String::compareTo);
        forbidden.sort(String::compareTo);
        String hash = SafeRedactor.hashValue(
                String.join(",", allowed) + "|" + String.join(",", forbidden)
                        + "|" + constraints.stopRequested() + "|" + constraints.stopReasonHash());
        return hash == null ? "none" : hash;
    }

    private static Set<String> safeLabels(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> safe = new LinkedHashSet<>();
        for (String value : values) {
            String label = safeLabel(value, "none");
            if (!"none".equals(label)) {
                safe.add(label);
            }
        }
        return Set.copyOf(safe);
    }

    private static void traceSuppressed(String stage, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        TraceStore.put(PREFIX + "suppressed.stage", safeStage);
        TraceStore.put(PREFIX + "suppressed.errorType", SafeRedactor.traceLabelOrFallback(errorType, "unknown"));
    }

    public record Decision(
            long sampleCount,
            boolean evidenceReady,
            String triggerReason,
            String dominantFailure,
            String hotspot,
            double confidence,
            String patchCandidate,
            String action,
            int axisCount,
            String policyDecision,
            String constraintHash) {
    }

    public record ProbeConstraints(
            Set<String> allowedPatchCandidates,
            Set<String> forbiddenActions,
            boolean stopRequested,
            String stopReasonHash) {

        public ProbeConstraints {
            allowedPatchCandidates = safeLabels(allowedPatchCandidates);
            forbiddenActions = safeLabels(forbiddenActions);
            stopReasonHash = safeLabel(stopReasonHash, "none");
        }

        public static ProbeConstraints allowOnly(String... candidates) {
            return new ProbeConstraints(
                    candidates == null ? Set.of() : Set.of(candidates),
                    Set.of(),
                    false,
                    "none");
        }

        public static ProbeConstraints stop(String reason) {
            String hash = SafeRedactor.hashValue(reason);
            return new ProbeConstraints(Set.of(), Set.of(), true, hash == null ? "none" : hash);
        }
    }

    private record PolicyOutcome(String patchCandidate, String action, String decision) {
    }
}
