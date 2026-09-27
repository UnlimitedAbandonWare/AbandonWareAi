package com.example.lms.resilience;

import static com.example.lms.resilience.RagFailureBlackboxValues.*;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.example.lms.cfvm.RawSlotExtractor;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.learning.virtualpoint.VirtualPoint;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphClient;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphClient.RecoveryRecommendation;
import com.example.lms.telemetry.MatrixTelemetryExtractor;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Request-scoped RAG failure blackbox.
 *
 * <p>It projects ablation/failure-pattern signals into allowlisted
 * {@code blackbox.risk.*} trace keys. It never stores raw query/snippet/key
 * material; only low-cardinality labels, counts, hashes, and bounded scores are
 * emitted.</p>
 */
@Service
public class RagFailureBlackboxService {

    public static final String PREFIX = "blackbox.risk.";
    public static final double HIGH_RISK_THRESHOLD = 0.65d;
    private static final Logger LOG = LoggerFactory.getLogger(RagFailureBlackboxService.class);

    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;
    private final ObjectProvider<com.example.lms.learning.virtualpoint.VirtualPointService> virtualPointServiceProvider;
    private final ObjectProvider<FailurePatternOrchestrator> failurePatternProvider;
    private final ObjectProvider<Neo4jKnowledgeGraphClient> graphClientProvider;

    @Value("${rag.blackbox.risk.enabled:true}")
    private boolean enabled;

    @Value("${rag.blackbox.risk.virtual-point.enabled:false}")
    private boolean virtualPointEnabled;

    @Value("${rag.blackbox.risk.virtual-point.min-similarity:0.92}")
    private double virtualPointMinSimilarity;

    @Value("${rag.blackbox.risk.history-correction.enabled:true}")
    private boolean historyCorrectionEnabled = true;

    @Value("${rag.blackbox.risk.history-correction.contamination-threshold:0.35}")
    private double historyContaminationThreshold = 0.35d;

    @Value("${rag.blackbox.risk.history-correction.apply-threshold:0.65}")
    private double historyCorrectionApplyThreshold = HIGH_RISK_THRESHOLD;

    @Value("${rag.blackbox.risk.history-correction.require-current-signal:true}")
    private boolean historyCorrectionRequireCurrentSignal = true;

    @Value("${rag.blackbox.graph.enabled:false}")
    private boolean graphEnabled;

    @Value("${rag.blackbox.graph.min-confidence:0.65}")
    private double graphMinConfidence;

    @Value("${rag.blackbox.graph.top-k:3}")
    private int graphTopK;

    public RagFailureBlackboxService(
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            ObjectProvider<com.example.lms.learning.virtualpoint.VirtualPointService> virtualPointServiceProvider,
            ObjectProvider<FailurePatternOrchestrator> failurePatternProvider) {
        this(debugEventStoreProvider, virtualPointServiceProvider, failurePatternProvider, null);
    }

    @Autowired
    public RagFailureBlackboxService(
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            ObjectProvider<com.example.lms.learning.virtualpoint.VirtualPointService> virtualPointServiceProvider,
            ObjectProvider<FailurePatternOrchestrator> failurePatternProvider,
            ObjectProvider<Neo4jKnowledgeGraphClient> graphClientProvider) {
        this.debugEventStoreProvider = debugEventStoreProvider;
        this.virtualPointServiceProvider = virtualPointServiceProvider;
        this.failurePatternProvider = failurePatternProvider;
        this.graphClientProvider = graphClientProvider;
    }

    public Snapshot refresh(String where) {
        if (!enabled) {
            return Snapshot.empty("disabled");
        }
        Map<String, Object> trace = snapshotTrace();
        Snapshot observedSnapshot = analyze(trace, cooldownSignals());
        Snapshot priorAppliedSnapshot = applyVirtualPointPrior(observedSnapshot);
        GraphRecommendation graphRecommendation = recommendFromGraph(priorAppliedSnapshot);
        Snapshot finalSnapshot = applyGraphRecommendation(priorAppliedSnapshot, graphRecommendation);
        writeTrace(finalSnapshot, where);
        writeGraphRecommendationTrace(graphRecommendation);
        rememberVirtualPoint(observedSnapshot);
        rememberGraph(observedSnapshot);
        emitDebug(finalSnapshot, where);
        return finalSnapshot;
    }

    public Snapshot refreshOnce(String where) {
        return currentOrRefresh(where);
    }

    public Snapshot currentOrRefresh(String where) {
        if (!enabled) {
            return Snapshot.empty("disabled");
        }
        Map<String, Object> trace = snapshotTrace();
        if (hasProjection(trace)) {
            writeWhere(where);
            return snapshotFromTrace(trace);
        }
        return refresh(where);
    }

    public static Snapshot projectCurrentTrace(String where) {
        Snapshot snapshot = analyze(snapshotTrace(), List.of());
        writeTrace(snapshot, where);
        return snapshot;
    }

    public static Snapshot analyze(Map<String, Object> trace) {
        return analyze(trace, List.of());
    }

    public static Snapshot analyze(Map<String, Object> trace, List<Map<String, Object>> cooldowns) {
        Map<String, Object> safeTrace = trace == null ? Map.of() : trace;
        List<Candidate> candidates = new ArrayList<>();

        addDirectCandidates(candidates, safeTrace);
        addAblationCandidates(candidates, safeTrace);
        addTraceAnchorCandidates(candidates, safeTrace);
        addCooldownCandidates(candidates, cooldowns);

        candidates.sort(Comparator
                .comparingDouble(Candidate::priorityScore).reversed()
                .thenComparing((Candidate c) -> priority(c.failureClass()), Comparator.reverseOrder())
                .thenComparing(Candidate::failureClass));

        Candidate top = candidates.isEmpty()
                ? new Candidate("none", "observe", "none", 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, false, "observe_only", "no_failure_signal")
                : candidates.get(0);

        double riskScore = round4(top.risk());
        double priorityScore = round4(top.priorityScore());
        String dominantFailure = safeLabel(top.failureClass(), "none");
        String restoreAction = restoreActionFor(dominantFailure);
        if (!"observe_only".equals(top.restoreAction())) {
            restoreAction = top.restoreAction();
        }
        String patternId = patternId(safeTrace, dominantFailure, top.hotspot());
        Map<String, Object> matrix = matrixSnapshot(safeTrace, riskScore);
        List<Map<String, Object>> rank = rankedRows(candidates);
        Map<String, Object> topContributor = top.toPublicMap();
        boolean highRisk = Math.max(riskScore, priorityScore) >= HIGH_RISK_THRESHOLD;
        String vectorDecision = (highRisk || "vector_quarantine".equals(restoreAction) || "safe_path_bypass".equals(restoreAction))
                ? "QUARANTINE"
                : "SHADOW_REVIEW";

        return new Snapshot(
                riskScore,
                priorityScore,
                dominantFailure,
                safeLabel(top.hotspot(), "none"),
                topContributor,
                rank,
                patternId,
                restoreAction,
                round4(confidence(top, rank.size())),
                safePublicLabel(top.reason(), "observe_only"),
                matrix,
                vectorDecision,
                highRisk);
    }

