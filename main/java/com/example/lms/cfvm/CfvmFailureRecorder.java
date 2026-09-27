package com.example.lms.cfvm;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.example.lms.search.TraceStore;
import com.example.lms.orchestration.control.RagControlLearningGate;
import com.example.lms.orchestration.control.RagControlRuntimeAdapter;
import com.example.lms.service.TrainingService;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bridges compact CFVM trace signatures into the failure-pattern memory lane.
 */
@Component
public class CfvmFailureRecorder {
    public static final String RAG_ORIGIN_MARKER = "ragOrigin";
    private static final String DEFAULT_SOURCE = "cfvm";
    private static final String DEFAULT_HOTSPOT = "orchestration";

    private final ObjectProvider<RawMatrixBuffer> rawMatrixBufferProvider;
    private final ObjectProvider<FailurePatternMemoryService> failurePatternMemoryProvider;
    private final ObjectProvider<CfvmJbCbCalculator> jbCbCalculatorProvider;
    private final ObjectProvider<RetrievalOrderService> retrievalOrderServiceProvider;
    private final ObjectProvider<TrainingService> trainingServiceProvider;
    private final ObjectProvider<RagControlLearningGate> ragControlLearningGateProvider;

    @Autowired
    public CfvmFailureRecorder(ObjectProvider<RawMatrixBuffer> rawMatrixBufferProvider,
                               ObjectProvider<FailurePatternMemoryService> failurePatternMemoryProvider,
                               ObjectProvider<CfvmJbCbCalculator> jbCbCalculatorProvider,
                               ObjectProvider<RetrievalOrderService> retrievalOrderServiceProvider,
                               ObjectProvider<TrainingService> trainingServiceProvider,
                               ObjectProvider<RagControlLearningGate> ragControlLearningGateProvider) {
        this.rawMatrixBufferProvider = rawMatrixBufferProvider;
        this.failurePatternMemoryProvider = failurePatternMemoryProvider;
        this.jbCbCalculatorProvider = jbCbCalculatorProvider;
        this.retrievalOrderServiceProvider = retrievalOrderServiceProvider;
        this.trainingServiceProvider = trainingServiceProvider;
        this.ragControlLearningGateProvider = ragControlLearningGateProvider;
    }

    public CfvmFailureRecorder(ObjectProvider<RawMatrixBuffer> rawMatrixBufferProvider,
                               ObjectProvider<FailurePatternMemoryService> failurePatternMemoryProvider,
                               ObjectProvider<CfvmJbCbCalculator> jbCbCalculatorProvider,
                               ObjectProvider<RetrievalOrderService> retrievalOrderServiceProvider,
                               ObjectProvider<TrainingService> trainingServiceProvider) {
        this(rawMatrixBufferProvider,
                failurePatternMemoryProvider,
                jbCbCalculatorProvider,
                retrievalOrderServiceProvider,
                trainingServiceProvider,
                null);
    }

    public CfvmFailureRecorder(ObjectProvider<RawMatrixBuffer> rawMatrixBufferProvider,
                               ObjectProvider<FailurePatternMemoryService> failurePatternMemoryProvider,
                               ObjectProvider<CfvmJbCbCalculator> jbCbCalculatorProvider,
                               ObjectProvider<RetrievalOrderService> retrievalOrderServiceProvider) {
        this(rawMatrixBufferProvider, failurePatternMemoryProvider, jbCbCalculatorProvider, retrievalOrderServiceProvider, null);
    }

    public CfvmFailureRecorder(ObjectProvider<RawMatrixBuffer> rawMatrixBufferProvider,
                               ObjectProvider<FailurePatternMemoryService> failurePatternMemoryProvider,
                               ObjectProvider<CfvmJbCbCalculator> jbCbCalculatorProvider) {
        this(rawMatrixBufferProvider, failurePatternMemoryProvider, jbCbCalculatorProvider, null);
    }

    public CfvmFailureRecorder(ObjectProvider<RawMatrixBuffer> rawMatrixBufferProvider,
                               ObjectProvider<FailurePatternMemoryService> failurePatternMemoryProvider) {
        this(rawMatrixBufferProvider, failurePatternMemoryProvider, null);
    }

