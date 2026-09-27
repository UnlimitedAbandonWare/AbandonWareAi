package com.example.lms.service.rag.orchestrator;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF contract tests (seedOnly replay path — zero live retriever calls):
 *
 *  - each input list's ORIGINAL ordinal rank is used (never rank ~ 1/score);
 *  - the same document key across DIFFERENT source lists sums contributions
 *    and is returned once, keeping the representative's provenance metadata;
 *  - a repeated key INSIDE one list consumes a rank slot but adds no vote;
 *  - documents that only share text are not merged (distinct url identity);
 *  - the fusion window (candidateK) is wider than the public result size
 *    (topK), so a reranker can promote a candidate beyond the final cut;
 *  - a single-source pool is topped up to topK instead of being starved by
 *    the diversity soft-cap.
 *
 * Defaults used when no RagProperties bean is present: k0=60,
 * wWeb=1.0, wVector=0.8, wBm25=0.9, wKg=0.7.
 */
class UnifiedRagOrchestratorRrfContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    private static UnifiedRagOrchestrator.QueryRequest seedOnlyRequest(int topK) {
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "rrf contract";
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;
        request.topK = topK;
        request.seedOnly = true;
        return request;
    }

    private static Content seed(String url, String text) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of("url", url))));
    }

    private static Content seed(String url, String text, String score) {
        return Content.from(TextSegment.from(text,
                Metadata.from(Map.of("url", url, "score", score))));
    }

    private static List<String> resultIds(UnifiedRagOrchestrator.QueryResponse response) {
        return response.results.stream().map(d -> d.id).toList();
    }

    @Test
    void sameDocumentAcrossListsAggregatesContributionsAndAppearsOnce() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = seedOnlyRequest(8);
        request.seedWeb = List.of(
                seed("https://shared/a", "doc A web leg"),
                seed("https://web/b", "doc B web only"));
        request.seedVector = List.of(
                seed("https://shared/a", "doc A vector leg"),
                seed("https://vec/c", "doc C vector only"));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        // A: 1.0/61 + 0.8/61 = 0.0295 | B: 1.0/62 = 0.0161 | C: 0.8/62 = 0.0129
        assertEquals(List.of("https://shared/a", "https://web/b", "https://vec/c"),
                resultIds(response));
        assertEquals(1, response.results.stream()
                .filter(d -> "https://shared/a".equals(d.id)).count(),
                "the cross-list document must be returned exactly once");
        assertEquals("WEB", response.results.get(0).source,
                "representative keeps the strongest contribution's provenance");
    }

    @Test
    void sourceListOrdinalRankWinsOverScoreMagnitude() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = seedOnlyRequest(8);
        // X is first in its list but carries a tiny score; Y is second with a
        // near-perfect score. Rank must come from list position, not 1/score.
        request.seedVector = List.of(
                seed("https://v/x", "doc X", "0.01"),
                seed("https://v/y", "doc Y", "0.99"));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(List.of("https://v/x", "https://v/y"), resultIds(response),
                "list position is the rank; score must not reorder a list");
        assertEquals(1, response.results.get(0).rank);
        assertEquals(2, response.results.get(1).rank);
    }

    @Test
    void duplicateWithinOneListAddsNoExtraContribution() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = seedOnlyRequest(8);
        // A appears twice inside the SAME vector list; B is rank 3 there.
        // C is rank 1 of the web list.
        request.seedVector = List.of(
                seed("https://v/a", "doc A first copy"),
                seed("https://v/a", "doc A duplicate"),
                seed("https://v/b", "doc B"));
        request.seedWeb = List.of(seed("https://w/c", "doc C"));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        // If the in-list duplicate scored twice, A would total
        // 0.8/61 + 0.8/62 = 0.0260 and outrank C (1.0/61 = 0.0164).
        assertEquals(List.of("https://w/c", "https://v/a", "https://v/b"),
                resultIds(response),
                "intra-list duplicate consumes a rank slot but adds no vote");
        assertEquals(1, response.results.stream()
                .filter(d -> "https://v/a".equals(d.id)).count());
    }

    @Test
    void sameTextDifferentIdentityIsNotMergedAndProvenanceIsKept() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = seedOnlyRequest(8);
        request.seedVector = List.of(
                Content.from(TextSegment.from("identical body text",
                        Metadata.from(Map.of("url", "https://docs/p", "docName", "alpha.pdf")))),
                Content.from(TextSegment.from("identical body text",
                        Metadata.from(Map.of("url", "https://docs/q", "docName", "beta.pdf")))));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(2, response.results.size(),
                "different documents must not merge just because text matches");
        assertEquals(List.of("https://docs/p", "https://docs/q"), resultIds(response));
        assertEquals("alpha.pdf", response.results.get(0).meta.get("docName"));
        assertEquals("beta.pdf", response.results.get(1).meta.get("docName"));
    }

    @Test
    void singleSourceEligibleCandidatesAreToppedUpToRequestedTopK() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = seedOnlyRequest(8);
        List<Content> seeds = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            seeds.add(seed("https://v/" + i, "vector doc " + i));
        }
        request.seedVector = seeds;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(8, response.results.size(),
                "with only one source, eligible candidates must fill topK");
    }

    @Test
    void sameUrlDifferentChunksMergeOnceAndKeepSourceProvenance() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = seedOnlyRequest(8);
        // Document-level result contract: a web snippet and a vector chunk of
        // the SAME page are one document. The merged row must keep provenance
        // (which sources contributed) and the merged-away chunk evidence.
        request.seedWeb = List.of(seed("https://shared/page", "web intro snippet"));
        request.seedVector = List.of(seed("https://shared/page", "vector chunk seven"));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size(),
                "same URL aggregates into one document-level row");
        UnifiedRagOrchestrator.Doc merged = response.results.get(0);
        assertEquals("https://shared/page", merged.id);
        Object sources = merged.meta.get("rrfSources");
        assertTrue(sources instanceof java.util.Collection
                        && ((java.util.Collection<?>) sources).containsAll(List.of("WEB", "VECTOR")),
                "merged row must record contributing sources, got: " + sources);
        assertEquals(2, merged.meta.get("rrfMerged"),
                "merged row must record how many entries were folded in");
    }

    @Test
    void sameEvidenceDifferentSourceScopedIdsMergeOnce() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = seedOnlyRequest(8);
        // No URL, identical title+body, but source-scoped ids (WEB:x vs
        // VECTOR:x). The id namespaces differ, yet the content identity is
        // provable — splitting wastes a slot and loses the second vote.
        request.seedWeb = List.of(Content.from(TextSegment.from("identical body",
                Metadata.from(Map.of("docId", "shared-7", "title", "Same Title")))));
        request.seedVector = List.of(Content.from(TextSegment.from("identical body",
                Metadata.from(Map.of("docId", "shared-7", "title", "Same Title")))));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size(),
                "provable same evidence must not split over source-local ids");
    }

    @Test
    void callerAssertedIdentityWinsAsFoldTarget() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = seedOnlyRequest(8);
        // A caller-seeded doc with an explicit id is an asserted identity; a
        // source-scoped VECTOR doc with identical content is the same evidence
        // and must fold INTO the caller's id, not the other way around.
        UnifiedRagOrchestrator.Doc callerDoc = new UnifiedRagOrchestrator.Doc();
        callerDoc.id = "caller/42";
        callerDoc.title = "Same Title";
        callerDoc.snippet = "identical body";
        callerDoc.source = "WEB";
        callerDoc.score = 0.9;
        request.seedCandidates = List.of(callerDoc);
        request.seedVector = List.of(Content.from(TextSegment.from("identical body",
                Metadata.from(Map.of("docId", "shared-7", "title", "Same Title")))));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size(),
                "identical content must aggregate under the asserted caller id");
        assertEquals("caller/42", response.results.get(0).id);
    }

    @Test
    void differentContentDifferentSourceIdsStaySplit() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = seedOnlyRequest(8);
        // Guard: the content fold must not merge genuinely different evidence.
        request.seedWeb = List.of(Content.from(TextSegment.from("web-only body",
                Metadata.from(Map.of("docId", "w1", "title", "Web Title")))));
        request.seedVector = List.of(Content.from(TextSegment.from("vector-only body",
                Metadata.from(Map.of("docId", "v1", "title", "Vector Title")))));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(2, response.results.size(),
                "distinct evidence under different source ids must stay split");
    }

    @Test
    void candidateWindowVsCollectionCountsAreDistinct() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        // collected(pool) -> fused(reranker input) -> final(results) must be
        // distinguishable stages; widening the window must not fabricate
        // candidates that were never collected.
        List<Content> vec = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            vec.add(seed("https://v/" + i, "v" + i));
        }
        List<Content> web = List.of(
                seed("https://w/1", "w1"), seed("https://w/2", "w2"), seed("https://w/3", "w3"));

        UnifiedRagOrchestrator.QueryRequest def = seedOnlyRequest(8);
        def.seedVector = vec;
        def.seedWeb = web;
        UnifiedRagOrchestrator.QueryTrace t = orchestrator.queryWithTrace(def);
        assertEquals(11, t.pool.size(), "collected");
        assertEquals(11, t.fused.size(), "fused = reranker input (window)");
        assertEquals(8, t.response.results.size(), "final public boundary");
        assertEquals(11, ((Number) t.response.debug.get("fuse.candidateK")).intValue());

        // Explicit smaller topK on the same pool: window unchanged, cut smaller.
        // vectorTopK keeps the vector leg at 8 so the collected pool is equal.
        UnifiedRagOrchestrator.QueryRequest narrow = seedOnlyRequest(4);
        narrow.vectorTopK = 8;
        narrow.seedVector = vec;
        narrow.seedWeb = web;
        UnifiedRagOrchestrator.QueryTrace t2 = orchestrator.queryWithTrace(narrow);
        assertEquals(11, t2.pool.size());
        assertEquals(11, t2.fused.size());
        assertEquals(4, t2.response.results.size());

        // Cross-list duplicate: collected 11 -> distinct evidence 10.
        UnifiedRagOrchestrator.QueryRequest dup = seedOnlyRequest(8);
        dup.seedVector = vec;
        dup.seedWeb = List.of(seed("https://v/1", "w copy of v1"),
                seed("https://w/2", "w2"), seed("https://w/3", "w3"));
        UnifiedRagOrchestrator.QueryTrace t3 = orchestrator.queryWithTrace(dup);
        assertEquals(11, t3.pool.size(), "collected includes the duplicate");
        assertEquals(10, t3.fused.size(), "dedup removes the cross-list copy");
        assertEquals(8, t3.response.results.size());

        // Sparse pool: a wider window cannot create candidates out of nothing.
        UnifiedRagOrchestrator.QueryRequest sparse = seedOnlyRequest(8);
        sparse.seedVector = List.of(seed("https://v/1", "a"), seed("https://v/2", "b"));
        UnifiedRagOrchestrator.QueryTrace t4 = orchestrator.queryWithTrace(sparse);
        assertEquals(2, t4.pool.size());
        assertEquals(2, t4.fused.size());
        assertEquals(2, t4.response.results.size(),
                "honest under-fill: window widening is not a fetch multiplier");
    }

    @Test
    void rerankerCanPromoteCandidateBeyondFinalTopK() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        AtomicInteger onnxInputCount = new AtomicInteger();
        // Stub reranker: reverses the candidate order (worst -> first).
        ReflectionTestUtils.setField(orchestrator, "onnxReranker",
                (com.example.lms.service.rag.rerank.CrossEncoderReranker) (query, candidates, topN) -> {
                    onnxInputCount.set(candidates.size());
                    List<Content> reversed = new ArrayList<>(candidates);
                    java.util.Collections.reverse(reversed);
                    return reversed;
                });

        UnifiedRagOrchestrator.QueryRequest request = seedOnlyRequest(3);
        request.enableOnnx = true;
        // Seeds are truncated to effectiveVectorTopK; keep all six by sizing the
        // caller-specified vector window larger than the public topK.
        request.vectorTopK = 6;
        List<Content> seeds = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            seeds.add(seed("https://v/" + i, "vector doc " + i));
        }
        request.seedVector = seeds;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(6, onnxInputCount.get(),
                "reranker must see the wider candidate window, not a pre-cut topK");
        assertEquals(6, ((Number) response.debug.get("fuse.candidateK")).intValue());
        assertEquals(3, response.results.size());
        assertEquals("https://v/6", response.results.get(0).id,
                "the last candidate must be promotable to the top by the reranker");
        assertTrue(response.results.stream().allMatch(d -> "VECTOR".equals(d.source)));
    }
}