    private static void addDirectCandidates(List<Candidate> out, Map<String, Object> trace) {
        if (truthyAny(trace, "web.naver.providerDisabled", "web.brave.providerDisabled", "web.serpapi.providerDisabled", "web.tavily.providerDisabled")
                || listLikePresent(trace.get("providerDisabledSignals"))
                || listLikePresent(trace.get("rag.eval.providerDisabledSignals"))) {
            out.add(candidate("provider_disabled", "provider", 0.95d, 1.0d, recurrenceFor(trace,
                            "providerDisabledSignals.count", "rag.eval.providerDisabledSignals.count"), true,
                    "disable_provider_failsoft", "provider_disabled_signal"));
        }

        if (gpuGatewayBlocked(trace)) {
            out.add(candidate("gpu_gateway_unreachable", "gpu_gateway", 0.82d, 1.0d, recurrenceFor(trace,
                            "uaw.gpu-gateway.admission.blocked.count"), false,
                    "cooldown_reorder", "desktop_gpu_gateway_unreachable"));
        }

        double gpuHardwarePressure = gpuHardwareAdmissionPressure(trace);
        if (gpuHardwarePressure >= 0.50d) {
            out.add(candidate("gpu_hardware_pressure", "gpu_hardware", 0.80d, gpuHardwarePressure,
                    recurrenceFor(trace, "uaw.gpu-hardware.admission.blocked.count",
                            "uaw.gpu-hardware.admission.retrainBlocked.count"), false,
                    "demote_heavy_work", gpuHardwareDecisionReason(trace)));
        }

        KgNeo4jDegradationSignal kgNeo4j = kgNeo4jDegradationSignal(trace);
        if (kgNeo4j.degraded()) {
            out.add(candidate("kg_neo4j_degraded", "kg", 0.80d, kgNeo4j.pressure(),
                    recurrenceFor(trace, "retrieval.kg.neo4j.events.count",
                            "retrieval.dependency.kg.failure.count"), false,
                    "kg_dependency_fallback", kgNeo4j.reason()));
        }

        if (afterFilterStarved(trace)) {
            out.add(candidate("after_filter_starvation", "web_filter", 0.90d, 1.0d, recurrenceFor(trace,
                            "web.failsoft.starvationFallback.count",
                            "starvationFallback.count",
                            "rag.eval.afterFilterStarvationSignals.count"), true,
                    "anchor_compression_topup", "returned_count_after_filter_zero"));
        }

        if (truthyAny(trace, "web.naver.zeroResults", "web.brave.zeroResults", "web.serpapi.zeroResults", "web.tavily.zeroResults")) {
            out.add(candidate("web_starvation", "web", 0.62d, 0.90d, recurrenceFor(trace,
                            "rag.eval.zeroResultSignals.count", "zeroResultSignals.count"), false,
                    "anchor_compression_topup", "provider_zero_results"));
        }

        String attachmentFailure = safeLabel(asString(trace.get("attachment.text.emptyReason")), "");
        if ("text_layer_empty_ocr_required".equals(attachmentFailure)) {
            out.add(candidate("text_layer_empty_ocr_required", "attachment_pdf", 0.34d, 0.75d,
                    recurrenceFor(trace, "attachment.localDocs.count"), false,
                    "observe_only", "pdf_text_layer_empty"));
        } else if ("archive_empty".equals(attachmentFailure)) {
            out.add(candidate("archive_empty", "attachment_archive", 0.25d, 0.65d,
                    recurrenceFor(trace, "attachment.archive.entryCount"), false,
                    "observe_only", "archive_tree_no_entries"));
        } else if ("archive_error".equals(attachmentFailure)
                || "attachment_extraction_failed".equals(attachmentFailure)) {
            out.add(candidate("attachment_extraction_failed", "attachment", 0.40d, 0.75d,
                    recurrenceFor(trace, "attachment.localDocs.count"), false,
                    "attachment_failsoft_skip", "attachment_extraction_failed"));
        }

        if (truthyAny(trace, "web.await.missing_future.any")
                || "missing_future".equalsIgnoreCase(asString(trace.get("web.await.skipped.last")))
                || positive(trace, "web.await.events.missingFuture.count")) {
            out.add(candidate("missing_future", "web_await", 0.92d, 1.0d, recurrenceFor(trace,
                            "web.await.events.missingFuture.count", "web.await.events.count"), true,
                    "web_await_bypass", "async_future_missing"));
        }

        if (llmRetryBudgetExceeded(trace)) {
            String code = firstLabel(trace, "llm.retryBudget.code", "llm.error.code");
            double severity = "upstream_5xx".equals(code) ? 0.86d : 0.80d;
            out.add(candidate("llm_upstream_retry_exhausted", "llm_route", severity, 1.0d, recurrenceFor(trace,
                            "llm.retryBudget.exceeded.count"), true,
                    "llm_route_degrade", "llm_retry_budget_" + safeLabel(code, "unknown")));
        }

        if (truthyAny(trace, "qtx.llm.modelRequired")
                || contains(asString(trace.get("qtx.llm.error.code")), "MODEL_REQUIRED")
                || contains(asString(trace.get("qtx.llm.error")), "model required")) {
            out.add(candidate("model_required", "llm_route", 0.88d, 1.0d, recurrenceFor(trace,
                            "qtx.llm.modelRequired.count"), true,
                    "llm_route_degrade", "required_model_blank"));
        }
        boolean llmClientFailure = llmClientBlankOrFailed(trace);
        if (truthyAny(trace, "bypass.silentFailure", "orch.noiseEscape.bypassSilentFailure", "nightmare.finalRescue.used", "llm.call.blank", "llm.output.blank") || llmClientFailure || maxLong(trace, "nightmare.silent.events", "llm.call.blank.count", "llm.client.blank.count", "llm.client.failed.count") > 0L
                || "blank_response".equals(firstLabel(trace, "llm.output.reason")) || "BLANK_RESPONSE".equals(asString(trace == null ? null : trace.get("llm.error.code")))) {
            boolean llmBlank = truthyAny(trace, "llm.call.blank", "llm.output.blank") || positive(trace, "llm.call.blank.count") || llmClientFailure;
            out.add(candidate("silent_failure", llmBlank ? "llm_route" : "fault_mask", 0.84d, 1.0d, recurrenceFor(trace,
                            "nightmare.silent.events", "llm.call.blank.count", "llm.client.blank.count", "llm.client.failed.count"), true,
                    llmBlank ? "llm_route_degrade" : "safe_path_bypass", llmBlank ? llmRouteSilentReason(trace) : "silent_failure_detected"));
        }

        HistoryContaminationSignal historySignal = historyContaminationSignal(trace, 0.35d);
        if (historySignal.currentSignal()) {
            out.add(candidate("context_contamination", "memory_history", Math.max(0.86d, historySignal.score()),
                    1.0d, recurrenceFor(trace, "learning.validation.contaminationSignals.count",
                            "prompt.memory.compressor.lineDropCount"), true,
                    "vector_quarantine", "history_context_contamination"));
        }

        if (truthyAny(trace, "vector.scopeFilter.relaxed", "vector.docTypeFilter.relaxed",
                "vector.poisoning.bypass", "vector.fp.bypassed")
                || learningContaminationSignals(trace) > 0
                || hasContaminationSignal(trace)) {
            out.add(candidate("context_contamination", "vector", 0.86d, 1.0d, recurrenceFor(trace,
                            "learning.validation.contaminationSignals.count"), true,
                    "vector_quarantine", "contamination_or_vector_degraded"));
        }

        if (truthyAny(trace, "web.naver.cancelled", "web.brave.cancelled", "web.serpapi.cancelled", "web.tavily.cancelled")
                || positive(trace, "web.failsoft.cancelled.naver.count") || positive(trace, "web.failsoft.cancelled.brave.count")) {
            out.add(candidate("cancelled", "web", 0.72d, 0.90d, recurrenceFor(trace,
                            "web.failsoft.cancelled.naver.count", "web.failsoft.cancelled.brave.count"), false,
                    "cooldown_reorder", "provider_cancelled"));
        }
        if (truthyAny(trace, "web.timeout", "web.naver.timeout", "web.brave.timeout", "web.serpapi.timeout",
                "web.tavily.timeout")
                || positive(trace, "web.await.events.timeout.count")) {
            out.add(candidate("timeout", "web", 0.72d, 0.90d, recurrenceFor(trace,
                            "web.await.events.timeout.count"), false,
                    "cooldown_reorder", "provider_timeout"));
        }

        if (positive(trace, "web.failsoft.rateLimitBackoff.skipped.cooldown.count")
                || positive(trace, "web.await.events.rateLimit.count")) {
            out.add(candidate("rate_limit", "web", 0.72d, 0.90d, recurrenceFor(trace,
                            "web.failsoft.rateLimitBackoff.skipped.cooldown.count",
                            "web.await.events.rateLimit.count"), false,
                    "cooldown_reorder", "provider_rate_limit"));
        }

        if (truthyAny(trace, "finalSigmoidGate.failed", "gate.final.failed", "citation.gate.failed")
                || contains(asString(trace.get("learning.error.hotspot")), "evidence")
                || evidencePromotionBlocked(trace)) {
            out.add(candidate("evidence_gate", "final_gate", 0.78d, 0.90d, recurrenceFor(trace,
                            "citation.gate.failed.count", "gate.final.failed.count"), false,
                    "evidence_gate_strict", "final_evidence_gate"));
        }
    }

    @SuppressWarnings("unchecked")
    private static void addAblationCandidates(List<Candidate> out, Map<String, Object> trace) {
        Object value = trace.get("ablation.probabilities");
        if (!(value instanceof Iterable<?> rows)) {
            return;
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<Object, Object> map = (Map<Object, Object>) raw;
            String step = safeLabel(asString(map.get("step")), "ablation");
            String guard = safeLabel(asString(map.get("guard")), "ablation");
            String failure = classify(step + " " + guard);
            double p = clamp01(asDouble(map.get("p"), 0.0d));
            double delta = clamp01(asDouble(map.get("delta"), 0.0d));
            double severity = Math.max(severityFor(failure), clamp01(0.50d + delta));
            out.add(candidate(failure, step, severity, Math.max(0.05d, p), recurrence(trace),
                    silentFailureClass(failure), restoreActionFor(failure),
                    "ablation:" + guard));
        }
    }

    private static void addTraceAnchorCandidates(List<Candidate> out, Map<String, Object> trace) {
        List<Map<String, Object>> rows = traceAnchorRows(trace);
        if (rows.isEmpty()) {
            return;
        }
        double routePressure = traceAnchorRouteCorrectionNeed(trace);
        for (Map<String, Object> row : rows) {
            String routeHint = safeLabel(asString(row.get("routeHint")), "");
            String component = safeLabel(asString(row.get("component")), "unknown");
            String stage = safeLabel(asString(row.get("stage")), component);
            String lane = safeLabel(asString(row.get("lane")), component.toUpperCase(Locale.ROOT));
            double p = clamp01(asDouble(row.get("p"), 0.0d));
            double delta = clamp01(asDouble(row.get("delta"), 0.0d));
            double expected = clamp01(Math.max(asDouble(row.get("expectedDelta"), 0.0d), delta * p));
            double pressure = Math.max(expected, routePressure);
            if (pressure <= 0.0d && p <= 0.0d) {
                continue;
            }
            String failure = traceAnchorFailure(routeHint, component, stage + " " + lane);
            String action = traceAnchorRouteAction(routeHint, failure);
            double severity = Math.max(severityFor(failure), clamp01(0.55d + pressure));
            double probability = Math.max(0.05d, Math.max(p, pressure));
            out.add(candidate(failure, "trace_anchor_" + component, severity, probability, recurrence(trace),
                    silentFailureClass(failure), action, "trace_anchor:" + safeLabel(routeHint, "route")));
        }
    }

    private static void addCooldownCandidates(List<Candidate> out, List<Map<String, Object>> cooldowns) {
        if (cooldowns == null) {
            return;
        }
        for (Map<String, Object> signal : cooldowns) {
            if (signal == null || !truthy(signal.get("coolingDown"))) {
                continue;
            }
            String source = safeLabel(asString(signal.get("source")), "source");
            out.add(candidate(source + "_cooldown", source, 0.68d, 0.80d, 1.0d, false,
                    "cooldown_reorder", "failure_pattern_cooldown"));
        }
    }

    private List<Map<String, Object>> cooldownSignals() {
        FailurePatternOrchestrator failures = failurePatternProvider == null ? null : failurePatternProvider.getIfAvailable();
        if (failures == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String source : List.of("web", "vector", "kg", "llm", "qtx", "disambig")) {
            try {
                FailurePatternOrchestrator.CooldownView view = failures.inspectCooldown(source);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", source);
                row.put("coolingDown", view.coolingDown());
                row.put("remainingMs", Math.max(0L, view.remainingMs()));
                row.put("kind", safeLabel(view.lastKind(), ""));
                out.add(row);
            } catch (Exception e) {
                traceSkipped("cooldown_signal", source, e);
            }
        }
        return out;
    }

    private static Candidate candidate(String failureClass,
                                       String hotspot,
                                       double severity,
                                       double probability,
                                       double recurrence,
                                       boolean silent,
                                       String restoreAction,
                                       String reason) {
        double boost = silent ? 1.25d : 1.0d;
        double risk = clamp01(severity * probability * boost);
        double priorityScore = clamp01(risk * Math.max(0.0d, recurrence));
        return new Candidate(
                safeLabel(failureClass, "unknown"),
                safeLabel(hotspot, "unknown"),
                safePublicLabel(reason, "signal"),
                round4(risk),
                round4(priorityScore),
                round4(severity),
                round4(probability),
                round4Unbounded(recurrence),
                silent,
                safeLabel(restoreAction, restoreActionFor(failureClass)),
                safePublicLabel(reason, "signal"));
    }

    private static void traceSkipped(String stage, String detail, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeDetail = SafeRedactor.traceLabelOrFallback(detail, "unknown");
        String errorType = errorType(error);
        String safeErrorType = SafeRedactor.traceLabelOrFallback(errorType, "unknown");
        String key = "blackbox.suppressed." + safeStage;
        TraceStore.put(key, true);
        TraceStore.put(key + ".detail", safeDetail);
        TraceStore.put(key + ".errorType", safeErrorType);
        LOG.debug("[AWX][blackbox] trace skipped stage={} detail={} errorType={}",
                safeStage,
                safeDetail,
                safeErrorType);
    }

    private static String errorType(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        if (error instanceof NumberFormatException) {
            return "invalid_number";
        }
        return error.getClass().getSimpleName();
    }

