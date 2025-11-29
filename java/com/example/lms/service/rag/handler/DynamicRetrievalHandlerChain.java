package com.example.lms.service.rag.handler;




import org.springframework.beans.factory.annotation.Autowired;
import com.example.lms.config.alias.NineTileAliasCorrector;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.service.ner.LLMNamedEntityExtractor;
import com.example.lms.service.rag.knowledge.UniversalLoreRegistry;
import com.example.lms.service.rag.*;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.strategy.RetrievalOrderService.Source;
import com.example.lms.telemetry.SseEventPublisher;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.query.Query;
import com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.example.lms.guard.rulebreak.RuleBreakContext;
import com.example.lms.service.rag.handler.RuleBreakRetrievalDecorator;



/**
 * A retrieval handler chain that dynamically determines the order of invocation for
 * Web, Vector, and Knowledge Graph sources based on the query characteristics.
 * <p>
 * The chain executes a number of preparatory stages (memory reload, self-ask,
 * analyze, adaptive web search) before delegating to the dynamic order plan
 * provided by {
    @Autowired(required=false) com.nova.protocol.alloc.RiskKAllocator kalloc;
    @Autowired(required=false) com.nova.protocol.properties.NovaNextProperties nprops;

    @Autowired(required=false) com.nova.protocol.alloc.RiskKAllocator kalloc;
    @Autowired(required=false) com.nova.protocol.properties.NovaNextProperties nprops;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private NineTileAliasCorrector aliasCorrector;


    public static class PlanParams {
    private final com.abandonware.ai.agent.service.rag.bm25.Bm25LocalRetriever bm25Retriever;
        public int webTopK = 10;
        public int timeoutMs = 1800;
        public boolean calibratedRrf = true;
    }
    private PlanParams plan = new PlanParams();
    public void bindPlan(PlanParams p) { if (p != null) this.plan = p; }
    @link RetrievalOrderService}.  Each stage is fail-soft: any
 * exception thrown by an individual handler is caught and logged, allowing the
 * remaining stages to continue unhindered.  After exhausting the dynamic plan
 * or filling the accumulator to the configured limit, an optional repair
 * handler may post-process results.
 */
@RequiredArgsConstructor
@org.springframework.stereotype.Component

public class DynamicRetrievalHandlerChain implements RetrievalHandler {

  @org.springframework.beans.factory.annotation.Value("${rag.diversity.enabled:true}") private boolean diversityEnabled;
  @org.springframework.beans.factory.annotation.Value("${rag.diversity.lambda:0.7}") private double mmrLambda;

  @org.springframework.beans.factory.annotation.Autowired(required = false) private telemetry.LoggingSseEventPublisher sse;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NineTileAliasCorrector aliasCorrector;


//     private Object sse;  // removed duplicate

    private static final Logger log = LoggerFactory.getLogger(DynamicRetrievalHandlerChain.class);

    private final MemoryHandler memoryHandler;
    private final SelfAskWebSearchRetriever selfAsk;
    private final AnalyzeWebSearchRetriever analyze;
    private final AdaptiveWebSearchHandler adaptiveWeb;
    private final WebSearchRetriever web;
    private final LangChainRAGService rag;
    private final EvidenceRepairHandler repair;
    private final QueryComplexityGate gate;
    private final KnowledgeGraphHandler kg;
    private final RetrievalOrderService orderService;
    private final WeightedReciprocalRankFuser fuser;
    private final LLMNamedEntityExtractor nerExtractor;
    private final UniversalLoreRegistry loreRegistry;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.risk.RiskScorer riskScorer;


    @Value("${pinecone.index.name}")
    private String pineconeIndexName;

    @Value("${rag.search.top-k:5}")
    private int topK;

