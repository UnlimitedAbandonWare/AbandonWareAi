package com.example.lms.service.rag;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionMergeServiceTest {
    private static final String HEADER = "### (실험적 아이디어 · 비공식)";

    @Test
    void canonicalAndExactLegacyLeadingHeadingsAreOwnedOnce() {
        ProjectionMergeService service = new ProjectionMergeService();
        String expected = "grounded\n\n---\n" + HEADER + "\ncreative body";
        for (String heading : java.util.List.of(HEADER, "### 異붿륫/鍮꾧났???꾩씠?붿뼱")) {
            assertEquals(expected, service.merge("grounded", heading + "\n\ncreative body", null));
        }
    }

    @Test
    void identicalOwnedTrailingBlockIsIdempotentButDifferentCreativeIsPreserved() {
        ProjectionMergeService service = new ProjectionMergeService();
        String first = service.merge("grounded", "creative body", null);
        assertEquals(first, service.merge(service.merge(first, "creative body", null), "creative body", null));
        assertTrue(service.merge(first, "different creative", null).endsWith("different creative"));
    }

    @Test
    void customHeaderAndBlankOverrideKeepTheirExistingPrecedence() {
        ProjectionMergeService service = new ProjectionMergeService();
        assertEquals("g\n\n---\n### custom\nc", service.merge("g", "### custom\nc", Map.of("free-header", "### custom")));
        assertEquals("g\n\n---\n" + HEADER + "\nc", service.merge("g", "c", Map.of("free-header", " ")));
    }

    @Test
    void bodyQuoteCodeFenceAndUnknownHeadingsAreNotRemoved() {
        ProjectionMergeService service = new ProjectionMergeService();
        for (String creative : java.util.List.of("intro\n" + HEADER, "> " + HEADER, "```\n" + HEADER + "\n```", "### legitimate subsection\nbody")) {
            assertEquals("g\n\n---\n" + HEADER + "\n" + creative, service.merge("g", creative, null));
        }
        assertEquals(HEADER + "\nbody\n\n---\n" + HEADER + "\nc", service.merge(HEADER + "\nbody", "c", null));
    }

    @Test
    void emptyAndKeepFalseContractsRemainUnchanged() {
        ProjectionMergeService service = new ProjectionMergeService();
        assertEquals("", service.merge(null, null, null));
        assertEquals("g", service.merge("g", " ", null));
        assertEquals("c", service.merge(" ", "c", Map.of("keep-free-side-notes", false)));
        assertEquals("g", service.merge("g", HEADER + "\nc", Map.of("keep-free-side-notes", false)));
    }

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