    public RecordResult record(String source,
                               String failureClass,
                               String hotspot,
                               String sessionId,
                               Map<String, Object> trace) {
        Map<String, Object> snapshot = trace == null ? new LinkedHashMap<>() : new LinkedHashMap<>(trace);
        boolean explicitRagOrigin = booleanValue(snapshot.remove(RAG_ORIGIN_MARKER));
        TraceStore.put("cfvm.ragControl.present", false);
        TraceStore.put("cfvm.ragControl.hold", false);
        TraceStore.put("cfvm.ragControl.failureClass", null);
        TraceStore.put("cfvm.ragControl.errorType", null);
        CfvmJbCbCalculator.JbCbResult jbCb = calculateJbCb(snapshot);
        if (jbCb != null) {
            snapshot.put("cfvm.jb.score", jbCb.jb());
            snapshot.put("cfvm.cb.score", jbCb.cb());
        }
        String signature = RawSlotExtractor.signature(snapshot);
        long patternId = RawSlotExtractor.patternIdFromTrace(snapshot);
        String patternHex = Long.toHexString(patternId);
        int signatureLength = signature == null ? 0 : signature.length();
        int traceSize = snapshot.size();

        if (shouldHoldRagWrites(source, failureClass, snapshot, explicitRagOrigin)) {
            return heldResult(source, failureClass, hotspot, patternHex, signatureLength, traceSize);
        }
        if (classifyOutcome(failureClass) == FailureOutcome.NON_CANCEL_DOWNGRADE) {
            return nonMutatingResult(
                    source, failureClass, hotspot, patternHex, signatureLength, traceSize);
        }

        boolean buffered = buffer(patternId, traceSize, signatureLength);
        boolean memoryRecorded = recordMemory(source, failureClass, hotspot, sessionId, signature,
                patternHex, traceSize, signatureLength);
        traceRecordContract(source, failureClass, hotspot, buffered, memoryRecorded);

        TraceStore.put("cfvm.recorder.patternId", patternHex);
        TraceStore.put("cfvm.recorder.signature.len", signatureLength);
        TraceStore.put("cfvm.recorder.trace.count", traceSize);
        TraceStore.put("cfvm.recorder.buffered", buffered);
        TraceStore.put("cfvm.recorder.memory.recorded", memoryRecorded);
        TraceStore.put("cfvm.buffered", buffered);
        TraceStore.put("cfvm.memoryRecorded", memoryRecorded);
        TraceStore.put("cfvm.patternHex", patternHex);
        TraceStore.put("cfvm.failureRecorder", "recorded");
        TraceStore.put("cfvm.slot.extracted", true);
        traceNormalizedContract(jbCb, patternHex);
        boolean trainingRecorded = recordTrainingFailurePattern(sessionId, failureClass, signature, boltzmannWeight(jbCb));
        TraceStore.put("cfvm.training.recorded", trainingRecorded);
        return new RecordResult(buffered, memoryRecorded, patternHex, signatureLength, traceSize);
    }

    private static FailureOutcome classifyOutcome(String failureClass) {
        return "quality_downgrade".equals(normalized(failureClass))
                ? FailureOutcome.NON_CANCEL_DOWNGRADE
                : FailureOutcome.RECORDABLE_FAILURE;
    }

    private RecordResult nonMutatingResult(
            String source,
            String failureClass,
            String hotspot,
            String patternHex,
            int signatureLength,
            int traceSize) {
        traceRecordContract(source, failureClass, hotspot, false, false);
        TraceStore.put("cfvm.record.skipReason", "non_cancel_downgrade");
        TraceStore.put("cfvm.recorder.patternId", patternHex);
        TraceStore.put("cfvm.recorder.signature.len", signatureLength);
        TraceStore.put("cfvm.recorder.trace.count", traceSize);
        TraceStore.put("cfvm.recorder.buffered", false);
        TraceStore.put("cfvm.recorder.memory.recorded", false);
        TraceStore.put("cfvm.buffered", false);
        TraceStore.put("cfvm.memoryRecorded", false);
        TraceStore.put("cfvm.patternHex", patternHex);
        TraceStore.put("cfvm.failureRecorder", "skipped");
        TraceStore.put("cfvm.slot.extracted", true);
        TraceStore.put("cfvm.triggered", false);
        TraceStore.put("cfvm.retrievalOrderAdjusted", false);
        TraceStore.put("cfvm.retrievalOrderDisabledReason", "non_cancel_downgrade");
        TraceStore.put("cfvm.recoveryPath", "[]");
        TraceStore.put("cfvm.recoveryPathDisabledReason", "non_cancel_downgrade");
        TraceStore.put("cfvm.training.recorded", false);
        return new RecordResult(false, false, patternHex, signatureLength, traceSize);
    }

