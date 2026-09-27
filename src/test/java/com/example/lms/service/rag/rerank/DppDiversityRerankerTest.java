package com.example.lms.service.rag.rerank;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DppDiversityRerankerTest {

    private final DppDiversityReranker stableReranker = new DppDiversityReranker();
    private final DppDiversityReranker.Config stableConfig =
            new DppDiversityReranker.Config(0.7d, 3);

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void determinantQualityStartsWithHighestRelevanceCandidateNotInputHead() {
        DppDiversityReranker reranker = new DppDiversityReranker(
                new DppDiversityReranker.Config(0.65d, 2));

        Probe staleHead = new Probe("stale", "old peripheral note", 0.10d);
        Probe best = new Probe("alpha", "alpha beta core evidence", 0.96d);
        Probe diverse = new Probe("gamma", "gamma delta separate evidence", 0.75d);

        List<Probe> out = reranker.rerank(
                List.of(staleHead, best, diverse),
                "alpha beta",
                2,
                p -> p.title + " " + p.snippet,
                p -> p.score);

        assertEquals(best, out.get(0));
        assertTrue(out.contains(diverse));
        assertFalse(out.contains(staleHead));
        assertEquals(3, TraceStore.get("dpp.rerank.inputCount"));
        assertEquals(2, TraceStore.get("dpp.rerank.outputCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("dpp.rerank.skipped"));
        assertEquals("", TraceStore.get("dpp.rerank.skipReason"));
        assertTrue((Double) TraceStore.get("dpp.rerank.diversityScore") > 0.0d);
        assertEquals(Boolean.TRUE, TraceStore.get("hypernova.dppApplied"));
        assertEquals(3, TraceStore.get("hypernova.dppInputCount"));
        assertEquals(2, TraceStore.get("hypernova.dppOutputCount"));
        assertEquals(2, TraceStore.get("hypernova.dppK"));
        assertEquals("", TraceStore.get("hypernova.dppDisabledReason"));
    }

    @Test
    void emptyInputPublishesDppSkipTrace() {
        DppDiversityReranker reranker = new DppDiversityReranker();

        List<String> out = reranker.rerank(List.of(), "alpha beta", 3);

        assertTrue(out.isEmpty());
        assertEquals(0, TraceStore.get("dpp.rerank.inputCount"));
        assertEquals(0, TraceStore.get("dpp.rerank.outputCount"));
        assertEquals(0.0d, TraceStore.get("dpp.rerank.diversityScore"));
        assertEquals(Boolean.TRUE, TraceStore.get("dpp.rerank.skipped"));
        assertEquals("empty_input", TraceStore.get("dpp.rerank.skipReason"));
    }

    @Test
    void canonicalRerankerDocumentsDeterminantalKernelSelection() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java"));

        assertTrue(source.contains("determinantal"));
        assertTrue(source.contains("determinant"));
        assertTrue(source.contains("Cubic lambda falloff is intentional"));
        assertFalse(source.contains("MMR-style approximation"));
        assertFalse(source.contains("Do not assume it is a determinantal-kernel solver"));
    }

    @Test
    void typedExtractorKeepsHotPathOffReflectiveGetters() {
        DppDiversityReranker reranker = new DppDiversityReranker(
                new DppDiversityReranker.Config(0.5d, 2));

        Probe first = new Probe("alpha beta", "core evidence", 0.95d);
        Probe duplicate = new Probe("alpha beta", "core evidence", 0.90d);
        Probe diverse = new Probe("gamma delta", "separate evidence", 0.80d);

        List<Probe> out = reranker.rerank(
                List.of(first, duplicate, diverse),
                "alpha beta",
                2,
                p -> p.title + " " + p.snippet,
                p -> p.score);

        assertEquals(List.of(first, diverse), out);
    }

    @Test
    void rerankReturnsTheOriginalCandidateObjectsWhenOrderChanges() {
        DppDiversityReranker reranker = new DppDiversityReranker(
                new DppDiversityReranker.Config(0.65d, 3));
        Probe stale = new Probe("stale", "old peripheral note", 0.10d);
        Probe best = new Probe("alpha", "alpha beta core evidence", 0.96d);
        Probe diverse = new Probe("gamma", "gamma delta separate evidence", 0.75d);

        List<Probe> out = reranker.rerank(
                List.of(stale, best, diverse),
                "alpha beta",
                3,
                p -> p.title + " " + p.snippet,
                p -> p.score);

        assertSame(best, out.get(0));
        assertTrue(out.stream().allMatch(candidate ->
                candidate == stale || candidate == best || candidate == diverse));
    }

    @Test
    void determinantTiesUseStableKeysNotArrivalOrder() {
        Item c = new Item("c", "same", 0.8d);
        Item a = new Item("a", "same", 0.8d);
        Item b = new Item("b", "same", 0.8d);
        List<Item> forward = List.of(c, a, b);
        List<Item> reverse = List.of(b, a, c);

        List<Item> first = stableReranker.rerank(
                stableConfig, forward, "q", 3,
                Item::text, Item::relevance, Item::id);
        List<Item> second = stableReranker.rerank(
                stableConfig, reverse, "q", 3,
                Item::text, Item::relevance, Item::id);

        assertEquals(List.of("a", "b", "c"), first.stream().map(Item::id).toList());
        assertEquals(List.of("a", "b", "c"), second.stream().map(Item::id).toList());
        assertSame(a, first.get(0));
        assertSame(a, second.get(0));
    }

    @Test
    void candidatesWithoutAnyRecoverableStableKeyAreExcluded() {
        Item dropped = new Item("", "", 0.9d);
        Item kept = new Item("kept", "body", 0.8d);

        List<Item> result = stableReranker.rerank(
                stableConfig, List.of(dropped, kept), "q", 2,
                Item::text, Item::relevance, Item::id);

        assertEquals(List.of("kept"), result.stream().map(Item::id).toList());
        assertSame(kept, result.get(0));
    }

    @Test
    void lowerLambdaChoosesMoreDiverseSecondDocument() {
        Probe first = new Probe("alpha beta", "core evidence", 1.0d);
        Probe duplicate = new Probe("alpha beta", "core evidence", 0.95d);
        Probe diverse = new Probe("gamma delta", "separate evidence", 0.50d);

        DppDiversityReranker relevanceHeavy = new DppDiversityReranker(
                new DppDiversityReranker.Config(0.90d, 2));
        DppDiversityReranker diversityHeavy = new DppDiversityReranker(
                new DppDiversityReranker.Config(0.30d, 2));

        assertEquals(List.of(first, duplicate), relevanceHeavy.rerank(
                List.of(first, duplicate, diverse),
                "alpha beta",
                2,
                p -> p.title + " " + p.snippet,
                p -> p.score));
        assertEquals(List.of(first, diverse), diversityHeavy.rerank(
                List.of(first, duplicate, diverse),
                "alpha beta",
                2,
                p -> p.title + " " + p.snippet,
                p -> p.score));
    }

    @Test
    void cachesExtractedTextAndShinglesForLongCandidatePool() {
        DppDiversityReranker reranker = new DppDiversityReranker(
                new DppDiversityReranker.Config(0.35d, 3));
        String longCore = "alpha beta ".repeat(300);
        List<Probe> candidates = List.of(
                new Probe("alpha", longCore, 1.0d),
                new Probe("alpha duplicate", longCore, 0.95d),
                new Probe("gamma", "gamma delta ".repeat(300), 0.80d),
                new Probe("theta", "theta lambda ".repeat(300), 0.70d));
        AtomicInteger extractionCount = new AtomicInteger();

        reranker.rerank(
                candidates,
                "alpha beta",
                3,
                p -> {
                    extractionCount.incrementAndGet();
                    return p.title + " " + p.snippet;
                },
                p -> p.score);

        assertEquals(candidates.size(), extractionCount.get());
    }

    @Test
    void widePoolSelectionKeepsFiveDiverseCandidates() {
        DppDiversityReranker reranker = new DppDiversityReranker(
                new DppDiversityReranker.Config(0.35d, 5));
        Probe alpha = new Probe("alpha", "same core evidence ".repeat(80), 1.0d);
        Probe alphaDuplicate = new Probe("alpha duplicate", "same core evidence ".repeat(80), 0.98d);
        Probe beta = new Probe("beta", "beta operating evidence ".repeat(80), 0.82d);
        Probe gamma = new Probe("gamma", "gamma implementation evidence ".repeat(80), 0.78d);
        Probe delta = new Probe("delta", "delta vector evidence ".repeat(80), 0.74d);
        Probe epsilon = new Probe("epsilon", "epsilon memory evidence ".repeat(80), 0.70d);

        List<Probe> out = reranker.rerank(
                List.of(alpha, alphaDuplicate, beta, gamma, delta, epsilon),
                "alpha beta gamma delta epsilon",
                5,
                p -> p.title + " " + p.snippet,
                p -> p.score);

        assertEquals(5, out.size());
        assertEquals(alpha, out.get(0));
        assertTrue(out.contains(beta));
        assertTrue(out.contains(gamma));
        assertTrue(out.contains(delta));
        assertTrue(out.contains(epsilon));
        assertTrue(!out.contains(alphaDuplicate));
    }

    @Test
    void legacySameSimpleNameAliasesStayPurged() {
        assertFalse(Files.exists(Path.of(
                "main/java/com/abandonware/ai/service/rag/rerank/DppDiversityReranker.java")));
        assertFalse(Files.exists(Path.of(
                "main/java/com/abandonware/ai/service/rag/handler/DppDiversityReranker.java")));
    }

    @Test
    void canonicalRerankerIsSpringComponentWithDefaultConfig() {
        assertTrue(DppDiversityReranker.class.isAnnotationPresent(Component.class));

        DppDiversityReranker reranker = new DppDiversityReranker();

        assertEquals(List.of("a"), reranker.rerank(List.of("a", "a"), "a", 1));
    }

    @Test
    void dynamicRetrievalHandlerUsesInjectedDppRerankerInsteadOfDirectConstruction() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertTrue(source.contains("private DppDiversityReranker dppDiversityReranker"));
        assertFalse(source.contains("new DppDiversityReranker("));
        assertTrue(source.contains("dpp.reranker.source"));
    }

    @Test
    void textExtractorFallbackLeavesRedactedTraceBreadcrumb() {
        DppDiversityReranker reranker = new DppDiversityReranker(
                new DppDiversityReranker.Config(0.65d, 1));
        SafeToString candidate = new SafeToString("fallback alpha beta");

        List<SafeToString> out = reranker.rerank(
                List.of(candidate),
                "alpha beta",
                1,
                item -> {
                    throw new IllegalStateException("raw extractor private detail");
                },
                item -> 0.8d);

        assertEquals(List.of(candidate), out);
        assertEquals(Boolean.TRUE, TraceStore.get("dpp.extractor.fallback"));
        assertEquals("IllegalStateException", TraceStore.get("dpp.extractor.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw extractor private detail"));
    }

    @Test
    void relevanceFallbackLeavesRedactedTraceBreadcrumb() {
        DppDiversityReranker reranker = new DppDiversityReranker(
                new DppDiversityReranker.Config(0.65d, 1));
        Probe candidate = new Probe("alpha", "alpha beta evidence", 0.8d);

        List<Probe> out = reranker.rerank(
                List.of(candidate),
                "alpha beta",
                1,
                item -> item.title + " " + item.snippet,
                item -> {
                    throw new IllegalArgumentException("raw relevance private detail");
                });

        assertEquals(List.of(candidate), out);
        assertEquals(Boolean.TRUE, TraceStore.get("dpp.relevance.fallback"));
        assertEquals("IllegalArgumentException", TraceStore.get("dpp.relevance.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw relevance private detail"));
    }

    private record SafeToString(String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    private record Item(String id, String text, double relevance) {
    }

    private static final class Probe {
        private final String title;
        private final String snippet;
        private final double score;

        private Probe(String title, String snippet, double score) {
            this.title = title;
            this.snippet = snippet;
            this.score = score;
        }

        public String getTitle() {
            throw new AssertionError("typed DPP path must not call reflective getters");
        }

        public String getSnippet() {
            throw new AssertionError("typed DPP path must not call reflective getters");
        }

        @Override
        public String toString() {
            throw new AssertionError("typed DPP path must not fall back to toString");
        }
    }
}
