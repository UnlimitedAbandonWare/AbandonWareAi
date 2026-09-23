package com.example.lms.risk;

import com.example.lms.search.TraceStore;
import com.nova.protocol.alloc.RiskKAllocator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopKShrinkerAspectTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void usesCurrentBlackboxRiskScoreInsteadOfFixedMidpoint() throws Throwable {
        TopKShrinkerAspect aspect = new TopKShrinkerAspect();
        aspect.scorer = new RiskScorer();
        aspect.baseK = 20;
        aspect.minK = 4;
        TraceStore.put("blackbox.risk.riskScore", 0.9d);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { "ordinary query", 20 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> {
            Object[] adjusted = invocation.getArgument(0);
            return adjusted[1];
        });

        Object result = aspect.adjust(pjp);

        assertEquals(9, result);
        assertEquals(0.9d, TraceStore.get("retrieval.topK.rdi"));
        assertEquals("trace", TraceStore.get("retrieval.topK.rdiSource"));
        assertEquals(20, TraceStore.get("retrieval.topK.original"));
        assertEquals(9, TraceStore.get("retrieval.topK.adjusted"));
    }

    @Test
    void fallbackRiskDefaultsToNoShrinkWhenNoRiskSignalExists() throws Throwable {
        TopKShrinkerAspect aspect = new TopKShrinkerAspect();
        aspect.scorer = new RiskScorer();
        aspect.baseK = 20;
        aspect.minK = 4;

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { 20 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> invocation.<Object[]>getArgument(0)[0]);

        Object result = aspect.adjust(pjp);

        assertEquals(20, result);
        assertEquals(0.0d, TraceStore.get("retrieval.topK.rdi"));
        assertEquals("fallback-config", TraceStore.get("retrieval.topK.rdiSource"));
    }

    @Test
    void fallbackRiskCanBeConfiguredWithoutHardcodedMidpoint() throws Throwable {
        TopKShrinkerAspect aspect = new TopKShrinkerAspect();
        aspect.scorer = new RiskScorer();
        aspect.baseK = 20;
        aspect.minK = 4;
        aspect.fallbackRisk = 0.9d;

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { 20 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> invocation.<Object[]>getArgument(0)[0]);

        Object result = aspect.adjust(pjp);

        assertEquals(9, result);
        assertEquals(0.9d, TraceStore.get("retrieval.topK.rdi"));
        assertEquals("fallback-config", TraceStore.get("retrieval.topK.rdiSource"));
    }

    @Test
    void optionalRiskKAllocatorCanOwnAdjustedTopKBudget() throws Throwable {
        TopKShrinkerAspect aspect = new TopKShrinkerAspect();
        aspect.scorer = new RiskScorer();
        aspect.riskKAllocator = new FixedBudgetAllocator(7);
        aspect.baseK = 20;
        aspect.minK = 4;
        TraceStore.put("blackbox.risk.riskScore", 0.9d);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { "ordinary query", 20 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> invocation.<Object[]>getArgument(0)[1]);

        Object result = aspect.adjust(pjp);

        assertEquals(7, result);
        assertEquals(7, TraceStore.get("retrieval.topK.adjusted"));
        assertEquals(7, TraceStore.get("retrieval.topK.allocator.primaryK"));
        assertEquals("risk-k-allocator:trace", TraceStore.get("retrieval.topK.rdiSource"));
    }

    @Test
    void invalidTraceRiskUsesStableReasonCodeWithoutRawValue() throws Throwable {
        TopKShrinkerAspect aspect = new TopKShrinkerAspect();
        aspect.scorer = new RiskScorer();
        aspect.baseK = 20;
        aspect.minK = 4;
        TraceStore.put("blackbox.risk.riskScore", "not-a-number");

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { 20 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> invocation.<Object[]>getArgument(0)[0]);

        Object result = aspect.adjust(pjp);

        assertEquals(20, result);
        assertEquals("invalid_number", TraceStore.get("risk.topK.suppressed.normalizeRisk.errorType"));
        assertEquals("normalizeRisk", TraceStore.get("risk.topK.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("risk.topK.suppressed.errorType"));
        String errorType = String.valueOf(TraceStore.get("risk.topK.suppressed.normalizeRisk.errorType"));
        assertFalse(errorType.contains("NumberFormatException"));
    }

    @Test
    void nonFiniteTraceRiskUsesStableReasonCodeWithoutShrink() throws Throwable {
        TopKShrinkerAspect aspect = new TopKShrinkerAspect();
        aspect.scorer = new RiskScorer();
        aspect.baseK = 20;
        aspect.minK = 4;
        TraceStore.put("blackbox.risk.riskScore", Double.NaN);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { 20 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(invocation -> invocation.<Object[]>getArgument(0)[0]);

        Object result = aspect.adjust(pjp);

        assertEquals(20, result);
        assertEquals("invalid_number", TraceStore.get("risk.topK.suppressed.normalizeRisk.errorType"));
        assertEquals("normalizeRisk", TraceStore.get("risk.topK.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("risk.topK.suppressed.errorType"));
        assertEquals("fallback-config", TraceStore.get("retrieval.topK.rdiSource"));
    }

    private static final class FixedBudgetAllocator implements RiskKAllocator {
        private final int primaryK;

        private FixedBudgetAllocator(int primaryK) {
            this.primaryK = primaryK;
        }

        @Override
        public int[] alloc(double[] logits, double[] risk, int totalK, double temp, int[] floor) {
            return new int[] { primaryK, Math.max(0, totalK - primaryK) };
        }
    }
}