    private enum FailureOutcome {
        RECORDABLE_FAILURE,
        NON_CANCEL_DOWNGRADE
    }

    private boolean shouldHoldRagWrites(
            String source,
            String failureClass,
            Map<String, Object> snapshot,
            boolean explicitRagOrigin) {
        if (!isRagOrigin(explicitRagOrigin)) {
            return false;
        }
        try {
            RagControlLearningGate gate = ragControlLearningGateProvider == null
                    ? null
                    : ragControlLearningGateProvider.getIfAvailable();
            if (gate == null) {
                TraceStore.put("cfvm.ragControl.present", false);
                TraceStore.put("cfvm.ragControl.hold", true);
                TraceStore.put("cfvm.ragControl.failureClass", "learning_gate_unavailable");
                return true;
            }
            TraceStore.put("cfvm.ragControl.present", true);
            TraceStore.put("cfvm.ragControl.failureClass", null);
            boolean verificationKnown = snapshot.containsKey("verificationAccepted")
                    || snapshot.containsKey("finalAnswer.verificationAcceptedForMemory");
            boolean verificationAccepted = booleanValue(snapshot.get("verificationAccepted"))
                    || booleanValue(snapshot.get("finalAnswer.verificationAcceptedForMemory"));
            boolean answerBlank = !booleanValueOrDefault(snapshot.get("responseObserved"), true)
                    || "silent_failure".equals(normalized(failureClass))
                    || "model_blank".equals(normalized(failureClass));
            RagControlLearningGate.Decision decision = gate.evaluate(
                    RagControlLearningGate.Boundary.CFVM_RAG_PRE_WRITE,
                    new RagControlRuntimeAdapter.RuntimeInput(
                            true,
                            firstCount(snapshot, "returnedCount", "outCount"),
                            firstCount(snapshot, "afterFilterCount", "promotedCount"),
                            answerBlank,
                            verificationKnown,
                            verificationAccepted,
                            booleanValue(snapshot.get("hardGuardHeld"))));
            TraceStore.put("cfvm.ragControl.hold", decision.holdWrites());
            return decision.holdWrites();
        } catch (RuntimeException controlFailure) {
            TraceStore.put("cfvm.ragControl.present", false);
            TraceStore.put("cfvm.ragControl.hold", true);
            TraceStore.put("cfvm.ragControl.failureClass", "learning_gate_failed");
            TraceStore.put("cfvm.ragControl.errorType", SafeRedactor.traceLabelOrFallback(
                    controlFailure.getClass().getSimpleName(), "runtime_exception"));
            return true;
        }
    }

    private RecordResult heldResult(
            String source,
            String failureClass,
            String hotspot,
            String patternHex,
            int signatureLength,
            int traceSize) {
        traceRecordContract(source, failureClass, hotspot, false, false);
        TraceStore.put("cfvm.record.skipReason", "rag_control_hold");
        TraceStore.put("cfvm.recorder.patternId", patternHex);
        TraceStore.put("cfvm.recorder.signature.len", signatureLength);
        TraceStore.put("cfvm.recorder.trace.count", traceSize);
        TraceStore.put("cfvm.recorder.buffered", false);
        TraceStore.put("cfvm.recorder.memory.recorded", false);
        TraceStore.put("cfvm.buffered", false);
        TraceStore.put("cfvm.memoryRecorded", false);
        TraceStore.put("cfvm.patternHex", patternHex);
        TraceStore.put("cfvm.failureRecorder", "skipped");
        TraceStore.put("cfvm.slot.extracted", true);
        TraceStore.put("cfvm.triggered", false);
        TraceStore.put("cfvm.retrievalOrderAdjusted", false);
        TraceStore.put("cfvm.retrievalOrderDisabledReason", "rag_control_hold");
        TraceStore.put("cfvm.recoveryPath", "[]");
        TraceStore.put("cfvm.recoveryPathDisabledReason", "rag_control_hold");
        TraceStore.put("cfvm.training.recorded", false);
        return new RecordResult(false, false, patternHex, signatureLength, traceSize);
    }

    private static boolean isRagOrigin(boolean explicitRagOrigin) {
        return explicitRagOrigin;
    }

    private static int firstCount(Map<String, Object> snapshot, String first, String second) {
        if (snapshot == null) {
            return 0;
        }
        for (String key : new String[] { first, second }) {
            Object value = snapshot.get(key);
            if (value instanceof Number number) {
                return Math.max(0, number.intValue());
            }
        }
        return 0;
    }

