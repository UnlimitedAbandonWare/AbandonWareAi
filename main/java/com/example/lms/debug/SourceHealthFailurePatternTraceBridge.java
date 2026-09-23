package com.example.lms.debug;

import com.example.lms.cfvm.CfvmFailureRecorder;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors the repo-local source-health scorecard prediction into existing
 * runtime observability seams.
 */
@Service
public class SourceHealthFailurePatternTraceBridge {
    private static final String DEFAULT_KIND = "unknown_source_health_risk";
    private static final String DEFAULT_PATTERN_ID = "FP-SOURCE-HEALTH-UNKNOWN";
    private static final String DEFAULT_SOURCE_RISK = "unknown";
    private static final int MAX_TRACE_KEYS = 24;

    private final DebugEventStore debugEventStore;
    private final ObjectProvider<CfvmFailureRecorder> cfvmFailureRecorderProvider;

    public SourceHealthFailurePatternTraceBridge(
            DebugEventStore debugEventStore,
            ObjectProvider<CfvmFailureRecorder> cfvmFailureRecorderProvider) {
        this.debugEventStore = debugEventStore;
        this.cfvmFailureRecorderProvider = cfvmFailureRecorderProvider;
    }

    public RecordResult recordPrediction(Map<String, Object> prediction, String sourceStage) {
        if (prediction == null || prediction.isEmpty()) {
            TraceStore.put("sourceHealth.bridge.skipped", "missing_prediction");
            return new RecordResult(false, false, "missing_prediction");
        }

        String kind = safeId(prediction.get("failurePatternKind"), DEFAULT_KIND);
        String patternId = safeId(prediction.get("patternId"), DEFAULT_PATTERN_ID);
        String sourceRiskId = safeId(prediction.get("sourceRiskId"), DEFAULT_SOURCE_RISK);
        String stage = safeId(sourceStage, "sourceHealthScorecard");
        double riskScore = clampScore(prediction.get("riskScore"));
        double amplifiedSignalScore = clampScore(prediction.get("amplifiedSignalScore"));
        boolean clamped = wasClamped(prediction.get("riskScore")) || wasClamped(prediction.get("amplifiedSignalScore"));
        List<String> traceKeys = safeTraceKeys(prediction.get("traceStoreKeys"));
        List<String> amplifierTraceKeys = safeTraceKeys(prediction.get("amplifierTraceKeys"));
        String manifestHash = safeHash(prediction.get("patchDropManifestHash"));

        traceSourceHealth(kind, patternId, sourceRiskId, stage, riskScore,
                amplifiedSignalScore, clamped, traceKeys, amplifierTraceKeys, manifestHash);

        CfvmResult cfvm = recordCfvm(kind, sourceRiskId, stage, riskScore,
                amplifiedSignalScore, traceKeys, amplifierTraceKeys);
        boolean debugEventEmitted = emitDebugEvent(kind, patternId, sourceRiskId, stage, riskScore,
                amplifiedSignalScore, traceKeys, amplifierTraceKeys, manifestHash, cfvm);
        return new RecordResult(debugEventEmitted, cfvm.recorded(), cfvm.skipReason());
    }

    private static void traceSourceHealth(String kind,
                                          String patternId,
                                          String sourceRiskId,
                                          String stage,
                                          double riskScore,
                                          double amplifiedSignalScore,
                                          boolean clamped,
                                          List<String> traceKeys,
                                          List<String> amplifierTraceKeys,
                                          String manifestHash) {
        TraceStore.put("sourceHealth.runtimeBridge", "trace_debug_cfvm");
        TraceStore.put("sourceHealth.sourceStage", stage);
        TraceStore.put("sourceHealth.failurePatternKind", kind);
        TraceStore.put("sourceHealth.patternId", patternId);
        TraceStore.put("sourceHealth.sourceRiskId", sourceRiskId);
        TraceStore.put("sourceHealth.riskScore", riskScore);
        TraceStore.put("sourceHealth.amplifiedSignalScore", amplifiedSignalScore);
        TraceStore.put("sourceHealth.traceStoreKeyCount", traceKeys.size());
        TraceStore.put("sourceHealth.amplifierTraceKeyCount", amplifierTraceKeys.size());
        TraceStore.put("sourceHealth.runtimeScoreClaim", false);
        TraceStore.put("sourceHealth.producerExecutionObserved", false);
        TraceStore.put("hypernova.twpmP", 4.0d);
        TraceStore.put("hypernova.cvarPhi", amplifiedSignalScore);
        TraceStore.put("hypernova.riskKAlloc", riskKAlloc(amplifiedSignalScore));
        TraceStore.put("hypernova.clampApplied", clamped);
        if (manifestHash != null) {
            TraceStore.put("sourceHealth.patchDropManifestHash", manifestHash);
        }
    }

