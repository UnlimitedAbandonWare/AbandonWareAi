package com.nova.protocol.alloc;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleRiskKAllocatorTest {

    @Test
    void highRiskLaneLosesBudgetButKeepsFloorAndTotalK() {
        SimpleRiskKAllocator allocator = new SimpleRiskKAllocator();

        int[] out = allocator.alloc(
                new double[]{2.0d, 2.0d, 2.0d},
                new double[]{0.0d, 0.95d, 0.0d},
                12,
                1.0d,
                new int[]{1, 1, 1});

        assertEquals(12, out[0] + out[1] + out[2]);
        assertTrue(out[1] >= 1);
        assertTrue(out[1] < out[0]);
        assertTrue(out[1] < out[2]);
    }

    @Test
    void missingRiskVectorKeepsSoftmaxCompatibility() {
        SimpleRiskKAllocator allocator = new SimpleRiskKAllocator();

        int[] out = allocator.alloc(
                new double[]{3.0d, 1.0d, 1.0d},
                null,
                9,
                1.0d,
                new int[]{1, 1, 1});

        assertEquals(9, out[0] + out[1] + out[2]);
        assertTrue(out[0] > out[1]);
        assertTrue(out[0] > out[2]);
    }

    @Test
    void allocationPublishesRiskKSoftmaxTraceAliases() {
        TraceStore.clear();
        SimpleRiskKAllocator allocator = new SimpleRiskKAllocator();

        int[] out = allocator.alloc(
                new double[]{2.0d, 2.0d},
                new double[]{0.0d, 0.95d},
                10,
                1.0d,
                new int[]{1, 1});

        assertEquals(out[0], TraceStore.get("hypernova.riskK.webK"));
        assertEquals(out[1], TraceStore.get("hypernova.riskK.vectorK"));
        double webScore = ((Number) TraceStore.get("hypernova.riskK.softmax.webScore")).doubleValue();
        double vectorScore = ((Number) TraceStore.get("hypernova.riskK.softmax.vectorScore")).doubleValue();
        assertTrue(webScore > vectorScore);
        Map<?, ?> riskKAlloc = (Map<?, ?>) TraceStore.get("hypernova.riskKAlloc");
        assertEquals(Boolean.TRUE, riskKAlloc.get("used"));
        assertEquals(10, riskKAlloc.get("totalK"));
        assertEquals(10, riskKAlloc.get("sum"));
    }

    @Test
    void overBudgetFloorsAreCappedWithoutNegativeLanes() {
        SimpleRiskKAllocator allocator = new SimpleRiskKAllocator();

        int[] out = allocator.alloc(
                new double[]{1.0d, 1.0d, 1.0d},
                null,
                2,
                1.0d,
                new int[]{3, 3, 3});

        assertEquals(2, out[0] + out[1] + out[2]);
        assertTrue(out[0] >= 0);
        assertTrue(out[1] >= 0);
        assertTrue(out[2] >= 0);
    }

    @Test
    void overflowingFloorsAreRescaledWithoutNegativeLanes() {
        SimpleRiskKAllocator allocator = new SimpleRiskKAllocator();

        int[] out = allocator.alloc(
                new double[]{0.0d, 0.0d},
                null,
                1,
                1.0d,
                new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE});

        assertTrue(java.util.Arrays.stream(out).allMatch(value -> value >= 0));
        assertEquals(1L, java.util.Arrays.stream(out).asLongStream().sum());
    }

    @Test
    void fallbackForMissingLogitsClampsNegativeBudgetToZero() {
        SimpleRiskKAllocator allocator = new SimpleRiskKAllocator();

        int[] out = allocator.alloc(null, null, -3, 1.0d, null);

        assertEquals(0, out[0] + out[1] + out[2]);
        assertEquals(0, out[0]);
        assertEquals(0, out[1]);
        assertEquals(0, out[2]);
    }
}