    private static boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value)
                || (value instanceof String text && "true".equalsIgnoreCase(text.trim()));
    }

    private static boolean booleanValueOrDefault(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return booleanValue(value);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void traceNormalizedContract(CfvmJbCbCalculator.JbCbResult jbCb, String patternHex) {
        TraceStore.put("cfvm.triggered", true);
        TraceStore.put("cfvm.rawTileId", patternHex);
        TraceStore.put("cfvm.retrievalOrderAdjusted", false);
        TraceStore.put("cfvm.recoveryPath", "[]");
        if (jbCb == null) {
            traceRetrievalOrderDisabled("jb_cb_unavailable");
            return;
        }
        int tile = activeTile(jbCb);
        TraceStore.put("cfvm.activeTile", tile);
        RawMatrixBuffer buffer = rawMatrixBuffer();
        if (buffer == null) {
            traceRetrievalOrderDisabled("raw_matrix_buffer_unavailable");
            return;
        }
        double[] weights = buffer.getWeights();
        if (tile >= 0 && tile < weights.length) {
            TraceStore.put("cfvm.boltzmannWeight", weights[tile]);
        }
        applyRetrievalOrder(tile, weights);
    }

    private static int activeTile(CfvmJbCbCalculator.JbCbResult jbCb) {
        int jbBin = Math.min((int) (Math.max(0.0d, jbCb.jb()) * 3.0d), 2);
        int cbBin = Math.min((int) (Math.max(0.0d, jbCb.cb()) * 3.0d), 2);
        return (jbBin * 3) + cbBin;
    }

    private CfvmJbCbCalculator.JbCbResult calculateJbCb(Map<String, Object> snapshot) {
        CfvmJbCbCalculator calculator = jbCbCalculator();
        if (calculator == null) {
            TraceStore.put("cfvm.jbcb.absent", true);
            return null;
        }
        try {
            return calculator.calculate(snapshot);
        } catch (RuntimeException ex) {
            TraceStore.put("cfvm.jbcb.error", ex == null ? "unknown" : ex.getClass().getSimpleName());
            return null;
        }
    }

    private boolean buffer(long patternId, int traceSize, int signatureLength) {
        RawMatrixBuffer buffer = rawMatrixBuffer();
        if (buffer == null) {
            return false;
        }
        try {
            buffer.add(patternId, traceSize, signatureLength);
            traceBoltzmannTemperature(buffer);
            return true;
        } catch (RuntimeException ex) {
            traceBoltzmannTemperature(buffer);
            traceBufferFailure(ex);
            return false;
        }
    }

    private static void traceRecordContract(String source,
                                            String failureClass,
                                            String hotspot,
                                            boolean buffered,
                                            boolean memoryRecorded) {
        TraceStore.put("cfvm.record.source", safeLabel(source, DEFAULT_SOURCE));
        TraceStore.put("cfvm.record.failureClass", safeLabel(failureClass, "unknown"));
        TraceStore.put("cfvm.record.hotspot", safeLabel(hotspot, DEFAULT_HOTSPOT));
        TraceStore.put("cfvm.record.buffered", buffered);
        TraceStore.put("cfvm.record.memoryRecorded", memoryRecorded);
        TraceStore.put("cfvm.record.skipReason", recordSkipReason(buffered, memoryRecorded));
    }

    private static void traceBoltzmannTemperature(RawMatrixBuffer buffer) {
        if (buffer == null) {
            return;
        }
        double temp = buffer.getBoltzmannTemp();
        TraceStore.put("cfvm.record.boltzmannTemp", temp);
        TraceStore.put("cfvm.boltzmannTemp", temp);
    }

    private static void traceBufferFailure(RuntimeException ex) {
        String message = ex == null ? null : ex.getMessage();
        TraceStore.put("cfvm.record.bufferFailed", true);
        TraceStore.put("cfvm.record.bufferError",
                SafeRedactor.traceLabelOrFallback(ex == null ? null : ex.getClass().getSimpleName(), "unknown"));
        TraceStore.put("cfvm.record.bufferErrorHash", SafeRedactor.hashValue(message));
        TraceStore.put("cfvm.record.bufferErrorLength", message == null ? 0 : message.length());
        TraceStore.put("cfvm.record.bufferFailureReason", "raw_matrix_buffer_error");
        TraceStore.put("cfvm.record.skipReason", "raw_matrix_buffer_error");
    }

    private RawMatrixBuffer rawMatrixBuffer() {
        if (rawMatrixBufferProvider == null) {
            TraceStore.put("cfvm.buffer.absent", true);
            return null;
        }
        try {
            RawMatrixBuffer buffer = rawMatrixBufferProvider.getIfAvailable();
            if (buffer == null) {
                TraceStore.put("cfvm.buffer.absent", true);
            }
            return buffer;
        } catch (BeansException | IllegalStateException ex) {
            traceSuppressedProvider("rawMatrixBuffer", ex);
            return null;
        }
    }

    private boolean applyRetrievalOrder(int activeTile, double[] weights) {
        RetrievalOrderService service = retrievalOrderService();
        if (service == null) {
            traceRetrievalOrderDisabled("RetrievalOrderService_bean_missing_or_adjust_failed");
            return false;
        }
        try {
            boolean adjusted = service.adjustFromCfvm(activeTile, weights);
            if (adjusted) {
                TraceStore.put("cfvm.retrievalOrderAdjusted", true);
                TraceStore.put("cfvm.retrievalOrderDisabledReason", "");
                TraceStore.put("cfvm.recoveryPathDisabledReason", "");
            } else if (TraceStore.get("cfvm.retrievalOrderDisabledReason") == null) {
                traceRetrievalOrderDisabled("RetrievalOrderService_bean_missing_or_adjust_failed");
            }
            return adjusted;
        } catch (RuntimeException ex) {
            TraceStore.put("cfvm.retrievalOrder.adjustErrorCaught", true);
            traceRetrievalOrderAdjustError(ex);
            traceRetrievalOrderDisabled("RetrievalOrderService_bean_missing_or_adjust_failed");
            return false;
        }
    }

    private RetrievalOrderService retrievalOrderService() {
        if (retrievalOrderServiceProvider == null) {
            return null;
        }
        try {
            return retrievalOrderServiceProvider.getIfAvailable();
        } catch (BeansException | IllegalStateException ex) {
            traceSuppressedProvider("retrievalOrderService", ex);
            return null;
        }
    }

    private boolean recordTrainingFailurePattern(String sessionId,
                                                 String failureClass,
                                                 String signature,
                                                 double boltzmannWeight) {
        TrainingService trainingService = trainingService();
        if (trainingService == null) {
            return false;
        }
        try {
            String safeSlot = SafeRedactor.redact(signature);
            trainingService.recordFailurePattern(
                    sessionId,
                    safeLabel(failureClass, "unknown"),
                    safeSlot == null || safeSlot.isBlank() ? "empty" : safeSlot,
                    boltzmannWeight);
            return true;
        } catch (RuntimeException ex) {
            traceSuppressedProvider("trainingService", ex);
            return false;
        }
    }

    private TrainingService trainingService() {
        if (trainingServiceProvider == null) {
            return null;
        }
        try {
            return trainingServiceProvider.getIfAvailable();
        } catch (BeansException | IllegalStateException ex) {
            traceSuppressedProvider("trainingService", ex);
            return null;
        }
    }

    private double boltzmannWeight(CfvmJbCbCalculator.JbCbResult jbCb) {
        if (jbCb == null) {
            return 0.0d;
        }
        RawMatrixBuffer buffer = rawMatrixBuffer();
        if (buffer == null) {
            return 0.0d;
        }
        double[] weights = buffer.getWeights();
        int tile = activeTile(jbCb);
        if (tile < 0 || tile >= weights.length) {
            return 0.0d;
        }
        double value = weights[tile];
        return Double.isFinite(value) ? value : 0.0d;
    }

    private static void traceRetrievalOrderDisabled(String reason) {
        TraceStore.put("cfvm.retrievalOrderAdjusted", false);
        TraceStore.put("cfvm.retrievalOrderDisabledReason", reason);
        TraceStore.put("cfvm.recoveryPath", "[]");
        TraceStore.put("cfvm.recoveryPathDisabledReason", reason);
    }

    private static void traceRetrievalOrderAdjustError(RuntimeException ex) {
        String message = ex == null ? null : ex.getMessage();
        TraceStore.put("cfvm.retrievalOrder.adjustError",
                SafeRedactor.traceLabelOrFallback(
                        ex == null ? null : ex.getClass().getSimpleName(), "unknown"));
        TraceStore.put("cfvm.retrievalOrder.adjustErrorHash", SafeRedactor.hashValue(message));
        TraceStore.put("cfvm.retrievalOrder.adjustErrorLength", message == null ? 0 : message.length());
    }

    private static void traceSuppressedProvider(String stage, RuntimeException ex) {
        String safeStage = safeLabel(stage, "unknown");
        TraceStore.put("cfvm.provider.suppressed.stage", safeStage);
        TraceStore.put("cfvm.provider.suppressed." + safeStage, true);
        TraceStore.put("cfvm.provider.suppressed.errorType",
                ex == null ? "unknown" : ex.getClass().getSimpleName());
    }

    private boolean recordMemory(String source,
                                 String failureClass,
                                 String hotspot,
                                 String sessionId,
                                 String signature,
                                 String patternHex,
                                 int traceSize,
                                 int signatureLength) {
        FailurePatternMemoryService memory = failurePatternMemory();
        if (memory == null) {
            return false;
        }
        String safeSource = safeLabel(source, DEFAULT_SOURCE);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("kind", "cfvm_failure_pattern");
        input.put("source", sessionId == null || sessionId.isBlank() ? safeSource : safeSource + "_session_present");
        input.put("failureClass", safeLabel(failureClass, "unknown"));
        input.put("hotspot", safeLabel(hotspot, DEFAULT_HOTSPOT));
        input.put("intent", "cfvm:" + patternHex);
        input.put("evidence", signature == null ? "" : signature);
        input.put("patchAction", "recall_safe_patch");
        input.put("decision", "observed");
        input.put("matrix", Map.of(
                "m1", traceSize,
                "m2", signatureLength,
                "failurePatternKind", safeLabel(failureClass, "unknown")));
        try {
            Map<String, Object> out = memory.record(input);
            return Boolean.TRUE.equals(out.get("recorded"));
        } catch (RuntimeException ex) {
            traceRecordMemoryFailure(ex);
            return false;
        }
    }

    private FailurePatternMemoryService failurePatternMemory() {
        if (failurePatternMemoryProvider == null) {
            TraceStore.put("cfvm.memory.absent", true);
            return null;
        }
        try {
            FailurePatternMemoryService memory = failurePatternMemoryProvider.getIfAvailable();
            if (memory == null) {
                TraceStore.put("cfvm.memory.absent", true);
            }
            return memory;
        } catch (BeansException | IllegalStateException ex) {
            traceSuppressedProvider("failurePatternMemory", ex);
            return null;
        }
    }

    private CfvmJbCbCalculator jbCbCalculator() {
        if (jbCbCalculatorProvider == null) {
            TraceStore.put("cfvm.jbcb.absent", true);
            return null;
        }
        try {
            CfvmJbCbCalculator calculator = jbCbCalculatorProvider.getIfAvailable();
            if (calculator == null) {
                TraceStore.put("cfvm.jbcb.absent", true);
            }
            return calculator;
        } catch (BeansException | IllegalStateException ex) {
            traceSuppressedProvider("jbCbCalculator", ex);
            return null;
        }
    }

    private static String safeLabel(String value, String fallback) {
        String safe = SafeRedactor.traceLabelOrFallback(value, fallback);
        return safe == null || safe.isBlank() ? fallback : safe;
    }

    private static String recordSkipReason(boolean buffered, boolean memoryRecorded) {
        if (!buffered) {
            Object reason = TraceStore.get("cfvm.record.bufferFailureReason");
            String safeReason = SafeRedactor.traceLabelOrFallback(reason == null ? null : String.valueOf(reason),
                    "raw_matrix_buffer_unavailable");
            return safeReason == null || safeReason.isBlank() ? "raw_matrix_buffer_unavailable" : safeReason;
        }
        if (memoryRecorded) {
            return "";
        }
        Object reason = TraceStore.get("cfvm.record.memoryFailureReason");
        String safeReason = SafeRedactor.traceLabelOrFallback(reason == null ? null : String.valueOf(reason),
                "failure_pattern_memory_unavailable");
        return safeReason == null || safeReason.isBlank() ? "failure_pattern_memory_unavailable" : safeReason;
    }

    private static void traceRecordMemoryFailure(RuntimeException ex) {
        TraceStore.put("cfvm.record.memoryFailed", true);
        TraceStore.put("cfvm.record.memoryError",
                SafeRedactor.traceLabelOrFallback(ex == null ? null : ex.getClass().getSimpleName(), "unknown"));
        TraceStore.put("cfvm.record.memoryFailureReason", "failure_pattern_memory_error");
        TraceStore.put("cfvm.record.skipReason", "failure_pattern_memory_error");
    }

    public record RecordResult(
            boolean buffered,
            boolean memoryRecorded,
            String patternId,
            int signatureLength,
            int traceSize) {
    }
}
