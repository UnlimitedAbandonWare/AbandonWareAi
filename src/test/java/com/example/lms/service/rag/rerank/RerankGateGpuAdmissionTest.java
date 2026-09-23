package com.example.lms.service.rag.rerank;

import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RerankGateGpuAdmissionTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void gpuAdmissionReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/rerank/RerankGate.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(
                "TraceStore.put(\"rerank.ce.gpuHardwareAdmission.reason\", admission.getOrDefault(\"reason\", \"\"));"));
        assertFalse(source.contains(
                "TraceStore.put(\"rerank.ce.gpuHardwareAdmission.reason\", SafeRedactor.safeMessage(String.valueOf(admission.getOrDefault(\"reason\", \"\")), 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"rerank.ce.gpuHardwareAdmission.reason\", SafeRedactor.traceLabelOrFallback(String.valueOf(admission.getOrDefault(\"reason\", \"\")), \"unknown\"));"));
    }

    @Test
    void gpuAdmissionSkipsCrossEncoderWhenHardwareTelemetryIsUnavailable() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.gpu-hardware.admission.rerank-gate-enabled", "true")
                .withProperty("awx.gpu-hardware.telemetry.enabled", "true")
                .withProperty("awx.gpu-hardware.telemetry.timeout-ms", "100")
                .withProperty("awx.gpu-hardware.admission.memory-warn-threshold", "0.005")
                .withProperty("awx.gpu-hardware.admission.memory-block-threshold", "0.01");
        RerankGate gate = new RerankGate(env);

        boolean shouldRerank = gate.shouldRerank(List.of(Content.from("a"), Content.from("b")));

        assertFalse(shouldRerank);
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.ce.skipped"));
        assertEquals("gpu_hardware_admission", TraceStore.get("rerank.ce.skipReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("rerank.ce.gpuHardwareAdmission.allowed"));
    }

    @Test
    void smallCandidateSetBelowCrossEncoderTopKSkipsRerank() {
        RerankGate gate = new RerankGate(new MockEnvironment());
        ReflectionTestUtils.setField(gate, "ceTopK", 4);

        boolean shouldRerank = gate.shouldRerank(List.of(Content.from("a"), Content.from("b")));

        assertFalse(shouldRerank);
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.ce.skipped"));
        assertEquals("insufficient_candidates", TraceStore.get("rerank.ce.skipReason"));
    }

    @Test
    void textSegmentFallbackLeavesTypeOnlyTraceBreadcrumb() {
        RerankGate gate = new RerankGate(new MockEnvironment());
        ReflectionTestUtils.setField(gate, "ceTopK", 2);
        ReflectionTestUtils.setField(gate, "uncertaintyThreshold", 0.0d);
        Content broken = mock(Content.class);
        when(broken.textSegment()).thenThrow(new IllegalStateException("raw private segment failure"));
        when(broken.toString()).thenReturn("same sized fallback");

        boolean shouldRerank = gate.shouldRerank(List.of(broken, Content.from("same sized fallback")));

        assertTrue(shouldRerank);
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.ce.textFallback"));
        assertEquals("IllegalStateException", TraceStore.get("rerank.ce.textFallback.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private segment failure"));
    }
}