    private CfvmResult recordCfvm(String kind,
                                  String sourceRiskId,
                                  String stage,
                                  double riskScore,
                                  double amplifiedSignalScore,
                                  List<String> traceKeys,
                                  List<String> amplifierTraceKeys) {
        CfvmFailureRecorder recorder;
        try {
            recorder = cfvmFailureRecorderProvider == null ? null : cfvmFailureRecorderProvider.getIfAvailable();
        } catch (BeansException | IllegalStateException ex) {
            traceCfvmSkipped("cfvm_recorder_provider_error", ex);
            return new CfvmResult(false, "cfvm_recorder_provider_error", "");
        }
        if (recorder == null) {
            TraceStore.put("sourceHealth.cfvm.recorded", false);
            TraceStore.put("sourceHealth.cfvm.skipReason", "cfvm_recorder_unavailable");
            return new CfvmResult(false, "cfvm_recorder_unavailable", "");
        }
        try {
            Map<String, Object> cfvmTrace = new LinkedHashMap<>();
            cfvmTrace.put("sourceHealth.failurePatternKind", kind);
            cfvmTrace.put("sourceHealth.sourceRiskId", sourceRiskId);
            cfvmTrace.put("sourceHealth.riskScore", riskScore);
            cfvmTrace.put("sourceHealth.amplifiedSignalScore", amplifiedSignalScore);
            cfvmTrace.put("sourceHealth.traceStoreKeyCount", traceKeys.size());
            cfvmTrace.put("sourceHealth.amplifierTraceKeyCount", amplifierTraceKeys.size());
            cfvmTrace.put("hypernova.twpmP", 4.0d);
            cfvmTrace.put("hypernova.cvarPhi", amplifiedSignalScore);
            cfvmTrace.put("hypernova.riskKAlloc", riskKAlloc(amplifiedSignalScore));
            cfvmTrace.put("chain.steps.planned", 9);
            cfvmTrace.put("chain.steps.executed", riskKAlloc(amplifiedSignalScore));
            cfvmTrace.put("chain.steps.failed", Math.max(0, 9 - riskKAlloc(amplifiedSignalScore)));
            CfvmFailureRecorder.RecordResult result = recorder.record(
                    "source-health",
                    kind,
                    sourceRiskId,
                    "",
                    cfvmTrace);
            boolean recorded = result != null && result.buffered();
            String patternHex = result == null ? "" : safeId(result.patternId(), "");
            TraceStore.put("sourceHealth.cfvm.recorded", recorded);
            TraceStore.put("sourceHealth.cfvm.memoryRecorded", result != null && result.memoryRecorded());
            TraceStore.put("sourceHealth.cfvm.patternHex", patternHex);
            if (!recorded) {
                TraceStore.put("sourceHealth.cfvm.skipReason", "cfvm_buffer_unavailable");
            }
            return new CfvmResult(recorded, recorded ? "" : "cfvm_buffer_unavailable", patternHex);
        } catch (RuntimeException ex) {
            traceCfvmSkipped("cfvm_record_failed", ex);
            return new CfvmResult(false, "cfvm_record_failed", "");
        }
    }

