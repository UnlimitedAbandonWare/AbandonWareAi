package com.abandonware.ai.addons.complexity;

import com.abandonware.ai.addons.config.AddonsProperties;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComplexityGatingCoordinatorTest {

    @Test
    void disabledComplexityDoesNotReturnNegativeTopK() {
        AddonsProperties props = new AddonsProperties();
        props.getComplexity().setEnabled(false);
        props.getWeb().setTopKDefault(-5);
        props.getVector().setTopKDefault(-12);

        ComplexityGatingCoordinator coordinator = new ComplexityGatingCoordinator(
                new QueryComplexityClassifier(), props);

        RetrievalHints hints = coordinator.decide("hello", Locale.ROOT, Map.of());

        assertEquals(0, hints.webTopK());
        assertEquals(0, hints.vectorTopK());
    }
}
