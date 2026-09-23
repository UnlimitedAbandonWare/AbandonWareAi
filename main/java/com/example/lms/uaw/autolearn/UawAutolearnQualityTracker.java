package com.example.lms.uaw.autolearn;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ops.RagOpsLedgerService;
import com.example.lms.trace.SafeRedactor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded AutoLearn validation metrics and anomaly tracker.
 *
 * <p>It never stores raw questions, answers, snippets, or secrets. Debug events
 * use only a caller-provided sample hash plus low-cardinality validation data.
 */
@Component
public class UawAutolearnQualityTracker {

    private static final Logger log = LoggerFactory.getLogger(UawAutolearnQualityTracker.class);

    private static final String METRIC_SAMPLE_TOTAL = "uaw.autolearn.validation.sample_total";
    private static final String METRIC_EXTERNAL_ERROR_TOTAL = "uaw.autolearn.validation.external_error_total";
    private static final String METRIC_ANOMALY_TOTAL = "uaw.autolearn.validation.anomaly_total";
    private static final String METRIC_CYCLE_TOTAL = "uaw.autolearn.validation.cycle_total";

    private final UawAutolearnProperties props;
    private final MeterRegistry meterRegistry;
    private final DebugEventStore debugEventStore;
    private final ObjectProvider<RagFailureBlackboxService> blackboxProvider;
    private final ArrayDeque<Outcome> window = new ArrayDeque<>();
    private RagOpsLedgerService opsLedgerService;

    private boolean initialized;
    private double sampleScoreEwma;
    private double contextContaminationEwma;
    private double errorRateEwma;
    private volatile Map<String, Object> lastLoopDiagnostics = Map.of();
    private volatile Map<String, Object> lastLoopHotspot = Map.of();

    public UawAutolearnQualityTracker(
            UawAutolearnProperties props,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        this(props, meterRegistryProvider, debugEventStoreProvider, null);
    }

    @Autowired
    public UawAutolearnQualityTracker(
            UawAutolearnProperties props,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            ObjectProvider<RagFailureBlackboxService> blackboxProvider) {
        this.props = props == null ? new UawAutolearnProperties() : props;
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        this.debugEventStore = debugEventStoreProvider == null ? null : debugEventStoreProvider.getIfAvailable();
        this.blackboxProvider = blackboxProvider;
        registerGauges();
    }

    @Autowired(required = false)
    void setOpsLedgerService(RagOpsLedgerService opsLedgerService) {
        this.opsLedgerService = opsLedgerService;
    }

    public synchronized void seedThresholdTrace() {
        traceState(List.of(), false, false);
    }

    public synchronized double currentTuningDelta() {
        if (!dynamicThresholdEnabled()) {
            return 0.0d;
        }
        double delta = (errorRateWindow() - targetErrorRate()) * tuningScale();
        return clamp(delta, tuningDeltaMin(), tuningDeltaMax());
    }

    public synchronized QualitySnapshot snapshot() {
        Map<String, Integer> reasons = reasonCountsWindow();
        String topProblem = topProblem(reasons);
        List<String> flags = snapshotFlags(topProblem);
        boolean trainAllowed = trainAllowedByWindow();
        return new QualitySnapshot(
                window.size(),
                errorRateWindow(),
                errorRateEwma,
                sampleScoreEwma,
                contextContaminationEwma,
                currentTuningDelta(),
                maxTrainErrorRate(),
                trainAllowed,
                topProblem,
                reasons,
                flags);
    }

