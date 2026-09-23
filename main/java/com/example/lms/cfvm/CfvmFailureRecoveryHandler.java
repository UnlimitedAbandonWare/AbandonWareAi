package com.example.lms.cfvm;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalizes interrupt-recovery signals into the CFVM failure-pattern lane.
 */
@Component
public class CfvmFailureRecoveryHandler {
    private static final String FAILURE_CLASS = "timeout_cancel_downgraded";
    private static final String DEFAULT_ROUTE_HINT = "fail_soft_fallback";

    private final ObjectProvider<RawMatrixBuffer> rawMatrixBufferProvider;
    private final ObjectProvider<CfvmJbCbCalculator> jbCbCalculatorProvider;
    private final ObjectProvider<RetrievalOrderService> retrievalOrderServiceProvider;
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;

    public CfvmFailureRecoveryHandler(ObjectProvider<RawMatrixBuffer> rawMatrixBufferProvider,
                                      ObjectProvider<CfvmJbCbCalculator> jbCbCalculatorProvider,
                                      ObjectProvider<RetrievalOrderService> retrievalOrderServiceProvider,
                                      ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        this.rawMatrixBufferProvider = rawMatrixBufferProvider;
        this.jbCbCalculatorProvider = jbCbCalculatorProvider;
        this.retrievalOrderServiceProvider = retrievalOrderServiceProvider;
        this.debugEventStoreProvider = debugEventStoreProvider;
    }

    public RecoveryDecision observeCancelShieldDowngrade(String owner,
                                                         boolean requestedInterrupt,
                                                         boolean cancelled) {
        TraceStore.put("cfvm.failureRecovery.considered", true);
        boolean timeoutCondition = timeoutCondition();
        TraceStore.put("cfvm.failureRecovery.cancelShield.enabled", true);
        TraceStore.put("cfvm.failureRecovery.cancelTrueDowngraded", requestedInterrupt);
        TraceStore.put("cfvm.failureRecovery.timeoutCondition", timeoutCondition);
        TraceStore.put("cfvm.failureRecovery.cancelled", cancelled);

        if (!requestedInterrupt) {
            traceSkip("cancel_true_absent");
            return RecoveryDecision.skipped("cancel_true_absent");
        }
        if (!timeoutCondition) {
            traceSkip("timeout_condition_absent");
            return RecoveryDecision.skipped("timeout_condition_absent");
        }

        RawMatrixBuffer buffer = rawMatrixBuffer();
        CfvmJbCbCalculator calculator = jbCbCalculator();
        if (buffer == null || calculator == null) {
            String reason = buffer == null ? "raw_matrix_buffer_unavailable" : "jb_cb_calculator_unavailable";
            traceSkip(reason);
            return RecoveryDecision.skipped(reason);
        }

        Map<String, Object> snapshot = new LinkedHashMap<>(TraceStore.getAll());
        seedChainDefaults(snapshot);
        CfvmJbCbCalculator.JbCbResult jbCb = calculate(calculator, snapshot);
        int activeTile = activeTile(jbCb);
        double valueScore = resourceValueScore(snapshot);
        double failureWeight = failureWeight(snapshot, valueScore);
        String source = recoverySource(snapshot);
        String signature = RawSlotExtractor.signature(snapshot);
        String signatureHash = SafeRedactor.hashValue(signature);
        int signatureLength = signature == null ? 0 : signature.length();
        long patternId = RawSlotExtractor.patternIdFromTrace(snapshot);
        int snapshotTraceSize = snapshot.size();

        buffer.offer(new RawMatrixBuffer.Entry(patternId, snapshotTraceSize, signatureLength));
        TraceStore.put("cfvm.failureRecovery.snapshot.saved", true);
        TraceStore.put("cfvm.failureRecovery.snapshot.traceSize", snapshotTraceSize);
        TraceStore.put("cfvm.failureRecovery.rawBuffer.size", buffer.size());
        TraceStore.put("cfvm.failureRecovery.patternIdHash", SafeRedactor.hashValue(String.valueOf(patternId)));
        TraceStore.put("cfvm.failureRecovery.patternIdPresent", patternId != 0L);
        buffer.updateWeight(activeTile, failureWeight);
        double[] weights = buffer.getWeights();
        double boltzmannWeight = activeTile >= 0 && activeTile < weights.length ? weights[activeTile] : 0.0d;
        boolean retrievalAdjusted = adjustRetrievalOrder(activeTile, weights);

        traceRecovery(owner, activeTile, valueScore, failureWeight, boltzmannWeight,
                source, signatureHash, signatureLength, retrievalAdjusted);
        emitDebugEvent(owner, activeTile, valueScore, failureWeight, timeoutCondition, requestedInterrupt);
        return new RecoveryDecision(true, activeTile, failureWeight, valueScore, retrievalAdjusted, "");
    }

