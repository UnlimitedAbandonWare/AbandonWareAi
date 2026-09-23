package com.abandonware.ai.addons.complexity;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplexityValueObjectTest {

    @Test
    void complexityResultDefaultsNullsAndClampsConfidence() {
        Map<String, Object> features = new HashMap<>();
        features.put("tokens", 3);

        ComplexityResult result = new ComplexityResult(null, Double.NaN, features);
        features.put("tokens", 999);

        assertEquals(ComplexityTag.SIMPLE, result.tag());
        assertEquals(0.0d, result.confidence());
        assertEquals(3, result.features().get("tokens"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.features().put("mutate", true));
    }

    @Test
    void retrievalHintsClampTopKAndDefaultRoutingProfile() {
        RetrievalHints hints = new RetrievalHints(-3, -9, true, true, true, true, " ");

        assertEquals(0, hints.webTopK());
        assertEquals(0, hints.vectorTopK());
        assertEquals("default", hints.routingProfile());
    }
}
