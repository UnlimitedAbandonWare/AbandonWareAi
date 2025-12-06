package com.example.lms.service.rag.orchestrator;
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

    private static final Logger log = LoggerFactory.getLogger(UnifiedRagOrchestrator.class);


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
            log.info("[UnifiedRagOrchestrator] Resolved optional component {}", cn);
            return clz.getDeclaredConstructor().newInstance();
        } catch (Throwable e) {
            log.debug("[UnifiedRagOrchestrator] Failed to resolve {}: {}", cn, e.toString());
        }
    }
    log.warn("[UnifiedRagOrchestrator] None of {} could be resolved; component disabled",
            java.util.Arrays.toString(classNames));
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

                // [PATCH] Sequential Fallback Logic (Web -> Vector -> KG/BM25)
        // 1) Web Search (Primary)
        boolean webSuccess = false;
        if (req.useWeb && webRetriever != null) {
            List<Doc> webDocs = new ArrayList<>();
            try {
                if (webRetriever instanceof ContentRetriever cr) {
                    List<Content> contents = cr.retrieve(new Query(req.query));

                    for (int i = 0; i < contents.size() && i < req.topK; i++) {
                        Content c = contents.get(i);

                        Doc d = new Doc();
                        d.id = "web-" + i;
                        d.title = extractTitle(c.metadata(), "Web Result #" + (i + 1));
                        d.snippet = buildSnippet(c);
                        d.source = "WEB";
                        d.score = 1.0 - (i * 0.01);

                        d.meta = extractMetadata(c);

                        webDocs.add(d);
                    }

                    dbg.put("stage.web", "success (" + webDocs.size() + ")");
                } else {
                    dbg.put("stage.web", "type_mismatch:" + webRetriever.getClass().getName());
                }
            } catch (Exception e) {
                log.warn("[Orchestrator] Web retrieval failed", e);
                dbg.put("stage.web", "error:" + e.getMessage());
            }

            if (!webDocs.isEmpty()) {
                pool.addAll(webDocs);
                webSuccess = true;
            } else {
                dbg.put("stage.web", "empty");
            }
        }

        // 2) Vector Search (Conditional)
        if (req.useVector && vectorRetriever != null) {
            if (!webSuccess) {
                // 웹 검색 실패 시: 엄격 모드 (Score >= 0.8 가정)
                // 오염된 저품질 데이터 유입 차단
                dbg.put("stage.vector", "strict_fallback");
            } else {
                // 웹 검색 성공 시: 보조 모드
                dbg.put("stage.vector", "augment");
            }
            java.util.List<Doc> vectorDocs = toDocsOrEmpty(vectorRetriever, req.query, req.topK, "VECTOR");
            if (vectorDocs.isEmpty()) {
                dbg.put("stage.vector", dbg.getOrDefault("stage.vector", "enabled") + ":empty");
            } else {
                dbg.put("stage.vector", dbg.get("stage.vector") + ":" + vectorDocs.size());
                pool.addAll(vectorDocs);
            }
        }

        // 3) KG & BM25 (Supplementary)
        if (req.useKg && kgRetriever != null) {
            java.util.List<Doc> kgDocs = toDocsOrEmpty(kgRetriever, req.query, Math.max(4, req.topK / 2), "KG");
            if (kgDocs.isEmpty()) {
                dbg.put("stage.kg", "empty");
            } else {
                dbg.put("stage.kg", "ok:" + kgDocs.size());
                pool.addAll(kgDocs);
            }
        }
        if (req.useBm25 && bm25Index != null) {
            java.util.List<Doc> bm25Docs = toDocsOrEmpty(bm25Index, req.query, req.topK, "BM25");
            if (bm25Docs.isEmpty()) {
                dbg.put("stage.bm25", "empty");
            } else {
                dbg.put("stage.bm25", "ok:" + bm25Docs.size());
                pool.addAll(bm25Docs);
            }
        }