    public synchronized CycleDiagnostics finishCycle(
            String sessionId,
            int attempted,
            int accepted,
            boolean aborted,
            String datasetPath) {
        QualitySnapshot snap = snapshot();
        double acceptanceRate = attempted <= 0 ? 0.0d : clamp01(accepted / (double) attempted);
        String trainDecision;
        if (aborted) {
            trainDecision = "ABORTED";
        } else if (!snap.trainAllowed()) {
            trainDecision = "BLOCK_RETRAIN";
        } else if (accepted > 0) {
            trainDecision = "ALLOW_RETRAIN";
        } else {
            trainDecision = "ACCUMULATE";
        }
        List<String> flags = cycleFlags(snap, attempted, accepted, aborted);

        TraceStore.put("learning.cycle.attempted", Math.max(0, attempted));
        TraceStore.put("learning.cycle.accepted", Math.max(0, accepted));
        TraceStore.put("learning.cycle.acceptanceRate", acceptanceRate);
        TraceStore.put("learning.cycle.trainDecision", trainDecision);
        TraceStore.put("learning.cycle.topProblem", snap.topProblem());
        TraceStore.put("learning.training.allowed", snap.trainAllowed());
        TraceStore.put("learning.training.blockReason", snap.trainAllowed() ? "" : snap.topProblem());
        TraceStore.put("learning.metrics.reasonCounts", snap.reasonCounts());
        TraceStore.put("learning.metrics.maxTrainErrorRate", snap.maxTrainErrorRate());
        TraceStore.put("learning.metrics.snapshot", snapshotMap(snap));
        refreshBlackbox("UawAutolearnQualityTracker.finishCycle");
        Map<String, Object> loopDiagnostics =
                loopDiagnosticsMap(sessionId, datasetPath, attempted, accepted, acceptanceRate, trainDecision, snap, flags);
        Map<String, Object> loopHotspot = hotspotMap(trainDecision, snap, flags);
        lastLoopDiagnostics = stableSnapshot(loopDiagnostics);
        lastLoopHotspot = stableSnapshot(loopHotspot);
        TraceStore.put("uaw.idle.loop-diagnostics", loopDiagnostics);
        TraceStore.put("uaw.autolearn.loop.hotspot", loopHotspot);

        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_CYCLE_TOTAL, Tags.of(
                    "decision", safeReason(trainDecision),
                    "top_problem", safeReason(snap.topProblem()),
                    "allowed", Boolean.toString(snap.trainAllowed()))).increment();
        }
        emitCycleDebug(sessionId, datasetPath, attempted, accepted, acceptanceRate, trainDecision, snap, flags);
        CycleDiagnostics diagnostics = new CycleDiagnostics(
                snap.windowSize(),
                snap.errorRateWindow(),
                snap.errorRateEwma(),
                snap.sampleScoreEwma(),
                snap.contextContaminationEwma(),
                snap.tuningDelta(),
                snap.maxTrainErrorRate(),
                snap.trainAllowed(),
                snap.topProblem(),
                trainDecision,
                acceptanceRate,
                snap.reasonCounts(),
                flags);
        recordOpsLedgerCycle(sessionId, datasetPath, attempted, accepted, aborted, diagnostics);
        return diagnostics;
    }

    private void recordOpsLedgerCycle(String sessionId,
                                      String datasetPath,
                                      int attempted,
                                      int accepted,
                                      boolean aborted,
                                      CycleDiagnostics diagnostics) {
        RagOpsLedgerService ledger = opsLedgerService;
        if (ledger == null) {
            return;
        }
        try {
            ledger.recordAutolearnCycle(sessionId, datasetPath, attempted, accepted, aborted, diagnostics);
        } catch (Exception e) {
            log.debug("[AWX2AF2][ops-ledger] AutoLearn capture hook skipped. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    private void recordOpsLedgerDiagnostic(String section, Map<String, Object> diagnostics) {
        RagOpsLedgerService ledger = opsLedgerService;
        if (ledger == null) {
            return;
        }
        try {
            ledger.recordAutolearnDiagnostic(section, diagnostics);
        } catch (Exception e) {
            log.debug("[AWX2AF2][ops-ledger] AutoLearn diagnostic hook skipped. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    public Map<String, Object> lastLoopDiagnostics() {
        return lastLoopDiagnostics;
    }

    public synchronized void mergeLastLoopDiagnostics(String section, Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String safeSection = safeReason(section);
        Map<String, Object> snapshot = stableSnapshot(values);
        Map<String, Object> next = new LinkedHashMap<>(lastLoopDiagnostics);
        next.put(safeSection, snapshot);
        lastLoopDiagnostics = stableSnapshot(next);
        TraceStore.put("uaw.idle.loop-diagnostics", lastLoopDiagnostics);
        if ("gpu_gateway_blocked".equals(safeSection)) {
            projectGpuGatewayBlocked(snapshot);
        }
    }

    public Map<String, Object> lastLoopHotspot() {
        return lastLoopHotspot;
    }

    public synchronized LearningSampleValidationMetadata project(
            LearningSampleValidationMetadata validation,
            boolean providerDisabled,
            boolean finalGate) {
        if (validation == null) {
            return null;
        }
        double errorRate = errorRateWindow();
        List<String> flags = anomalyFlags(validation, providerDisabled, finalGate, false);
        boolean spike = isSpike(validation, errorRate);
        boolean drift = isDrift(validation);
        if (spike) {
            flags = appendFlag(flags, "spike");
        }
        if (drift) {
            flags = appendFlag(flags, "drift");
        }

        LearningSampleValidationMetadata.Runtime runtime = new LearningSampleValidationMetadata.Runtime(
                validation.runtime().evidenceCount(),
                validation.runtime().afterFilterCount(),
                validation.runtime().contextDiversity(),
                validation.runtime().laneCoverage(),
                errorRate);
        LearningSampleValidationMetadata current = validation
                .withRuntime(runtime)
                .withAnomalies(new LearningSampleValidationMetadata.Anomalies(flags, spike, drift));
        publishCurrentValidationTrace(current, flags, providerDisabled, finalGate, true);
        flags = mergeFlags(flags, blackboxFlags());
        String vectorDecision = vectorDecision(validation, flags);
        LearningSampleValidationMetadata projected = current
                .withAnomalies(new LearningSampleValidationMetadata.Anomalies(flags, spike, drift))
                .withFeedback(new LearningSampleValidationMetadata.Feedback(
                        validation.feedback().cfvmReward(),
                        vectorDecision));
        traceState(flags, spike, drift);
        TraceStore.put("learning.feedback.vectorDecision", vectorDecision);
        TraceStore.put("learning.validation.thresholdBreaks", thresholdBreaks(projected, providerDisabled, finalGate, true));
        TraceStore.put("learning.validation.contaminationSignals", contaminationSignals(projected, flags));
        TraceStore.put("learning.training.allowed", trainAllowedByWindow());
        return projected;
    }

    public synchronized void recordSample(
            LearningSampleValidationMetadata validation,
            String sessionId,
            String sampleHash,
            boolean providerDisabled,
            boolean finalGate,
            boolean writerOk) {
        if (validation == null || !metricsEnabled()) {
            return;
        }
        List<String> flags = anomalyFlags(validation, providerDisabled, finalGate, !writerOk);
        flags = mergeFlags(flags, validation.anomalies().flags());
        publishCurrentValidationTrace(validation, flags, providerDisabled, finalGate, writerOk);
        flags = mergeFlags(flags, blackboxFlags());
        boolean error = providerDisabled || !finalGate || !validation.accepted() || !writerOk || !flags.isEmpty();
        String reason = firstReason(flags, error ? "validation_error" : "ok");
        String hotspot = hotspotForFlags(flags, writerOk);
        Outcome outcome = new Outcome(error, validation.sampleScore(), validation.contextContaminationScore(), reason);
        window.addLast(outcome);
        trimWindow();
        updateEwma(outcome);

        traceState(flags, validation.anomalies().spike(), validation.anomalies().drift());
        TraceStore.put("learning.training.allowed", trainAllowedByWindow());
        TraceStore.put("learning.error.hotspot", hotspot);
        TraceStore.put("learning.validation.thresholdBreaks", thresholdBreaks(validation, providerDisabled, finalGate, writerOk));
        TraceStore.put("learning.validation.contaminationSignals", contaminationSignals(validation, flags));
        emitDebug("sample", sessionId, sampleHash, validation, flags, error, writerOk, hotspot);
        recordMetric(validation, flags, error, writerOk, hotspot);
    }

    public synchronized void recordExternalError(String reason) {
        if (!metricsEnabled()) {
            return;
        }
        String r = safeReason(reason);
        Outcome outcome = new Outcome(true, 0.0d, 1.0d, r);
        window.addLast(outcome);
        trimWindow();
        updateEwma(outcome);
        List<String> flags = List.of(r);
        String hotspot = hotspotForReason(r);
        traceState(flags, true, false);
        TraceStore.put("learning.metrics.externalError", r);
        TraceStore.put("learning.error.hotspot", hotspot);
        TraceStore.put("learning.training.allowed", trainAllowedByWindow());
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_EXTERNAL_ERROR_TOTAL, Tags.of("reason", r, "hotspot", hotspot)).increment();
            meterRegistry.counter(METRIC_ANOMALY_TOTAL, Tags.of("anomaly", r, "phase", "external_error")).increment();
        }
        if (debugEventStore != null) {
            Map<String, Object> data = baseDebugData(null, null, flags, true);
            data.put("reason", r);
            data.put("hotspot", hotspot);
            data.put("errorRateWindow", errorRateWindow());
            data.put("trainAllowed", trainAllowedByWindow());
            debugEventStore.emit(DebugProbeType.AUTOLEARN, DebugEventLevel.WARN,
                    "uaw_autolearn_external_error:" + r,
                    "AutoLearn validation external error",
                    "UawAutolearnQualityTracker.recordExternalError",
                    data,
                    null);
        }
    }

    private List<String> anomalyFlags(
            LearningSampleValidationMetadata validation,
            boolean providerDisabled,
            boolean finalGate,
            boolean writerFailed) {
        List<String> flags = new ArrayList<>();
        if (providerDisabled) {
            flags.add("provider_disabled");
        }
        if (!finalGate) {
            flags.add("final_gate_failed");
        }
        if (validation.rejectReasons().contains("contradiction_risk")
                || validation.contradictionScore() >= validation.thresholds().contradictionMax()) {
            flags.add("contradiction_risk");
        }
        if (!validation.accepted()) {
            flags.add("validation_rejected");
        }
        if (writerFailed) {
            flags.add("writer_failed");
        }
        if (validation.sampleScore() < validation.thresholds().sampleScoreMin()) {
            flags.add("sample_score_threshold");
        }
        if (validation.contaminationScore() > validation.thresholds().contaminationMax()
                || validation.contextContaminationScore() > validation.thresholds().contextContaminationMax()) {
            flags.add("context_contamination_threshold");
        }
        if (validation.requery().required() && !validation.requery().confirmed()) {
            flags.add("requery_unconfirmed");
        }
        return List.copyOf(flags);
    }

    private static List<Map<String, Object>> thresholdBreaks(
            LearningSampleValidationMetadata validation,
            boolean providerDisabled,
            boolean finalGate,
            boolean writerOk) {
        if (validation == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        addMinBreak(out, "sample_score_below_threshold", "sampleScore",
                validation.sampleScore(), validation.thresholds().sampleScoreMin(), "autolearn.validation");
        addMaxBreak(out, "contamination_risk", "contaminationScore",
                validation.contaminationScore(), validation.thresholds().contaminationMax(), "autolearn.validation");
        addMaxBreak(out, "context_contamination_threshold", "contextContaminationScore",
                validation.contextContaminationScore(), validation.thresholds().contextContaminationMax(), "autolearn.validation");
        addMaxBreak(out, "legacy_context_risk", "legacyContextScore",
                validation.legacyContextScore(), 0.40d, "autolearn.validation");
        addMaxBreakInclusive(out, "contradiction_risk", "contradictionScore",
                validation.contradictionScore(), validation.thresholds().contradictionMax(), "evidence_gate");
        addMaxBreak(out, "error_rate_window", "errorRateWindow",
                validation.runtime().errorRateWindow(), 0.30d, "autolearn.window");
        if (validation.requery().required() && !validation.requery().confirmed()) {
            out.add(thresholdBreak("unconfirmed_high_risk_requery", "requeryConfirmed",
                    0.0d, 1.0d, "==", Math.max(0.01d, validation.thresholds().requeryPenalty()), "autolearn.requery"));
        }
        if (providerDisabled || validation.rejectReasons().contains("provider_disabled")) {
            out.add(thresholdBreak("provider_disabled", "providerDisabled",
                    1.0d, 0.0d, "<=", 1.0d, "provider"));
        }
        if (!finalGate || validation.rejectReasons().contains("final_gate_failed")) {
            out.add(thresholdBreak("final_gate_failed", "finalGate",
                    0.0d, 1.0d, "==", 1.0d, "evidence_gate"));
        }
        if (!writerOk) {
            out.add(thresholdBreak("writer_failed", "writerOk",
                    0.0d, 1.0d, "==", 1.0d, "writer"));
        }
        if ("QUARANTINE".equalsIgnoreCase(validation.feedback().vectorDecision())) {
            out.add(thresholdBreak("vector_quarantine", "vectorDecision",
                    1.0d, 0.0d, "<=", 1.0d, "vector"));
        }
        return List.copyOf(out);
    }

    private static List<String> contaminationSignals(LearningSampleValidationMetadata validation, List<String> flags) {
        if (validation == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (validation.contaminationScore() >= validation.thresholds().contaminationMax()) {
            out.add("raw_trace_or_secret_like_text");
        }
        if (validation.legacyContextScore() > 0.40d) {
            out.add("legacy_source_root");
        }
        if (validation.runtime().contextDiversity() < 0.30d) {
            out.add("low_diversity");
        }
        if (validation.rejectReasons().contains("provider_disabled") || containsFlag(flags, "provider_disabled")) {
            out.add("provider_disabled_fallback");
        }
        if (validation.rejectReasons().contains("contradiction_risk") || containsFlag(flags, "contradiction_risk")) {
            out.add("evidence_conflict");
        }
        if ("QUARANTINE".equalsIgnoreCase(validation.feedback().vectorDecision())) {
            out.add("quarantine_vector_decision");
        }
        if (flags != null) {
            for (String flag : flags) {
                if (flag != null && (flag.contains("spike") || flag.contains("drift"))) {
                    out.add(flag);
                }
            }
        }
        return out.stream().distinct().toList();
    }

    private static void addMinBreak(List<Map<String, Object>> out,
                                    String label,
                                    String metric,
                                    double value,
                                    double threshold,
                                    String stage) {
        if (value < threshold) {
            out.add(thresholdBreak(label, metric, value, threshold, ">=", threshold - value, stage));
        }
    }

    private static void addMaxBreak(List<Map<String, Object>> out,
                                    String label,
                                    String metric,
                                    double value,
                                    double threshold,
                                    String stage) {
        if (value > threshold) {
            out.add(thresholdBreak(label, metric, value, threshold, "<=", value - threshold, stage));
        }
    }

    private static void addMaxBreakInclusive(List<Map<String, Object>> out,
                                             String label,
                                             String metric,
                                             double value,
                                             double threshold,
                                             String stage) {
        if (value >= threshold) {
            out.add(thresholdBreak(label, metric, value, threshold, "<=", value - threshold, stage));
        }
    }

    private static Map<String, Object> thresholdBreak(String label,
                                                      String metric,
                                                      double value,
                                                      double threshold,
                                                      String comparator,
                                                      double severity,
                                                      String stage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label == null ? "" : label);
        row.put("metric", metric == null ? "" : metric);
        row.put("value", round4(value));
        row.put("threshold", round4(threshold));
        row.put("comparator", comparator == null ? "" : comparator);
        row.put("severity", round4(Math.max(0.0d, severity)));
        row.put("stage", stage == null ? "" : stage);
        return row;
    }

    private static boolean containsFlag(List<String> flags, String target) {
        return flags != null && flags.contains(target);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> traceList(String key) {
        Object value = TraceStore.get(key);
        if (value instanceof List<?> list) {
            return List.copyOf((List<Object>) list);
        }
        return List.of();
    }

    private boolean isSpike(LearningSampleValidationMetadata validation, double errorRate) {
        if (!initialized) {
            return false;
        }
        return validation.contextContaminationScore() >= Math.max(spikeContaminationScore(), contextContaminationEwma + spikeContaminationRise())
                || errorRate >= Math.max(spikeErrorRate(), errorRateEwma + spikeErrorRateRise());
    }

    private boolean isDrift(LearningSampleValidationMetadata validation) {
        if (!initialized || window.size() < Math.min(driftMinWindow(), maxWindowSize())) {
            return false;
        }
        return validation.sampleScore() <= sampleScoreEwma - driftSampleScoreDrop()
                || validation.contextContaminationScore() >= contextContaminationEwma + driftContaminationRise();
    }

    private String vectorDecision(LearningSampleValidationMetadata validation, List<String> flags) {
        if (!autoQuarantineEnabled()) {
            return validation.accepted() ? "SHADOW_REVIEW" : "QUARANTINE";
        }
        if (!validation.accepted() || (flags != null && !flags.isEmpty())) {
            return "QUARANTINE";
        }
        return "SHADOW_REVIEW";
    }

    private void updateEwma(Outcome outcome) {
        double alpha = ewmaAlpha();
        double errorValue = outcome.error ? 1.0d : 0.0d;
        if (!initialized) {
            sampleScoreEwma = outcome.sampleScore;
            contextContaminationEwma = outcome.contextContamination;
            errorRateEwma = errorValue;
            initialized = true;
            return;
        }
        sampleScoreEwma = ewma(sampleScoreEwma, outcome.sampleScore, alpha);
        contextContaminationEwma = ewma(contextContaminationEwma, outcome.contextContamination, alpha);
        errorRateEwma = ewma(errorRateEwma, errorValue, alpha);
    }

    private void trimWindow() {
        int max = maxWindowSize();
        while (window.size() > max) {
            window.removeFirst();
        }
    }

    private double errorRateWindow() {
        if (window.isEmpty()) {
            return 0.0d;
        }
        int errors = 0;
        for (Outcome o : window) {
            if (o.error) {
                errors++;
            }
        }
        return clamp01(errors / (double) window.size());
    }

    private void traceState(List<String> flags, boolean spike, boolean drift) {
        double errorRate = errorRateWindow();
        TraceStore.put("learning.metrics.errorRateWindow", errorRate);
        TraceStore.put("learning.metrics.windowSize", window.size());
        TraceStore.put("learning.metrics.sampleScoreEwma", sampleScoreEwma);
        TraceStore.put("learning.metrics.contextContaminationEwma", contextContaminationEwma);
        TraceStore.put("learning.metrics.errorRateEwma", errorRateEwma);
        TraceStore.put("learning.threshold.tuningDelta", currentTuningDelta());
        TraceStore.put("learning.anomaly.flags", flags == null ? List.of() : List.copyOf(flags));
        TraceStore.put("learning.anomaly.spike", spike);
        TraceStore.put("learning.anomaly.drift", drift);
    }

    private void emitDebug(
            String phase,
            String sessionId,
            String sampleHash,
            LearningSampleValidationMetadata validation,
            List<String> flags,
            boolean error,
            boolean writerOk,
            String hotspot) {
        if (debugEventStore == null) {
            return;
        }
        Map<String, Object> data = baseDebugData(sessionId, sampleHash, flags, error);
        data.put("phase", phase);
        data.put("writerOk", writerOk);
        data.put("decision", validation.accepted() ? "accepted" : "rejected");
        data.put("vectorDecision", validation.feedback().vectorDecision());
        data.put("sampleScore", validation.sampleScore());
        data.put("contradictionScore", validation.contradictionScore());
        data.put("contradictionCause", validation.contradictionCause());
        data.put("contextContaminationScore", validation.contextContaminationScore());
        LearningSampleValidationMetadata.Runtime runtime = validation.runtime();
        data.put("evidenceCount", runtime.evidenceCount());
        data.put("afterFilterCount", runtime.afterFilterCount());
        data.put("contextDiversity", runtime.contextDiversity());
        data.put("laneCoverage", runtime.laneCoverage());
        data.put("sampleScoreMin", validation.thresholds().sampleScoreMin());
        data.put("contaminationMax", validation.thresholds().contaminationMax());
        data.put("requeryPenalty", validation.thresholds().requeryPenalty());
        data.put("rejectReasons", validation.rejectReasons());
        data.put("errorRateWindow", errorRateWindow());
        data.put("tuningDelta", currentTuningDelta());
        data.put("trainAllowed", trainAllowedByWindow());
        data.put("hotspot", hotspot == null ? "none" : hotspot);
        data.put("thresholdBreaks", thresholdBreaks(validation,
                containsFlag(flags, "provider_disabled"),
                !containsFlag(flags, "final_gate_failed"),
                writerOk));
        data.put("contaminationSignals", contaminationSignals(validation, flags));
        debugEventStore.emit(DebugProbeType.AUTOLEARN, error ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                "uaw_autolearn_validation:" + (error ? "error" : "ok"),
                "AutoLearn validation sample outcome",
                "UawAutolearnQualityTracker.recordSample",
                data,
                null);
    }

    private void emitCycleDebug(
            String sessionId,
            String datasetPath,
            int attempted,
            int accepted,
            double acceptanceRate,
            String trainDecision,
            QualitySnapshot snap,
            List<String> flags) {
        if (debugEventStore == null || !cycleDebugEnabled()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        putSessionDiagnostics(data, sessionId);
        putDatasetDiagnostics(data, datasetPath);
        data.put("attempted", Math.max(0, attempted));
        data.put("accepted", Math.max(0, accepted));
        data.put("acceptanceRate", acceptanceRate);
        data.put("trainDecision", trainDecision);
        data.put("trainAllowed", snap.trainAllowed());
        data.put("topProblem", snap.topProblem());
        data.put("errorRateWindow", snap.errorRateWindow());
        data.put("errorRateEwma", snap.errorRateEwma());
        data.put("sampleScoreEwma", snap.sampleScoreEwma());
        data.put("contextContaminationEwma", snap.contextContaminationEwma());
        data.put("tuningDelta", snap.tuningDelta());
        data.put("maxTrainErrorRate", snap.maxTrainErrorRate());
        data.put("reasonCounts", snap.reasonCounts());
        data.put("flags", flags == null ? List.of() : List.copyOf(flags));
        debugEventStore.emit(DebugProbeType.AUTOLEARN,
                snap.trainAllowed() && !"ABORTED".equals(trainDecision) ? DebugEventLevel.INFO : DebugEventLevel.WARN,
                "uaw_autolearn_cycle:" + safeReason(trainDecision),
                "AutoLearn cycle self-diagnosis",
                "UawAutolearnQualityTracker.finishCycle",
                data,
                null);
    }

    private void recordMetric(
            LearningSampleValidationMetadata validation,
            List<String> flags,
            boolean error,
            boolean writerOk,
            String hotspot) {
        if (meterRegistry == null) {
            return;
        }
        String anomaly = flags == null || flags.isEmpty() ? "none" : safeReason(flags.get(0));
        meterRegistry.counter(METRIC_SAMPLE_TOTAL, Tags.of(
                "outcome", error ? "error" : "ok",
                "decision", validation.accepted() ? "accepted" : "rejected",
                "writer", writerOk ? "ok" : "failed",
                "anomaly", anomaly,
                "hotspot", hotspot == null || hotspot.isBlank() ? "none" : hotspot,
                "vector_decision", safeReason(validation.feedback().vectorDecision()))).increment();
        if (!"none".equals(anomaly)) {
            meterRegistry.counter(METRIC_ANOMALY_TOTAL, Tags.of("anomaly", anomaly, "phase", "sample")).increment();
        }
    }

    private void registerGauges() {
        if (meterRegistry == null) {
            return;
        }
        Gauge.builder("uaw.autolearn.validation.error_rate.window", this, UawAutolearnQualityTracker::gaugeErrorRateWindow)
                .description("Rolling AutoLearn validation error-rate within the bounded window")
                .register(meterRegistry);
        Gauge.builder("uaw.autolearn.validation.error_rate.ewma", this, UawAutolearnQualityTracker::gaugeErrorRateEwma)
                .description("EWMA AutoLearn validation error-rate")
                .register(meterRegistry);
        Gauge.builder("uaw.autolearn.validation.sample_score.ewma", this, UawAutolearnQualityTracker::gaugeSampleScoreEwma)
                .description("EWMA AutoLearn sample validation score")
                .register(meterRegistry);
        Gauge.builder("uaw.autolearn.validation.context_contamination.ewma", this, UawAutolearnQualityTracker::gaugeContextContaminationEwma)
                .description("EWMA AutoLearn context contamination score")
                .register(meterRegistry);
        Gauge.builder("uaw.autolearn.validation.threshold.tuning_delta", this, UawAutolearnQualityTracker::gaugeThresholdTuningDelta)
                .description("Current dynamic threshold delta applied by AutoLearn quality guard")
                .register(meterRegistry);
        Gauge.builder("uaw.autolearn.validation.window.size", this, UawAutolearnQualityTracker::gaugeWindowSize)
                .description("Current AutoLearn validation bounded-window size")
                .register(meterRegistry);
    }

    private synchronized double gaugeErrorRateWindow() {
        return errorRateWindow();
    }

    private synchronized double gaugeErrorRateEwma() {
        return errorRateEwma;
    }

    private synchronized double gaugeSampleScoreEwma() {
        return sampleScoreEwma;
    }

    private synchronized double gaugeContextContaminationEwma() {
        return contextContaminationEwma;
    }

    private synchronized double gaugeThresholdTuningDelta() {
        return currentTuningDelta();
    }

    private synchronized double gaugeWindowSize() {
        return window.size();
    }

    private Map<String, Integer> reasonCountsWindow() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Outcome outcome : window) {
            String reason = safeReason(outcome.reason);
            counts.put(reason, counts.getOrDefault(reason, 0) + 1);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    private String topProblem(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "none";
        }
        String top = "none";
        int max = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > max && !"ok".equals(entry.getKey())) {
                top = entry.getKey();
                max = entry.getValue();
            }
        }
        return max == 0 ? "none" : top;
    }

    private List<String> snapshotFlags(String topProblem) {
        List<String> flags = new ArrayList<>();
        if (window.isEmpty()) {
            flags.add("no_window");
        }
        if (errorRateWindow() > maxTrainErrorRate()) {
            flags.add("error_rate_threshold");
        }
        if (errorRateWindow() >= spikeErrorRate() || contextContaminationEwma >= spikeContaminationScore()) {
            flags.add("spike_risk");
        }
        if (topProblem != null && !topProblem.isBlank() && !"none".equals(topProblem)) {
            flags.add("top_problem:" + safeReason(topProblem));
        }
        return List.copyOf(flags);
    }

    private List<String> cycleFlags(QualitySnapshot snap, int attempted, int accepted, boolean aborted) {
        List<String> flags = new ArrayList<>(snap.flags());
        if (aborted) {
            flags.add("aborted_by_user");
        }
        if (attempted > 0 && accepted == 0) {
            flags.add("zero_acceptance");
        }
        return List.copyOf(flags);
    }

    private Map<String, Object> snapshotMap(QualitySnapshot snap) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("windowSize", snap.windowSize());
        data.put("errorRateWindow", snap.errorRateWindow());
        data.put("errorRateEwma", snap.errorRateEwma());
        data.put("sampleScoreEwma", snap.sampleScoreEwma());
        data.put("contextContaminationEwma", snap.contextContaminationEwma());
        data.put("tuningDelta", snap.tuningDelta());
        data.put("maxTrainErrorRate", snap.maxTrainErrorRate());
        data.put("trainAllowed", snap.trainAllowed());
        data.put("topProblem", snap.topProblem());
        data.put("reasonCounts", snap.reasonCounts());
        data.put("flags", snap.flags());
        return data;
    }

    private Map<String, Object> loopDiagnosticsMap(
            String sessionId,
            String datasetPath,
            int attempted,
            int accepted,
            double acceptanceRate,
            String trainDecision,
            QualitySnapshot snap,
            List<String> flags) {
        Map<String, Object> data = snapshotMap(snap);
        putSessionDiagnostics(data, sessionId);
        putDatasetDiagnostics(data, datasetPath);
        data.put("attempted", Math.max(0, attempted));
        data.put("accepted", Math.max(0, accepted));
        data.put("acceptanceRate", acceptanceRate);
        data.put("trainDecision", trainDecision == null ? "" : trainDecision);
        data.put("hotspot", hotspotForReason(snap.topProblem()));
        data.put("flags", flags == null ? List.of() : List.copyOf(flags));
        data.put("thresholdBreaks", traceList("learning.validation.thresholdBreaks"));
        data.put("contaminationSignals", traceList("learning.validation.contaminationSignals"));
        data.put("blackbox", blackboxSummary());
        Object vectorDecision = TraceStore.get("learning.feedback.vectorDecision");
        data.put("vectorDecision", vectorDecision == null ? "" : String.valueOf(vectorDecision));
        return data;
    }

    private Map<String, Object> hotspotMap(String trainDecision, QualitySnapshot snap, List<String> flags) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hotspot", hotspotForFlags(flags, true));
        data.put("topProblem", snap.topProblem());
        data.put("reasonCounts", snap.reasonCounts());
        data.put("trainDecision", trainDecision == null ? "" : trainDecision);
        data.put("errorRateWindow", snap.errorRateWindow());
        data.put("flags", flags == null ? List.of() : List.copyOf(flags));
        data.put("blackbox", blackboxSummary());
        return data;
    }

    private List<String> blackboxFlags() {
        refreshBlackbox("UawAutolearnQualityTracker");
        double risk = Math.max(traceDouble("blackbox.risk.riskScore"), traceDouble("blackbox.risk.priorityScore"));
        boolean highRisk = truthy(TraceStore.get("blackbox.risk.highRisk"))
                || risk >= RagFailureBlackboxService.HIGH_RISK_THRESHOLD;
        if (!highRisk) {
            return List.of();
        }
        String failure = safeReason(String.valueOf(TraceStore.get("blackbox.risk.dominantFailure")));
        if (failure.isBlank() || "none".equals(failure)) {
            failure = "unknown";
        }
        return List.of("blackbox_" + failure);
    }

    private void refreshBlackbox(String where) {
        try {
            RagFailureBlackboxService service = blackboxProvider == null ? null : blackboxProvider.getIfAvailable();
            if (service != null) {
                service.refresh(where);
            }
        } catch (Throwable ignore) {
            log.debug("[AWX][uaw][quality] blackbox refresh skipped where={}",
                    SafeRedactor.traceLabelOrFallback(where, "unknown"));
        }
    }

    private void projectGpuGatewayBlocked(Map<String, Object> details) {
        Map<String, Object> safeDetails = details == null ? Map.of() : details;
        Map<String, Integer> reasonCounts = Map.of("gpu_gateway_unreachable", 1);
        String decision = String.valueOf(safeDetails.getOrDefault("trainDecision", "BLOCK_HEAVY_WORK"));
        String diagnosis = String.valueOf(safeDetails.getOrDefault("diagnosis", "desktop_gpu_gateway_unreachable"));

        TraceStore.put("uaw.gpu-gateway.admission.blocked", true);
        TraceStore.put("uaw.gpu-gateway.admission.blocked.count", 1);
        TraceStore.put("learning.error.hotspot", "provider");
        TraceStore.put("learning.loop.dominantFailure", "gpu_gateway_unreachable");
        TraceStore.put("learning.loop.diagnosis", "desktop_gpu_gateway_unreachable");
        TraceStore.put("learning.loop.phaseFailures", reasonCounts);
        TraceStore.put("learning.metrics.reasonCounts", reasonCounts);
        TraceStore.put("learning.training.allowed", false);
        TraceStore.put("learning.training.blockReason", "gpu_gateway_unreachable");
        refreshBlackbox("UawAutolearnQualityTracker.mergeLastLoopDiagnostics.gpu_gateway_blocked");

        Map<String, Object> hotspot = new LinkedHashMap<>();
        hotspot.put("hotspot", "provider");
        hotspot.put("topProblem", "gpu_gateway_unreachable");
        hotspot.put("reasonCounts", reasonCounts);
        hotspot.put("trainDecision", decision);
        hotspot.put("errorRateWindow", errorRateWindow());
        hotspot.put("flags", List.of("gpu_gateway_unreachable"));
        hotspot.put("diagnosis", diagnosis);
        hotspot.put("blackbox", blackboxSummary());
        lastLoopHotspot = stableSnapshot(hotspot);
        TraceStore.put("uaw.autolearn.loop.hotspot", lastLoopHotspot);
        recordOpsLedgerDiagnostic("gpu_gateway_blocked", safeDetails);
    }

    private void publishCurrentValidationTrace(LearningSampleValidationMetadata validation,
                                               List<String> flags,
                                               boolean providerDisabled,
                                               boolean finalGate,
                                               boolean writerOk) {
        traceState(flags, validation.anomalies().spike(), validation.anomalies().drift());
        TraceStore.put("learning.feedback.vectorDecision", validation.feedback().vectorDecision());
        TraceStore.put("learning.validation.thresholdBreaks", thresholdBreaks(validation, providerDisabled, finalGate, writerOk));
        TraceStore.put("learning.validation.contaminationSignals", contaminationSignals(validation, flags));
        TraceStore.put("learning.training.allowed", trainAllowedByWindow());
    }

    private static Map<String, Object> blackboxSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        putIfTracePresent(out, "riskScore", "blackbox.risk.riskScore");
        putIfTracePresent(out, "priorityScore", "blackbox.risk.priorityScore");
        putIfTracePresent(out, "dominantFailure", "blackbox.risk.dominantFailure");
        putIfTracePresent(out, "hotspot", "blackbox.risk.hotspot");
        putIfTracePresent(out, "patternId", "blackbox.risk.patternId");
        putIfTracePresent(out, "restoreAction", "blackbox.risk.restoreAction");
        putIfTracePresent(out, "confidence", "blackbox.risk.confidence");
        putIfTracePresent(out, "vectorDecision", "blackbox.risk.vectorDecision");
        putIfTracePresent(out, "highRisk", "blackbox.risk.highRisk");
        putIfTracePresent(out, "decisionReason", "blackbox.risk.decisionReason");
        putIfTracePresent(out, "historyContaminationScore", "blackbox.risk.historyContaminationScore");
        putIfTracePresent(out, "historySignalCount", "blackbox.risk.historySignalCount");
        putIfTracePresent(out, "historyCorrectionAction", "blackbox.risk.historyCorrectionAction");
        putIfTracePresent(out, "firstWhere", "blackbox.risk.firstWhere");
        putIfTracePresent(out, "lastWhere", "blackbox.risk.lastWhere");
        putIfTracePresent(out, "projectionVersion", "blackbox.risk.projectionVersion");
        putMatrixSummaryIfTracePresent(out);
        return out;
    }

    private static void putMatrixSummaryIfTracePresent(Map<String, Object> out) {
        Object raw = TraceStore.get("blackbox.risk.matrix");
        if (!(raw instanceof Map<?, ?> matrix) || matrix.isEmpty()) {
            return;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : matrix.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!"matrix.schemaVersion".equals(key) && !key.startsWith("q_")) {
                continue;
            }
            if ("matrix.schemaVersion".equals(key)) {
                safe.put(key, String.valueOf(entry.getValue()));
            } else {
                safe.put(key, SafeRedactor.diagnosticValue("blackbox.risk.matrix." + key, entry.getValue()));
            }
        }
        if (!safe.isEmpty()) {
            out.put("matrix", Collections.unmodifiableMap(safe));
        }
    }

    private static void putIfTracePresent(Map<String, Object> out, String key, String traceKey) {
        Object value = TraceStore.get(traceKey);
        if (value != null) {
            out.put(key, SafeRedactor.diagnosticValue(traceKey, value));
        }
    }

    private static double traceDouble(String key) {
        Object value = TraceStore.get(key);
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            if (Double.isFinite(parsed)) {
                return parsed;
            }
            TraceStore.put("uaw.autolearn.quality.suppressed.traceDouble",
                    SafeRedactor.traceLabelOrFallback(key, "unknown"));
            return 0.0d;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            if (Double.isFinite(parsed)) {
                return parsed;
            }
            TraceStore.put("uaw.autolearn.quality.suppressed.traceDouble",
                    SafeRedactor.traceLabelOrFallback(key, "unknown"));
            return 0.0d;
        } catch (NumberFormatException ignore) {
            TraceStore.put("uaw.autolearn.quality.suppressed.traceDouble",
                    SafeRedactor.traceLabelOrFallback(key, "unknown"));
            return 0.0d;
        }
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0d;
        }
        String s = value == null ? "" : String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stableSnapshot(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey() == null ? "unknown" : entry.getKey();
            Object value = stableValue(key, entry.getValue(), 0);
            if (value != null) {
                out.put(key, value);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Object stableValue(String key, Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (depth > 4) {
            return "(depth-limit)";
        }
        if (SafeRedactor.isRestrictedKey(key)) {
            return SafeRedactor.diagnosticValue(key, value);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                String childKey = String.valueOf(entry.getKey());
                Object child = stableValue(joinKey(key, childKey), entry.getValue(), depth + 1);
                if (child != null) {
                    out.put(childKey, child);
                }
            }
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object item : iterable) {
                out.add(stableValue(key, item, depth + 1));
                if (out.size() >= 80) {
                    out.add("(truncated)");
                    break;
                }
            }
            return Collections.unmodifiableList(out);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence seq) {
            return SafeRedactor.redact(seq.toString());
        }
        return SafeRedactor.diagnosticValue(key, value);
    }

    private static String joinKey(String parent, String child) {
        if (parent == null || parent.isBlank()) {
            return child;
        }
        return parent + "." + child;
    }

    private static void putSessionDiagnostics(Map<String, Object> data, String sessionId) {
        String sid = trimToEmpty(sessionId);
        data.put("hasSessionId", !sid.isEmpty());
        data.put("sessionHash", hashOrEmpty(sid));
    }

    private static void putDatasetDiagnostics(Map<String, Object> data, String datasetPath) {
        String path = trimToEmpty(datasetPath);
        data.put("hasDatasetPath", !path.isEmpty());
        data.put("datasetFileHash", datasetFileHash(path));
        data.put("datasetFileLength", datasetFileLength(path));
        data.put("datasetPathHash", hashOrEmpty(path));
    }

    private static String datasetFileName(String path) {
        String p = trimToEmpty(path).replace('\\', '/');
        if (p.isEmpty()) {
            return "";
        }
        int idx = p.lastIndexOf('/');
        return idx >= 0 && idx + 1 < p.length() ? p.substring(idx + 1) : p;
    }

    private static String datasetFileHash(String path) {
        String fileName = datasetFileName(path);
        return fileName.isEmpty() ? "" : hashOrEmpty(fileName);
    }

    private static int datasetFileLength(String path) {
        return datasetFileName(path).length();
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String hotspotForFlags(List<String> flags, boolean writerOk) {
        if (!writerOk) {
            return "writer";
        }
        if (flags != null) {
            for (String flag : flags) {
                String hotspot = hotspotForReason(flag);
                if (!"none".equals(hotspot)) {
                    return hotspot;
                }
            }
        }
        return "none";
    }

    private static String hotspotForReason(String reason) {
        String r = safeReason(reason);
        if (r.contains("writer") || r.contains("ingest") || r.contains("upsert")) {
            return "writer";
        }
        if (r.contains("provider") || r.contains("api_disabled") || r.contains("disabled")
                || r.contains("gpu_gateway")) {
            return "provider";
        }
        if (r.contains("insufficient_evidence") || r.contains("final_gate") || r.contains("empty_result")
                || r.contains("low_context_diversity") || r.contains("contradiction")
                || r.contains("after_filter") || r.contains("starvation")) {
            return "evidence_gate";
        }
        if (r.contains("context_contamination") || r.contains("contamination_risk")
                || r.contains("legacy_context")) {
            return "context_contamination";
        }
        if (r.contains("sample_score") || r.contains("error_rate") || r.contains("threshold")) {
            return "threshold";
        }
        if (r.contains("requery")) {
            return "requery";
        }
        if (r.contains("spike") || r.contains("drift")) {
            return "signal_shift";
        }
        if (r.contains("validation")) {
            return "validation";
        }
        return "none";
    }

    private boolean trainAllowedByWindow() {
        return window.isEmpty() || errorRateWindow() <= maxTrainErrorRate();
    }

    private static Map<String, Object> baseDebugData(
            String sessionId,
            String sampleHash,
            List<String> flags,
            boolean error) {
        Map<String, Object> data = new LinkedHashMap<>();
        putSessionDiagnostics(data, sessionId);
        data.put("sampleHash", sampleHash == null ? "" : sampleHash);
        data.put("error", error);
        data.put("flags", flags == null ? List.of() : List.copyOf(flags));
        return data;
    }

    private boolean metricsEnabled() {
        return validationProps().isMetricsEnabled();
    }

    private boolean dynamicThresholdEnabled() {
        return validationProps().isDynamicThresholdEnabled();
    }

    private boolean autoQuarantineEnabled() {
        return validationProps().isAutoQuarantineEnabled();
    }

    private boolean cycleDebugEnabled() {
        return validationProps().isCycleDebugEnabled();
    }

    private int maxWindowSize() {
        return Math.max(4, validationProps().getWindowSize());
    }

    private double ewmaAlpha() {
        return clamp(validationProps().getEwmaAlpha(), 0.01d, 1.0d);
    }

    private double targetErrorRate() {
        return clamp01(validationProps().getTargetErrorRate());
    }

    private double tuningScale() {
        return clamp(validationProps().getTuningScale(), 0.0d, 10.0d);
    }

    private double tuningDeltaMin() {
        return clamp(validationProps().getTuningDeltaMin(), -1.0d, 1.0d);
    }

    private double tuningDeltaMax() {
        return clamp(validationProps().getTuningDeltaMax(), -1.0d, 1.0d);
    }

    private double maxTrainErrorRate() {
        return clamp01(validationProps().getMaxTrainErrorRate());
    }

    private double spikeErrorRate() {
        return clamp01(validationProps().getSpikeErrorRate());
    }

    private double spikeErrorRateRise() {
        return clamp01(validationProps().getSpikeErrorRateRise());
    }

    private double spikeContaminationScore() {
        return clamp01(validationProps().getSpikeContaminationScore());
    }

    private double spikeContaminationRise() {
        return clamp01(validationProps().getSpikeContaminationRise());
    }

    private int driftMinWindow() {
        return Math.max(2, validationProps().getDriftMinWindow());
    }

    private double driftSampleScoreDrop() {
        return clamp01(validationProps().getDriftSampleScoreDrop());
    }

    private double driftContaminationRise() {
        return clamp01(validationProps().getDriftContaminationRise());
    }

    private UawAutolearnProperties.Validation validationProps() {
        UawAutolearnProperties.Validation validation = props.getValidation();
        return validation == null ? new UawAutolearnProperties.Validation() : validation;
    }

    private static double ewma(double current, double next, double alpha) {
        return current * (1.0d - alpha) + next * alpha;
    }

    private static List<String> appendFlag(List<String> flags, String flag) {
        List<String> out = new ArrayList<>(flags == null ? List.of() : flags);
        if (flag != null && !flag.isBlank() && !out.contains(flag)) {
            out.add(flag);
        }
        return List.copyOf(out);
    }

    private static List<String> mergeFlags(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        if (a != null) {
            out.addAll(a);
        }
        if (b != null) {
            for (String flag : b) {
                if (flag != null && !flag.isBlank() && !out.contains(flag)) {
                    out.add(flag);
                }
            }
        }
        return List.copyOf(out);
    }

    private static String firstReason(List<String> flags, String fallback) {
        if (flags != null) {
            for (String flag : flags) {
                if (flag != null && !flag.isBlank()) {
                    return safeReason(flag);
                }
            }
        }
        return safeReason(fallback);
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String safe = SafeRedactor.traceLabelOrFallback(value, "unknown");
        String normalized = (safe == null || safe.isBlank() ? "unknown" : safe)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]+", "_");
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0d, 1.0d);
    }

    private static double round4(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        double lo = Math.min(min, max);
        double hi = Math.max(min, max);
        return Math.max(lo, Math.min(hi, value));
    }

    public record QualitySnapshot(
            int windowSize,
            double errorRateWindow,
            double errorRateEwma,
            double sampleScoreEwma,
            double contextContaminationEwma,
            double tuningDelta,
            double maxTrainErrorRate,
            boolean trainAllowed,
            String topProblem,
            Map<String, Integer> reasonCounts,
            List<String> flags) {
        public QualitySnapshot {
            windowSize = Math.max(0, windowSize);
            errorRateWindow = clamp01(errorRateWindow);
            errorRateEwma = clamp01(errorRateEwma);
            sampleScoreEwma = clamp01(sampleScoreEwma);
            contextContaminationEwma = clamp01(contextContaminationEwma);
            maxTrainErrorRate = clamp01(maxTrainErrorRate);
            topProblem = safeReason(topProblem == null || topProblem.isBlank() ? "none" : topProblem);
            reasonCounts = reasonCounts == null ? Map.of() : Map.copyOf(reasonCounts);
            flags = flags == null ? List.of() : List.copyOf(flags);
        }
    }

    public record CycleDiagnostics(
            int windowSize,
            double errorRateWindow,
            double errorRateEwma,
            double sampleScoreEwma,
            double contextContaminationEwma,
            double tuningDelta,
            double maxTrainErrorRate,
            boolean trainAllowed,
            String topProblem,
            String trainDecision,
            double acceptanceRate,
            Map<String, Integer> reasonCounts,
            List<String> flags) {
        public CycleDiagnostics {
            windowSize = Math.max(0, windowSize);
            errorRateWindow = clamp01(errorRateWindow);
            errorRateEwma = clamp01(errorRateEwma);
            sampleScoreEwma = clamp01(sampleScoreEwma);
            contextContaminationEwma = clamp01(contextContaminationEwma);
            maxTrainErrorRate = clamp01(maxTrainErrorRate);
            topProblem = safeReason(topProblem == null || topProblem.isBlank() ? "none" : topProblem);
            trainDecision = trainDecision == null || trainDecision.isBlank() ? "UNKNOWN" : trainDecision;
            acceptanceRate = clamp01(acceptanceRate);
            reasonCounts = reasonCounts == null ? Map.of() : Map.copyOf(reasonCounts);
            flags = flags == null ? List.of() : List.copyOf(flags);
        }
    }

    private record Outcome(boolean error, double sampleScore, double contextContamination, String reason) {
    }
}
