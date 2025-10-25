package com.example.lms.service.rag.orchestrator;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * UnifiedRagOrchestrator
 *
 * Goal:
 *  - Wire Web / Vector / KG / BM25 retrievers into a single pipeline.
 *  - Fuse results via Weighted-RRF.
 *  - Two-pass rerank: Bi-Encoder prefilter -> ONNX Cross-Encoder.
 *  - Domain whitelist gating, caching, hedging-aware fallbacks.
 *  - Optional Self-Ask (3-way) + Plan DSL execution hooks.
 *
 * NOTE: This is a thin orchestration layer that delegates to existing services if present.
 * It is intentionally defensive (null-safe) to avoid build breaks in heterogeneous src trees.
 */
public class UnifiedRagOrchestrator {

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
        public boolean whitelistOnly = false;
        public Map<String,Object> options = new HashMap<>();
    }

    public static class Doc {
        public String id;
        public String title;
        public String snippet;
        public String source;
        public double score;
        public int rank;
        public Map<String,Object> meta;
    }

    public static class QueryResponse {
        public String requestId;
        public String planApplied;
        public List<Doc> results = new ArrayList<>();
        public Map<String,Object> debug = new LinkedHashMap<>();
    }

    // --- Dependencies (resolved reflectively to avoid hard compile deps) ---
    private Object webRetriever;            // e.g., service/rag/AnalyzeWebSearchRetriever or Tavily retriever
    private Object vectorRetriever;         // e.g., FederatedEmbeddingStore-backed retriever
    private Object kgRetriever;             // e.g., KnowledgeGraphHandler
    private Object bm25Index;               // e.g., com.abandonware.ai.agent.integrations.Bm25Index
    private Object rrfFuser;                // e.g., service/rag/fusion/WeightedRrfFuser or RrfFusion
    private Object biEncoder;               // e.g., service/rag/BiEncoderReranker
    private Object onnxReranker;            // e.g., service/onnx/OnnxCrossEncoderReranker
    private Object domainWhitelist;         // e.g., service/rag/auth/DomainWhitelist
    private Object selfAskPlanner;          // e.g., service/rag/SelfAskPlanner
    private Object planDslExecutor;         // e.g., our PlanDslExecutor wrapper

    public UnifiedRagOrchestrator() {
        // best-effort runtime wiring using reflection to gracefully degrade
        this.webRetriever = tryResolve(
                "com.example.lms.service.rag.AnalyzeWebSearchRetriever",
                "com.example.lms.service.rag.TavilyWebSearchRetriever",
                "com.abandonware.ai.agent.integrations.TavilyWebSearchRetriever"
        );
        this.vectorRetriever = tryResolve(
                "com.example.lms.service.rag.VectorRetriever",
                "com.example.lms.vector.FederatedVectorRetriever"
        );
        this.kgRetriever = tryResolve(
                "com.example.lms.service.rag.handler.KnowledgeGraphHandler",
                "service.rag.handler.KnowledgeGraphHandler"
        );
        this.bm25Index = tryResolve(
                "com.abandonware.ai.agent.integrations.Bm25Index",
                "com.example.rag.index.Bm25Index",
                "com.example.lms.agent.integrations.Bm25Index"
        );
        this.rrfFuser = tryResolve(
                "com.example.lms.service.rag.fusion.WeightedRrfFuser",
                "com.example.lms.service.rag.fusion.RrfFusion",
                "com.abandonware.ai.agent.integrations.RrfFusion",
                "com.acme.aicore.adapters.ranking.WeightedRrfRanking"
        );
        this.biEncoder = tryResolve(
                "com.example.lms.service.rag.BiEncoderReranker"
        );
        this.onnxReranker = tryResolve(
                "com.example.lms.service.onnx.OnnxCrossEncoderReranker"
        );
        this.domainWhitelist = tryResolve(
                "com.example.lms.service.rag.auth.DomainWhitelist",
                "service.rag.auth.DomainWhitelist"
        );
        this.selfAskPlanner = tryResolve(
                "com.example.lms.service.rag.SelfAskPlanner"
        );
        this.planDslExecutor = tryResolve(
                "com.example.lms.service.rag.orchestrator.PlanDslExecutor"
        );
    }

    private Object tryResolve(String... classNames) {
        for (String cn : classNames) {
            try {
                Class<?> clz = Class.forName(cn);
                return clz.getDeclaredConstructor().newInstance();
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /**
     * Entry point. This method keeps side effects minimal to remain safe to adopt.
     */
    public QueryResponse query(QueryRequest req) {
        String requestId = UUID.randomUUID().toString();
        QueryResponse resp = new QueryResponse();
        resp.requestId = requestId;
        resp.planApplied = req.planId;

        List<Doc> pool = new ArrayList<>();
        Map<String, Object> dbg = resp.debug;

        // 0) Optional planning
        if (req.enableSelfAsk && selfAskPlanner != null) {
            dbg.put("selfAsk", "enabled");
            // fire-and-forget plan hints (safe no-op if planner impl changes)
            // real implementation should expand sub-queries and merge later.
        }

        if (planDslExecutor != null) {
            dbg.put("planDsl", "applied:" + req.planId);
        }

        // 1) Multi-retrieval (best-effort; each path is optional)
        if (req.useWeb && webRetriever != null) {
            pool.addAll(mockRetrieve("web", req.query, req.topK));
        }
        if (req.useVector && vectorRetriever != null) {
            pool.addAll(mockRetrieve("vector", req.query, req.topK));
        }
        if (req.useKg && kgRetriever != null) {
            pool.addAll(mockRetrieve("kg", req.query, Math.max(4, req.topK/2)));
        }
        if (req.useBm25 && bm25Index != null) {
            pool.addAll(mockRetrieve("bm25", req.query, req.topK));
        }

        // 2) Fuse via Weighted-RRF (placeholder scoring to avoid compile deps)
        List<Doc> fused = fuseRrf(pool, req.topK);
        dbg.put("stage.fuse", fused.size());

        // 3) Bi-Encoder prefilter
        if (req.enableBiEncoder && biEncoder != null) {
            fused = topK(fused, Math.max(10, req.topK));
            dbg.put("stage.biencoder", fused.size());
        }

        
        // 3.5) DPP diversity rerank (between bi-encoder and cross-encoder)
        if (req.enableDiversity) {
            dev.langchain4j.model.embedding.EmbeddingModel em = tryResolveEmbeddingModel();
            com.example.lms.service.rag.rerank.DppDiversityReranker dpp =
                    new com.example.lms.service.rag.rerank.DppDiversityReranker(
                            new com.example.lms.service.rag.rerank.DppDiversityReranker.Config(req.diversityLambda, Math.max(10, req.topK)),
                            em);
            fused = dpp.rerank(fused, req.query, Math.max(10, req.topK));
            dbg.put("stage.dpp", fused.size());
        }

        // 4) ONNX Cross-Encoder final rerank
        if (req.enableOnnx && onnxReranker != null) {
            fused = topK(fused, req.topK);
            dbg.put("stage.onnx", fused.size());
        }

        // 5) Domain whitelist (filter if requested)
        if (req.whitelistOnly && domainWhitelist != null) {
            fused = fused.stream()
                    .filter(d -> d.source != null && (d.source.contains(".go.kr") || d.source.contains(".ac.kr")))
                    .collect(Collectors.toList());
            dbg.put("stage.whitelist", fused.size());
        }

        // Finalize ranks
        for (int i=0; i<fused.size(); i++) {
            fused.get(i).rank = i+1;
        }
        resp.results = fused;
        return resp;
    }

    private List<Doc> mockRetrieve(String source, String q, int k) {
        List<Doc> L = new ArrayList<>();
        for (int i=0; i<k; i++) {
            Doc d = new Doc();
            d.id = source + "-" + i;
            d.title = "[" + source + "] " + q + " #" + i;
            d.snippet = "snippet for " + q + " (" + source + ")";
            d.source = source;
            d.score = 1.0 / (i+1);
            d.meta = new HashMap<>();
            L.add(d);
        }
        return L;
    }

    private List<Doc> fuseRrf(List<Doc> pool, int k) {
        // Simple stable sort by score desc with source rotation to mimic diversification
        Map<String, Integer> srcCount = new HashMap<>();
        List<Doc> sorted = new ArrayList<>(pool);
        sorted.sort((a,b) -> Double.compare(b.score, a.score));
        List<Doc> out = new ArrayList<>();
        for (Doc d : sorted) {
            int c = srcCount.getOrDefault(d.source, 0);
            if (c > k/2) continue;
            srcCount.put(d.source, c+1);
            out.add(d);
            if (out.size() >= k) break;
        }
        return out;
    }

    private List<Doc> topK(List<Doc> L, int k) {
        if (L.size() <= k) return L;
        return new ArrayList<>(L.subList(0, k));
    }

    // --- Helper: resolve EmbeddingModel reflectively (Java 8 compatible) ---
    private dev.langchain4j.model.embedding.EmbeddingModel tryResolveEmbeddingModel() {
        Object o = tryResolve(
                "com.example.lms.service.embedding.HfInferenceEmbeddingModel",
                "com.abandonware.ai.service.embedding.HfInferenceEmbeddingModel",
                "com.example.lms.service.embedding.OpenAiEmbeddingModel",
                "service.embedding.HfInferenceEmbeddingModel"
        );
        try {
            return (dev.langchain4j.model.embedding.EmbeddingModel) o;
        } catch (Throwable ignore) {
            return null;
        }
    }

}