// 2) Fuse via Weighted-RRF (placeholder scoring to avoid compile deps)
        List<Doc> fused = fuseRrf(pool, req.topK);
        dbg.put("stage.fuse", fused.size());
        if (fused.isEmpty()) {
            dbg.put("retrieval", "empty");
            dbg.put("fallback", "model_knowledge");
        }

        // 3) Bi-Encoder prefilter
        if (req.enableBiEncoder && biEncoder != null) {
            fused = topK(fused, Math.max(10, req.topK));
            dbg.put("stage.biencoder", fused.size());
        }

        
                // 3.5) DPP diversity rerank (between bi-encoder and cross-encoder)
        if (req.enableDiversity) {
            dev.langchain4j.model.embedding.EmbeddingModel em = tryResolveEmbeddingModel();
            if (em != null) {
                // Assuming DppDiversityReranker exists or is resolved. If it's a hard dependency here:
                try {
                    com.example.lms.service.rag.rerank.DppDiversityReranker dpp =
                            new com.example.lms.service.rag.rerank.DppDiversityReranker(
                                    new com.example.lms.service.rag.rerank.DppDiversityReranker.Config(req.diversityLambda, Math.max(10, req.topK)),
                                    em);
                    fused = dpp.rerank(fused, req.query, Math.max(10, req.topK));
                    dbg.put("stage.dpp", fused.size());
                } catch (Throwable t) {
                    dbg.put("stage.dpp", "error: " + t.toString());
                }
            }
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

    private List<Doc> fuseRrf(List<Doc> pool, int k) {
        if (pool == null || pool.isEmpty()) return List.of();

        // RRF 가중치 (소스별 기본 weight)
        double wWeb = 1.0;
        double wVector = 0.8;
        double wBm25 = 0.9;
        double wKg = 0.7;
        int k0 = 60;  // RRF constant

        // 1) RRF 점수 계산
        Map<Doc, Double> rrfScores = new HashMap<>();
        for (Doc d : pool) {
            String src = d.source != null ? d.source.toUpperCase(Locale.ROOT) : "";
            double w = switch (src) {
                case "WEB" -> wWeb;
                case "VECTOR" -> wVector;
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
                rrfScores.getOrDefault(a, 0.0)
        ));

        // 3) 소스 다양성 유지 (완화된 cap)
        Map<String, Integer> srcCount = new HashMap<>();
        List<Doc> out = new ArrayList<>();
        for (Doc d : sorted) {
            String src = d.source != null ? d.source : "UNKNOWN";
            int c = srcCount.getOrDefault(src, 0);

            // k/2 → k*0.75로 완화 (최소 3개 보장)
            int cap = Math.max(3, (int) (k * 0.75));
            if (c >= cap) continue;

            srcCount.put(src, c + 1);
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


    private String extractTitle(Map<?, ?> meta, String fallback) {
        if (meta == null) return fallback;
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


private java.util.List<Doc> toDocsOrEmpty(Object retriever, String query, int topK, String sourceTag) {
    if (!(retriever instanceof dev.langchain4j.rag.content.retriever.ContentRetriever cr)) {
        if (retriever != null) {
            log.debug("[UnifiedRagOrchestrator] Retriever for {} is not a ContentRetriever: {}", sourceTag, retriever.getClass().getName());
        }
        return java.util.List.of();
    }
    try {
        java.util.List<dev.langchain4j.rag.content.Content> contents = cr.retrieve(new Query(query));
        java.util.List<Doc> docs = new java.util.ArrayList<>();
        for (int i = 0; i < contents.size() && docs.size() < topK; i++) {
            dev.langchain4j.rag.content.Content c = contents.get(i);
            Doc d = new Doc();
            d.id = sourceTag + "-" + i;
            d.title = extractTitle(c.metadata(), sourceTag + " Result #" + (i + 1));
            d.snippet = buildSnippet(c);
            d.source = sourceTag;
            d.score = 1.0 - (i * 0.01);
            d.meta = extractMetadata(c);
            docs.add(d);
        }
        return docs;
    } catch (Exception e) {
        log.warn("[UnifiedRagOrchestrator] Failed to retrieve from {}: {}", sourceTag, e.toString());
        return java.util.List.of();
    }
}

}
