package ai.abandonware.nova.orch.anchor;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnchorNarrowerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void narrowsCandidatesAndTracesHashOnlyAnchors() {
        AnchorNarrower narrower = new AnchorNarrower();
        String raw = "secret project GraphRAG KG fusion prompt";

        AnchorNarrowingResult result = narrower.narrow(raw, List.of(
                "GraphRAG KG fusion metrics",
                "unrelated cooking recipe",
                "KG fusion retention"), 3, 0.65d);
        List<String> filtered = narrower.filterCandidates(raw, List.of(
                "GraphRAG KG fusion metrics",
                "unrelated cooking recipe",
                "KG fusion retention"), result, 0.65d);

        assertTrue(result.anchorConfidence() > 0.0d);
        assertTrue(result.rejectedCandidateCount() >= 1);
        assertTrue(filtered.contains("GraphRAG KG fusion metrics"));
        assertFalse(filtered.contains("unrelated cooking recipe"));
        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(trace.contains("rag.anchor.anchors"));
        assertFalse(trace.contains(raw));
        assertFalse(trace.contains("GraphRAG KG fusion prompt"));
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> anchors =
                (List<java.util.Map<String, Object>>) TraceStore.get("rag.anchor.anchors");
        assertEquals(12, String.valueOf(anchors.get(0).get("hash12")).length());
    }

    @Test
    void koreanQueryDoesNotLetAsciiAcronymDominateAnchors() {
        AnchorNarrower narrower = new AnchorNarrower();

        AnchorNarrowingResult result = narrower.narrow(
                "RAG 기반 검색 시스템 성능",
                List.of("검색 시스템 성능 벤치마크", "RAG overview"),
                1,
                0.65d);

        assertFalse(result.anchors().isEmpty());
        String first = result.anchors().get(0);
        assertTrue(first.matches(".*[\\uAC00-\\uD7A3].*"), first);
        assertFalse("RAG".equalsIgnoreCase(first));
    }

    @Test
    void filterCandidatesPreservesSeedWhenAllCandidatesAreRejected() {
        AnchorNarrower narrower = new AnchorNarrower();
        String raw = "GraphRAG KG fusion";
        AnchorNarrowingResult result = narrower.narrow(raw, List.of(
                "completely unrelated cooking",
                "another unrelated sports item"), 3, 0.05d);

        List<String> filtered = narrower.filterCandidates(raw, List.of(
                "completely unrelated cooking",
                "another unrelated sports item"), result, 0.05d);

        assertEquals(List.of("completely unrelated cooking", "another unrelated sports item"), filtered);
        assertEquals("preserve_seed", TraceStore.get("rag.anchor.filterFallback"));
    }

    @Test
    void anchorReasonTraceDoesNotStoreRawFreeFormText() throws Exception {
        String rawReason = "private anchor reason api_key=secret-value-for-redaction";
        AnchorNarrowingResult result = new AnchorNarrowingResult(
                List.of("GraphRAG"),
                0.75d,
                0.25d,
                1,
                0,
                rawReason);

        Method method = AnchorNarrower.class.getDeclaredMethod("traceNarrowing", AnchorNarrowingResult.class);
        method.setAccessible(true);
        method.invoke(null, result);

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawReason), trace);
        assertFalse(trace.contains("private anchor reason"), trace);
        assertFalse(trace.contains("secret-value-for-redaction"), trace);
        assertTrue(String.valueOf(TraceStore.get("rag.anchor.reason")).startsWith("hash:"));
    }

    @Test
    void anchorReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/anchor/AnchorNarrower.java"));

        assertFalse(source.contains("TraceStore.put(\"rag.anchor.reason\", result.reason());"));
        assertTrue(source.contains("TraceStore.put(\"rag.anchor.reason\", SafeRedactor.traceLabelOrFallback(result.reason(), \"\"));"));
    }

    @Test
    void traceFallbacksUseNonRecursiveBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/anchor/AnchorNarrower.java"));

        assertFalse(source.contains("catch (Throwable ignore)"),
                "Anchor trace fallbacks should name suppressed failures");
        assertTrue(source.contains("traceSuppressed(\"filterCandidates\", traceError)"));
        assertTrue(source.contains("traceSuppressed(\"tracePick\", traceError)"));
        assertTrue(source.contains("traceSuppressed(\"traceVariants\", traceError)"));
        assertTrue(source.contains("traceSuppressed(\"traceNarrowing\", traceError)"));
        assertTrue(source.contains("[AnchorNarrower] trace skipped stage={} errorType={}"));
    }
}
