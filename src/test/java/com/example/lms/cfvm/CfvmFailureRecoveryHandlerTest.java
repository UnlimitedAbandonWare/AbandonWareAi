package com.example.lms.cfvm;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.strategy.RetrievalOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfvmFailureRecoveryHandlerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void cancelShieldTimeoutDowngradePreprocessesCfvmRecoveryAndAdjustsRetrievalOrder() {
        RawMatrixBuffer buffer = new RawMatrixBuffer(9, 0.1d);
        DebugEventStore debugEvents = new DebugEventStore();
        CfvmFailureRecoveryHandler handler = new CfvmFailureRecoveryHandler(
                provider(buffer),
                provider(new CfvmJbCbCalculator()),
                provider(new RetrievalOrderService()),
                provider(debugEvents));
        TraceStore.put("timeout.stage", "cancel_shield_invoke_all");
        TraceStore.put("chain.steps.planned", 4);
        TraceStore.put("chain.steps.executed", 3);
        TraceStore.put("chain.steps.failed", 1);
        TraceStore.put("resource.valueScore", 0.82d);
        TraceStore.put("web.await.root.engine", "web");

        CfvmFailureRecoveryHandler.RecoveryDecision decision =
                handler.observeCancelShieldDowngrade("searchIoExecutor", true, true);

        assertTrue(decision.applied());
        assertEquals(6, decision.activeTile());
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.failureRecovery.triggered"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.failureRecovery.preprocessed"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.failureRecovery.cancelTrueDowngraded"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.failureRecovery.timeoutCondition"));
        assertEquals("timeout_cancel_downgraded", TraceStore.get("cfvm.failureRecovery.failureClass"));
        assertEquals("fail_soft_fallback", TraceStore.get("cfvm.recovery.routeHint"));
        assertEquals("web", TraceStore.get("failpattern.searchRecovery.source"));
        assertEquals("timeout_cancel_downgraded", TraceStore.get("failpattern.searchRecovery.reason"));
        assertEquals("CFVM", TraceStore.get("retrievalOrder.lastSetBy"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.retrievalOrderAdjusted"));
        assertTrue(((Number) TraceStore.get("cfvm.failureRecovery.failureWeight")).doubleValue() >= 0.90d);
        assertTrue(((String) TraceStore.get("cfvm.failureRecovery.signatureHash")).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.failureRecovery.snapshot.saved"));
        assertEquals(1, buffer.size());
        RawMatrixBuffer.Entry entry = buffer.snapshot().get(0);
        assertTrue(entry.patternId() != 0L);
        assertTrue(entry.traceSize() >= 8L);
        assertTrue(entry.signatureLength() > 0L);
        assertFalse(String.valueOf(TraceStore.getAll()).contains("searchIoExecutor"));
        assertEquals(1, debugEvents.listByProbe(DebugProbeType.AGENT_REPORT_CFVM, 10).size());
        Map<String, Object> eventData = debugEvents.listByProbe(DebugProbeType.AGENT_REPORT_CFVM, 10).get(0).data();
        assertEquals(true, eventData.get("cancelTrueDowngraded"));
        assertEquals(true, eventData.get("timeoutCondition"));
    }

    @Test
    void cancelTrueWithoutTimeoutLeavesConsideredBreadcrumbButDoesNotMutateWeights() {
        RawMatrixBuffer buffer = new RawMatrixBuffer(9, 0.1d);
        CfvmFailureRecoveryHandler handler = new CfvmFailureRecoveryHandler(
                provider(buffer),
                provider(new CfvmJbCbCalculator()),
                provider(new RetrievalOrderService()),
                provider(new DebugEventStore()));

        CfvmFailureRecoveryHandler.RecoveryDecision decision =
                handler.observeCancelShieldDowngrade("searchIoExecutor", true, true);

        assertFalse(decision.applied());
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.failureRecovery.considered"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.failureRecovery.timeoutCondition"));
        assertEquals("timeout_condition_absent", TraceStore.get("cfvm.failureRecovery.skipReason"));
        assertEquals(0.0d, buffer.getWeights()[0], 1.0e-9d);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
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
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