    private static void writeTrace(Snapshot snapshot, String where) {
        if (snapshot == null) {
            return;
        }
        try {
            TraceStore.put(PREFIX + "riskScore", snapshot.riskScore());
            TraceStore.put(PREFIX + "priorityScore", snapshot.priorityScore());
            TraceStore.put(PREFIX + "dominantFailure", snapshot.dominantFailure());
            TraceStore.put(PREFIX + "hotspot", snapshot.hotspot());
            TraceStore.put(PREFIX + "topContributor", snapshot.topContributor());
            TraceStore.put(PREFIX + "rank", snapshot.rank());
            TraceStore.put(PREFIX + "patternId", snapshot.patternId());
            TraceStore.put(PREFIX + "restoreAction", snapshot.restoreAction());
            TraceStore.put(PREFIX + "confidence", snapshot.confidence());
            TraceStore.put(PREFIX + "decisionReason", SafeRedactor.traceLabelOrFallback(snapshot.decisionReason(), "unknown"));
            TraceStore.put(PREFIX + "matrix", snapshot.matrix());
            TraceStore.put(PREFIX + "vectorDecision", snapshot.vectorDecision());
            TraceStore.put(PREFIX + "highRisk", snapshot.highRisk());
            Map<String, Object> currentTrace = snapshotTrace(); RagConstitutionalScorecard.projectTrace(currentTrace, snapshot);
            TraceStore.put(PREFIX + "jbPressure", round4(jbPressure(currentTrace)));
            TraceStore.put(PREFIX + "cbPressure", round4(cbPressure(currentTrace)));
            TraceStore.put(PREFIX + "traceAnchor", traceAnchorSummary(currentTrace));
            TraceStore.put(PREFIX + "anchorStack", anchorStackSummary(currentTrace));
            HistoryContaminationSignal historySignal = historyContaminationSignal(snapshotTrace(), 0.35d);
            double historyScore = historySignal.score();
            double requeryNeed = asDouble(snapshot.matrix().get("q_requery_correction_need"), 0.0d);
            TraceStore.put(PREFIX + "historyContaminationScore", round4(historyScore));
            TraceStore.put(PREFIX + "historySignalCount", Math.max(0, historySignal.count()));
            TraceStore.put(PREFIX + "historyCorrectionAction",
                    historyScore >= 0.35d || requeryNeed >= 0.35d ? "history_context_requery" : "observe");
            TraceStore.put(PREFIX + "projectionVersion", "blackbox-risk-v2");
            writeKgNeo4jRiskAliases(currentTrace);
            writeWhere(where);
        } catch (Throwable t) {
            traceSkipped("risk_trace_projection", where, t);
        }
    }

    private static boolean hasProjection(Map<String, Object> trace) {
        return trace != null
                && trace.containsKey(PREFIX + "riskScore")
                && trace.containsKey(PREFIX + "dominantFailure")
                && trace.containsKey(PREFIX + "restoreAction");
    }

    private static Snapshot snapshotFromTrace(Map<String, Object> trace) {
        Map<String, Object> safeTrace = trace == null ? Map.of() : trace;
        double riskScore = round4(asDouble(safeTrace.get(PREFIX + "riskScore"), 0.0d));
        double priorityScore = round4(asDouble(safeTrace.get(PREFIX + "priorityScore"), riskScore));
        boolean highRisk = safeTrace.containsKey(PREFIX + "highRisk")
                ? truthy(safeTrace.get(PREFIX + "highRisk"))
                : Math.max(riskScore, priorityScore) >= HIGH_RISK_THRESHOLD;
        return new Snapshot(
                riskScore,
                priorityScore,
                safeLabel(asString(safeTrace.get(PREFIX + "dominantFailure")), "none"),
                safeLabel(asString(safeTrace.get(PREFIX + "hotspot")), "none"),
                toStringObjectMap(safeTrace.get(PREFIX + "topContributor")),
                toPublicRows(safeTrace.get(PREFIX + "rank")),
                safeLabel(asString(safeTrace.get(PREFIX + "patternId")), ""),
                safeLabel(asString(safeTrace.get(PREFIX + "restoreAction")), "observe_only"),
                round4(asDouble(safeTrace.get(PREFIX + "confidence"), 0.0d)),
                safePublicLabel(asString(safeTrace.get(PREFIX + "decisionReason")), "existing_projection"),
                toStringObjectMap(safeTrace.get(PREFIX + "matrix")),
                safeLabel(asString(safeTrace.get(PREFIX + "vectorDecision")), "SHADOW_REVIEW").toUpperCase(Locale.ROOT),
                highRisk);
    }

    private static void writeWhere(String where) {
        String label = safeLabel(where, "");
        TraceStore.putIfAbsent(PREFIX + "firstWhere", label);
        TraceStore.put(PREFIX + "lastWhere", label);
        TraceStore.put(PREFIX + "where", label);
    }

