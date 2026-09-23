package com.abandonware.ai.service.onnx;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxSemaphoreGuardTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        Thread.interrupted();
    }

    @Test
    void interruptedAcquireRecordsTraceAndDoesNotRunBody() {
        OnnxSemaphoreGuard guard = new OnnxSemaphoreGuard(1);
        AtomicBoolean bodyRan = new AtomicBoolean(false);

        Thread.currentThread().interrupt();

        assertTrue(guard.withPermit(() -> {
            bodyRan.set(true);
            return "value";
        }, 100).isEmpty());
        assertFalse(bodyRan.get());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.onnx.serviceGate.suppressed"));
        assertEquals("tryAcquire.interrupted", TraceStore.get("rerank.onnx.serviceGate.suppressed.stage"));
        assertEquals("cancelled", TraceStore.get("rerank.onnx.serviceGate.suppressed.errorClass"));
    }
}
