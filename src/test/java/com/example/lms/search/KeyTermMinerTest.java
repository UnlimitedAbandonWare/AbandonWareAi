package com.example.lms.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyTermMinerTest {

    @Test
    void returnsTheHighestFrequencyIndexedTerm() {
        KeyTermMiner miner = new KeyTermMiner();

        List<String> terms = miner.topKeyTerms(
                List.of(
                        "durable durable durable evidence",
                        "evidence grounded answer"),
                1);

        assertEquals(List.of("durable"), terms);
    }
}
