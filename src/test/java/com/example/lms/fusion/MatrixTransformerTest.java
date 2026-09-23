package com.example.lms.fusion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MatrixTransformerTest {

    @Test
    void allocateLinesReturnsEmptyMapForEmptyInput() {
        Map<String, Integer> out = MatrixTransformer.allocateLines(List.of(), 12, 2);

        assertEquals(Map.of(), out);
    }
}