    @Override
    public void handle(Query query, List<Content> accumulator) {
        if (accumulator == null) {
            return;
        }
        // 1. Session memory load (fail-soft)
        Long sessionId = null;
        try {
            if (query != null && query.metadata() != null) {
                java.util.Map<String, Object> md = toMap(query.metadata());
                Object sidObj = md.get(LangChainRAGService.META_SID);
                if (sidObj != null) {
                    sessionId = Long.parseLong(String.valueOf(sidObj));
                }
            }
        } catch (Exception ignore) {
            // ignore errors
        }
        if (sessionId != null) {
            try {
                String hist = memoryHandler.loadForSession(sessionId);
                if (hist != null && !hist.isBlank()) {
                    accumulator.add(Content.from(hist));
                    // Early-cut removed: do not return when reaching topK at this stage
                }
            } catch (Exception e) {
                log.warn("[Memory] {}", e.toString());
            }
        }
        // 2. Determine query text for downstream heuristics
        String qText = (query != null && query.text() != null) ? query.text().trim() : "";
        if (aliasCorrector != null && !qText.isBlank()) {
            try {
                Map<String, Object> aliasCtx = new HashMap<>();
                if (query != null && query.metadata() != null) {
                    java.util.Map<String, Object> md = toMap(query.metadata());
                    Object domain = md.get("intent.domain");
                    if (domain == null) {
                        domain = md.get("vp.topTile");
                    }
                    if (domain != null) {
                        aliasCtx.put("intent.domain", domain);
                    }
                }
                qText = aliasCorrector.correct(qText, Locale.KOREAN, aliasCtx);
            } catch (Exception e) {
                log.debug("[Alias] NineTile alias correction failed, continuing with original query", e);
            }
        }

        // [Lore] Inject YAML-based domain knowledge using LLM NER before heavy web/vector retrieval.
        if (nerExtractor != null && loreRegistry != null) {
            try {
                java.util.List<String> entities = nerExtractor.extract(qText);
                if (entities != null && !entities.isEmpty()) {
                    java.util.List<com.example.lms.service.rag.knowledge.UniversalLoreRegistry.DomainKnowledge> lores =
                            loreRegistry.findLore(entities);
                    if (lores != null && !lores.isEmpty()) {
                        log.info("[Lore] Injecting {} entries for entities: {}", lores.size(), entities);
                        for (com.example.lms.service.rag.knowledge.UniversalLoreRegistry.DomainKnowledge lore : lores) {
                            String entityName = (lore.getNames() != null && !lore.getNames().isEmpty())
                                    ? lore.getNames().get(0)
                                    : "";
                            accumulator.add(Content.from(
                                    TextSegment.from(
                                            "[Lore: " + lore.getDomain() + "] " + lore.getContent(),
                                            Metadata.from(java.util.Map.of("source", "UniversalLore", "entity", entityName))
                                    )
                            ));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Lore] Failed to inject lore", e);
            }
        }

        // 3. Self-Ask stage (only when gate signals it)
        boolean needSelf = false;
        try {
            needSelf = gate != null && gate.needsSelfAsk(qText);
        } catch (Exception e) {
            log.warn("[SelfAskGate] {}", e.toString());
        }
        if (needSelf) {
            try {
                add(accumulator, selfAsk.retrieve(query));
                // Early-cut removed: continue gathering evidence instead of returning
            } catch (Exception e) {
                log.warn("[SelfAsk] {}", e.toString());
            }
        }
        // 4. Analyze stage (only when gate signals it)
        boolean needAnalyze = false;
        try {
            needAnalyze = gate != null && gate.needsAnalyze(qText);
        } catch (Exception e) {
            log.warn("[AnalyzeGate] {}", e.toString());
        }
        if (needAnalyze) {
            try {
                add(accumulator, analyze.retrieve(query));
                // Early-cut removed: continue gathering evidence instead of returning
            } catch (Exception e) {
                log.warn("[Analyze] {}", e.toString());
            }
        }
        // 5. Adaptive web search stage
        if (adaptiveWeb != null) {
            try {
                adaptiveWeb.handle(query, accumulator);
                // Early-cut removed: do not return here; allow subsequent stages
            } catch (Exception e) {
                log.warn("[AdaptiveWeb] {}", e.toString());
            }
        }
        // 6. Dynamic order execution: Web / Vector / KG as decided by orderService
        java.util.List<Source> plan;
        try {
            plan = orderService.decideOrder(qText);
        } catch (Exception e) {
            // fallback to default order
            log.warn("[OrderService] decide failed; using default", e);
            plan = List.of(Source.WEB, Source.VECTOR, Source.KG);
        }
        // SSE event for order decision
        try {
            _sse("ORDER_DECISION", new SseEventPublisher.Payload()
                .kv("steps", plan)
                .kv("reason", "heuristic")
                .build());
        } catch (Exception e) {
            log.debug("ORDER_DECISION SSE skipped: {}", e.toString());
        }
        log.debug("[ORDER_DECISION] plan={} reason={}", plan, "heuristic");
        
        // Collect per-source results for fusion
        java.util.List<Content> webRes = new java.util.ArrayList<>();
        java.util.List<Content> vecRes = new java.util.ArrayList<>();
        java.util.List<Content> kgRes  = new java.util.ArrayList<>();

        for (Source src : plan) {
            // Early-cut removed: do not break when accumulator reaches topK; continue through all sources
            try {
                switch (src) {
                    case WEB -> {
                        var r = web.retrieve(query);
                        if (r != null) webRes.addAll(r);
                    }
                    case VECTOR -> {
                        ContentRetriever vector = rag.asContentRetriever(pineconeIndexName);
                        var r = vector.retrieve(query);
                        if (r != null) vecRes.addAll(r);
                    }
                    case KG -> {
                        if (kg != null) {
                            var r = kg.retrieve(query);
                            if (r != null) kgRes.addAll(r);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[{}] fail-soft: {}", src, e.toString());
            }
        }
        // Apply weighted RRF fusion across available sources
        java.util.List<java.util.List<Content>> sources = new java.util.ArrayList<>();
        if (!webRes.isEmpty()) sources.add(webRes);
        if (!vecRes.isEmpty()) sources.add(vecRes);
        if (!kgRes.isEmpty())  sources.add(kgRes);

        if (!sources.isEmpty()) {
            
            // RDI-based deceleration: shrink topK when the risk signal is high
            int usedTopK = topK;
            try {
                if (riskScorer != null) {
                    java.util.List<Content> union = new java.util.ArrayList<>(webRes.size() + vecRes.size() + kgRes.size());
                    union.addAll(webRes); union.addAll(vecRes); union.addAll(kgRes);
                    int rdi = riskScorer.computeRdi(new com.example.risk.ListingContext(union));
                    int orig = usedTopK;
                    usedTopK = adjustTopK(orig, rdi);
                    try {
                        _sse("RISK_DECEL", new SseEventPublisher.Payload()
                            .kv("rdi", rdi).kv("topK.orig", orig).kv("topK.used", usedTopK).build());
                    } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                log.debug("[RISK_DECEL] {}", e.toString());
            }

            var fused = fuser.fuse(sources, usedTopK);
            add(accumulator, fused);
            try {
                _sse("FUSION_APPLIED", new SseEventPublisher.Payload()
                    .kv("sizes", java.util.Map.of("web", webRes.size(), "vec", vecRes.size(), "kg", kgRes.size()))
                    .kv("topK", topK).build());
            } catch (Exception ignore) {}
        }
// 7. Repair stage (post-processing)
        try {
            if (repair != null) {
                add(accumulator, repair.retrieve(query));
            }
        } catch (Exception e) {
            log.warn("[Repair] {}", e.toString());
        }
    }

    private static void add(List<Content> target, List<Content> source) {
        if (source != null && !source.isEmpty()) {
            target.addAll(source);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> toMap(Object meta) {
        if (meta == null) return java.util.Map.of();
        try {
            java.lang.reflect.Method m = meta.getClass().getMethod("asMap");
            return (java.util.Map<String, Object>) m.invoke(meta);
        } catch (NoSuchMethodException e) {
            try {
                java.lang.reflect.Method m2 = meta.getClass().getMethod("map");
                return (java.util.Map<String, Object>) m2.invoke(meta);
            } catch (Exception ex) {
                return java.util.Map.of();
            }
        } catch (Exception ex) {
            return java.util.Map.of();
        }
    }

    // [HARDENING] ensure SID metadata is present on every query
    private dev.langchain4j.rag.query.Query ensureSidMetadata(dev.langchain4j.rag.query.Query original, String sessionKey) {
        var md = original.metadata() != null
            ? original.metadata()
            : dev.langchain4j.data.document.Metadata.from(
                java.util.Map.of(com.example.lms.service.rag.LangChainRAGService.META_SID, sessionKey));
        // Directly construct a new Query with the updated metadata.  LangChain4j 1.0.x
        // exposes a public constructor taking text and metadata, so we avoid the deprecated
        // builder API and any reflective fallback.
        return new dev.langchain4j.rag.query.Query(original.text(), md);
    }



    /** Piecewise topK shrink based on RDI heuristic. */
    private int adjustTopK(int base, int rdi) {
        if (base <= 1) return base;
        if (rdi >= 70) {
            return Math.max(1, (int) Math.floor(base * 0.6));
        } else if (rdi >= 40) {
            return Math.max(1, (int) Math.floor(base * 0.8));
        }
        return base;
    }



    /**
     * 표준 접근자: 체인 구성 스텝을 불변 리스트로 노출.
     * 내부 구현 차이를 흡수하기 위해 List 타입 필드를 탐색합니다.
     */
    @SuppressWarnings({"rawtypes","unchecked"})
    public java.util.List<Object> getSteps() {
        // Try common field names
        for (String fName : new String[]{"steps","handlers","chain"}) {
            try {
                java.lang.reflect.Field f = this.getClass().getDeclaredField(fName);
                f.setAccessible(true);
                Object v = f.get(this);
                if (v instanceof java.util.List) {
                    return java.util.Collections.unmodifiableList((java.util.List) v);
                }
            } catch (NoSuchFieldException ignore) {
            } catch (ReflectiveOperationException e) {
                // ignore and continue
            }
        }
        // Fallback: first List-typed field in declared fields
        try {
            for (java.lang.reflect.Field f : this.getClass().getDeclaredFields()) {
                if (java.util.List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(this);
                    if (v instanceof java.util.List) {
                        return java.util.Collections.unmodifiableList((java.util.List) v);
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            // ignore
        }
        return java.util.Collections.emptyList();
    }



// === Dynamic K-Allocation Hook (auto-injected) ===
// Compute per-source topK before invoking web/vector/KG retrievers.
private com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.KPlan __decideKPlan(String intent, String query, boolean officialOnly) {
    com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.Settings ks = new com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.Settings();
    // TODO: bind settings from application.yml (retrieval.kalloc.*)
    com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator allocator = new com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator(ks);
    return allocator.decide(new com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.Input(intent, query, officialOnly));
}
// Usage example (pseudo):
// KPlan plan = __decideKPlan(request.intent(), request.query(), request.officialSourcesOnly());
// webTopK = plan.webK; vectorTopK = plan.vectorK; kgTopK = plan.kgK;
// === /Dynamic K-Allocation Hook ===


    private void _sse(Object a1, Object a2) {
        if (sse == null) return;
        try {
            sse.getClass().getMethod("emit", String.class, Object.class).invoke(sse, String.valueOf(a1), a2);
        } catch (Throwable _t) { }
    }
    private void _sse(Object a1, Object a2, java.util.Map meta) {
        if (sse == null) return;
        try {
            try {
                sse.getClass().getMethod("emit", String.class, Object.class, java.util.Map.class).invoke(sse, String.valueOf(a1), a2, meta);
            } catch (NoSuchMethodException _e) {
                sse.getClass().getMethod("emit", String.class, Object.class).invoke(sse, String.valueOf(a1), a2);
            }
        } catch (Throwable _t) { }
    }

}