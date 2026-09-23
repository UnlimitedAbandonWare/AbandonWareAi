package com.abandonware.ai.addons.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddonsPropertiesTest {

    @Test
    void defaultRequestBudgetPreservesCompatibilityDeadline() {
        AddonsProperties props = new AddonsProperties();

        assertEquals(1_500L, props.getBudget().getDefaultMs());
    }

    @Test
    void numericSettersClampToRuntimeSafeBounds() {
        AddonsProperties props = new AddonsProperties();

        props.getBudget().setDefaultMs(-10);
        props.getOnnx().setMaxConcurrent(0);
        props.getOnnx().setQueueWaitMs(-5);
        props.getWeb().setTopKDefault(-3);
        props.getVector().setTopKDefault(-8);
        props.getSynthesis().setMoeMix(Double.NaN);
        props.getSynthesis().setMinBytesPerItem(-1);
        props.getOcr().setTopK(-2);

        assertEquals(1L, props.getBudget().getDefaultMs());
        assertEquals(1, props.getOnnx().getMaxConcurrent());
        assertEquals(1L, props.getOnnx().getQueueWaitMs());
        assertEquals(0, props.getWeb().getTopKDefault());
        assertEquals(0, props.getVector().getTopKDefault());
        assertEquals(0.0d, props.getSynthesis().getMoeMix());
        assertEquals(1, props.getSynthesis().getMinBytesPerItem());
        assertEquals(0, props.getOcr().getTopK());
    }

    @Test
    void authorityTierSetterTreatsNullAsEmptyMap() {
        AddonsProperties props = new AddonsProperties();

        props.getSynthesis().setAuthorityTier(null);

        assertTrue(props.getSynthesis().getAuthorityTier().isEmpty());
    }
}
