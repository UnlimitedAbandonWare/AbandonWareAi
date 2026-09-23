package com.abandonware.ai.addons.onnx;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.abandonware.ai.addons.config.AddonsProperties;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxSemaphoreGateTest {

    @AfterEach
    void tearDown() {
        Thread.interrupted();
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    @Test
    void interruptedAcquireReturnsFallbackAndLeavesRedactedBreadcrumb() {
        OnnxSemaphoreGate gate = new OnnxSemaphoreGate(new AddonsProperties());

        Thread.currentThread().interrupt();
        String result = gate.withPermit(() -> "ce", () -> "fallback");

        assertEquals("fallback", result);
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.onnx.gate.suppressed"));
        assertEquals("tryAcquire.interrupted", TraceStore.get("rerank.onnx.gate.suppressed.stage"));
        assertEquals("cancelled", TraceStore.get("rerank.onnx.gate.suppressed.errorClass"));
        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains("secret"));
    }

    @Test
    void expiredBudgetReturnsFallbackAndLeavesBreadcrumb() throws Exception {
        OnnxSemaphoreGate gate = new OnnxSemaphoreGate(new AddonsProperties());
        AtomicBoolean ceCalled = new AtomicBoolean(false);
        TimeBudgetContext.set(new TimeBudget(1));
        Thread.sleep(5);

        String result = gate.withPermit(() -> {
            ceCalled.set(true);
            return "ce";
        }, () -> "fallback");

        assertEquals("fallback", result);
        assertFalse(ceCalled.get());
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.onnx.gate.suppressed"));
        assertEquals("budget.expired", TraceStore.get("rerank.onnx.gate.suppressed.stage"));
        assertEquals("expected_failure", TraceStore.get("rerank.onnx.gate.suppressed.errorClass"));
    }
}