    private static Map<String, Object> toStringObjectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String publicKey = SafeRedactor.traceLabelOrFallback(key, "field");
            out.put(publicKey, SafeRedactor.diagnosticValue(key, entry.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<Map<String, Object>> toPublicRows(Object raw) {
        if (!(raw instanceof Iterable<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : rows) {
            Map<String, Object> mapped = toStringObjectMap(row);
            if (!mapped.isEmpty()) {
                out.add(mapped);
            }
        }
        return List.copyOf(out);
    }

    private void rememberVirtualPoint(Snapshot snapshot) {
        if (!virtualPointEnabled || snapshot == null || snapshot.matrix() == null || snapshot.matrix().isEmpty()) {
            return;
        }
        com.example.lms.learning.virtualpoint.VirtualPointService service =
                virtualPointServiceProvider == null ? null : virtualPointServiceProvider.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            float[] vector = vectorFromMatrix(snapshot.matrix());
            if (vector.length == 0) {
                return;
            }
            service.put("blackbox:" + snapshot.patternId(), new VirtualPoint(
                    vector,
                    snapshot.riskScore(),
                    snapshot.priorityScore(),
                    snapshot.dominantFailure(),
                    snapshot.restoreAction(),
                    snapshot.patternId(),
                    System.currentTimeMillis()));
        } catch (Exception e) {
            traceSkipped("virtual_point_write", "blackbox", e);
        }
    }

    private Snapshot applyVirtualPointPrior(Snapshot snapshot) {
        if (!virtualPointEnabled || snapshot == null || snapshot.matrix() == null || snapshot.matrix().isEmpty()) {
            return snapshot;
        }
        if (!historyCorrectionEnabled) {
            traceVirtualPointPrior(false, 0.0d, "", false, "history_correction_disabled");
            return snapshot;
        }
        Map<String, Object> currentTrace = snapshotTrace();
        double currentSignalThreshold = clamp01(historyContaminationThreshold);
        HistoryContaminationSignal historySignal = historyContaminationSignal(currentTrace, currentSignalThreshold);
        traceVirtualPointCurrentSignal(historyCorrectionRequireCurrentSignal, historySignal.currentSignal(),
                historySignal.score());
        if (historyCorrectionRequireCurrentSignal && !historySignal.currentSignal()) {
            traceVirtualPointPrior(false, 0.0d, "", false, "current_signal_required");
            return snapshot;
        }
        com.example.lms.learning.virtualpoint.VirtualPointService service =
                virtualPointServiceProvider == null ? null : virtualPointServiceProvider.getIfAvailable();
        if (service == null) {
            return snapshot;
        }
        try {
            float[] vector = vectorFromMatrix(snapshot.matrix());
            if (vector.length == 0) {
                traceVirtualPointPrior(false, 0.0d, "", false, "empty_vector");
                return snapshot;
            }
            var match = service.nearest(vector, virtualPointMinSimilarity);
            if (match.isEmpty()) {
                traceVirtualPointPrior(false, 0.0d, "", false, "no_prior_match");
                return snapshot;
            }
            com.example.lms.learning.virtualpoint.VirtualPointService.Match prior = match.get();
            VirtualPoint point = prior.point();
            double priorRisk = Math.max(point.riskScore, point.priorityScore);
            double applyThreshold = Math.max(0.0d, historyCorrectionApplyThreshold);
            if (priorRisk < applyThreshold) {
                traceVirtualPointPrior(true, prior.similarity(), point.patternId, false,
                        "prior_below_threshold", point);
                return snapshot;
            }
            if ("none".equals(point.dominantFailure) && "observe_only".equals(point.restoreAction)) {
                traceVirtualPointPrior(true, prior.similarity(), point.patternId, false,
                        "prior_observe_only", point);
                return snapshot;
            }

            double currentRisk = Math.max(snapshot.riskScore(), snapshot.priorityScore());
            double boostedRisk = round4(Math.max(snapshot.riskScore(), priorRisk * prior.similarity() * 0.90d));
            double boostedPriority = round4(Math.max(snapshot.priorityScore(), priorRisk * prior.similarity()));
            boolean applyPriorLabel = currentRisk < HIGH_RISK_THRESHOLD || "none".equals(snapshot.dominantFailure());
            String dominantFailure = applyPriorLabel ? point.dominantFailure : snapshot.dominantFailure();
            String restoreAction = applyPriorLabel || "observe_only".equals(snapshot.restoreAction())
                    ? point.restoreAction
                    : snapshot.restoreAction();
            if (restoreAction == null || restoreAction.isBlank() || "none".equals(restoreAction)) {
                restoreAction = restoreActionFor(dominantFailure);
            }
            boolean highRisk = Math.max(boostedRisk, boostedPriority) >= applyThreshold;
            if (!highRisk) {
                traceVirtualPointPrior(true, prior.similarity(), point.patternId, false,
                        "boost_below_threshold", point);
                return snapshot;
            }

            Map<String, Object> topContributor = virtualPointContributor(snapshot, point, prior.similarity(), boostedRisk, boostedPriority);
            List<Map<String, Object>> rank = mergePriorRank(topContributor, snapshot.rank());
            Map<String, Object> matrix = updateMatrixRisk(snapshot.matrix(), boostedRisk);
            String vectorDecision = ("vector_quarantine".equals(restoreAction) || "safe_path_bypass".equals(restoreAction))
                    ? "QUARANTINE"
                    : snapshot.vectorDecision();
            if ("SHADOW_REVIEW".equals(vectorDecision) && highRisk) {
                vectorDecision = "QUARANTINE";
            }
            traceVirtualPointPrior(true, prior.similarity(), point.patternId, true,
                    "virtual_point_prior", point);
            return new Snapshot(
                    boostedRisk,
                    boostedPriority,
                    safeLabel(dominantFailure, "none"),
                    snapshot.hotspot(),
                    topContributor,
                    rank,
                    snapshot.patternId(),
                    safeLabel(restoreAction, restoreActionFor(dominantFailure)),
                    snapshot.confidence(),
                    "virtual_point_prior",
                    matrix,
                    vectorDecision,
                    true);
        } catch (Exception e) {
            traceSkipped("virtual_point_prior_lookup", snapshot.dominantFailure(), e);
            traceVirtualPointPrior(false, 0.0d, "", false, "prior_lookup_failed");
            return snapshot;
        }
    }

    private GraphRecommendation recommendFromGraph(Snapshot snapshot) {
        if (!graphEnabled) {
            return GraphRecommendation.empty("disabled");
        }
        if (snapshot == null || "none".equals(snapshot.dominantFailure())) {
            return GraphRecommendation.empty("no_current_failure");
        }
        Neo4jKnowledgeGraphClient client = graphClientProvider == null ? null : graphClientProvider.getIfAvailable();
        if (client == null) {
            return GraphRecommendation.empty("missing_neo4j_client");
        }
        String disabledReason = client.disabledReason();
        if (disabledReason != null) {
            return GraphRecommendation.empty(disabledReason);
        }
        try {
            Map<String, Object> trace = snapshotTrace();
            double jb = jbPressure(trace);
            double cb = cbPressure(trace);
            int matrixTile = matrixTile(trace, snapshot.matrix());
            List<RecoveryRecommendation> recommendations = client.recommendRecovery(
                    snapshot.patternId(),
                    snapshot.dominantFailure(),
                    snapshot.hotspot(),
                    matrixTile,
                    jb,
                    cb,
                    Math.max(1, graphTopK));
            if (recommendations == null || recommendations.isEmpty()) {
                return GraphRecommendation.empty("no_graph_match");
            }
            RecoveryRecommendation top = recommendations.get(0);
            double confidence = clamp01(top.confidence());
            String action = safeLabel(top.restoreAction(), "observe_only");
            boolean applied = confidence >= clamp01(graphMinConfidence)
                    && !"observe_only".equals(action)
                    && ("observe_only".equals(snapshot.restoreAction())
                    || "none".equals(snapshot.dominantFailure())
                    || Math.max(snapshot.riskScore(), snapshot.priorityScore()) < HIGH_RISK_THRESHOLD);
            return new GraphRecommendation(
                    action,
                    safeLabel(top.patternId(), ""),
                    confidence,
                    top.reason() == null || top.reason().isBlank() ? "similar_failure_path" : top.reason().trim(),
                    applied);
        } catch (Exception e) {
            traceSkipped("graph_recommendation_lookup", snapshot.dominantFailure(), e);
            return GraphRecommendation.empty("lookup_failed");
        }
    }

    private Snapshot applyGraphRecommendation(Snapshot snapshot, GraphRecommendation recommendation) {
        if (snapshot == null || recommendation == null || !recommendation.applied()) {
            return snapshot;
        }
        String action = safeLabel(recommendation.restoreAction(), "observe_only");
        if ("observe_only".equals(action)) {
            return snapshot;
        }
        Map<String, Object> topContributor = new LinkedHashMap<>();
        topContributor.put("failureClass", snapshot.dominantFailure());
        topContributor.put("hotspot", snapshot.hotspot());
        topContributor.put("riskScore", snapshot.riskScore());
        topContributor.put("priorityScore", Math.max(snapshot.priorityScore(), recommendation.confidence()));
        topContributor.put("similarity", recommendation.confidence());
        topContributor.put("priorPatternId", recommendation.patternId());
        topContributor.put("restoreAction", action);
        topContributor.put("reason", "graph_recommendation");
        List<Map<String, Object>> rank = mergePriorRank(Collections.unmodifiableMap(topContributor), snapshot.rank());
        boolean highRisk = Math.max(snapshot.riskScore(), recommendation.confidence()) >= HIGH_RISK_THRESHOLD;
        return new Snapshot(
                snapshot.riskScore(),
                round4(Math.max(snapshot.priorityScore(), recommendation.confidence())),
                snapshot.dominantFailure(),
                snapshot.hotspot(),
                Collections.unmodifiableMap(topContributor),
                rank,
                snapshot.patternId(),
                action,
                Math.max(snapshot.confidence(), recommendation.confidence()),
                "graph_recommendation",
                snapshot.matrix(),
                "vector_quarantine".equals(action) || "safe_path_bypass".equals(action)
                        ? "QUARANTINE"
                        : snapshot.vectorDecision(),
                highRisk || snapshot.highRisk());
    }

    private void writeGraphRecommendationTrace(GraphRecommendation recommendation) {
        try {
            GraphRecommendation rec = recommendation == null ? GraphRecommendation.empty("none") : recommendation;
            TraceStore.put(PREFIX + "graphRecommendation.enabled", graphEnabled);
            TraceStore.put(PREFIX + "graphRecommendation.restoreAction", rec.restoreAction());
            TraceStore.put(PREFIX + "graphRecommendation.patternId", rec.patternId());
            TraceStore.put(PREFIX + "graphRecommendation.confidence", round4(rec.confidence()));
            TraceStore.put(PREFIX + "graphRecommendation.reason", SafeRedactor.traceLabelOrFallback(rec.reason(), ""));
            TraceStore.put(PREFIX + "graphRecommendation.applied", rec.applied());
        } catch (Throwable t) {
            traceSkipped("graph_recommendation_trace", "graph", t);
        }
    }

    private void rememberGraph(Snapshot snapshot) {
        if (!graphEnabled || snapshot == null || "none".equals(snapshot.dominantFailure())) {
            return;
        }
        Neo4jKnowledgeGraphClient client = graphClientProvider == null ? null : graphClientProvider.getIfAvailable();
        if (client == null || client.disabledReason() != null) {
            return;
        }
        try {
            Map<String, Object> trace = snapshotTrace();
            client.upsertFailurePattern(
                    snapshot.patternId(),
                    snapshot.dominantFailure(),
                    snapshot.hotspot(),
                    snapshot.restoreAction(),
                    snapshot.confidence(),
                    snapshot.riskScore(),
                    matrixTile(trace, snapshot.matrix()),
                    jbPressure(trace),
                    cbPressure(trace));
        } catch (Exception e) {
            traceSkipped("graph_persistence", snapshot.dominantFailure(), e);
        }
    }

    private static double jbPressure(Map<String, Object> trace) {
        double score = 0.0d;
        if (truthyAny(trace, "finalSigmoidGate.failed", "gate.final.failed", "citation.gate.failed")) {
            score = Math.max(score, 0.85d);
        }
        if (maxDouble(trace, "gate.failure.count", "citation.gate.failed.count", "finalSigmoidGate.failed.count") > 0.0d) {
            score = Math.max(score, 0.80d);
        }
        score = Math.max(score, anchorPressure(trace, "gate", "evidence", "citation", "model_guard"));
        score = Math.max(score, probabilityPressure(trace, "evidence", "citation", "final", "model_guard"));
        return round4(score);
    }

    private static double cbPressure(Map<String, Object> trace) {
        double score = 0.0d;
        if (truthyAny(trace, "web.naver.providerDisabled", "web.brave.providerDisabled", "web.serpapi.providerDisabled", "web.tavily.providerDisabled")
                || listLikePresent(trace == null ? null : trace.get("providerDisabledSignals"))
                || listLikePresent(trace == null ? null : trace.get("rag.eval.providerDisabledSignals"))) {
            score = Math.max(score, 0.90d);
        }
        if (afterFilterStarved(trace)) {
            score = Math.max(score, 0.80d);
        }
        if (hasContaminationSignal(trace)) {
            score = Math.max(score, 0.78d);
        }
        if (maxDouble(trace,
                "web.await.events.timeout.count",
                "web.await.events.rateLimit.count",
                "web.failsoft.rateLimitBackoff.skipped.cooldown.count") > 0.0d) {
            score = Math.max(score, 0.70d);
        }
        score = Math.max(score, anchorPressure(trace, "provider", "disabled", "timeout", "rate", "context", "vector"));
        score = Math.max(score, probabilityPressure(trace, "provider", "disabled", "timeout", "rate", "context", "vector"));
        return round4(score);
    }

    private static Map<String, Object> traceAnchorSummary(Map<String, Object> trace) {
        Map<String, Object> top = traceAnchorTop(trace);
        if (top.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("anchorHash", safeLabel(asString(top.get("anchorHash")), ""));
        safe.put("evidenceDigestHash", safeLabel(asString(top.get("evidenceDigestHash")), ""));
        safe.put("matrixTile", Math.max(0L, toLong(top.get("matrixTile"))));
        safe.put("delta", round4(asDouble(top.get("delta"), 0.0d)));
        safe.put("p", round4(asDouble(top.get("p"), 0.0d)));
        safe.put("expectedDelta", round4(asDouble(top.get("expectedDelta"), 0.0d)));
        safe.put("component", safeLabel(asString(top.get("component")), "unknown"));
        safe.put("stage", safeLabel(asString(top.get("stage")), "unknown"));
        safe.put("lane", safeLabel(asString(top.get("lane")), "unknown"));
        safe.put("routeHint", safeLabel(asString(top.get("routeHint")), ""));
        safe.put("routeCorrectionNeed", round4(traceAnchorRouteCorrectionNeed(trace)));
        return Collections.unmodifiableMap(safe);
    }

    private static List<Map<String, Object>> anchorStackSummary(Map<String, Object> trace) {
        List<Map<String, Object>> traceAnchors = traceAnchorRows(trace);
        if (!traceAnchors.isEmpty()) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : traceAnchors) {
                Map<String, Object> safe = new LinkedHashMap<>();
                safe.put("component", safeLabel(asString(row.get("component")), "unknown"));
                safe.put("stage", safeLabel(asString(row.get("stage")), "unknown"));
                safe.put("lane", safeLabel(asString(row.get("lane")), "unknown"));
                safe.put("p", round4(asDouble(row.get("p"), 0.0d)));
                safe.put("expectedDelta", round4(asDouble(row.get("expectedDelta"), 0.0d)));
                safe.put("anchorHash", safeLabel(asString(row.get("anchorHash")), ""));
                safe.put("evidenceDigestHash", safeLabel(asString(row.get("evidenceDigestHash")), ""));
                safe.put("matrixTile", Math.max(0L, toLong(row.get("matrixTile"))));
                safe.put("routeHint", safeLabel(asString(row.get("routeHint")), ""));
                out.add(Collections.unmodifiableMap(safe));
                if (out.size() >= 5) {
                    break;
                }
            }
            return List.copyOf(out);
        }
        Object raw = trace == null ? null : trace.get("ablation.anchor.primaryStack");
        if (!(raw instanceof Iterable<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("component", safeLabel(asString(map.get("component")), "unknown"));
            safe.put("stage", safeLabel(asString(map.get("stage")), "unknown"));
            safe.put("lane", safeLabel(asString(map.get("lane")), "unknown"));
            safe.put("eventCount", Math.max(0L, toLong(map.get("eventCount"))));
            safe.put("p", round4(asDouble(map.get("p"), 0.0d)));
            safe.put("expectedDelta", round4(asDouble(map.get("expectedDelta"), 0.0d)));
            safe.put("topGuard", safeLabel(asString(map.get("topGuard")), "unknown"));
            safe.put("topAnchorHash", safeLabel(asString(map.get("topAnchorHash")), ""));
            out.add(Collections.unmodifiableMap(safe));
            if (out.size() >= 5) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static double maxLossContribution(Map<String, Object> trace) {
        double out = 0.0d;
        out = Math.max(out, maxExpectedDelta(trace == null ? null : trace.get("ablation.traceAnchor.rows")));
        out = Math.max(out, maxExpectedDelta(trace == null ? null : trace.get("ablation.anchor.primaryStack")));
        out = Math.max(out, maxExpectedDelta(trace == null ? null : trace.get("ablation.byStep")));
        Object probabilities = trace == null ? null : trace.get("ablation.probabilities");
        if (probabilities instanceof Iterable<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    double delta = asDouble(map.get("delta"), 0.0d);
                    double p = asDouble(map.get("p"), 0.0d);
                    out = Math.max(out, clamp01(delta * p));
                }
            }
        }
        return round4(out);
    }

    private static double maxExpectedDelta(Object raw) {
        double out = 0.0d;
        if (!(raw instanceof Iterable<?> rows)) {
            return out;
        }
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) {
                out = Math.max(out, asDouble(map.get("expectedDelta"), 0.0d));
            }
        }
        return clamp01(out);
    }

