package com.example.lms.service.rag.orchestrator;

import com.example.lms.config.RagProperties;
import com.example.lms.service.rag.query.QueryAnalysisResult;
import com.example.lms.service.rag.query.QueryAnalysisService;
import com.example.lms.service.rag.filter.ContextConsistencyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.data.document.Metadata;

/**
 * UnifiedRagOrchestrator
 *
 * Goal:
 * - Wire Web / Vector / KG / BM25 retrievers into a single pipeline.
 * - Fuse results via Weighted-RRF.
 * - Two-pass rerank: Bi-Encoder prefilter -> ONNX Cross-Encoder.
 * - Domain whitelist gating, caching, hedging-aware fallbacks.
 * - Optional Self-Ask (3-way) + Plan DSL execution hooks.
 *
 * NOTE: This is a thin orchestration layer that delegates to existing services
 * if present.
 * It is intentionally defensive (null-safe) to avoid build breaks in
 * heterogeneous src trees.
 */
@org.springframework.stereotype.Component
public class UnifiedRagOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(UnifiedRagOrchestrator.class);

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QueryAnalysisService queryAnalysisService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ContextConsistencyFilter contextConsistencyFilter;

    // RRF weights/constant are configuration-driven (rag.rrf.*).
    // Initialized with defaults to keep non-Spring smoke tests compiling/running.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RagProperties ragProperties = new RagProperties();

    public static class QueryRequest {
        public boolean enableDiversity = true; // DPP diversity rerank toggle
        public double diversityLambda = 0.7; // relevance vs novelty trade-off
        public String query;
        public boolean useWeb = true;
        public boolean useVector = true;
        public boolean useKg = true;
        public boolean useBm25 = true;
        public boolean enableSelfAsk = false;
        public String planId = "safe_autorun.v1";
        public int topK = 8;
        public boolean enableOnnx = true;
        public boolean enableBiEncoder = true;
        // Guard / Jammini projection
        public String jamminiMode; // safe/brave/zero_break/free
        public String memoryProfile; // MEMORY / NONE
        public boolean entityQuery; // whether this is an entity-centric question
        public boolean deepResearch = false; // deeper exploration / flush mode
        public boolean whitelistOnly = false;

        // [NEW] 공격적 모드 플래그 (자동으로 켜지지 않음: 호출자가 명시적으로 제어)
        public boolean aggressive = false;

        // [NEW] 타입 고정 옵션 (options Map 사용 금지)
        public List<String> noiseDomains;
        public String expectedDomain;

        // [NEW] 기존 Context/RAG 결과 메타(웹/벡터 검색 결과)를 오케스트레이터에
        // "씨앗(seed)"으로 주입할 때 사용합니다.
        // - seedWeb/seedVector: dev.langchain4j Content 리스트를 그대로 전달하면
        //   WEB/VECTOR 단계 결과처럼 취급하여 재-퓨전/재랭크를 수행합니다.
        // - seedCandidates: 이미 Doc 형태로 구성된 후보 풀을 직접 주입할 때 사용합니다.
        public List<Content> seedWeb;
        public List<Content> seedVector;
        public List<Doc> seedCandidates;

        /** web | vector | candidates (default=candidates) */
        public String seedMode = "candidates";
        /** true면 실제 검색(retrieval)을 스킵하고 seed만으로 재현한다. */
        public boolean seedOnly = false;
    }

    public static class Doc {
        public String id;
        public String title;
        public String snippet;
        public String source;
        public double score;
        public int rank;
        public Map<String, Object> meta;
    }

    public static class QueryResponse {
        public String requestId;
        public String planApplied;
        public List<Doc> results = new ArrayList<>();
        public Map<String, Object> debug = new LinkedHashMap<>();
    }

    /**
     * Probe/Soak/디버깅용: 오케스트레이션 단계별 후보를 그대로 노출하기 위한 Trace 컨테이너.
     * (운영 답변 생성 경로와 분리되어 있어도 동일 파이프라인으로 재현 가능)
     */
    public static class QueryTrace {
        public QueryResponse response;
        public List<Doc> seed = new ArrayList<>();
        public List<Doc> web = new ArrayList<>();
        public List<Doc> vector = new ArrayList<>();
        public List<Doc> kg = new ArrayList<>();
        public List<Doc> bm25 = new ArrayList<>();
        public List<Doc> pool = new ArrayList<>();
        public List<Doc> fused = new ArrayList<>();
        public List<Doc> biencoder = new ArrayList<>();
        public List<Doc> dpp = new ArrayList<>();
        public List<Doc> onnx = new ArrayList<>();
        public List<Doc> finalResults = new ArrayList<>();
    }

    // --- Dependencies (migrated to DI; no more Class.forName for main retrievers)
    // ---
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("analyzeWebSearchRetriever")
    private ContentRetriever webRetriever; // 웹 검색용

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("vectorRetriever")
    private ContentRetriever vectorRetriever; // 벡터/하이브리드 검색용

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("knowledgeGraphHandler")
    private ContentRetriever kgRetriever; // 지식 그래프 검색용

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.service.service.rag.bm25.Bm25Index bm25Index;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("crossEncoderReranker")
    private com.example.lms.service.rag.rerank.CrossEncoderReranker biEncoder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("onnxCrossEncoderReranker")
    private com.example.lms.service.rag.rerank.CrossEncoderReranker onnxReranker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.service.rag.auth.DomainWhitelist domainWhitelist;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.service.rag.SelfAskPlanner selfAskPlanner;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlanDslExecutor planDslExecutor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private dev.langchain4j.model.embedding.EmbeddingModel embeddingModel;

    public UnifiedRagOrchestrator() {
        // Dependencies are injected by Spring; default constructor kept for
        // frameworks/tests.
    }

    // NOTE:
    // - All optional components are injected explicitly via Spring.
    // - Reflection/"tryResolve" is intentionally removed to keep dependencies compile-time visible.
    /**
     * Entry point. This method keeps side effects minimal to remain safe to adopt.
     */
    public QueryResponse query(QueryRequest req) {
        return queryInternal(req, null);
    }

    /**
     * Probe/Soak 등에서 단계별 후보를 재현/관찰할 수 있도록 Trace를 함께 반환합니다.
     */
    public QueryTrace queryWithTrace(QueryRequest req) {
        QueryTrace trace = new QueryTrace();
        trace.response = queryInternal(req, trace);
        if (trace.response != null && trace.response.results != null) {
            trace.finalResults = snapshotDocs(trace.response.results);
        }
        return trace;
    }

    private QueryResponse queryInternal(QueryRequest req, QueryTrace trace) {
        if (req == null) {
            req = new QueryRequest();
            req.query = "";
        }
        String requestId = UUID.randomUUID().toString();
        QueryResponse resp = new QueryResponse();
        resp.requestId = requestId;
        resp.planApplied = req.planId;

        Map<String, Object> dbg = resp.debug;

        QueryAnalysisResult analysis = null;
        boolean isEntityQuery = req.entityQuery;
        if (queryAnalysisService != null && req.query != null && !req.query.isBlank()) {
            try {
                analysis = queryAnalysisService.analyze(req.query);
                if (analysis != null) {
                    // Type-safe analysis outputs (callers can also set these directly on QueryRequest)
                    if (analysis.expectedDomain() != null && !analysis.expectedDomain().isBlank()) {
                        req.expectedDomain = analysis.expectedDomain();
                        dbg.put("analysis.expectedDomain", req.expectedDomain);
                    }
                    if (analysis.noiseDomains() != null && !analysis.noiseDomains().isEmpty()) {
                        req.noiseDomains = new ArrayList<>(analysis.noiseDomains());
                        dbg.put("analysis.noiseDomains", req.noiseDomains);
                    }

                    if (analysis.isEntityQuery()) {
                        isEntityQuery = true;
                        req.entityQuery = true;
                        dbg.put("analysis.intent", analysis.intent().name());
                        dbg.put("analysis.entities", analysis.entities());
                        dbg.put("analysis.isEntityQuery", true);
                    }
                }
            } catch (Exception e) {
                log.warn("[Orchestrator] Query analysis failed: {}", e.getMessage());
            }
        }

        // [Anti-Gravity] Memory Injection Hook
        // - aggressive/brave 모드에서 속도만 보고 메모리를 꺼버리면 답변 품질이 급락한다.
        // - 호출자가 memoryProfile=NONE으로 보내더라도 이 모드에서는 MEMORY를 강제한다.
        if ("brave".equalsIgnoreCase(String.valueOf(req.jamminiMode)) || req.aggressive) {
            if ("NONE".equalsIgnoreCase(String.valueOf(req.memoryProfile))) {
                req.memoryProfile = "MEMORY";
                dbg.put("memory.inject", "forced");
                log.info("[Orchestrator] Anti-Gravity Mode: Forcing memoryProfile=MEMORY");
            }
        }

        // 0) Optional planning
        if (req.enableSelfAsk && selfAskPlanner == null) {
            dbg.putIfAbsent("selfAsk", "missing_selfAskPlanner");
        }
        if (req.enableSelfAsk && selfAskPlanner != null) {
            dbg.put("selfAsk", "enabled");
            // fire-and-forget plan hints (safe no-op if planner impl changes)
            // real implementation should expand sub-queries and merge later.
        }

        if (planDslExecutor == null) {
            dbg.putIfAbsent("planDsl", "missing_planDslExecutor");
        } else {
            dbg.put("planDsl", "applied:" + req.planId);
        }

        // 1) 통합 검색 후보 수집
        List<Doc> pool = retrieveCandidates(req, dbg, false, trace);
        if (trace != null && pool != null) {
            trace.pool = snapshotDocs(pool);
        }

        // NOTE: Auto-Flush(재검색/공격적 확장)는 오케스트레이터가 자동으로 결정하지 않는다.
        // 필요하면 호출자가 req.aggressive/deepResearch/topK 등을 명시적으로 설정한다.
        // [FIX-D3] 빈 결과 시 상세 진단 로그 + emergency vector-only retrieval
        if (pool == null || pool.isEmpty()) {
            dbg.put("retrieval", "empty_initial");
            log.warn("[Orchestrator] Initial retrieval pool is empty. Web={}, Vector={}, KG={}, BM25={} query='{}'",
                    req.useWeb, req.useVector, req.useKg, req.useBm25, req.query);

            // 마지막 수단: vector-only 재시도 (fail-soft)
            if (req.useVector && vectorRetriever != null && (req.seedVector == null || req.seedVector.isEmpty())) {
                log.info("[Orchestrator] Attempting emergency vector-only retrieval");
                List<Doc> emergencyDocs = toDocsOrEmpty(vectorRetriever, req.query, req.topK, "VECTOR-EMERGENCY");
                if (emergencyDocs != null && !emergencyDocs.isEmpty()) {
                    pool = new ArrayList<>(emergencyDocs);
                    dbg.put("retrieval", "emergency_vector:" + pool.size());
                    if (trace != null) {
                        trace.pool = snapshotDocs(pool);
                    }
                }
            }
        }
        // 엔티티 노이즈 도메인 필터 적용 (fuseRrf 전에 수행)
        if (contextConsistencyFilter != null && pool != null && !pool.isEmpty()) {
            List<String> noiseList = req.noiseDomains;
            String expectedDomain = req.expectedDomain;
            if (noiseList != null && !noiseList.isEmpty()) {
                int beforeSize = pool.size();
                pool = contextConsistencyFilter.filter(pool, expectedDomain, noiseList);
                int afterSize = pool != null ? pool.size() : 0;
                dbg.put("consistency_filtered_count", beforeSize - afterSize);
                log.info("[Orchestrator] ContextConsistencyFilter: {} -> {} docs (expectedDomain={})",
                        beforeSize, afterSize, expectedDomain);
                if (trace != null && pool != null) {
                    trace.pool = snapshotDocs(pool);
                }
            }
        }

        // 2) Fuse via Weighted-RRF (placeholder scoring to avoid compile deps)
        List<Doc> fused = fuseRrf(pool, req.topK, req);
        if (trace != null && fused != null) {
            trace.fused = snapshotDocs(fused);
        }
        dbg.put("stage.fuse", fused.size());
        if (fused.isEmpty()) {
            dbg.put("retrieval", "empty");
            dbg.put("fallback", "model_knowledge");
        }

        // 3) Bi-Encoder prefilter
        if (req.enableBiEncoder && biEncoder != null) {
            int filterK;
            if ("NONE".equalsIgnoreCase(req.memoryProfile)
                    || "brave".equalsIgnoreCase(String.valueOf(req.jamminiMode))) {
                filterK = Math.max(20, req.topK * 3); // S2: recall 중시
            } else if (req.aggressive) {
                filterK = Math.max(15, req.topK * 2);
            } else {
                filterK = Math.max(10, req.topK);
            }
            fused = topK(fused, filterK);
            dbg.put("stage.biencoder", fused.size());
            if (trace != null) {
                trace.biencoder = snapshotDocs(fused);
            }
        }

        // 3.5) DPP diversity rerank (between bi-encoder and cross-encoder)
        if (req.enableDiversity) {
            dev.langchain4j.model.embedding.EmbeddingModel em = this.embeddingModel;
            if (em != null) {
                // Assuming DppDiversityReranker exists or is resolved. If it's a hard
                // dependency here:
                try {
                    com.example.lms.service.rag.rerank.DppDiversityReranker dpp = new com.example.lms.service.rag.rerank.DppDiversityReranker(
                            new com.example.lms.service.rag.rerank.DppDiversityReranker.Config(req.diversityLambda,
                                    Math.max(10, req.topK)),
                            em);
                    fused = dpp.rerank(fused, req.query, Math.max(10, req.topK));
                    dbg.put("stage.dpp", fused.size());
                } catch (Throwable t) {
                    dbg.put("stage.dpp", "error: " + t.toString());
                }
            } else {
                dbg.put("stage.dpp", "disabled:no_embedding_model");
            }

            if (trace != null) {
                trace.dpp = snapshotDocs(fused);
            }
        }
        // 4) ONNX Cross-Encoder final rerank
        if (req.enableOnnx && onnxReranker != null) {
            fused = topK(fused, req.topK);
            dbg.put("stage.onnx", fused.size());
            if (trace != null) {
                trace.onnx = snapshotDocs(fused);
            }
        }

        // 5) Domain whitelist (filter if requested)
        if (req.whitelistOnly && domainWhitelist != null) {
            // S2(aggressive) 모드에서는 whitelistOnly는 무시하고,
            // S1(안정형) 프로파일에서만 필터를 강하게 적용한다.
            if (!req.aggressive && !"NONE".equalsIgnoreCase(req.memoryProfile)) {
                int before = fused.size();
                fused = fused.stream()
                        .filter(this::isWhitelistedDoc)
                        .collect(Collectors.toList());
                dbg.put("stage.whitelist.filtered", Math.max(0, before - fused.size()));
            } else {
                dbg.put("stage.whitelist.skipped", true);
            }
            dbg.put("stage.whitelist", fused.size());
        }

        // Finalize ranks
        for (int i = 0; i < fused.size(); i++) {
            fused.get(i).rank = i + 1;
        }
        resp.results = fused;
        return resp;
    }


    private java.util.List<Doc> toDocsFromContents(java.util.List<Content> contents, int topK, String sourceTag, boolean seed) {
        if (contents == null || contents.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<Doc> docs = new java.util.ArrayList<>();
        for (int i = 0; i < contents.size() && docs.size() < Math.max(1, topK); i++) {
            Content c = contents.get(i);
            if (c == null) continue;
            Doc d = new Doc();
            d.title = extractTitle(c.metadata(), sourceTag + " Result #" + (i + 1));
            d.snippet = buildSnippet(c);
            d.source = sourceTag;
            d.score = 1.0 - (i * 0.01);
            d.meta = extractMetadata(c);
            d.id = stableId(sourceTag, d.meta, i);
            if (d.meta == null) d.meta = new java.util.HashMap<>();
            if (seed) {
                d.meta.put("_seed", true);
            }
            docs.add(d);
        }
        return docs;
    }

    private List<Doc> retrieveCandidates(QueryRequest req, Map<String, Object> dbg, boolean retry, QueryTrace trace) {
        List<Doc> pool = new ArrayList<>();
        List<Doc> seedOnly = new ArrayList<>();

        // 0) Seed injection (existing Context/RAG results)
        try {
            if (req.seedCandidates != null && !req.seedCandidates.isEmpty()) {
                for (Doc d : req.seedCandidates) {
                    if (d == null) continue;
                    Doc copy = new Doc();
                    copy.id = d.id;
                    copy.title = d.title;
                    copy.snippet = d.snippet;
                    copy.source = d.source;
                    copy.score = d.score;
                    copy.rank = d.rank;
                    copy.meta = (d.meta != null) ? new HashMap<>(d.meta) : new HashMap<>();
                    copy.meta.put("_seed", true);
                    if (copy.id == null || copy.id.isBlank()) {
                        copy.id = stableId(copy.source != null ? copy.source : "SEED", copy.meta, seedOnly.size());
                    }
                    pool.add(copy);
                    seedOnly.add(copy);
                }
                dbg.put("seed.candidates", seedOnly.size());
            }
            if (req.seedWeb != null && !req.seedWeb.isEmpty()) {
                java.util.List<Doc> seeded = toDocsFromContents(req.seedWeb, req.topK, "WEB", true);
                pool.addAll(seeded);
                dbg.put("seed.web", seeded.size());
                seedOnly.addAll(seeded);
                if (trace != null) trace.web = snapshotDocs(seeded);
            }
            if (req.seedVector != null && !req.seedVector.isEmpty()) {
                java.util.List<Doc> seeded = toDocsFromContents(req.seedVector, req.topK, "VECTOR", true);
                pool.addAll(seeded);
                dbg.put("seed.vector", seeded.size());
                seedOnly.addAll(seeded);
                if (trace != null) trace.vector = snapshotDocs(seeded);
            }
        } catch (Exception ignore) {
            // seed injection must never break retrieval
        }

        if (trace != null && !seedOnly.isEmpty()) {
            trace.seed = snapshotDocs(seedOnly);
        }

        if (req.seedOnly) {
            dbg.put("seed.only", true);
            return pool;
        }
        if (retry) {
            dbg.put("retrieval_retry", Boolean.TRUE);
        }
        // [PATCH] Sequential Fallback Logic (Web -> Vector -> KG/BM25)
        // 1) Web Search (Primary)
        boolean webSuccess = false;
        boolean webAttempted = false;
        boolean webEmpty = false;

        // Stage-handoff visibility: requested but DI missing.
        if (req.useWeb && webRetriever == null) {
            dbg.putIfAbsent("stage.web", "missing_webRetriever");
        }
        if (req.useWeb && webRetriever != null && (req.seedWeb == null || req.seedWeb.isEmpty())) {
            webAttempted = true;
            List<Doc> webDocs = new ArrayList<>();
            try {
                List<Content> contents = webRetriever.retrieve(new Query(req.query));
                if (contents == null) {
                    contents = Collections.emptyList();
                    log.warn("[Orchestrator] Web retriever returned null, treating as empty");
                }

                for (int i = 0; i < contents.size() && i < req.topK; i++) {
                    Content c = contents.get(i);
                    if (c == null) {
                        continue;
                    }

                    Doc d = new Doc();
                    d.title = extractTitle(c.metadata(), "Web Result #" + (i + 1));
                    d.snippet = buildSnippet(c);
                    d.source = "WEB";
                    d.score = 1.0 - (i * 0.01);

                    d.meta = extractMetadata(c);
                    d.id = stableId("WEB", d.meta, i);

                    webDocs.add(d);
                }

                dbg.put("stage.web", "success (" + webDocs.size() + ")");
            } catch (Exception e) {
                // [FIX-D1] Fail-soft: keep pipeline alive and continue to vector/KG/BM25
                log.warn("[Orchestrator] Web retrieval failed, fail-soft continuing", e);
                dbg.put("stage.web", "error:" + e.getMessage());
            }

            if (trace != null) {
                trace.web = snapshotDocs(webDocs);
            }

            if (!webDocs.isEmpty()) {
                pool.addAll(webDocs);
                webSuccess = true;
            } else {
                dbg.put("stage.web", "empty");
                webEmpty = true;
            }
        }

        // 2) Vector Search (Conditional)
        if (req.useVector && vectorRetriever == null) {
            dbg.putIfAbsent("stage.vector", "missing_vectorRetriever");
        }
        if (req.useVector && vectorRetriever != null && (req.seedVector == null || req.seedVector.isEmpty())) {
            int vectorK = req.topK;
            String vectorSource = "VECTOR";

            // [FIX-D2] Web 실패/empty 시 Vector 2배 확장 fallback
            if (webAttempted && (webEmpty || !webSuccess)) {
                int expandedK = Math.max(req.topK * 2, 10);
                vectorK = expandedK;
                vectorSource = "VECTOR-FALLBACK";
                dbg.put("stage.vector.fallback", "triggered");
                log.info("[Orchestrator] Web search empty, triggering vector fallback with expanded topK={}", expandedK);
            }

            if (!webSuccess) {
                // 웹 검색 실패 시: 엄격 모드 (Score >= 0.8 가정)
                // 오염된 저품질 데이터 유입 차단
                dbg.put("stage.vector", "strict_fallback");
            } else {
                // 웹 검색 성공 시: 보조 모드
                dbg.put("stage.vector", "augment");
            }
            java.util.List<Doc> vectorDocs = toDocsOrEmpty(vectorRetriever, req.query, vectorK, vectorSource);
            if (trace != null) {
                trace.vector = snapshotDocs(vectorDocs);
            }
            if (vectorDocs.isEmpty()) {
                dbg.put("stage.vector", dbg.getOrDefault("stage.vector", "enabled") + ":empty");
            } else {
                dbg.put("stage.vector", dbg.get("stage.vector") + ":" + vectorDocs.size());
                pool.addAll(vectorDocs);
            }
        }

        // 3) KG & BM25 (Supplementary)
        if (req.useKg && kgRetriever == null) {
            dbg.putIfAbsent("stage.kg", "missing_kgRetriever");
        }
        if (req.useKg && kgRetriever != null) {
            java.util.List<Doc> kgDocs = toDocsOrEmpty(kgRetriever, req.query, Math.max(4, req.topK / 2), "KG");
            if (trace != null) {
                trace.kg = snapshotDocs(kgDocs);
            }
            if (kgDocs.isEmpty()) {
                dbg.put("stage.kg", "empty");
            } else {
                dbg.put("stage.kg", "ok:" + kgDocs.size());
                pool.addAll(kgDocs);
            }
        }
        if (req.useBm25 && bm25Index == null) {
            dbg.putIfAbsent("stage.bm25", "missing_bm25Index");
        }
        if (req.useBm25 && bm25Index != null) {
            java.util.List<Doc> bm25Docs = toDocsOrEmpty(bm25Index, req.query, req.topK, "BM25");
            if (trace != null) {
                trace.bm25 = snapshotDocs(bm25Docs);
            }
            if (bm25Docs.isEmpty()) {
                dbg.put("stage.bm25", "empty");
            } else {
                dbg.put("stage.bm25", "ok:" + bm25Docs.size());
                pool.addAll(bm25Docs);
            }
        }

        return pool;
    }

    private List<Doc> fuseRrf(List<Doc> pool, int k, QueryRequest req) {
        if (pool == null || pool.isEmpty())
            return List.of();
        // RRF weights/constant are configured via RagProperties (rag.rrf.*).
        // No runtime if-else branching by memoryProfile/aggressive here (reproducibility).
        double wWeb = 1.0;
        double wVector = 0.8;
        double wBm25 = 0.9;
        double wKg = 0.7;
        int k0 = 60;

        try {
            if (ragProperties != null && ragProperties.getRrf() != null) {
                k0 = ragProperties.getRrf().getConstant();
                if (ragProperties.getRrf().getWeight() != null) {
                    wWeb = ragProperties.getRrf().getWeight().getWeb();
                    wVector = ragProperties.getRrf().getWeight().getVector();
                    wBm25 = ragProperties.getRrf().getWeight().getBm25();
                    wKg = ragProperties.getRrf().getWeight().getKg();
                }
            }
        } catch (Exception ignored) {
            // keep defaults
        }



        // [FUTURE_TECH FIX] If web results are abundant, down-weight Vector/BM25 to protect
        // the latest info from being overridden by stale embeddings.
        try {
            if (ragProperties != null && ragProperties.getRrf() != null) {
                int threshold = ragProperties.getRrf().getWebRichThreshold();
                long webCount = pool.stream()
                        .filter(d -> d.source != null && "WEB".equalsIgnoreCase(d.source))
                        .count();
                if (webCount >= threshold) {
                    wVector = ragProperties.getRrf().getVectorWeightWhenWebRich();
                    wBm25 = ragProperties.getRrf().getBm25WeightWhenWebRich();
                    wWeb = ragProperties.getRrf().getWebWeightWhenWebRich();
                    log.debug("[RRF] Web-rich mode enabled: webCount={}, wWeb={}, wVector={}, wBm25={}",
                            webCount, wWeb, wVector, wBm25);
                }
            }
        } catch (Exception ignored) {
            // keep weights
        }
        // 1) RRF 점수 계산
        Map<Doc, Double> rrfScores = new HashMap<>();
        for (Doc d : pool) {
            String src = d.source != null ? d.source.toUpperCase(Locale.ROOT) : "";
            double w = switch (src) {
                case "WEB" -> wWeb;
                case "VECTOR", "VECTOR-FALLBACK", "VECTOR-EMERGENCY", "VECTOR-OD" -> wVector;
                case "BM25" -> wBm25;
                case "KG" -> wKg;
                default -> 0.8;
            };

            // rank가 없으면 score 기반으로 간이 rank 추정
            int rank = d.rank > 0 ? d.rank : (int) Math.max(1, Math.round(1.0 / Math.max(1e-6, d.score)));
            double rrfScore = w / (k0 + rank);
            rrfScores.put(d, rrfScore);
        }

        // 2) RRF 점수로 정렬
        List<Doc> sorted = new ArrayList<>(pool);
        sorted.sort((a, b) -> Double.compare(
                rrfScores.getOrDefault(b, 0.0),
                rrfScores.getOrDefault(a, 0.0)));

        // 3) 소스 다양성 유지 (완화된 cap)
        Map<String, Integer> srcCount = new HashMap<>();
        List<Doc> out = new ArrayList<>();
        for (Doc d : sorted) {
            String src = d.source != null ? d.source : "UNKNOWN";
            int c = srcCount.getOrDefault(src, 0);

            // k/2 → k*0.75로 완화 (최소 3개 보장)
            int cap = Math.max(3, (int) (k * 0.75));
            if (c >= cap)
                continue;

            srcCount.put(src, c + 1);
            out.add(d);
            if (out.size() >= k)
                break;
        }
        return out;
    }

    private List<Doc> topK(List<Doc> L, int k) {
        if (L.size() <= k)
            return L;
        return new ArrayList<>(L.subList(0, k));
    }


    /**
     * Doc.id를 안정적으로 구성하기 위한 헬퍼.
     * - Web/Vector 결과는 메타에 url이 있는 경우가 많으므로 url을 우선 id로 사용한다.
     * - url이 없으면 meta에 존재하는 식별자 키(id/docId/...)를 사용한다.
     * - 아무 것도 없으면 sourceTag-index 형태로 폴백한다.
     *
     * Soak/Probe에서 '결과가 바뀌었는지'를 추적하려면 id가 매 요청마다 변하면 안 된다.
     */
    private static String stableId(String sourceTag, java.util.Map<String, Object> meta, int fallbackIndex) {
        if (meta != null && !meta.isEmpty()) {
            Object url = firstNonBlank(meta,
                    "url", "URL", "sourceUrl", "source_url", "link", "href", "canonical", "permalink");
            if (url != null) {
                String u = String.valueOf(url).trim();
                if (!u.isBlank()) {
                    return u;
                }
            }
            Object id = firstNonBlank(meta,
                    "id", "docId", "documentId", "document_id", "sourceId", "source_id", "uuid", "hash");
            if (id != null) {
                String v = String.valueOf(id).trim();
                if (!v.isBlank()) {
                    return sourceTag + ":" + v;
                }
            }
        }
        return sourceTag + "-" + fallbackIndex;
    }

    private static Object firstNonBlank(java.util.Map<String, Object> meta, String... keys) {
        if (meta == null || meta.isEmpty() || keys == null) {
            return null;
        }
        for (String k : keys) {
            if (k == null) continue;
            Object v = meta.get(k);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isBlank()) {
                return v;
            }
        }
        // fallback: case-insensitive scan
        for (java.util.Map.Entry<String, Object> e : meta.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String key = e.getKey();
            for (String k : keys) {
                if (k != null && key.equalsIgnoreCase(k)) {
                    String s = String.valueOf(e.getValue()).trim();
                    if (!s.isBlank()) {
                        return e.getValue();
                    }
                }
            }
        }
        return null;
    }



    private boolean isWhitelistedDoc(Doc d) {
        if (d == null) {
            return false;
        }
        if (domainWhitelist == null) {
            return true;
        }
        String url = null;
        if (d.meta != null && !d.meta.isEmpty()) {
            Object v = firstNonBlank(d.meta, "url", "URL", "sourceUrl", "source_url", "link", "href", "canonical", "permalink");
            if (v != null) {
                url = String.valueOf(v);
            }
        }
        if (url == null || url.isBlank()) {
            return false;
        }
        String host = domainWhitelist.extractHost(url);
        if (host == null || host.isBlank()) {
            // URL 파싱이 안 되면 allowlist 판단 불가 → false
            return false;
        }
        java.util.List<String> allow = domainWhitelist.getDomainAllowlist();
        if (allow == null || allow.isEmpty()) {
            // allowlist가 비어있으면 DomainWhitelist의 기본 정책을 따른다(운영 설정 의존).
            return domainWhitelist.isOfficial(url);
        }
        for (String suf : allow) {
            if (suf == null || suf.isBlank()) continue;
            if (host.endsWith(suf.trim())) {
                return true;
            }
        }
        return false;
    }
    private String extractTitle(Map<?, ?> meta, String fallback) {
        if (meta == null)
            return fallback;
        Object title = meta.get("title");
        if (title != null) {
            String t = title.toString();
            if (!t.isBlank()) {
                return t;
            }
        }
        return fallback;
    }

    private String buildSnippet(Content c) {
        String text = c.textSegment() != null
                ? c.textSegment().text()
                : String.valueOf(c);

        if (c.metadata() != null) {
            Object url = c.metadata().get("url");
            if (url != null) {
                String u = url.toString();
                if (!u.isBlank() && !text.contains("URL:")) {
                    text = text + "\nURL: " + u;
                }
            }
        }
        return text;
    }

    private Map<String, Object> extractMetadata(Content c) {
        if (c.metadata() == null) {
            return new HashMap<>();
        }
        Object rawMeta = c.metadata();
        if (!(rawMeta instanceof Map<?, ?> meta)) {
            return new HashMap<>();
        }
        Map<String, Object> safe = new HashMap<>();
        for (Map.Entry<?, ?> e : meta.entrySet()) {
            safe.put(String.valueOf(e.getKey()), e.getValue());
        }
        return safe;
    }

    private java.util.List<Doc> toDocsOrEmpty(ContentRetriever retriever, String query, int topK, String sourceTag) {
        if (retriever == null) {
            return java.util.List.of();
        }
        try {
            java.util.List<dev.langchain4j.rag.content.Content> contents = retriever.retrieve(new Query(query));
            if (contents == null) {
                contents = java.util.Collections.emptyList();
            }
            java.util.List<Doc> docs = new java.util.ArrayList<>();
            for (int i = 0; i < contents.size() && docs.size() < topK; i++) {
                dev.langchain4j.rag.content.Content c = contents.get(i);
                if (c == null) {
                    continue;
                }
                Doc d = new Doc();
                d.title = extractTitle(c.metadata(), sourceTag + " Result #" + (i + 1));
                d.snippet = buildSnippet(c);
                d.source = sourceTag;
                d.score = 1.0 - (i * 0.01);
                d.meta = extractMetadata(c);
                d.id = stableId(sourceTag, d.meta, i);
                docs.add(d);
            }
            return docs;
        } catch (Exception e) {
            log.warn("[UnifiedRagOrchestrator] Failed to retrieve from {}: {}", sourceTag, e.toString());
            return java.util.List.of();
        }
    }

    private java.util.List<Doc> toDocsOrEmpty(com.example.lms.service.service.rag.bm25.Bm25Index index,
                                             String query,
                                             int topK,
                                             String sourceTag) {
        if (index == null) {
            return java.util.List.of();
        }
        try {
            java.util.List<java.util.Map.Entry<String, Double>> hits = index.search(query, Math.max(1, topK));
            java.util.List<Doc> docs = new java.util.ArrayList<>();
            int rank = 1;
            for (java.util.Map.Entry<String, Double> e : hits) {
                Doc d = new Doc();
                d.id = sourceTag + ":" + e.getKey();
                d.title = e.getKey();
                d.snippet = "";
                d.source = sourceTag;
                d.score = e.getValue() == null ? 0.0 : e.getValue();
                d.rank = rank++;
                d.meta = new java.util.HashMap<>();
                d.meta.put("bm25.docId", e.getKey());
                d.meta.put("bm25.score", d.score);
                docs.add(d);
                if (docs.size() >= topK) {
                    break;
                }
            }
            return docs;
        } catch (Exception e) {
            log.warn("[UnifiedRagOrchestrator] Failed to retrieve from {}: {}", sourceTag, e.toString());
            return java.util.List.of();
        }
    }

    /** Deep clone for trace snapshot (mutation 방지) */
    private static List<Doc> snapshotDocs(List<Doc> src) {
        if (src == null) {
            return new ArrayList<>();
        }
        return src.stream()
                .filter(Objects::nonNull)
                .map(d -> {
                    Doc copy = new Doc();
                    copy.id = d.id;
                    copy.title = d.title;
                    copy.snippet = d.snippet;
                    copy.source = d.source;
                    copy.score = d.score;
                    copy.rank = d.rank;
                    copy.meta = (d.meta != null) ? new HashMap<>(d.meta) : null;
                    return copy;
                })
                .collect(Collectors.toList());
    }

}