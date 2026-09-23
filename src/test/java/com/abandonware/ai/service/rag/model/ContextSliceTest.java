package com.abandonware.ai.service.rag.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ContextSliceTest {

    @Test
    void constructorAndSetterNormalizeInvalidScoreAndRank() {
        ContextSlice slice = new ContextSlice("id", "title", "snippet", "source", Double.NaN, -5);

        assertFalse(Double.isNaN(slice.getScore()));
        assertEquals(0.0d, slice.getScore());
        assertEquals(0, slice.getRank());

        slice.setScore(Double.POSITIVE_INFINITY);
        slice.setRank(-1);

        assertEquals(0.0d, slice.getScore());
        assertEquals(0, slice.getRank());
    }
}
