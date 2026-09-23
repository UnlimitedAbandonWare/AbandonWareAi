package com.example.lms.cfvm;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.example.lms.search.TraceStore;
import com.example.lms.orchestration.control.RagControlCoordinator;
import com.example.lms.orchestration.control.RagControlLearningGate;
import com.example.lms.orchestration.control.RagControlProperties;
import com.example.lms.orchestration.control.RagControlRolloutState;
import com.example.lms.orchestration.control.RagControlRuntimeAdapter;
import com.example.lms.orchestration.control.RagGuardProbeComposer;
import com.example.lms.service.TrainingService;
import com.example.lms.strategy.RetrievalOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CfvmFailureRecorderTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void recordsCompactPatternIntoBufferAndFailureMemory() throws Exception {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        Path memory = tempDir.resolve("failure-pattern-memory.jsonl");
        FailurePatternMemoryService service = new FailurePatternMemoryService(
                new ObjectMapper(), new ToolManifestCatalog(), tempDir, memory);
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(provider(buffer), provider(service));

        CfvmFailureRecorder.RecordResult out = recorder.record(
                "extremez",
                "provider_disabled",
                "ExtremeZBurstAspect",
                "session-with-ownerToken=dummy-value",
                Map.of(
                        "web.naver.providerDisabled", true,
                        "extremez.risk.primaryCause", "provider_disabled",
                        "learning.error.hotspot", "Authorization: Bearer " + "dummy-token"));

        String line = Files.readString(memory);
        assertTrue(out.buffered());
        assertTrue(out.memoryRecorded());
        assertEquals(1, buffer.size());
        assertEquals("recorded", TraceStore.get("cfvm.failureRecorder"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.slot.extracted"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.triggered"));
        assertEquals(out.patternId(), TraceStore.get("cfvm.rawTileId"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.buffered"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.memoryRecorded"));
        assertEquals(out.patternId(), TraceStore.get("cfvm.patternHex"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.retrievalOrderAdjusted"));
        assertEquals("jb_cb_unavailable",
                TraceStore.get("cfvm.retrievalOrderDisabledReason"));
        assertEquals("[]", TraceStore.get("cfvm.recoveryPath"));
        assertEquals("jb_cb_unavailable",
                TraceStore.get("cfvm.recoveryPathDisabledReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.recorder.buffered"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.recorder.memory.recorded"));
        assertEquals("extremez", TraceStore.get("cfvm.record.source"));
        assertEquals("provider_disabled", TraceStore.get("cfvm.record.failureClass"));
        assertEquals("ExtremeZBurstAspect", TraceStore.get("cfvm.record.hotspot"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.record.buffered"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.record.memoryRecorded"));
        assertEquals("", TraceStore.get("cfvm.record.skipReason"));
        assertEquals(0.35d, (Double) TraceStore.get("cfvm.boltzmannTemp"), 0.0001d);
        assertTrue(line.contains("cfvm_failure_pattern"));
        assertTrue(line.contains("evidenceHash12"));
        assertFalse(line.contains("dummy-value"));
        assertFalse(line.contains("dummy-token"));
        assertFalse(line.contains("Authorization"));
    }

    @Test
    void enforcedRagControlHoldSkipsEveryCfvmMutation() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        FailurePatternMemoryService memory = mock(FailurePatternMemoryService.class);
        RetrievalOrderService retrievalOrder = mock(RetrievalOrderService.class);
        TrainingService training = mock(TrainingService.class);
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider(memory),
                provider((CfvmJbCbCalculator) null),
                provider(retrievalOrder),
                provider(training),
                provider(enforcedLearningGate()));

        CfvmFailureRecorder.RecordResult result = recorder.record(
                "rag",
                "silent_failure",
                "ChatWorkflow.finalAnswer",
                "session-id",
                Map.of("ragOrigin", true, "returnedCount", 0, "responseObserved", false));

        assertFalse(result.buffered());
        assertFalse(result.memoryRecorded());
        assertEquals(0, buffer.size());
        assertEquals("rag_control_hold", TraceStore.get("cfvm.record.skipReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.ragControl.hold"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.training.recorded"));
        verifyNoInteractions(memory, retrievalOrder, training);
    }

    @Test
    void explicitExtremeZRagOriginIsConsumedBeforeSignatureAndTraceCount() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider((FailurePatternMemoryService) null),
                provider((CfvmJbCbCalculator) null),
                provider((RetrievalOrderService) null),
                provider((TrainingService) null),
                provider(enforcedLearningGate()));

        CfvmFailureRecorder.RecordResult result = recorder.record(
                "extremez",
                "provider_disabled",
                "ExtremeZBurstAspect",
                "session-id",
                Map.of(
                        CfvmFailureRecorder.RAG_ORIGIN_MARKER, true,
                        "returnedCount", 0,
                        "responseObserved", false));

        assertFalse(result.buffered());
        assertEquals(2, result.traceSize());
        assertEquals("rag_control_hold", TraceStore.get("cfvm.record.skipReason"));
    }

    @Test
    void ragGateProviderResolutionFailureFailsClosedBeforeAnyMutation() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        @SuppressWarnings("unchecked")
        ObjectProvider<RagControlLearningGate> failingProvider = mock(ObjectProvider.class);
        when(failingProvider.getIfAvailable()).thenThrow(new IllegalStateException("private-provider-detail"));
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider((FailurePatternMemoryService) null),
                provider((CfvmJbCbCalculator) null),
                provider((RetrievalOrderService) null),
                provider((TrainingService) null),
                failingProvider);

        CfvmFailureRecorder.RecordResult result = recorder.record(
                "rag", "timeout", "retrieval", "session-id",
                Map.of(CfvmFailureRecorder.RAG_ORIGIN_MARKER, true));

        assertFalse(result.buffered());
        assertEquals(0, buffer.size());
        assertEquals("learning_gate_failed", TraceStore.get("cfvm.ragControl.failureClass"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private-provider-detail"));
    }

    @Test
    void missingRagGateFailsClosedBeforeAnyExplicitRagMutation() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider((FailurePatternMemoryService) null),
                provider((CfvmJbCbCalculator) null),
                provider((RetrievalOrderService) null),
                provider((TrainingService) null),
                provider((RagControlLearningGate) null));

        CfvmFailureRecorder.RecordResult result = recorder.record(
                "rag", "timeout", "retrieval", "session-id",
                Map.of(CfvmFailureRecorder.RAG_ORIGIN_MARKER, true));

        assertFalse(result.buffered());
        assertEquals(0, buffer.size());
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.ragControl.hold"));
        assertEquals("learning_gate_unavailable", TraceStore.get("cfvm.ragControl.failureClass"));
    }

    @Test
    void genericRetrievalSourceWithoutExplicitMarkerIsNotTeamKilled() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider((FailurePatternMemoryService) null),
                provider((CfvmJbCbCalculator) null),
                provider((RetrievalOrderService) null),
                provider((TrainingService) null),
                provider(enforcedLearningGate()));

        CfvmFailureRecorder.RecordResult result = recorder.record(
                "retrieval", "timeout", "allied-health-probe", "session-id", Map.of());

        assertTrue(result.buffered());
        assertEquals(1, buffer.size());
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.ragControl.hold"));
    }

    @Test
    void enforcedGateDoesNotTeamKillNonRagSourceHealthRecording() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider((FailurePatternMemoryService) null),
                provider((CfvmJbCbCalculator) null),
                provider((RetrievalOrderService) null),
                provider((TrainingService) null),
                provider(enforcedLearningGate()));

        CfvmFailureRecorder.RecordResult result = recorder.record(
                "source_health",
                "timeout",
                "SourceHealthFailurePatternTraceBridge",
                "",
                Map.of("sourceHealth", true));

        assertTrue(result.buffered());
        assertEquals(1, buffer.size());
        assertEquals("recorded", TraceStore.get("cfvm.failureRecorder"));
    }

    @Test
    void nonCancelQualityDowngradeDoesNotRecordFailurePattern() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        FailurePatternMemoryService memory = mock(FailurePatternMemoryService.class);
        TrainingService training = mock(TrainingService.class);
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider(memory),
                provider((CfvmJbCbCalculator) null),
                provider((RetrievalOrderService) null),
                provider(training));

        CfvmFailureRecorder.RecordResult result = recorder.record(
                "rag",
                "quality_downgrade",
                "orchestration",
                "s1",
                Map.of());

        assertFalse(result.buffered());
        assertFalse(result.memoryRecorded());
        assertEquals(0, buffer.size());
        assertEquals("non_cancel_downgrade", TraceStore.get("cfvm.record.skipReason"));
        assertEquals("skipped", TraceStore.get("cfvm.failureRecorder"));
        verifyNoInteractions(memory, training);
    }

    @Test
    void missingMemoryServiceStillBuffersFailurePattern() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(provider(buffer), provider(null));

        CfvmFailureRecorder.RecordResult out = recorder.record(
                "extremez",
                "after_filter_starvation",
                "ExtremeZBurstAspect",
                "",
                Map.of("web.naver.afterFilterCount", 0L, "web.naver.filter.rawCount", 3L));

        assertTrue(out.buffered());
        assertFalse(out.memoryRecorded());
        assertEquals(1, buffer.size());
    }

    @Test
    void memoryRecordFailureLeavesBreadcrumbAndStillBuffersPattern() {
        String raw = "Authorization Bearer " + fakeKey();
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        FailurePatternMemoryService throwingMemory = new FailurePatternMemoryService(
                new ObjectMapper(), new ToolManifestCatalog(), tempDir, tempDir.resolve("unused.jsonl")) {
            @Override
            public Map<String, Object> record(Map<String, Object> input) {
                throw new IllegalStateException(raw);
            }
        };
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(provider(buffer), provider(throwingMemory));

        CfvmFailureRecorder.RecordResult out = recorder.record(
                "extremez",
                "timeout",
                "DynamicRetrievalHandlerChain",
                "",
                Map.of("chain.steps.planned", 4));

        assertTrue(out.buffered());
        assertFalse(out.memoryRecorded());
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.record.memoryFailed"));
        assertEquals("IllegalStateException", TraceStore.get("cfvm.record.memoryError"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.record.buffered"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.record.memoryRecorded"));
        assertEquals("failure_pattern_memory_error", TraceStore.get("cfvm.record.skipReason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(fakeKey()));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("Authorization"));
    }

    @Test
    void bufferFailureLeavesSkipReasonAndBoltzmannTemperatureBreadcrumb() {
        RawMatrixBuffer buffer = new RawMatrixBuffer() {
            @Override
            public synchronized void add(long id, long a, long b) {
                throw new IllegalStateException("ownerToken=" + fakeKey());
            }
        };
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(provider(buffer), provider(null));

        CfvmFailureRecorder.RecordResult out = recorder.record(
                "extremez",
                "timeout",
                "DynamicRetrievalHandlerChain",
                "",
                Map.of("chain.steps.planned", 4));

        assertFalse(out.buffered());
        assertFalse(out.memoryRecorded());
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.record.bufferFailed"));
        assertEquals("IllegalStateException", TraceStore.get("cfvm.record.bufferError"));
        assertEquals("raw_matrix_buffer_error", TraceStore.get("cfvm.record.skipReason"));
        assertEquals(0.35d, (Double) TraceStore.get("cfvm.boltzmannTemp"), 0.0001d);
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(fakeKey()), trace);
        assertFalse(trace.contains("ownerToken"), trace);
    }

    @Test
    void recorderCalculatesJbCbScoresBeforeBuildingSignature() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        buffer.updateWeight(6, 1.0d);
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider(null),
                provider(new CfvmJbCbCalculator()));

        CfvmFailureRecorder.RecordResult out = recorder.record(
                "extremez",
                "timeout",
                "DynamicRetrievalHandlerChain",
                "",
                Map.of(
                        "chain.steps.planned", 4,
                        "chain.steps.executed", 3,
                        "chain.steps.failed", 1));

        assertTrue(out.buffered());
        assertEquals(0.75d, (Double) TraceStore.get("cfvm.jb.score"), 1.0e-9d);
        assertEquals(0.25d, (Double) TraceStore.get("cfvm.cb.score"), 1.0e-9d);
        assertEquals(0.75d, (Double) TraceStore.get("cfvm.jb"), 1.0e-9d);
        assertEquals(0.25d, (Double) TraceStore.get("cfvm.cb"), 1.0e-9d);
        assertEquals(6, TraceStore.get("cfvm.activeTile"));
        assertTrue(((Double) TraceStore.get("cfvm.boltzmannWeight")) > 0.0d);
        assertEquals(3, TraceStore.get("cfvm.jb.executed"));
        assertEquals(4, TraceStore.get("cfvm.jb.planned"));
        assertEquals(1, TraceStore.get("cfvm.cb.failed"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.detector.activated"));
        assertEquals("jb_high_cb_low", TraceStore.get("cfvm.lissajous.pattern"));
        assertEquals("RetrievalOrderService_bean_missing_or_adjust_failed",
                TraceStore.get("cfvm.retrievalOrderDisabledReason"));
        assertEquals(RawSlotExtractor.signature(Map.of(
                "chain.steps.planned", 4,
                "chain.steps.executed", 3,
                "chain.steps.failed", 1,
                "cfvm.jb.score", 0.75d,
                "cfvm.cb.score", 0.25d)).length(), out.signatureLength());
    }

    @Test
    void recorderAppliesRetrievalOrderServiceWhenCfvmWeightIsActionable() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        buffer.setBoltzmannTemp(0.1d);
        buffer.updateWeight(6, 1.0d);
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider((FailurePatternMemoryService) null),
                provider(new CfvmJbCbCalculator()),
                provider(new RetrievalOrderService()));

        CfvmFailureRecorder.RecordResult out = recorder.record(
                "extremez",
                "timeout",
                "DynamicRetrievalHandlerChain",
                "",
                Map.of(
                        "chain.steps.planned", 4,
                        "chain.steps.executed", 3,
                        "chain.steps.failed", 1));

        assertTrue(out.buffered());
        assertEquals(6, TraceStore.get("cfvm.activeTile"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.retrievalOrderAdjusted"));
        assertEquals("CFVM", TraceStore.get("retrievalOrder.lastSetBy"));
        assertEquals("[VECTOR, KG, WEB]", TraceStore.get("cfvm.recoveryPath"));
        assertEquals("", TraceStore.get("cfvm.retrievalOrderDisabledReason"));
        assertEquals("", TraceStore.get("cfvm.recoveryPathDisabledReason"));
        assertEquals(6, TraceStore.get("retrievalOrder.cfvm.dominantSlot"));
    }

    @Test
    void recorderMirrorsFailurePatternIntoTrainingRagHook() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        buffer.setBoltzmannTemp(0.1d);
        buffer.updateWeight(6, 1.0d);
        TrainingService trainingService = mock(TrainingService.class);
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider((FailurePatternMemoryService) null),
                provider(new CfvmJbCbCalculator()),
                provider(new RetrievalOrderService()),
                provider(trainingService));

        recorder.record(
                "extremez",
                "timeout",
                "DynamicRetrievalHandlerChain",
                "session-with-ownerToken=dummy-value",
                Map.of(
                        "chain.steps.planned", 4,
                        "chain.steps.executed", 3,
                        "chain.steps.failed", 1,
                        "learning.error.hotspot", "Authorization: Bearer " + fakeKey()));

        verify(trainingService).recordFailurePattern(
                eq("session-with-ownerToken=dummy-value"),
                eq("timeout"),
                argThat(slot -> slot != null
                        && slot.contains("jb=0.75")
                        && slot.contains("cb=0.25")
                        && !slot.contains(fakeKey())
                        && !slot.contains("Authorization")),
                anyDouble());
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.training.recorded"));
    }

    @Test
    void retrievalOrderFailureRecordsHashOnlyBreadcrumb() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        buffer.setBoltzmannTemp(0.1d);
        buffer.updateWeight(6, 1.0d);
        RetrievalOrderService throwingService = new RetrievalOrderService() {
            @Override
            public boolean adjustFromCfvm(int activeTile, double[] weights) {
                throw new IllegalStateException("Authorization Bearer " + fakeKey());
            }
        };
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider((FailurePatternMemoryService) null),
                provider(new CfvmJbCbCalculator()),
                provider(throwingService));

        recorder.record(
                "extremez",
                "timeout",
                "DynamicRetrievalHandlerChain",
                "",
                Map.of(
                        "chain.steps.planned", 4,
                        "chain.steps.executed", 3,
                        "chain.steps.failed", 1));

        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.retrievalOrderAdjusted"));
        assertEquals("RetrievalOrderService_bean_missing_or_adjust_failed",
                TraceStore.get("cfvm.retrievalOrderDisabledReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.retrievalOrder.adjustErrorCaught"));
        assertEquals("IllegalStateException", TraceStore.get("cfvm.retrievalOrder.adjustError"));
        String errorHash = String.valueOf(TraceStore.get("cfvm.retrievalOrder.adjustErrorHash"));
        assertTrue(errorHash.startsWith("hash:"));
        assertFalse(errorHash.contains(fakeKey()));
        assertFalse(String.valueOf(TraceStore.get("cfvm.retrievalOrder.adjustError")).contains("Authorization"));
        assertTrue(((Number) TraceStore.get("cfvm.retrievalOrder.adjustErrorLength")).intValue() > 0);
    }

    @Test
    void providerAndCalculatorFallbacksEmitNamedBreadcrumbs() throws Exception {
        String recorder = Files.readString(Path.of("main/java/com/example/lms/cfvm/CfvmFailureRecorder.java"));
        String calculator = Files.readString(Path.of("main/java/com/example/lms/cfvm/CfvmJbCbCalculator.java"));

        assertTrue(recorder.contains("traceSuppressedProvider(\"rawMatrixBuffer\", ex);"));
        assertTrue(recorder.contains("traceSuppressedProvider(\"retrievalOrderService\", ex);"));
        assertTrue(recorder.contains("traceSuppressedProvider(\"failurePatternMemory\", ex);"));
        assertTrue(recorder.contains("traceSuppressedProvider(\"jbCbCalculator\", ex);"));
        assertTrue(recorder.contains("TraceStore.put(\"cfvm.provider.suppressed.\" + safeStage, true);"));
        assertTrue(calculator.contains("traceSuppressed(\"cfvm.jbcb.intValue\", ex);"));
    }

    @Test
    void providerSuppressedTraceIncludesSafeAggregateStageWithoutRawSecret() throws Exception {
        String secret = com.example.lms.test.SecretFixtures.openAiKey();
        Method method = CfvmFailureRecorder.class.getDeclaredMethod(
                "traceSuppressedProvider", String.class, RuntimeException.class);
        method.setAccessible(true);

        method.invoke(null, "rawMatrixBuffer " + secret, new IllegalStateException("raw " + secret));

        Object safeStage = TraceStore.get("cfvm.provider.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.provider.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("cfvm.provider.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(secret));
    }

    @Test
    void jbCbNumericFallbackUsesStableReasonCodeWithoutRawValue() {
        String raw = "private planned steps ownerToken=fake-token";

        new CfvmJbCbCalculator().calculate(Map.of("chain.steps.planned", raw));

        assertEquals("invalid_number", TraceStore.get("cfvm.jbcb.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
    }

    @Test
    void jbCbSuppressedTraceIncludesSafeAggregateStageWithoutRawSecret() throws Exception {
        String secret = com.example.lms.test.SecretFixtures.openAiKey();
        Method method = CfvmJbCbCalculator.class.getDeclaredMethod(
                "traceSuppressed", String.class, RuntimeException.class);
        method.setAccessible(true);

        method.invoke(null, "cfvm.jbcb.intValue " + secret, new IllegalStateException("raw " + secret));

        Object safeStage = TraceStore.get("cfvm.jbcb.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.jbcb.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("cfvm.jbcb.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(secret));
    }

    @Test
    void missingProvidersEmitAbsentBreadcrumbsAndContinueFailSoft() {
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(provider(null), provider(null), provider(null));

        CfvmFailureRecorder.RecordResult out = recorder.record(
                "extremez",
                "provider_disabled",
                "ExtremeZBurstAspect",
                "",
                Map.of());

        assertFalse(out.buffered());
        assertFalse(out.memoryRecorded());
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.buffer.absent"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.memory.absent"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.jbcb.absent"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.signature.empty"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.patternId.zero"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.buffered"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.memoryRecorded"));
        assertEquals("0", TraceStore.get("cfvm.patternHex"));
    }

    @Test
    void calculatorFailureRecordsErrorTypeOnlyAndStillBuffersPattern() {
        String raw = "ownerToken=" + fakeKey();
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        CfvmJbCbCalculator throwingCalculator = new CfvmJbCbCalculator() {
            @Override
            public JbCbResult calculate(Map<String, Object> trace) {
                throw new IllegalStateException(raw);
            }
        };
        CfvmFailureRecorder recorder = new CfvmFailureRecorder(
                provider(buffer),
                provider((FailurePatternMemoryService) null),
                provider(throwingCalculator));

        CfvmFailureRecorder.RecordResult out = recorder.record(
                "extremez",
                "timeout",
                "DynamicRetrievalHandlerChain",
                "",
                Map.of("chain.steps.planned", 4));

        assertTrue(out.buffered());
        assertEquals("IllegalStateException", TraceStore.get("cfvm.jbcb.error"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.buffered"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.memoryRecorded"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(fakeKey()));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    private static RagControlLearningGate enforcedLearningGate() {
        RagControlRolloutState rollout = new RagControlRolloutState(
                new RagControlProperties(1, 0.01d, 20));
        rollout.record(new RagControlRolloutState.Observation(true, false, false, false, 1));
        return new RagControlLearningGate(
                new RagControlCoordinator(new RagGuardProbeComposer(), rollout),
                new RagControlRuntimeAdapter());
    }

    private static String fakeKey() {
        return "sk-" + "cfvmSecretLeak123456789012345";
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new FixedObjectProvider<>(value);
    }

    private static final class FixedObjectProvider<T> implements ObjectProvider<T> {
        private final T value;

        private FixedObjectProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public T getObject() {
            return value;
        }

        @Override
        public Iterator<T> iterator() {
            return value == null ? Collections.emptyIterator() : java.util.List.of(value).iterator();
        }
    }
}
