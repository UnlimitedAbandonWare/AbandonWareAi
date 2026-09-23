package com.nova.protocol.fusion;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodeClampTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void tracedClampReturnsSameValueAndStoresCountOnlyFields() {
        double plain = BodeClamp.apply(0.6d, 1.6d);

        double traced = BodeClamp.applyTraced(0.6d, 1.6d, "nova.hypernova");

        assertEquals(plain, traced, 1.0e-12d);
        assertEquals(0.6d, TraceStore.get("nova.hypernova.bodeClamp.input"));
        assertEquals(1.6d, TraceStore.get("nova.hypernova.bodeClamp.c"));
        assertEquals(traced, TraceStore.get("nova.hypernova.bodeClamp.result"));
    }

    @Test
    void nonFiniteInputsStayInsideClampRange() {
        double positiveInfinite = BodeClamp.apply(Double.POSITIVE_INFINITY, 1.6d);

        assertEquals(0.0d, BodeClamp.apply(Double.NaN, 1.6d));
        assertEquals(1.0d / Math.sqrt(1.6d), positiveInfinite, 1.0e-12d);
        assertEquals(0.0d, BodeClamp.apply(Double.NEGATIVE_INFINITY, 1.6d));
        assertTrue(Double.isFinite(positiveInfinite));
        assertTrue(Double.isFinite(BodeClamp.apply(1.0e308d, 1.6d)));
    }
}