    private static void seedChainDefaults(Map<String, Object> snapshot) {
        snapshot.putIfAbsent("chain.steps.planned", 3);
        snapshot.putIfAbsent("chain.steps.executed", 1);
        snapshot.putIfAbsent("chain.steps.failed", 1);
        snapshot.put("cfvm.failureRecovery.failureClass", FAILURE_CLASS);
        snapshot.put("cfvm.failureRecovery.cancelTrueDowngraded", true);
    }

    private static CfvmJbCbCalculator.JbCbResult calculate(CfvmJbCbCalculator calculator,
                                                          Map<String, Object> snapshot) {
        try {
            return calculator.calculate(snapshot);
        } catch (RuntimeException ex) {
            TraceStore.put("cfvm.failureRecovery.jbcb.suppressed", true);
            TraceStore.put("cfvm.failureRecovery.jbcb.suppressed.errorType", errorType(ex));
            return CfvmJbCbCalculator.JbCbResult.EMPTY;
        }
    }

    private static int activeTile(CfvmJbCbCalculator.JbCbResult jbCb) {
        CfvmJbCbCalculator.JbCbResult result = jbCb == null ? CfvmJbCbCalculator.JbCbResult.EMPTY : jbCb;
        int jbBin = Math.min((int) (Math.max(0.0d, result.jb()) * 3.0d), 2);
        int cbBin = Math.min((int) (Math.max(0.0d, result.cb()) * 3.0d), 2);
        return (jbBin * 3) + cbBin;
    }

    private boolean adjustRetrievalOrder(int activeTile, double[] weights) {
        RetrievalOrderService service = retrievalOrderService();
        if (service == null) {
            TraceStore.put("cfvm.failureRecovery.retrievalOrderAdjusted", false);
            TraceStore.put("cfvm.failureRecovery.retrievalOrderSkipReason", "retrieval_order_service_unavailable");
            return false;
        }
        try {
            boolean adjusted = service.adjustFromCfvm(activeTile, weights);
            TraceStore.put("cfvm.failureRecovery.retrievalOrderAdjusted", adjusted);
            TraceStore.put("cfvm.retrievalOrderAdjusted", adjusted);
            return adjusted;
        } catch (RuntimeException ex) {
            TraceStore.put("cfvm.failureRecovery.retrievalOrderAdjusted", false);
            TraceStore.put("cfvm.retrievalOrderAdjusted", false);
            TraceStore.put("cfvm.failureRecovery.retrievalOrderError", errorType(ex));
            TraceStore.put("cfvm.failureRecovery.retrievalOrderErrorHash",
                    SafeRedactor.hashValue(ex.getMessage()));
            return false;
        }
    }

