package com.abandonware.ai.agent.integrations;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopKTest {

    @Test
    void keepsHighestScoresAndReturnsDescendingOrder() {
        TopK<String> top = new TopK<>(2);

        top.add("low", 0.10d);
        top.add("high", 0.90d);
        top.add("mid", 0.50d);

        List<TopK.Item<String>> result = top.toListSortedDesc();
        assertEquals(2, result.size());
        assertEquals("high", result.get(0).value);
        assertEquals("mid", result.get(1).value);
    }

    @Test
    void zeroCapacityKeepsNoItems() {
        TopK<String> top = new TopK<>(0);

        top.add("ignored", 1.0d);

        assertTrue(top.toListSortedDesc().isEmpty());
    }
}
