package com.example.lms.service.rag.kg;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KgTailPowerMeanScorerTest {

    @Test
    void disabledScorerLeavesLegacyScoreUnchanged() {
        KgTailPowerMeanScorer scorer = new KgTailPowerMeanScorer(false, 2.0, 0.25);

        assertEquals(0.75, scorer.adjust(0.75, 0.5, 0.9, 0.8, 4), 1.0e-9);
    }

    @Test
    void enabledScorerIsDeterministicAndBoostIsClamped() {
        KgTailPowerMeanScorer scorer = new KgTailPowerMeanScorer(true, 4.0, 0.90);

        double first = scorer.adjust(1.0, 0.5, 0.95, 0.9, 20);
        double second = scorer.adjust(1.0, 0.5, 0.95, 0.9, 20);

        assertEquals(first, second, 1.0e-12);
        assertTrue(first >= 1.0);
        assertTrue(first <= 1.25);
        assertTrue(scorer.boundedSignal(0.5, 0.95, 0.9, 20) <= 1.0);
    }

    @Test
    void springPropertyDefaultEnablesKgTailScoring() {
        Constructor<?> ctor = KgTailPowerMeanScorer.class.getConstructors()[0];

        assertTrue(ctor.getParameters()[0]
                .getAnnotation(org.springframework.beans.factory.annotation.Value.class)
                .value()
                .contains("retrieval.fusion.kg-tail.enabled:true"));
    }
}