    private void traceRecovery(String owner,
                               int activeTile,
                               double valueScore,
                               double failureWeight,
                               double boltzmannWeight,
                               String source,
                               String signatureHash,
                               int signatureLength,
                               boolean retrievalAdjusted) {
        TraceStore.put("cfvm.failureRecovery.triggered", true);
        TraceStore.put("cfvm.failureRecovery.preprocessed", true);
        TraceStore.put("cfvm.failureRecovery.snapshot.saved", true);
        TraceStore.put("cfvm.failureRecovery.failureClass", FAILURE_CLASS);
        TraceStore.put("cfvm.failureRecovery.activeTile", activeTile);
        TraceStore.put("cfvm.failureRecovery.resourceValueScore", valueScore);
        TraceStore.put("cfvm.failureRecovery.failureWeight", failureWeight);
        TraceStore.put("cfvm.failureRecovery.boltzmannWeight", boltzmannWeight);
        TraceStore.put("cfvm.failureRecovery.signatureHash", signatureHash);
        TraceStore.put("cfvm.failureRecovery.signatureLength", signatureLength);
        TraceStore.put("cfvm.failureRecovery.ownerHash", SafeRedactor.hashValue(owner));
        TraceStore.put("cfvm.failureRecovery.ownerLength", owner == null ? 0 : owner.length());
        TraceStore.put("cfvm.recovery.failureWeight", failureWeight);
        TraceStore.put("cfvm.recovery.valueScore", valueScore);
        TraceStore.put("cfvm.recovery.routeHint", DEFAULT_ROUTE_HINT);
        TraceStore.put("cfvm.recovery.source", source);
        TraceStore.put("cfvm.kalloc.recovery.applied", true);
        TraceStore.put("cfvm.kalloc.recovery.failureWeight", failureWeight);
        TraceStore.put("cfvm.kalloc.recovery.valueScore", valueScore);
        TraceStore.put("cfvm.kalloc.traceAnchor.routeHint", DEFAULT_ROUTE_HINT);
        TraceStore.put("cfvm.kalloc.traceAnchor.pressure", failureWeight);
        TraceStore.put("failpattern.searchRecovery.source", source);
        TraceStore.put("failpattern.searchRecovery.reason", FAILURE_CLASS);
        TraceStore.put("cfvm.failureRecovery.retrievalOrderAdjusted", retrievalAdjusted);
    }

    private void emitDebugEvent(String owner,
                                int activeTile,
                                double valueScore,
                                double failureWeight,
                                boolean timeoutCondition,
                                boolean cancelTrueDowngraded) {
        DebugEventStore store = debugEventStore();
        if (store == null) {
            TraceStore.put("cfvm.failureRecovery.debugEvent.skipped", "debug_event_store_unavailable");
            return;
        }
        try {
            store.emit(
                    DebugProbeType.AGENT_REPORT_CFVM,
                    DebugEventLevel.INFO,
                    "cfvm_failure_recovery_cancel_shield",
                    "CFVM failure recovery observed",
                    "CfvmFailureRecoveryHandler.observeCancelShieldDowngrade",
                    Map.of(
                            "activeTile", activeTile,
                            "resourceValueScore", valueScore,
                            "failureWeight", failureWeight,
                            "timeoutCondition", timeoutCondition,
                            "cancelTrueDowngraded", cancelTrueDowngraded,
                            "routeHint", DEFAULT_ROUTE_HINT,
                            "ownerHash", SafeRedactor.hashValue(owner),
                            "ownerLength", owner == null ? 0 : owner.length()),
                    null);
        } catch (RuntimeException ex) {
            TraceStore.put("cfvm.failureRecovery.debugEvent.failed", true);
            TraceStore.put("cfvm.failureRecovery.debugEvent.errorType", errorType(ex));
        }
    }

    private static double resourceValueScore(Map<String, Object> snapshot) {
        double fromResource = doubleValue(snapshot.get("resource.valueScore"), Double.NaN);
        if (Double.isFinite(fromResource)) {
            return clamp01(fromResource);
        }
        double fromKalloc = doubleValue(snapshot.get("cfvm.kalloc.valueScore"), Double.NaN);
        return Double.isFinite(fromKalloc) ? clamp01(fromKalloc) : 0.50d;
    }

    private static double failureWeight(Map<String, Object> snapshot, double valueScore) {
        double explicit = doubleValue(snapshot.get("resource.failureWeight"), Double.NaN);
        if (!Double.isFinite(explicit)) {
            explicit = doubleValue(snapshot.get("cfvm.recovery.failureWeight"), Double.NaN);
        }
        if (Double.isFinite(explicit)) {
            return clamp01(explicit);
        }
        return clamp01(Math.max(0.90d, 0.50d + (0.50d * valueScore)));
    }

    private static String recoverySource(Map<String, Object> snapshot) {
        String source = safeLabel(snapshot.get("web.await.root.engine"), "");
        if (source == null || source.isBlank() || "unknown".equals(source)) {
            source = safeLabel(snapshot.get("web.await.root.stage"), "");
        }
        if (source == null || source.isBlank() || "unknown".equals(source)) {
            return "web";
        }
        if (source.contains("naver") || source.contains("brave") || source.contains("serp")) {
            return source;
        }
        if (source.contains("web")) {
            return "web";
        }
        return source;
    }

