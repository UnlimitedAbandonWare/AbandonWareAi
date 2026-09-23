package com.acme.aicore.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptDomainModelTest {

    @Test
    void promptParamsClampMaxContextToAtLeastOne() {
        PromptParams params = PromptParams.defaults();

        params.setMaxCtx(0);
        assertEquals(1, params.maxCtx());

        params.setMaxCtx(-10);
        assertEquals(1, params.maxCtx());
    }

    @Test
    void rankedDocNormalizesNonFiniteScores() {
        assertEquals(0.0, RankedDoc.of("nan", Double.NaN).score());
        assertEquals(0.0, RankedDoc.of("neg-inf", Double.NEGATIVE_INFINITY).score());
        assertEquals(1.0, RankedDoc.of("score", 1.0).score());
    }
}
