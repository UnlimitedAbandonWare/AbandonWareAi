package com.example.lms.service.rag.graph;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparseNodeInferenceServiceTest {

    @Test
    void walksBoundedAssociativeMemoryPathsWithoutCycles() {
        SparseNodeInferenceService service = new SparseNodeInferenceService();

        List<SparseNodeInferenceService.MemoryPath> paths = service.infer(
                Set.of("Alpha"),
                entity -> switch (entity) {
                    case "Alpha" -> Map.of("RELATES_TO", Set.of("Beta"));
                    case "Beta" -> Map.of("SUPPORTS", Set.of("Gamma", "Alpha"));
                    default -> Map.of();
                },
                entity -> 0.8d,
                2,
                10);

        assertTrue(paths.stream().anyMatch(path ->
                "Alpha --RELATES_TO--> Beta --SUPPORTS--> Gamma".equals(path.render())));
        assertTrue(paths.stream().noneMatch(path -> path.render().contains("Gamma --")));
        assertEquals(2, paths.stream().mapToInt(SparseNodeInferenceService.MemoryPath::depth).max().orElse(0));
    }

    @Test
    void sparseNodeFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/graph/SparseNodeInferenceService.java"));

        assertTrue(source.contains("traceSuppressed(\"relations\""));
        assertTrue(source.contains("traceSuppressed(\"confidence\""));
        assertTrue(source.contains("log.debug(\"[SparseNodeInferenceService] fail-soft stage={} err={}"));
        assertFalse(source.contains("log.debug(\"[SparseNodeInferenceService] fail-soft stage={} err={}\", stage, error);"));
        assertTrue(source.contains(
                "String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, \"unknown\");"));
        assertFalse(source.contains("catch (Exception ignore) {"));
    }

    @Test
    void providerFailuresLeaveTraceStoreBreadcrumbsWithoutRawMessages() {
        SparseNodeInferenceService service = new SparseNodeInferenceService();

        TraceStore.clear();
        service.infer(
                Set.of("Alpha"),
                entity -> {
                    throw new IllegalStateException("raw relation secret");
                },
                entity -> 0.9d,
                2,
                5);

        assertEquals(true, TraceStore.get("retrieval.kg.sparseNode.suppressed.relations"));
        assertEquals("IllegalStateException", TraceStore.get("retrieval.kg.sparseNode.relations.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("raw relation secret"));

        TraceStore.clear();
        service.infer(
                Set.of("Alpha"),
                entity -> Map.of("RELATES_TO", Set.of("Beta")),
                entity -> {
                    throw new IllegalArgumentException("raw confidence secret");
                },
                2,
                5);

        assertEquals(true, TraceStore.get("retrieval.kg.sparseNode.suppressed.confidence"));
        assertEquals("IllegalArgumentException", TraceStore.get("retrieval.kg.sparseNode.confidence.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("raw confidence secret"));
    }
}
