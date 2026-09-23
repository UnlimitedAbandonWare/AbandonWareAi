package com.abandonware.ai.agent.rag.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ResultTest {

    @Test
    void constructorAndSetterNormalizeInvalidScoreAndRank() {
        Result constructed = new Result("id", "title", "snippet", "source", Double.NaN, -3);

        assertFalse(Double.isNaN(constructed.getScore()));
        assertEquals(0.0d, constructed.getScore());
        assertEquals(0, constructed.getRank());

        constructed.setScore(Double.NEGATIVE_INFINITY);
        constructed.setRank(-1);

        assertEquals(0.0d, constructed.getScore());
        assertEquals(0, constructed.getRank());
    }
}
