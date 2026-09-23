package com.example.lms.service.rag.orchestrator;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.LangChainRAGService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bundle-1 regression tests for the mawain design-defect directive:
 * A1) Unified VECTOR axis must call the pure vector leaf, never the hybrid bean.
 * A2) Explicit execution limits (seedOnly, memoryProfile=NONE, whitelistOnly,
 *     caller-disabled axes) must survive plan application and fallback paths.
 */
class UnifiedRagOrchestratorVectorAxisPolicyTest {

    @AfterEach
    void clearTrace() {
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    private static UnifiedRagOrchestrator.QueryRequest baseRequest() {
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;
        return request;
    }

    @Test
    void vectorAxisUsesPureVectorLeafNotHybridRetriever() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        AtomicInteger hybridCalls = new AtomicInteger();
        AtomicInteger leafCalls = new AtomicInteger();
        List<dev.langchain4j.rag.query.Query> leafQueries = new ArrayList<>();
        ReflectionTestUtils.setField(orchestrator, "vectorRetriever", (ContentRetriever) query -> {
            hybridCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("hybrid-wire-result")));
        });
        LangChainRAGService ragService = mock(LangChainRAGService.class);
        when(ragService.asContentRetriever(anyString())).thenReturn((ContentRetriever) query -> {
            leafCalls.incrementAndGet();
            leafQueries.add(query);
            TextSegment segment = TextSegment.from("pure vector evidence", Metadata.from(Map.of("url", "https://docs.example/v1")));
            return List.of(Content.from(segment));
        });
        ReflectionTestUtils.setField(orchestrator, "langChainRAGService", ragService);

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "vector only policy check";
        request.topK = 3;
        request.useVector = true;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, leafCalls.get());
        assertEquals(0, hybridCalls.get());
        assertEquals("success:1", response.debug.get("stage.vector"));
        assertTrue(response.results.stream().allMatch(doc -> "VECTOR".equals(doc.source)));
        // Effective topK must reach the leaf through query metadata (leaf default is 5).
        assertEquals(3, ((Number) com.example.lms.service.rag.QueryUtils
                .metadata(leafQueries.get(0)).get("vectorTopK")).intValue());
    }

    @Test
    void seedOnlyNeverInvokesLiveRetrievalEvenWhenPoolIsEmpty() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        AtomicInteger legacyVectorCalls = new AtomicInteger();
        AtomicInteger leafCalls = new AtomicInteger();
        ReflectionTestUtils.setField(orchestrator, "vectorRetriever", (ContentRetriever) query -> {
            legacyVectorCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("must-not-retrieve")));
        });
        LangChainRAGService ragService = mock(LangChainRAGService.class);
        when(ragService.asContentRetriever(anyString())).thenReturn((ContentRetriever) query -> {
            leafCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("must-not-retrieve")));
        });
        ReflectionTestUtils.setField(orchestrator, "langChainRAGService", ragService);

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "seed only empty pool";
        request.seedOnly = true;
        request.useVector = true;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(Boolean.TRUE, response.debug.get("seed.only"));
        assertEquals(0, legacyVectorCalls.get());
        assertEquals(0, leafCalls.get());
        assertTrue(response.results.isEmpty());
    }

    @Test
    void seedOnlyWithEmptySeedVectorListStillNeverRetrieves() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        AtomicInteger legacyVectorCalls = new AtomicInteger();
        ReflectionTestUtils.setField(orchestrator, "vectorRetriever", (ContentRetriever) query -> {
            legacyVectorCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("must-not-retrieve")));
        });
        LangChainRAGService ragService = mock(LangChainRAGService.class);
        when(ragService.asContentRetriever(anyString())).thenReturn((ContentRetriever) query -> {
            legacyVectorCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("must-not-retrieve")));
        });
        ReflectionTestUtils.setField(orchestrator, "langChainRAGService", ragService);

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "seed only empty vector list";
        request.seedOnly = true;
        request.seedVector = List.of();
        request.useVector = true;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(0, legacyVectorCalls.get());
        assertTrue(response.results.isEmpty());
    }

    @Test
    void explicitMemoryProfileNoneSurvivesBraveAndAggressive() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();

        UnifiedRagOrchestrator.QueryRequest brave = baseRequest();
        brave.query = "explicit none under brave";
        brave.jamminiMode = "brave";
        brave.memoryProfile = "NONE";
        orchestrator.query(brave);
        assertEquals("NONE", brave.memoryProfile);

        UnifiedRagOrchestrator.QueryRequest aggressive = baseRequest();
        aggressive.query = "explicit none under aggressive";
        aggressive.aggressive = true;
        aggressive.memoryProfile = "NONE";
        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(aggressive);
        assertEquals("NONE", aggressive.memoryProfile);
        assertNotEquals("forced", response.debug.get("memory.inject"));
    }

    @Test
    void planCannotWeakenCallerWhitelistOrDisabledAxes() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "planHintApplier",
                new PlanHintApplier(new DefaultResourceLoader()));

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "caller restrictions survive plan";
        request.planId = "zero100.v1"; // officialSourcesOnly=false, kgTopK=2
        request.whitelistOnly = true;
        request.useKg = false;
        request.seedOnly = true;
        request.seedCandidates = List.of(seedDoc("s1", "https://official.test/a"));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals("zero100.v1", response.debug.get("plan.id"));
        assertTrue(request.whitelistOnly);
        assertFalse(request.useKg);
        assertEquals(true, response.debug.get("plan.officialOnly"));
        // whitelistOnly=true with no DomainWhitelist bean must fail closed.
        assertEquals("policy_unavailable", response.debug.get("stage.whitelist"));
        assertTrue(response.results.isEmpty());
    }

    @Test
    void whitelistOnlyFailsClosedWhenPolicyBeanMissing() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "strict whitelist without policy bean";
        request.whitelistOnly = true;
        request.seedOnly = true;
        request.seedCandidates = List.of(
                seedDoc("allowed-looking", "https://official.test/a"),
                seedDoc("other", "https://other.test/b"));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals("policy_unavailable", response.debug.get("stage.whitelist"));
        assertEquals(2, response.debug.get("stage.whitelist.filtered"));
        assertTrue(response.results.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("rag.whitelist.policy_unavailable"));
    }

    /**
     * Behavioral evidence (common entry point): with only the legacy
     * 'vectorRetriever' bean wired and no LangChainRAGService, the pure leaf is
     * unavailable. The VECTOR stage must record an unavailable reason and must
     * NOT fall back to the hybrid bean (which carries web/SelfAsk side effects).
     */
    @Test
    void vectorLeafUnavailableRecordsReasonAndNeverCallsHybrid() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        AtomicInteger hybridCalls = new AtomicInteger();
        ReflectionTestUtils.setField(orchestrator, "vectorRetriever", (ContentRetriever) query -> {
            hybridCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("hybrid-must-not-run")));
        });

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "vector axis without pure leaf";
        request.useVector = true;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(0, hybridCalls.get(), "hybrid bean must not be invoked as a vector fallback");
        assertEquals("unavailable_pure_vector_leaf", response.debug.get("stage.vector"));
        assertEquals("missing_bean", response.debug.get("retrieval.dependency.vector.status"));
        assertTrue(response.results.isEmpty());
    }

    /**
     * The Query handed to the pure leaf must carry the caller's effective
     * vectorTopK AND the repository's default doc-type search scope (added by
     * QueryUtils.buildQuery). QueryRequest owns no session carrier, so no sid
     * may be invented — the leaf's global-pool default governs. threadId in
     * particular is never copied into query metadata.
     */
    @Test
    void vectorQueryCarriesTopKAndScopeAndInventsNoSid() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        List<dev.langchain4j.rag.query.Query> leafQueries = new ArrayList<>();
        LangChainRAGService ragService = mock(LangChainRAGService.class);
        when(ragService.asContentRetriever(anyString())).thenReturn((ContentRetriever) query -> {
            leafQueries.add(query);
            return List.of(Content.from(TextSegment.from("scoped vector evidence")));
        });
        ReflectionTestUtils.setField(orchestrator, "langChainRAGService", ragService);

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "scope propagation check";
        request.threadId = "thread-should-not-become-sid";
        request.useVector = true;
        request.topK = 5;

        orchestrator.query(request);

        assertEquals(1, leafQueries.size());
        Map<String, Object> meta = com.example.lms.service.rag.QueryUtils.metadata(leafQueries.get(0));
        assertEquals(5, ((Number) meta.get("vectorTopK")).intValue(),
                "effective vectorTopK must reach the leaf query");
        assertEquals("KB,MEMORY,LEGACY", String.valueOf(meta.get("allowed_doc_types")),
                "the repository default doc-type scope must reach the leaf");
        assertFalse(meta.containsKey("sid"), "no sid may be invented");
        assertFalse(meta.containsKey("threadId"), "threadId must not leak into query metadata");
        assertEquals("scope propagation check", leafQueries.get(0).text());
    }

    /**
     * useWeb=false + useVector=true: the empty-result and exception paths must
     * perform zero web retrieval / page collection and zero hybrid calls.
     */
    @Test
    void vectorEmptyAndExceptionPathsNeverInvokeWebOrHybrid() {
        AtomicInteger webCalls = new AtomicInteger();
        AtomicInteger hybridCalls = new AtomicInteger();

        // Path A: leaf returns empty
        UnifiedRagOrchestrator emptyCase = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(emptyCase, "webRetriever", (ContentRetriever) query -> {
            webCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("must-not-fetch")));
        });
        ReflectionTestUtils.setField(emptyCase, "vectorRetriever", (ContentRetriever) query -> {
            hybridCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("must-not-fetch")));
        });
        LangChainRAGService emptyRag = mock(LangChainRAGService.class);
        AtomicInteger emptyLeafCalls = new AtomicInteger();
        when(emptyRag.asContentRetriever(anyString())).thenReturn((ContentRetriever) query -> {
            emptyLeafCalls.incrementAndGet();
            return List.of();
        });
        ReflectionTestUtils.setField(emptyCase, "langChainRAGService", emptyRag);

        UnifiedRagOrchestrator.QueryRequest emptyReq = baseRequest();
        emptyReq.query = "vector empty path";
        emptyReq.useVector = true;
        UnifiedRagOrchestrator.QueryResponse emptyResp = emptyCase.query(emptyReq);
        // Main leg + the documented emergency retry on an empty pool: the leaf
        // is invoked twice, and both calls go to the pure leaf only.
        assertEquals(2, emptyLeafCalls.get());
        assertEquals("empty_result", emptyResp.debug.get("stage.vector"));

        // Path B: leaf throws
        UnifiedRagOrchestrator failCase = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(failCase, "webRetriever", (ContentRetriever) query -> {
            webCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("must-not-fetch")));
        });
        ReflectionTestUtils.setField(failCase, "vectorRetriever", (ContentRetriever) query -> {
            hybridCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("must-not-fetch")));
        });
        LangChainRAGService failRag = mock(LangChainRAGService.class);
        AtomicInteger failLeafCalls = new AtomicInteger();
        when(failRag.asContentRetriever(anyString())).thenReturn((ContentRetriever) query -> {
            failLeafCalls.incrementAndGet();
            throw new IllegalStateException("synthetic vector outage");
        });
        ReflectionTestUtils.setField(failCase, "langChainRAGService", failRag);

        UnifiedRagOrchestrator.QueryRequest failReq = baseRequest();
        failReq.query = "vector exception path";
        failReq.useVector = true;
        UnifiedRagOrchestrator.QueryResponse failResp = failCase.query(failReq);
        // Main leg failure is recorded; the emergency retry hits the same leaf.
        assertEquals(2, failLeafCalls.get());
        assertEquals("failed:vector_retrieval_failed", failResp.debug.get("stage.vector"));

        assertEquals(0, webCalls.get(), "no web retrieval/page collection on vector-only paths");
        assertEquals(0, hybridCalls.get(), "no hybrid fallback on vector-only paths");
    }

    @Test
    void exhaustedRequestBudgetBlocksEmergencyVectorRetry() throws Exception {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        LangChainRAGService ragService = mock(LangChainRAGService.class);
        AtomicInteger leafCalls = new AtomicInteger();
        when(ragService.asContentRetriever(anyString())).thenReturn((ContentRetriever) query -> {
            leafCalls.incrementAndGet();
            return List.of();
        });
        ReflectionTestUtils.setField(orchestrator, "langChainRAGService", ragService);

        TimeBudgetContext.set(new TimeBudget(1));
        Thread.sleep(30L); // request deadline already spent before query entry

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "budget-dead emergency check";
        request.useVector = true;
        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        // Initial leg still runs once; the emergency second call is gated off
        // by the exhausted request budget and records its termination reason.
        assertEquals(1, leafCalls.get());
        assertEquals("skipped:request_budget_exhausted",
                response.debug.get("retrieval.emergency"));
    }

    @Test
    void emergencyVectorRetryAllowedWhileBudgetRemains() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        LangChainRAGService ragService = mock(LangChainRAGService.class);
        AtomicInteger leafCalls = new AtomicInteger();
        when(ragService.asContentRetriever(anyString())).thenReturn((ContentRetriever) query -> {
            leafCalls.incrementAndGet();
            return List.of();
        });
        ReflectionTestUtils.setField(orchestrator, "langChainRAGService", ragService);

        TimeBudgetContext.set(new TimeBudget(30_000));

        UnifiedRagOrchestrator.QueryRequest request = baseRequest();
        request.query = "budget-alive emergency check";
        request.useVector = true;
        orchestrator.query(request);

        // Positive counterpart: with budget remaining, the bounded emergency
        // retry (exactly one extra call, max 2 total) is still allowed.
        assertEquals(2, leafCalls.get());
    }

    /**
     * Isolated Spring wiring check: only the named beans under test are
     * registered. The Unified VECTOR stage must resolve to the pure leaf
     * (LangChainRAGService.asContentRetriever), while the 'vectorRetriever'
     * bean remains present for the ordinary-chat hybrid path and is not used.
     * A second context without LangChainRAGService must leave the axis
     * unavailable without touching the hybrid bean.
     */
    @Test
    void springWiringConnectsVectorAxisToPureLeafOnly() {
        AtomicInteger hybridCalls = new AtomicInteger();
        AtomicInteger leafCalls = new AtomicInteger();
        ContentRetriever hybridBean = query -> {
            hybridCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("hybrid-bean-result")));
        };
        LangChainRAGService ragService = mock(LangChainRAGService.class);
        when(ragService.asContentRetriever(anyString())).thenReturn((ContentRetriever) query -> {
            leafCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("pure-leaf-result")));
        });

        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean("vectorRetriever", ContentRetriever.class, () -> hybridBean)
                .withBean(LangChainRAGService.class, () -> ragService)
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    assertSame(hybridBean, context.getBean("vectorRetriever"),
                            "ordinary-chat hybrid bean stays registered");
                    UnifiedRagOrchestrator.QueryRequest request = baseRequest();
                    request.query = "spring wiring vector axis";
                    request.useVector = true;
                    request.topK = 3;
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    assertEquals(1, leafCalls.get());
                    assertEquals(0, hybridCalls.get());
                    assertEquals("success:1", response.debug.get("stage.vector"));
                    assertTrue(response.results.stream().allMatch(d -> "VECTOR".equals(d.source)));
                });

        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean("vectorRetriever", ContentRetriever.class, () -> hybridBean)
                .withBean(UnifiedRagOrchestrator.class)
                .run(context -> {
                    UnifiedRagOrchestrator.QueryRequest request = baseRequest();
                    request.query = "spring wiring missing leaf";
                    request.useVector = true;
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    assertEquals("unavailable_pure_vector_leaf", response.debug.get("stage.vector"));
                    assertEquals(0, hybridCalls.get());
                });
    }

    /**
     * Characterization of the bean named 'vectorRetriever': a real
     * HybridRetriever instance runs its WEB leg first for SIMPLE queries. This
     * is why the Unified VECTOR axis must not route through that bean.
     * Counting stubs record actual retriever entry; the inner vector leaf must
     * remain untouched while web evidence is present.
     */
    @Test
    void realHybridRetrieverRunsWebLegInsideVectorNamedBean() {
        AtomicInteger webCalls = new AtomicInteger();
        AtomicInteger innerVectorCalls = new AtomicInteger();

        com.example.lms.service.rag.WebSearchRetriever web =
                mock(com.example.lms.service.rag.WebSearchRetriever.class);
        when(web.retrieve(any())).thenAnswer(inv -> {
            webCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("web side effect evidence")));
        });
        com.example.lms.service.rag.QueryComplexityGate gate =
                mock(com.example.lms.service.rag.QueryComplexityGate.class);
        when(gate.assess(anyString()))
                .thenReturn(com.example.lms.service.rag.QueryComplexityGate.Level.SIMPLE);
        com.example.lms.service.rag.detector.GameDomainDetector detector =
                mock(com.example.lms.service.rag.detector.GameDomainDetector.class);
        when(detector.detect(anyString())).thenReturn("GENERAL");
        LangChainRAGService ragService = mock(LangChainRAGService.class);
        when(ragService.asContentRetriever(anyString())).thenReturn((ContentRetriever) query -> {
            innerVectorCalls.incrementAndGet();
            return List.of();
        });
        com.example.lms.service.rag.rerank.LightWeightRanker ranker =
                mock(com.example.lms.service.rag.rerank.LightWeightRanker.class);
        when(ranker.rank(any(), anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        com.example.lms.service.rag.RelevanceScoringService scoring =
                mock(com.example.lms.service.rag.RelevanceScoringService.class);
        when(scoring.relatedness(anyString(), anyString())).thenReturn(1.0d);

        com.example.lms.service.rag.HybridRetriever hybrid = new com.example.lms.service.rag.HybridRetriever(
                ranker,
                mock(com.example.lms.service.rag.rerank.RerankGate.class),
                mock(com.example.lms.service.rag.auth.AuthorityScorer.class),
                mock(com.example.lms.service.rag.handler.RetrievalHandler.class),
                mock(com.example.lms.service.rag.fusion.ReciprocalRankFuser.class),
                mock(com.example.lms.service.rag.AnswerQualityEvaluator.class),
                mock(com.example.lms.service.rag.SelfAskPlanner.class),
                scoring,
                mock(com.example.lms.service.config.HyperparameterService.class),
                mock(com.example.lms.service.rag.rerank.ElementConstraintScorer.class),
                mock(com.example.lms.transform.QueryTransformer.class),
                mock(com.example.lms.service.scoring.AdaptiveScoringService.class),
                mock(com.example.lms.service.knowledge.KnowledgeBaseService.class),
                mock(com.example.lms.learning.NeuralPathFormationService.class),
                mock(com.example.lms.service.rag.SelfAskWebSearchRetriever.class),
                mock(com.example.lms.service.rag.AnalyzeWebSearchRetriever.class),
                web,
                gate,
                ragService,
                mock(dev.langchain4j.model.embedding.EmbeddingModel.class),
                mock(dev.langchain4j.store.embedding.EmbeddingStore.class),
                detector);

        hybrid.retrieve(new dev.langchain4j.rag.query.Query("simple factual question"));

        assertEquals(1, webCalls.get(),
                "the bean named 'vectorRetriever' performs a real web search for SIMPLE queries");
        assertEquals(0, innerVectorCalls.get(),
                "web hit present -> inner vector leg must not run");
    }

    private static UnifiedRagOrchestrator.Doc seedDoc(String id, String url) {
        UnifiedRagOrchestrator.Doc doc = new UnifiedRagOrchestrator.Doc();
        doc.id = id;
        doc.title = id;
        doc.snippet = "seed " + id;
        doc.source = "SEED";
        doc.score = 1.0d;
        doc.rank = 1;
        doc.meta = Map.of("url", url);
        return doc;
    }
}
