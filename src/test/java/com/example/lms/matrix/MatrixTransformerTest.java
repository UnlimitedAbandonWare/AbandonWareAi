package com.example.lms.matrix;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatrixTransformerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void transformBuildsContextSectionsOnly() {
        MatrixTransformer transformer = new MatrixTransformer();

        MatrixTransformer.MatrixResult result = transformer.transform(
                "s1",
                List.of("official web evidence about spring boot and rag orchestration"),
                "vector rag evidence with enough words for a context slice",
                "memory evidence with enough words for a context slice");

        assertTrue(result.unifiedContext().contains("### LIVE WEB RESULTS"));
        assertTrue(result.unifiedContext().contains("### VECTOR RAG"));
        assertTrue(result.unifiedContext().contains("### LONG-TERM MEMORY"));
        assertFalse(result.unifiedContext().contains("### INSTRUCTIONS"));
        assertEquals("sections_only", TraceStore.get("matrix.prompt.contract"));
        assertEquals("deterministic_source_gate", TraceStore.get("matrix.gate.mode"));
        assertEquals("not_applicable", TraceStore.get("matrix.gate.disabledReason"));
    }

    @Test
    void authoritySortKeepsWebBeforeRagAndMemory() {
        MatrixTransformer transformer = new MatrixTransformer();

        MatrixTransformer.MatrixResult result = transformer.transform(
                "s1",
                List.of("web authority slice about deterministic source gates"),
                "rag authority slice about deterministic source gates",
                "memory authority slice about deterministic source gates");

        int web = result.unifiedContext().indexOf("### LIVE WEB RESULTS");
        int rag = result.unifiedContext().indexOf("### VECTOR RAG");
        int mem = result.unifiedContext().indexOf("### LONG-TERM MEMORY");
        assertTrue(web >= 0);
        assertTrue(rag > web);
        assertTrue(mem > rag);
    }

    @Test
    void correctionMultiplierIsNeutralAndVariable() {
        double lowNovelty = MatrixTransformer.correctionMultiplier(0.0d);
        double neutral = MatrixTransformer.correctionMultiplier(0.5d);
        double highNovelty = MatrixTransformer.correctionMultiplier(1.0d);

        assertTrue(lowNovelty >= 0.75d && lowNovelty <= 1.25d);
        assertEquals(1.0d, neutral, 0.0001d);
        assertTrue(lowNovelty < neutral);
        assertTrue(neutral < highNovelty);
    }

    @Test
    void allocationNeverExceedsTotalSectionCapacity() {
        Map<String, Integer> allocation = MatrixTransformer.allocateLineBudget(
                Map.of("WEB", 10, "RAG", 1, "MEM", 1),
                9,
                4);

        int sum = allocation.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(sum <= 9);
        assertTrue(allocation.get("WEB") > 0);
        assertTrue(allocation.get("RAG") > 0);
        assertTrue(allocation.get("MEM") > 0);
    }

    @Test
    void clamp01KeepsZeroAtZeroInsteadOfPromotingEmptyEvidence() throws Exception {
        Method clamp = MatrixTransformer.class.getDeclaredMethod("clamp01", double.class);
        clamp.setAccessible(true);

        assertEquals(0.0d, (double) clamp.invoke(null, 0.0d), 0.0d);
        assertEquals(0.0d, (double) clamp.invoke(null, -1.0d), 0.0d);
        assertEquals(1.0d, (double) clamp.invoke(null, 1.5d), 0.0d);
    }

    @Test
    void sourceDoesNotContainDummyMoeHook() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/matrix/MatrixTransformer.java"));

        assertFalse(source.contains("MOE_PATCH_MINIMAL"));
        assertFalse(source.contains("new com.example.moe.MultiSourceMoE"));
        assertFalse(source.contains("{{1,0,0,0}}"));
        assertFalse(source.contains("dummy_moe_qkv_removed"));
        assertFalse(source.contains("catch (Throwable"));
    }
}
