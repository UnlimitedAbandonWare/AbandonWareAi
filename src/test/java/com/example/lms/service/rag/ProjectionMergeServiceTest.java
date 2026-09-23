package com.example.lms.service.rag;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionMergeServiceTest {

    @Test
    void nullConfigUsesDefaultMergeBehavior() {
        ProjectionMergeService service = new ProjectionMergeService();

        String merged = service.merge("grounded", "creative", null);

        assertTrue(merged.contains("grounded"));
        assertTrue(merged.contains("creative"));
    }

    @Test
    void keepFalseReturnsGroundedOnly() {
        ProjectionMergeService service = new ProjectionMergeService();

        assertEquals("grounded", service.merge(
                "grounded",
                "creative",
                Map.of("keep-free-side-notes", false)));
    }
}
