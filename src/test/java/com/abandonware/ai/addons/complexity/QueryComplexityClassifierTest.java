package com.abandonware.ai.addons.complexity;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryComplexityClassifierTest {

    @Test
    void koreanCurrentPriceQuestionRequiresWebWithoutRegexFailure() {
        QueryComplexityClassifier classifier = new QueryComplexityClassifier();

        ComplexityResult result = classifier.classify(
                "\uCD5C\uC2E0 \uAC00\uACA9 \uBE44\uAD50",
                Locale.KOREAN);

        assertEquals(ComplexityTag.WEB_REQUIRED, result.tag());
        assertEquals(Boolean.TRUE, result.features().get("hasWeb"));
        assertTrue(result.confidence() > 0.0d);
    }
}
