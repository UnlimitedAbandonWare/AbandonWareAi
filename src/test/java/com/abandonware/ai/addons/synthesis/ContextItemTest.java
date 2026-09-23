package com.abandonware.ai.addons.synthesis;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextItemTest {

    @Test
    void constructorNormalizesNullsAndNonFiniteScore() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("url", "https://example.test/doc");

        ContextItem item = new ContextItem(null, null, null, null, Double.NaN, -4, meta);
        meta.put("url", "mutated");

        assertEquals("", item.id());
        assertEquals("", item.title());
        assertEquals("", item.snippet());
        assertEquals("", item.source());
        assertEquals(0.0d, item.score());
        assertEquals(0, item.rank());
        assertEquals("https://example.test/doc", item.meta().get("url"));
        assertThrows(UnsupportedOperationException.class, () -> item.meta().put("other", true));
    }

    @Test
    void constructorKeepsFinitePositiveScoreAboveOneForRankersThatUseRawScores() {
        ContextItem item = new ContextItem("id", "title", "snippet", "source", 3.5d, 2, null);

        assertEquals(3.5d, item.score());
        assertTrue(item.meta().isEmpty());
    }
}