    private boolean emitDebugEvent(String kind,
                                   String patternId,
                                   String sourceRiskId,
                                   String stage,
                                   double riskScore,
                                   double amplifiedSignalScore,
                                   List<String> traceKeys,
                                   List<String> amplifierTraceKeys,
                                   String manifestHash,
                                   CfvmResult cfvm) {
        if (debugEventStore == null) {
            TraceStore.put("sourceHealth.debugEvent.emitted", false);
            TraceStore.put("sourceHealth.debugEvent.skipReason", "debug_event_store_unavailable");
            return false;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stage", "source_health_failure_pattern");
        data.put("sourceStage", stage);
        data.put("failureClass", "source_health.failure_pattern_prediction");
        data.put("failurePatternKind", kind);
        data.put("patternId", patternId);
        data.put("sourceRiskId", sourceRiskId);
        data.put("riskScore", riskScore);
        data.put("amplifiedSignalScore", amplifiedSignalScore);
        data.put("traceStoreKeyCount", traceKeys.size());
        data.put("amplifierTraceKeyCount", amplifierTraceKeys.size());
        data.put("traceStoreKeys", traceKeys);
        data.put("amplifierTraceKeys", amplifierTraceKeys);
        data.put("patchDropManifestHash", manifestHash);
        data.put("runtimeScoreClaim", false);
        data.put("producerExecutionObserved", false);
        data.put("cfvmRecorded", cfvm.recorded());
        data.put("cfvmSkipReason", cfvm.skipReason());
        data.put("cfvmPatternHex", cfvm.patternHex());
        data.put("promotedFromTraceStore", true);
        try {
            debugEventStore.emit(
                    DebugProbeType.TRACE_MEMORY,
                    amplifiedSignalScore > 0.0d ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                    "sourceHealth.failurePattern." + SafeRedactor.hash12(kind + "|" + patternId),
                    "[AWX][source-health] Failure-pattern prediction promoted to TraceStore/CFVM",
                    stage,
                    data,
                    null);
            TraceStore.put("sourceHealth.debugEvent.emitted", true);
            return true;
        } catch (RuntimeException ex) {
            TraceStore.put("sourceHealth.debugEvent.emitted", false);
            TraceStore.put("sourceHealth.debugEvent.skipReason", "debug_event_emit_failed");
            TraceStore.put("sourceHealth.debugEvent.errorType",
                    ex == null ? "unknown" : ex.getClass().getSimpleName());
            return false;
        }
    }

    private static void traceCfvmSkipped(String reason, RuntimeException ex) {
        TraceStore.put("sourceHealth.cfvm.recorded", false);
        TraceStore.put("sourceHealth.cfvm.skipReason", reason);
        TraceStore.put("sourceHealth.cfvm.errorType", ex == null ? "unknown" : ex.getClass().getSimpleName());
    }

    private static String safeId(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim().replaceAll("[\\r\\n\\t]+", " ");
        if (text.isBlank()) {
            return fallback;
        }
        String lower = text.toLowerCase();
        boolean sensitive = lower.contains("authorization")
                || lower.contains("owner-token")
                || lower.contains("owner_token")
                || lower.contains("ownertoken")
                || lower.contains("cookie")
                || lower.contains("secret=")
                || lower.contains("token=")
                || lower.contains("api_key=")
                || lower.contains("apikey=")
                || lower.contains("password=")
                || text.matches("(?i).*("
                        + "sk-[A-Za-z0-9_-]{20,}|"
                        + "AIza[0-9A-Za-z_-]{20,}|"
                        + "gsk_[A-Za-z0-9]{20,}|"
                        + "pcsk_[A-Za-z0-9_-]{20,}|"
                        + "sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}"
                        + ").*");
        if (!sensitive && text.matches("[A-Za-z0-9_.:\\-]{1,120}")) {
            return text;
        }
        String hash = SafeRedactor.hashValue(text);
        return hash == null || hash.isBlank() ? fallback : hash;
    }

    private static String safeHash(Object value) {
        String text = safeId(value, "");
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.matches("[a-fA-F0-9]{12,128}") || text.startsWith("hash:") ? text : SafeRedactor.hashValue(text);
    }

    private static List<String> safeTraceKeys(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addTraceKey(out, item);
            }
        } else {
            addTraceKey(out, value);
        }
        return List.copyOf(out);
    }

    private static void addTraceKey(List<String> out, Object value) {
        if (out.size() >= MAX_TRACE_KEYS) {
            return;
        }
        String key = safeId(value, "");
        if (key != null && !key.isBlank() && !key.startsWith("hash:")) {
            out.add(key);
        }
    }

    private static double clampScore(Object value) {
        double parsed = parseDouble(value, 0.0d);
        if (!Double.isFinite(parsed)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, parsed));
    }

    private static boolean wasClamped(Object value) {
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            return !Double.isFinite(parsed) || parsed < 0.0d || parsed > 1.0d;
        }
        if (value == null) {
            return false;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return !Double.isFinite(parsed) || parsed < 0.0d || parsed > 1.0d;
        } catch (RuntimeException ex) {
            traceScoreParseFallback(ex);
            return true;
        }
    }

    private static double parseDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (RuntimeException ex) {
            traceScoreParseFallback(ex);
            return fallback;
        }
    }

    private static void traceScoreParseFallback(RuntimeException ex) {
        TraceStore.put("sourceHealth.scoreParseFallback", true);
        TraceStore.put("sourceHealth.scoreParseFallback.errorType",
                ex == null ? "unknown" : ex.getClass().getSimpleName());
    }

    private static int riskKAlloc(double amplifiedSignalScore) {
        return Math.max(0, Math.min(9, (int) Math.ceil(clampScore(amplifiedSignalScore) * 9.0d)));
    }

    public record RecordResult(
            boolean debugEventEmitted,
            boolean cfvmRecorded,
            String cfvmSkipReason) {
    }

    private record CfvmResult(
            boolean recorded,
            String skipReason,
            String patternHex) {
    }
}