    private static List<Map<String, Object>> traceAnchorRows(Map<String, Object> trace) {
        Object raw = trace == null ? null : trace.get("ablation.traceAnchor.rows");
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof Iterable<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    out.add(copyTraceAnchorRow(map));
                    if (out.size() >= 12) {
                        break;
                    }
                }
            }
        }
        if (out.isEmpty()) {
            Object top = trace == null ? null : trace.get("ablation.traceAnchor.top");
            if (top instanceof Map<?, ?> map) {
                out.add(copyTraceAnchorRow(map));
            }
        }
        out.sort(Comparator
                .comparingDouble((Map<String, Object> row) -> -asDouble(row.get("expectedDelta"), 0.0d))
                .thenComparing(row -> String.valueOf(row.get("component")))
                .thenComparing(row -> String.valueOf(row.get("stage"))));
        return List.copyOf(out);
    }

    private static Map<String, Object> copyTraceAnchorRow(Map<?, ?> map) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("eventId", "traceIdHash", "requestIdHash", "sessionIdHash",
                "anchorHash", "anchorLen", "evidenceDigestHash", "matrixTile", "delta", "p",
                "expectedDelta", "component", "stage", "lane", "routeHint")) {
            if (map.containsKey(key)) {
                safe.put(key, map.get(key));
            }
        }
        return safe;
    }

    private static Map<String, Object> traceAnchorTop(Map<String, Object> trace) {
        List<Map<String, Object>> rows = traceAnchorRows(trace);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private static double maxTraceAnchorExpectedDelta(Map<String, Object> trace) {
        double out = maxDouble(trace,
                "ablation.traceAnchor.maxExpectedDelta",
                "ablation.traceAnchor.drop.max",
                "q_anchor_drop_pressure");
        out = Math.max(out, maxExpectedDelta(trace == null ? null : trace.get("ablation.traceAnchor.rows")));
        return round4(clamp01(out));
    }

    private static double maxTraceAnchorP(Map<String, Object> trace) {
        double out = maxDouble(trace, "ablation.traceAnchor.maxP", "q_anchor_lane_pressure");
        for (Map<String, Object> row : traceAnchorRows(trace)) {
            out = Math.max(out, asDouble(row.get("p"), 0.0d));
        }
        return round4(clamp01(out));
    }

    private static double traceAnchorRouteCorrectionNeed(Map<String, Object> trace) {
        double out = maxDouble(trace, "ablation.traceAnchor.routeCorrectionNeed", "q_route_correction_need");
        out = Math.max(out, maxTraceAnchorExpectedDelta(trace));
        return round4(clamp01(out));
    }

    private static String traceAnchorFailure(String routeHint, String component, String context) {
        String hint = safeLabel(routeHint, "").toLowerCase(Locale.ROOT);
        String comp = safeLabel(component, "").toLowerCase(Locale.ROOT);
        String text = (context == null ? "" : context).toLowerCase(Locale.ROOT);
        if ("brave_mode".equals(hint)) {
            return "drop_bottleneck";
        }
        if ("fail_soft_fallback".equals(hint)) {
            if ("web".equals(comp) || text.contains("provider") || text.contains("web")) {
                return "provider_disabled";
            }
            if (text.contains("model")) {
                return "model_required";
            }
            if (text.contains("timeout")) {
                return "timeout";
            }
            return "silent_failure";
        }
        if ("vector".equals(comp) || "memory".equals(comp) || text.contains("context")) {
            return "context_contamination";
        }
        return "after_filter_starvation";
    }

    private static String traceAnchorRouteAction(String routeHint, String failure) {
        String hint = safeLabel(routeHint, "").toLowerCase(Locale.ROOT);
        if ("brave_mode".equals(hint)) {
            return "brave_mode";
        }
        if ("fail_soft_fallback".equals(hint)) {
            return "fail_soft_fallback".equals(restoreActionFor(failure))
                    ? "fail_soft_fallback"
                    : restoreActionFor(failure);
        }
        if ("recovery".equals(hint)) {
            return restoreActionFor(failure);
        }
        return restoreActionFor(failure);
    }

    private static double anchorPressure(Map<String, Object> trace, String... needles) {
        Object raw = trace == null ? null : trace.get("ablation.anchor.secondaryStack");
        if (!(raw instanceof Iterable<?> rows)) {
            return 0.0d;
        }
        double score = 0.0d;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            String haystack = (asString(map.get("component")) + " " + asString(map.get("step")) + " "
                    + asString(map.get("guard"))).toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (needle != null && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                    score = Math.max(score, clamp01(asDouble(map.get("expectedDelta"), 0.0d) * 4.0d));
                }
            }
        }
        return score;
    }

    private static double probabilityPressure(Map<String, Object> trace, String... needles) {
        Object raw = trace == null ? null : trace.get("ablation.probabilities");
        if (!(raw instanceof Iterable<?> rows)) {
            return 0.0d;
        }
        double score = 0.0d;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            String haystack = (asString(map.get("step")) + " " + asString(map.get("guard"))).toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (needle != null && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                    score = Math.max(score, clamp01(asDouble(map.get("p"), 0.0d)));
                }
            }
        }
        return score;
    }

    private static int matrixTile(Map<String, Object> trace, Map<String, Object> matrix) {
        int tile = parseTile(trace == null ? null : trace.get("cfvm.kalloc.tile"));
        if (tile > 0) {
            return tile;
        }
        Object events = trace == null ? null : trace.get("selfask.branchQuality.events");
        if (events instanceof Iterable<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    tile = parseTile(map.get("matrixTile"));
                    if (tile > 0) {
                        return tile;
                    }
                }
            }
        }
        if (matrix == null || matrix.isEmpty()) {
            return 0;
        }
        double web = asDouble(matrix.get("m1_source_mix_web"), 0.0d);
        double vector = asDouble(matrix.get("m1_source_mix_vector"), 0.0d);
        double kg = asDouble(matrix.get("m1_source_mix_kg"), 0.0d);
        int axis = web >= vector && web >= kg ? 0 : (vector >= kg ? 1 : 2);
        double risk = asDouble(matrix.get("m7_risk"), 0.0d);
        int band = risk >= 0.66d ? 0 : (risk >= 0.33d ? 1 : 2);
        return (axis * 3) + band + 1;
    }

    private static int parseTile(Object raw) {
        if (raw instanceof Number n) {
            return Math.max(0, Math.min(99, n.intValue()));
        }
        String s = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("cfvm9:t")) {
            s = s.substring("cfvm9:t".length());
        } else if (s.startsWith("t")) {
            s = s.substring(1);
        }
        try {
            return Math.max(0, Math.min(99, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            traceSkipped("parse_tile", "matrix_tile", e);
            return 0;
        }
    }

    private static float[] vectorFromMatrix(Map<String, Object> matrix) {
        if (matrix == null || matrix.isEmpty()) {
            return new float[0];
        }
        Object orderObj = matrix.get("_order");
        if (!(orderObj instanceof Iterable<?> order)) {
            return new float[0];
        }
        List<Float> values = new ArrayList<>();
        for (Object key : order) {
            String label = String.valueOf(key);
            // Risk is stored as metadata; matching on it would block preemptive recall.
            double value = "m7_risk".equals(label) ? 0.0d : asDouble(matrix.get(label), 0.0d);
            values.add((float) value);
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i);
        }
        return vector;
    }

    private static Map<String, Object> virtualPointContributor(Snapshot snapshot,
                                                               VirtualPoint point,
                                                               double similarity,
                                                               double risk,
                                                               double priorityScore) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("failureClass", safeLabel(point.dominantFailure, snapshot == null ? "none" : snapshot.dominantFailure()));
        out.put("hotspot", snapshot == null ? "virtual_point" : snapshot.hotspot());
        out.put("riskScore", risk);
        out.put("priorityScore", priorityScore);
        out.put("similarity", round4(similarity));
        out.put("priorPatternId", safeLabel(point.patternId, ""));
        out.put("restoreAction", safeLabel(point.restoreAction, "observe_only"));
        out.put("reason", "virtual_point_prior");
        return Collections.unmodifiableMap(out);
    }

    private static List<Map<String, Object>> mergePriorRank(Map<String, Object> priorRow,
                                                            List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (priorRow != null && !priorRow.isEmpty()) {
            out.add(priorRow);
        }
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                if (row != null && !row.isEmpty()) {
                    out.add(row);
                }
                if (out.size() >= 9) {
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> updateMatrixRisk(Map<String, Object> matrix, double risk) {
        if (matrix == null || matrix.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(matrix);
        out.put("m7_risk", round4(risk));
        return Collections.unmodifiableMap(out);
    }

    private static long virtualPointAgeMs(VirtualPoint point, long nowMs) {
        if (point == null || point.seenAtMs <= 0L || nowMs < point.seenAtMs) {
            return -1L;
        }
        return nowMs - point.seenAtMs;
    }

    private static void traceVirtualPointPrior(boolean matched,
                                               double similarity,
                                               String priorPatternId,
                                               boolean applied,
                                               String reason) {
        traceVirtualPointPrior(matched, similarity, priorPatternId, applied, reason, null);
    }

    private static void traceVirtualPointPrior(boolean matched,
                                               double similarity,
                                               String priorPatternId,
                                               boolean applied,
                                               String reason,
                                               VirtualPoint point) {
        try {
            TraceStore.put(PREFIX + "virtualPoint.matched", matched);
            TraceStore.put(PREFIX + "virtualPoint.similarity", round4(similarity));
            TraceStore.put(PREFIX + "virtualPoint.priorPatternId", safeLabel(priorPatternId, ""));
            TraceStore.put(PREFIX + "virtualPoint.applied", applied);
            TraceStore.put(PREFIX + "virtualPoint.reason", safePublicLabel(reason, "none"));
            if (point != null) {
                long ageMs = virtualPointAgeMs(point, System.currentTimeMillis());
                TraceStore.put(PREFIX + "virtualPoint.ageKnown", ageMs >= 0L);
                TraceStore.put(PREFIX + "virtualPoint.ageMs", ageMs);
                TraceStore.put(PREFIX + "virtualPoint.freshnessDisposition", "shadow_uncalibrated");
                TraceStore.put(PREFIX + "virtualPoint.decisionAuthority",
                        applied ? "legacy_applied" : "not_applied");
            } else {
                TraceStore.put(PREFIX + "virtualPoint.ageKnown", null);
                TraceStore.put(PREFIX + "virtualPoint.ageMs", null);
                TraceStore.put(PREFIX + "virtualPoint.freshnessDisposition", null);
                TraceStore.put(PREFIX + "virtualPoint.decisionAuthority", null);
            }
        } catch (Throwable t) {
            traceSkipped("virtual_point_prior_trace", reason, t);
        }
    }

    private static void traceVirtualPointCurrentSignal(boolean required, boolean present, double score) {
        try {
            TraceStore.put(PREFIX + "virtualPoint.currentSignalRequired", required);
            TraceStore.put(PREFIX + "virtualPoint.currentSignalPresent", present);
            TraceStore.put(PREFIX + "virtualPoint.currentSignalScore", round4(score));
        } catch (Throwable t) {
            traceSkipped("virtual_point_current_signal_trace", "current_signal", t);
        }
    }

    private void emitDebug(Snapshot snapshot, String where) {
        DebugEventStore store = debugEventStoreProvider == null ? null : debugEventStoreProvider.getIfAvailable();
        if (store == null || snapshot == null || "none".equals(snapshot.dominantFailure())) {
            return;
        }
        try {
            Object previous = TraceStore.putIfAbsent(PREFIX + "debugEmitted", Boolean.TRUE);
            if (previous != null) {
                return;
            }
        } catch (Throwable t) {
            traceSkipped("debug_emit_rate_limit", where, t);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("failureClass", snapshot.dominantFailure());
        data.put("hotspot", snapshot.hotspot());
        data.put("patternId", snapshot.patternId());
        data.put("riskScore", snapshot.riskScore());
        data.put("priorityScore", snapshot.priorityScore());
        data.put("contributionTop", snapshot.topContributor());
        data.put("restoreAction", snapshot.restoreAction());
        data.put("decisionReason", SafeRedactor.traceLabelOrFallback(snapshot.decisionReason(), "unknown"));
        data.put("traceAnchor", traceAnchorSummary(snapshotTrace()));
        data.put("firstWhere", SafeRedactor.diagnosticValue(PREFIX + "firstWhere",
                TraceStore.get(PREFIX + "firstWhere")));
        data.put("lastWhere", safeLabel(where, ""));
        store.emit(
                DebugProbeType.ORCHESTRATION,
                snapshot.highRisk() ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                "rag_blackbox:" + snapshot.dominantFailure() + ":" + snapshot.restoreAction(),
                "[AWX2AF2][rag][blackbox] risk projection",
                "RagFailureBlackboxService.refresh",
                data,
                null);
    }

    private static List<Map<String, Object>> rankedRows(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Candidate c : candidates) {
            out.add(c.toPublicMap());
            if (out.size() >= 9) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> matrixSnapshot(Map<String, Object> trace, double riskScore) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source.web.count", sourceCount(trace, "WEB",
                "webSearch.returnedCount", "web.naver.returnedCount", "web.brave.returnedCount",
                "web.serpapi.returnedCount", "web.tavily.returnedCount"));
        summary.put("source.vector.count", sourceCount(trace, "VECTOR",
                "vector.returnedCount", "rag.vector.count", "retrieval.vector.k", "cfvm.kalloc.plan.vectorK"));
        summary.put("source.kg.count", sourceCount(trace, "KG",
                "kg.returnedCount", "rag.kg.count", "retrieval.kg.k", "cfvm.kalloc.plan.kgK"));
        summary.put("authority.avg", maxDouble(trace, "overdrive.authority.avg", "authority.avg"));
        summary.put("novelty.avg", maxDouble(trace, "m3_novelty", "novelty.avg"));
        summary.put("contradiction.score", maxDouble(trace, "overdrive.contradiction.mean", "extremez.risk.contradictionMean"));
        summary.put("reranker.cost", maxDouble(trace, "reranker.cost", "onnx.rerank.tookMs"));
        summary.put("loss.contribution", maxLossContribution(trace));
        summary.put("risk.score", riskScore);
        summary.put("ablation.traceAnchor.maxExpectedDelta", maxTraceAnchorExpectedDelta(trace));
        summary.put("ablation.traceAnchor.maxP", maxTraceAnchorP(trace));
        summary.put("ablation.traceAnchor.routeCorrectionNeed", traceAnchorRouteCorrectionNeed(trace));
        summary.put("q_anchor_drop_pressure", maxTraceAnchorExpectedDelta(trace));
        summary.put("q_anchor_lane_pressure", maxTraceAnchorP(trace));
        summary.put("q_route_correction_need", traceAnchorRouteCorrectionNeed(trace));
        boolean gpuGatewayBlocked = gpuGatewayBlocked(trace);
        long gpuGatewayBlockedCount = gpuGatewayBlocked
                ? Math.max(1L, maxLong(trace, "uaw.gpu-gateway.admission.blocked.count"))
                : maxLong(trace, "gpu.gateway.unreachable.count");
        boolean gpuHardwareBlocked = gpuHardwareAdmissionBlocked(trace);
        double gpuHardwarePressure = gpuHardwareAdmissionPressure(trace);
        summary.put("uaw.gpu-gateway.admission.blocked", gpuGatewayBlocked);
        summary.put("uaw.gpu-gateway.admission.blocked.count", gpuGatewayBlockedCount);
        summary.put("gpu.gateway.unreachable.count", gpuGatewayBlockedCount);
        summary.put("q_gpu_gateway_pressure", gpuGatewayBlocked ? 1.0d : maxDouble(trace,
                "q_gpu_gateway_pressure",
                "gpu.gateway.pressure",
                "gpu.gateway.unreachable.pressure"));
        summary.put("uaw.gpu-hardware.admission.blocked", gpuHardwareBlocked);
        summary.put("uaw.gpu-hardware.admission.retrainBlocked",
                truthyAny(trace, "uaw.gpu-hardware.admission.retrainBlocked"));
        summary.put("uaw.gpu-hardware.admission.pressure", gpuHardwarePressure);
        summary.put("q_gpu_hardware_pressure", gpuHardwarePressure);
        KgNeo4jDegradationSignal kgNeo4j = kgNeo4jDegradationSignal(trace);
        summary.put("q_kg_degradation_pressure", kgNeo4j.pressure());
        summary.put("q_graph_dependency_pressure", kgNeo4j.pressure());
        summary.put("latency.ms", maxDouble(trace, "request.latency.ms", "rag.latency.ms", "latency.ms"));
        summary.put("budget.usage", maxDouble(trace, "budget.usage", "timeBudget.usage"));
        summary.put("rag.eval.normalized.balancedScore", Math.max(
                nestedDouble(trace, "rag.eval.normalized", "balancedScore"),
                maxDouble(trace, "rag.eval.normalized.balancedScore", "rag.normalized.balancedScore")));
        summary.put("provider.disabled.count", signalCount(trace,
                "providerDisabledSignals", "rag.eval.providerDisabledSignals"));
        summary.put("zero.result.count", signalCount(trace,
                "zeroResultSignals", "rag.eval.zeroResultSignals"));
        summary.put("after.filter.starvation.count", signalCount(trace,
                "afterFilterStarvationSignals", "rag.eval.afterFilterStarvationSignals"));
        if (truthyAny(trace, "web.naver.providerDisabled", "web.brave.providerDisabled", "web.serpapi.providerDisabled", "web.tavily.providerDisabled")) {
            summary.put("provider.disabled.count", Math.max(1, toLong(summary.get("provider.disabled.count"))));
        }
        if (truthyAny(trace, "web.naver.zeroResults", "web.brave.zeroResults", "web.serpapi.zeroResults", "web.tavily.zeroResults")) {
            summary.put("zero.result.count", Math.max(1, toLong(summary.get("zero.result.count"))));
        }
        if (afterFilterStarved(trace)) {
            summary.put("after.filter.starvation.count", Math.max(1, toLong(summary.get("after.filter.starvation.count"))));
        }
        summary.put("timeout.count", signalCount(trace,
                "web.await.events.timeout.count", "web.timeout", "web.naver.timeout", "web.brave.timeout",
                "web.serpapi.timeout", "web.tavily.timeout"));
        summary.put("cancelled.count", signalCount(trace,
                "web.naver.cancelled", "web.brave.cancelled", "web.serpapi.cancelled", "web.tavily.cancelled",
                "web.failsoft.cancelled.naver.count", "web.failsoft.cancelled.brave.count"));
        summary.put("rate.limit.count", signalCount(trace,
                "web.await.events.rateLimit.count", "web.failsoft.rateLimitBackoff.skipped.cooldown.count"));
        long llmRetryBudgetExceededCount = llmRetryBudgetExceeded(trace) ? Math.max(1L, maxLong(trace, "llm.retryBudget.exceeded.count")) : maxLong(trace, "llm.retryBudget.exceeded.count");
        summary.put("llm.retry.budget_exhausted.count", llmRetryBudgetExceededCount);
        summary.put("q_llm_upstream_pressure", llmRetryBudgetExceeded(trace) ? 1.0d : maxDouble(trace, "q_llm_upstream_pressure", "llm.upstream.pressure"));
        summary.put("overdrive.failsoft.count", truthy(trace.get("overdrive.narrow.failSoft")) ? 1 : 0);
        summary.put("gate.failure.count", signalCount(trace,
                "citation.gate.failed", "gate.final.failed", "finalSigmoidGate.failed"));
        if (evidencePromotionBlocked(trace)) {
            summary.put("gate.failure.count", Math.max(1L, toLong(summary.get("gate.failure.count"))));
        }
        summary.put("q_stage_drop", Math.max(
                Math.max(maxDouble(trace, "rag.eval.stageDrop.max", "stage.drop.max"),
                        maxMapDouble(trace.get("rag.eval.stageDrop"))),
                maxThresholdValue(trace, "stage_drop_high", "stageDrop")));
        if (trace != null && trace.containsKey("rag.eval.stageSourceDiversity")) {
            summary.put("q_stage_source_collapse",
                    maxStageSourceCollapse(trace.get("rag.eval.stageSourceDiversity")));
        }
        summary.put("compression.input.count", maxNumeric(trace, "overdrive.narrow.input.count"));
        summary.put("compression.output.count", maxNumeric(trace, "overdrive.narrow.output.count"));
        summary.put("overdrive.score", maxDouble(trace, "overdrive.score"));
        int learningContaminationSignals = learningContaminationSignals(trace);
        summary.put("learning.quarantine.count",
                "QUARANTINE".equalsIgnoreCase(asString(trace.get("learning.feedback.vectorDecision")))
                        && learningContaminationSignals > 0 ? 1 : 0);
        summary.put("learning.contamination.signals", learningContaminationSignals);
        HistoryContaminationSignal historySignal = historyContaminationSignal(trace, 0.35d);
        summary.put("history.contamination.score", historySignal.score());
        summary.put("history.contamination.signals", historySignal.count());
        summary.put("history.requery.correction.need", historySignal.currentSignal() ? historySignal.score() : 0.0d);
        return new MatrixTelemetryExtractor().extract(summary);
    }

    private static void writeKgNeo4jRiskAliases(Map<String, Object> trace) {
        KgNeo4jDegradationSignal signal = kgNeo4jDegradationSignal(trace);
        if (!signal.degraded()) {
            return;
        }
        TraceStore.put(PREFIX + "kgNeo4jDegraded", Boolean.TRUE);
        TraceStore.put(PREFIX + "kgNeo4jFailureClass", signal.failureClass());
    }

    private static long sourceCount(Map<String, Object> trace, String sourcePrefix, String... fallbackKeys) {
        long fromDiversity = sourceDiversityCount(trace, sourcePrefix);
        return fromDiversity > 0 ? fromDiversity : maxNumeric(trace, fallbackKeys);
    }

    private static long sourceDiversityCount(Map<String, Object> trace, String sourcePrefix) {
        Object raw = trace == null ? null : trace.get("rag.eval.sourceDiversity");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return 0L;
        }
        String prefix = safeLabel(sourcePrefix, "").toUpperCase(Locale.ROOT);
        long count = 0L;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String source = String.valueOf(entry.getKey()).trim().toUpperCase(Locale.ROOT);
            if (source.startsWith(prefix)) {
                count += Math.max(0L, toLong(entry.getValue()));
            }
        }
        return count;
    }

    private static double nestedDouble(Map<String, Object> trace, String mapKey, String valueKey) {
        if (trace == null || mapKey == null || valueKey == null) {
            return 0.0d;
        }
        Object nested = trace.get(mapKey);
        if (!(nested instanceof Map<?, ?> map)) {
            return 0.0d;
        }
        return asDouble(map.get(valueKey), 0.0d);
    }

    private static int signalCount(Map<String, Object> trace, String... keys) {
        int count = 0;
        if (trace == null || keys == null) {
            return count;
        }
        for (String key : keys) {
            count += valueCount(trace.get(key));
        }
        return count;
    }

    private static int valueCount(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty() ? 0 : map.size();
        }
        if (value instanceof Iterable<?> rows) {
            int count = 0;
            for (Object ignored : rows) {
                count++;
            }
            return count;
        }
        String s = String.valueOf(value).trim();
        return s.isBlank() || "[]".equals(s) || "{}".equals(s) || "null".equalsIgnoreCase(s) ? 0 : 1;
    }

    private static int learningContaminationSignals(Map<String, Object> trace) {
        Object value = trace == null ? null : trace.get("learning.validation.contaminationSignals");
        if (!(value instanceof Iterable<?> rows)) {
            return valueCount(value);
        }
        int count = 0;
        for (Object row : rows) {
            String label = String.valueOf(row);
            if (contains(label, "quarantine_vector_decision")) {
                continue;
            }
            if (!label.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static double maxThresholdValue(Map<String, Object> trace, String label, String metric) {
        Object raw = trace == null ? null : trace.get("rag.eval.thresholdBreaks");
        if (!(raw instanceof Iterable<?> rows)) {
            return 0.0d;
        }
        double max = 0.0d;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            String rowLabel = asString(map.get("label"));
            String rowMetric = asString(map.get("metric"));
            if (!label.equals(rowLabel) && !metric.equals(rowMetric)) {
                continue;
            }
            max = Math.max(max, asDouble(map.get("value"), 0.0d));
        }
        return max;
    }

    private static double maxMapDouble(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return 0.0d;
        }
        double max = 0.0d;
        for (Object value : map.values()) {
            max = Math.max(max, asDouble(value, 0.0d));
        }
        return max;
    }

    private static double maxStageSourceCollapse(Object raw) {
        if (!(raw instanceof Map<?, ?> stages) || stages.isEmpty()) {
            return 0.0d;
        }
        double max = 0.0d;
        for (Object value : stages.values()) {
            max = Math.max(max, sourceCollapse(value));
        }
        return clamp01(max);
    }

    private static double sourceCollapse(Object raw) {
        if (!(raw instanceof Map<?, ?> counts) || counts.isEmpty()) {
            return 0.0d;
        }
        double total = 0.0d;
        int distinct = 0;
        for (Object value : counts.values()) {
            double n = Math.max(0.0d, asDouble(value, 0.0d));
            if (n > 0.0d) {
                distinct++;
                total += n;
            }
        }
        if (total <= 0.0d) {
            return 0.0d;
        }
        if (distinct <= 1) {
            return 1.0d;
        }
        double sumSquares = 0.0d;
        for (Object value : counts.values()) {
            double p = Math.max(0.0d, asDouble(value, 0.0d)) / total;
            sumSquares += p * p;
        }
        double rawDiversity = 1.0d - sumSquares;
        double maxDiversity = 1.0d - (1.0d / distinct);
        double normalizedDiversity = maxDiversity <= 0.0d ? 0.0d : clamp01(rawDiversity / maxDiversity);
        return 1.0d - normalizedDiversity;
    }

    private static double confidence(Candidate top, int size) {
        if (top == null || top.risk() <= 0.0d) {
            return 0.0d;
        }
        return clamp01(0.50d + (top.probability() * 0.30d) + Math.min(0.20d, size * 0.025d));
    }

    private static String patternId(Map<String, Object> trace, String failureClass, String hotspot) {
        try {
            Map<String, Object> compact = new LinkedHashMap<>(trace == null ? Map.of() : trace);
            compact.put("learning.error.hotspot", safeLabel(hotspot, ""));
            compact.put("extremez.risk.primaryCause", safeLabel(failureClass, ""));
            return Long.toUnsignedString(RawSlotExtractor.patternIdFromTrace(compact));
        } catch (Throwable t) {
            traceSkipped("pattern_id", failureClass, t);
            return SafeRedactor.hash12(failureClass + "|" + hotspot);
        }
    }

    private static double recurrence(Map<String, Object> trace) {
        return recurrenceFor(trace,
                "ablation.events.count",
                "web.failsoft.starvationFallback.count",
                "starvationFallback.count",
                "web.await.events.count",
                "nightmare.silent.events");
    }

    private static double recurrenceFor(Map<String, Object> trace, String... keys) {
        long count = Math.max(1L, maxLong(trace, keys));
        return Math.min(1.50d, 1.0d + (Math.log1p(count) * 0.10d));
    }

    private static String classify(String text) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (s.contains("gpu_hardware") || s.contains("gpu hardware") || s.contains("hardware_admission")) return "gpu_hardware_pressure";
        if (s.contains("gpu_gateway") || s.contains("desktop_gpu") || s.contains("gpu gateway")) return "gpu_gateway_unreachable";
        if (s.contains("kg_neo4j") || s.contains("neo4j") || s.contains("graph_dependency")) return "kg_neo4j_degraded";
        if (s.contains("provider") || s.contains("disabled")) return "provider_disabled";
        if (s.contains("after_filter") || s.contains("starv") || s.contains("zero")) return "after_filter_starvation";
        if (s.contains("missing_future") || s.contains("future")) return "missing_future";
        if (s.contains("drop_bottleneck") || s.contains("stage_drop") || s.contains("anchor_drop")) return "drop_bottleneck";
        if (s.contains("model_guard") || s.contains("evidence_guard") || s.contains("citation")
                || s.contains("final") || s.contains("evidence") || s.contains("escalation")) return "evidence_gate";
        if (s.contains("modelrequired") || s.contains("model_required") || s.contains("model required")
                || s.contains("required_model") || s.contains("blank_model")) return "model_required";
        if (s.contains("contamination") || s.contains("poison") || s.contains("vector")) return "context_contamination";
        if (s.contains("timeout")) return "timeout";
        if (s.contains("rate")) return "rate_limit";
        if (s.contains("ocr") || s.contains("attachment") || s.contains("archive")) return "attachment_extraction_failed";
        if (s.contains("silent") || s.contains("fault") || s.contains("blank")) return "silent_failure";
        return "ablation_signal";
    }

    private static double severityFor(String failureClass) {
        return switch (safeLabel(failureClass, "")) {
            case "provider_disabled" -> 0.95d;
            case "kg_neo4j_degraded" -> 0.80d;
            case "gpu_hardware_pressure" -> 0.80d;
            case "gpu_gateway_unreachable" -> 0.82d;
            case "after_filter_starvation" -> 0.90d;
            case "missing_future" -> 0.92d;
            case "llm_upstream_retry_exhausted" -> 0.86d;
            case "model_required" -> 0.88d;
            case "context_contamination" -> 0.86d;
            case "silent_failure" -> 0.84d;
            case "cancelled", "timeout", "rate_limit" -> 0.72d;
            case "text_layer_empty_ocr_required" -> 0.34d;
            case "attachment_extraction_failed" -> 0.40d;
            case "archive_empty" -> 0.25d;
            case "drop_bottleneck" -> 0.74d;
            case "evidence_gate" -> 0.78d;
            default -> 0.60d;
        };
    }

    private static int priority(String failureClass) {
        return switch (safeLabel(failureClass, "")) {
            case "provider_disabled" -> 90;
            case "llm_upstream_retry_exhausted" -> 86;
            case "kg_neo4j_degraded" -> 82;
            case "missing_future" -> 85;
            case "model_required" -> 80;
            case "gpu_hardware_pressure" -> 79;
            case "gpu_gateway_unreachable" -> 78;
            case "after_filter_starvation" -> 75;
            case "context_contamination" -> 70;
            case "silent_failure" -> 65;
            case "evidence_gate" -> 60;
            case "drop_bottleneck" -> 55;
            case "cancelled", "timeout", "rate_limit" -> 50;
            case "text_layer_empty_ocr_required", "attachment_extraction_failed" -> 20;
            case "archive_empty" -> 15;
            default -> 10;
        };
    }

    public static String restoreActionFor(String failureClass) {
        return switch (safeLabel(failureClass, "")) {
            case "provider_disabled" -> "disable_provider_failsoft";
            case "kg_neo4j_degraded" -> "kg_dependency_fallback";
            case "gpu_hardware_pressure" -> "demote_heavy_work";
            case "gpu_gateway_unreachable" -> "cooldown_reorder";
            case "after_filter_starvation", "web_starvation" -> "anchor_compression_topup";
            case "missing_future" -> "web_await_bypass";
            case "llm_upstream_retry_exhausted" -> "llm_route_degrade";
            case "model_required" -> "llm_route_degrade";
            case "context_contamination", "vector_degraded" -> "vector_quarantine";
            case "cancelled", "timeout", "rate_limit" -> "cooldown_reorder";
            case "text_layer_empty_ocr_required", "archive_empty" -> "observe_only";
            case "attachment_extraction_failed" -> "attachment_failsoft_skip";
            case "evidence_gate" -> "evidence_gate_strict";
            case "drop_bottleneck" -> "brave_mode";
            case "silent_failure" -> "safe_path_bypass";
            default -> "observe_only";
        };
    }

    private static boolean silentFailureClass(String failureClass) {
        return switch (safeLabel(failureClass, "")) {
            case "missing_future", "llm_upstream_retry_exhausted", "model_required", "silent_failure",
                    "context_contamination" -> true;
            default -> false;
        };
    }

    private static KgNeo4jDegradationSignal kgNeo4jDegradationSignal(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return KgNeo4jDegradationSignal.none();
        }
        boolean failedSignal = signalContains(trace.get("rag.eval.kgAxis.signals"), "kg_neo4j_failed");
        boolean disabledSignal = signalContains(trace.get("rag.eval.kgAxis.signals"), "kg_neo4j_disabled");
        boolean degradedSignal = signalContains(trace.get("rag.eval.kgAxis.signals"), "kg_neo4j_degraded")
                || signalContains(trace.get("rag.eval.kgAxis.signals"), "kg_sparse_node_degraded");
        boolean sparseNodeFailedSignal = signalContains(trace.get("rag.eval.kgAxis.signals"), "kg_sparse_node_failed");
        boolean sparseNodeNoPathSignal = signalContains(trace.get("rag.eval.kgAxis.signals"), "kg_sparse_node_no_graph_path");
        String status = firstLabel(trace,
                "rag.eval.kgAxis.neo4jStatus",
                "rag.eval.kgAxis.sparseNodeStatus",
                "retrieval.kg.neo4j.status",
                "retrieval.dependency.kg.status");
        String reason = firstLabel(trace,
                "rag.eval.kgAxis.neo4jDisabledReason",
                "rag.eval.kgAxis.sparseNodeDisabledReason",
                "retrieval.kg.neo4j.disabledReason");
        String failureClass = firstLabel(trace,
                "rag.eval.kgAxis.neo4jFailureClass",
                "rag.eval.kgAxis.sparseNodeFailureClass",
                "retrieval.kg.neo4j.failureClass",
                "retrieval.dependency.kg.failureClass");
        boolean failed = failedSignal
                || sparseNodeFailedSignal
                || sparseNodeNoPathSignal
                || "failed".equals(status)
                || "no_graph_path".equals(status)
                || status.contains("failure")
                || reason.contains("failed")
                || reason.contains("no_graph_path")
                || meaningfulFailureClass(failureClass);
        boolean disabled = disabledSignal
                || "disabled".equals(status)
                || reason.contains("disabled")
                || reason.startsWith("missing_");
        boolean degraded = degradedSignal || failed || disabled || "degraded".equals(status);
        if (!degraded) {
            return KgNeo4jDegradationSignal.none();
        }
        String safeFailureClass = meaningfulFailureClass(failureClass)
                ? failureClass
                : (failed ? "failed" : (disabled ? "disabled" : "degraded"));
        String safeReason = firstNonBlankLabel(reason, status, safeFailureClass, "kg_neo4j_degraded");
        double pressure = failed || disabled ? 1.0d : 0.70d;
        return new KgNeo4jDegradationSignal(true, safeFailureClass, safeReason, pressure);
    }

    private static boolean meaningfulFailureClass(String value) {
        String label = safeLabel(value, "");
        return !label.isBlank()
                && !"none".equals(label)
                && !"ok".equals(label)
                && !"success".equals(label)
                && !"enabled".equals(label)
                && !"configured".equals(label);
    }

    private static boolean signalContains(Object raw, String expected) {
        String needle = safeLabel(expected, "");
        if (needle.isBlank()) {
            return false;
        }
        if (raw instanceof Iterable<?> rows) {
            for (Object row : rows) {
                if (safeLabel(asString(row), "").contains(needle)) {
                    return true;
                }
            }
            return false;
        }
        return safeLabel(asString(raw), "").contains(needle);
    }

    private static String firstLabel(Map<String, Object> trace, String... keys) {
        if (trace == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (trace.containsKey(key)) {
                String label = safeLabel(asString(trace.get(key)), "");
                if (!label.isBlank() && !"none".equals(label)) {
                    return label;
                }
            }
        }
        return "";
    }

    private static String firstNonBlankLabel(String... values) {
        if (values == null) {
            return "none";
        }
        for (String value : values) {
            String label = safeLabel(value, "");
            if (!label.isBlank() && !"none".equals(label)) {
                return label;
            }
        }
        return "none";
    }

    private static boolean afterFilterStarved(Map<String, Object> trace) {
        return positive(trace, "web.naver.filter.rawCount") && zeroPresent(trace, "web.naver.afterFilterCount")
                || positive(trace, "web.naver.returnedCount") && zeroPresent(trace, "web.naver.afterFilterCount")
                || positive(trace, "web.brave.returnedCount") && zeroPresent(trace, "web.brave.afterFilterCount")
                || positive(trace, "web.serpapi.returnedCount") && zeroPresent(trace, "web.serpapi.afterFilterCount")
                || positive(trace, "web.tavily.returnedCount") && zeroPresent(trace, "web.tavily.afterFilterCount")
                || positive(trace, "rag.returnedCount") && zeroPresent(trace, "rag.afterFilterCount")
                || positive(trace, "web.failsoft.starvationFallback.count")
                || positive(trace, "starvationFallback.count")
                || truthyAny(trace, "web.failsoft.starvationFallback.used", "starvationFallback.used");
    }

    private static boolean llmRetryBudgetExceeded(Map<String, Object> trace) {
        return truthyAny(trace, "llm.retryBudget.exceeded")
                || positive(trace, "llm.retryBudget.exceeded.count")
                || "upstream_5xx".equals(firstLabel(trace, "llm.retryBudget.code", "llm.error.code"))
                        && positive(trace, "llm.retryBudget.elapsedMs");
    }

    private static boolean evidencePromotionBlocked(Map<String, Object> trace) {
        String reason = firstLabel(trace, "rag.evidence.promotion.disabledReason");
        return "evidence_gate_blocked".equals(reason)
                || zeroPresent(trace, "rag.evidence.promotion.promotedCount")
                        && Boolean.FALSE.equals(trace == null ? null : trace.get("rag.evidence.promotion.evidenceGatePassed"));
    }

    private static boolean gpuGatewayBlocked(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        if (truthyAny(trace, "uaw.gpu-gateway.admission.blocked")) {
            return true;
        }
        String reason = safeLabel(asString(trace.get("uaw.idle.skip.reason")), "");
        if ("gpu_gateway_unreachable".equals(reason)) {
            return true;
        }
        String lastIdle = safeLabel(asString(trace.get("uaw.idle.last")), "");
        if ("uaw.idle.gpu-gateway.blocked".equals(lastIdle)) {
            return true;
        }
        String status = safeLabel(asString(trace.get("uaw.gpu-gateway.admission.status")), "");
        return List.of("unreachable", "partial", "missing_endpoint", "preflight_error",
                "timeout", "connection_failed", "dns_failed").contains(status);
    }

    private static boolean gpuHardwareAdmissionBlocked(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        if (truthyAny(trace, "uaw.gpu-hardware.admission.blocked")) {
            return true;
        }
        String status = safeLabel(asString(trace.get("uaw.gpu-hardware.admission.status")), "");
        String pressureLevel = safeLabel(asString(trace.get("uaw.gpu-hardware.admission.pressureLevel")), "");
        return "blocked".equals(status) || "block".equals(pressureLevel);
    }

    private static double gpuHardwareAdmissionPressure(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return 0.0d;
        }
        double explicit = maxDouble(trace,
                "q_gpu_hardware_pressure",
                "gpu.hardware.pressure",
                "uaw.gpu-hardware.admission.pressure");
        if (explicit > 0.0d) {
            return explicit;
        }
        if (gpuHardwareAdmissionBlocked(trace)) {
            return 1.0d;
        }
        String status = safeLabel(asString(trace.get("uaw.gpu-hardware.admission.status")), "");
        String pressureLevel = safeLabel(asString(trace.get("uaw.gpu-hardware.admission.pressureLevel")), "");
        if ("degraded".equals(status) || "warn".equals(pressureLevel)) {
            return 0.55d;
        }
        if (truthyAny(trace, "uaw.gpu-hardware.admission.retrainBlocked")) {
            return 0.55d;
        }
        if (explicitFalse(trace, "uaw.gpu-hardware.admission.retrainAllowed")
                || explicitFalse(trace, "uaw.gpu-hardware.admission.rerankAllowed")
                || explicitFalse(trace, "uaw.gpu-hardware.admission.embeddingFallbackAllowed")) {
            return 0.55d;
        }
        return 0.0d;
    }

    private static boolean explicitFalse(Map<String, Object> trace, String key) {
        return trace != null && trace.containsKey(key) && !truthy(trace.get(key));
    }

    private static String gpuHardwareDecisionReason(Map<String, Object> trace) {
        String reason = safeLabel(asString(trace == null ? null : trace.get("uaw.gpu-hardware.admission.reason")), "");
        if (!reason.isBlank() && !"ok".equals(reason)) {
            return "gpu_hardware_" + reason;
        }
        if (truthyAny(trace, "uaw.gpu-hardware.admission.retrainBlocked")) {
            return "gpu_hardware_retrain_demoted";
        }
        return "gpu_hardware_admission_pressure";
    }

    private static boolean hasContaminationSignal(Map<String, Object> trace) {
        if (historyContaminationSignal(trace, 0.35d).currentSignal()) {
            return true;
        }
        Object signals = trace.get("learning.validation.contaminationSignals");
        if (signals instanceof Iterable<?> it) {
            for (Object signal : it) {
                String label = String.valueOf(signal);
                if (contains(label, "quarantine_vector_decision")) {
                    continue;
                }
                if (contains(label, "contamination")
                        || contains(label, "poison")
                        || contains(label, "secret")) {
                    return true;
                }
            }
        }
        return maxDouble(trace, "learning.contextContaminationScore", "context_contamination_score") >= 0.35d;
    }

    private static HistoryContaminationSignal historyContaminationSignal(Map<String, Object> trace, double threshold) {
        if (trace == null || trace.isEmpty()) {
            return new HistoryContaminationSignal(0.0d, 0, false);
        }
        double floor = clamp01(threshold);
        int count = 0;
        double score = 0.0d;
        double contextScore = maxDouble(trace,
                "learning.validation.contextContaminationScore",
                "learning.contextContaminationScore",
                "context_contamination_score",
                "prompt.memory.compressor.contaminationScore");
        if (contextScore >= floor) {
            count++;
            score = Math.max(score, contextScore);
        }
        double legacyScore = maxDouble(trace, "learning.validation.legacyContextScore", "legacy_context_score");
        if (legacyScore > 0.40d) {
            count++;
            score = Math.max(score, clamp01(legacyScore * 0.90d));
        }
        if (truthyAny(trace, "prompt.builder.contaminationFlag")) {
            count++;
            score = Math.max(score, 0.65d);
        }
        String memoryReason = asString(trace.get("prompt.memory.compressor.reason")).toLowerCase(Locale.ROOT);
        if (truthyAny(trace, "prompt.memory.compressor.activated")
                && (contextScore >= floor || memoryReason.contains("contamination"))) {
            count++;
            score = Math.max(score, Math.max(floor, contextScore));
        }
        int signalCount = historySignalLabelCount(trace.get("learning.validation.contaminationSignals"));
        if (signalCount > 0) {
            count += signalCount;
            score = Math.max(score, Math.min(1.0d, 0.45d + (signalCount * 0.12d)));
        }
        return new HistoryContaminationSignal(round4(score), count, count > 0 && score >= floor);
    }

    private static int historySignalLabelCount(Object value) {
        if (!(value instanceof Iterable<?> rows)) {
            return 0;
        }
        int count = 0;
        for (Object row : rows) {
            String label = String.valueOf(row).toLowerCase(Locale.ROOT);
            if (label.contains("quarantine_vector_decision")) {
                continue;
            }
            if (label.contains("contamination")
                    || label.contains("poison")
                    || label.contains("secret")
                    || label.contains("legacy")
                    || label.contains("raw_trace")
                    || label.contains("low_diversity")
                    || label.contains("context")) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Object> snapshotTrace() {
        try {
            return TraceStore.getAll();
        } catch (Throwable t) {
            traceSkipped("snapshot_trace", "trace_store", t);
            return Map.of();
        }
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
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0.0d;
        String s = value == null ? "" : String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
    }

    private static boolean listLikePresent(Object value) {
        if (value instanceof Iterable<?> it) return it.iterator().hasNext();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        String s = value == null ? "" : String.valueOf(value).trim();
        return !s.isBlank() && !"[]".equals(s) && !"{}".equals(s) && !"null".equalsIgnoreCase(s);
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

    private static long maxNumeric(Map<String, Object> trace, String... keys) {
        return maxLong(trace, keys);
    }

    private static double maxDouble(Map<String, Object> trace, String... keys) {
        double max = 0.0d;
        if (trace == null || keys == null) {
            return max;
        }
        for (String key : keys) {
            max = Math.max(max, asDouble(trace.get(key), 0.0d));
        }
        return max;
    }

    public record Snapshot(double riskScore, double priorityScore, String dominantFailure, String hotspot,
                           Map<String, Object> topContributor, List<Map<String, Object>> rank,
                           String patternId, String restoreAction, double confidence, String decisionReason,
                           Map<String, Object> matrix, String vectorDecision, boolean highRisk) {
        static Snapshot empty(String reason) {
            return new Snapshot(0.0d, 0.0d, "none", "none", Map.of(), List.of(), "", "observe_only", 0.0d,
                    safePublicLabel(reason, "none"), Map.of(), "SHADOW_REVIEW", false);
        }
    }

    private record GraphRecommendation(String restoreAction, String patternId, double confidence,
                                       String reason, boolean applied) {
        static GraphRecommendation empty(String reason) {
            return new GraphRecommendation("observe_only", "", 0.0d, safePublicLabel(reason, "none"), false);
        }
    }

    private record Candidate(String failureClass, String hotspot, String reason, double risk, double priorityScore,
                             double severity, double probability, double recurrence, boolean silent,
                             String restoreAction, String decisionReason) {
        Map<String, Object> toPublicMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("failureClass", failureClass);
            out.put("hotspot", hotspot);
            out.put("riskScore", risk);
            out.put("priorityScore", priorityScore);
            out.put("severity", severity);
            out.put("probability", probability);
            out.put("recurrence", recurrence);
            out.put("silentFailure", silent);
            out.put("restoreAction", restoreAction);
            out.put("reason", SafeRedactor.traceLabelOrFallback(decisionReason, "unknown"));
            return Collections.unmodifiableMap(out);
        }
    }

    private record HistoryContaminationSignal(double score, int count, boolean currentSignal) {
    }
}
