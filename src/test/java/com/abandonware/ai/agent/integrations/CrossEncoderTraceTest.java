package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CrossEncoderTraceTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void onnxFactoryFailureFallsBackWithRedactedBreadcrumb() {
        CrossEncoder encoder = CrossEncoder.fromMode("onnx", () -> {
            throw new IllegalStateException("secret raw onnx path C:/private/model.onnx");
        });

        assertInstanceOf(HeuristicCrossEncoder.class, encoder);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.crossEncoder.suppressed"));
        assertEquals("onnx.factory", TraceStore.get("agent.crossEncoder.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("agent.crossEncoder.suppressed.errorType"));
        assertEquals("onnx".length(), TraceStore.get("agent.crossEncoder.suppressed.modeLength"));
        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains("secret raw onnx path"), rendered);
        assertFalse(rendered.contains("C:/private/model.onnx"), rendered);
    }
}