    private static boolean timeoutCondition() {
        return TraceStore.get("timeout.stage") != null
                || Boolean.TRUE.equals(TraceStore.get("ops.cancelShield.invokeAll.timeout.timedOut"))
                || Boolean.TRUE.equals(TraceStore.get("ops.cancelShield.invokeAny.timedOut"));
    }

    private static void traceSkip(String reason) {
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        TraceStore.put("cfvm.failureRecovery.triggered", false);
        TraceStore.put("cfvm.failureRecovery.preprocessed", false);
        TraceStore.put("cfvm.failureRecovery.skipReason", safeReason);
    }

    private RawMatrixBuffer rawMatrixBuffer() {
        if (rawMatrixBufferProvider == null) {
            return null;
        }
        try {
            return rawMatrixBufferProvider.getIfAvailable();
        } catch (BeansException | IllegalStateException ex) {
            TraceStore.put("cfvm.failureRecovery.provider.suppressed", true);
            TraceStore.put("cfvm.failureRecovery.provider.suppressed.stage", "rawMatrixBuffer");
            TraceStore.put("cfvm.failureRecovery.provider.suppressed.errorType", errorType(ex));
            return null;
        }
    }

    private CfvmJbCbCalculator jbCbCalculator() {
        if (jbCbCalculatorProvider == null) {
            return null;
        }
        try {
            return jbCbCalculatorProvider.getIfAvailable();
        } catch (BeansException | IllegalStateException ex) {
            TraceStore.put("cfvm.failureRecovery.provider.suppressed", true);
            TraceStore.put("cfvm.failureRecovery.provider.suppressed.stage", "jbCbCalculator");
            TraceStore.put("cfvm.failureRecovery.provider.suppressed.errorType", errorType(ex));
            return null;
        }
    }

    private RetrievalOrderService retrievalOrderService() {
        if (retrievalOrderServiceProvider == null) {
            return null;
        }
        try {
            return retrievalOrderServiceProvider.getIfAvailable();
        } catch (BeansException | IllegalStateException ex) {
            TraceStore.put("cfvm.failureRecovery.provider.suppressed", true);
            TraceStore.put("cfvm.failureRecovery.provider.suppressed.stage", "retrievalOrderService");
            TraceStore.put("cfvm.failureRecovery.provider.suppressed.errorType", errorType(ex));
            return null;
        }
    }

    private DebugEventStore debugEventStore() {
        if (debugEventStoreProvider == null) {
            return null;
        }
        try {
            return debugEventStoreProvider.getIfAvailable();
        } catch (BeansException | IllegalStateException ex) {
            TraceStore.put("cfvm.failureRecovery.provider.suppressed", true);
            TraceStore.put("cfvm.failureRecovery.provider.suppressed.stage", "debugEventStore");
            TraceStore.put("cfvm.failureRecovery.provider.suppressed.errorType", errorType(ex));
            return null;
        }
    }

    private static double doubleValue(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            return Double.isFinite(parsed) ? parsed : fallback;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException ex) {
            TraceStore.put("cfvm.failureRecovery.suppressed.doubleValue", true);
            TraceStore.put("cfvm.failureRecovery.suppressed.doubleValue.errorType", "invalid_number");
            return fallback;
        }
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0.0d, Math.min(1.0d, value)) : 0.0d;
    }

    private static String safeLabel(Object value, String fallback) {
        return SafeRedactor.traceLabelOrFallback(value == null ? null : String.valueOf(value), fallback);
    }

    private static String errorType(Throwable ex) {
        if (ex instanceof NumberFormatException) {
            return "invalid_number";
        }
        return ex == null ? "unknown" : SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown");
    }

    public record RecoveryDecision(
            boolean applied,
            int activeTile,
            double failureWeight,
            double valueScore,
            boolean retrievalOrderAdjusted,
            String skipReason) {
        static RecoveryDecision skipped(String reason) {
            return new RecoveryDecision(false, -1, 0.0d, 0.0d, false,
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        }
    }
}
