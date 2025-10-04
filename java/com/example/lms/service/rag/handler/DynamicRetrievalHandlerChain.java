package com.example.lms.service.rag.handler;

import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.service.rag.*;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.strategy.RetrievalOrderService.Source;
import com.example.lms.telemetry.SseEventPublisher;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;

/**
 * A retrieval handler chain that dynamically determines the order of invocation for
 * Web, Vector, and Knowledge Graph sources based on the query characteristics.
 * <p>
 * The chain executes a number of preparatory stages (memory reload, self‑ask,
 * analyze, adaptive web search) before delegating to the dynamic order plan
 * provided by {@link RetrievalOrderService}.  Each stage is fail‑soft: any
 * exception thrown by an individual handler is caught and logged, allowing the
 * remaining stages to continue unhindered.  After exhausting the dynamic plan
 * or filling the accumulator to the configured limit, an optional repair
 * handler may post‑process results.
 */
@Slf4j
@RequiredArgsConstructor
public class DynamicRetrievalHandlerChain implements RetrievalHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DynamicRetrievalHandlerChain.class);


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
    private final SseEventPublisher sse;
    private final WeightedReciprocalRankFuser fuser;

    @Value("${pinecone.index.name}")
    private String pineconeIndexName;

    @Value("${rag.search.top-k:5}")
    private int topK;

    @Override
    public void handle(Query query, List<Content> accumulator) {
        if (accumulator == null) {
            return;
        }
        // 1. Session memory load (fail‑soft)
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
                    // Early‑cut removed: do not return when reaching topK at this stage
                }
            } catch (Exception e) {
                log.warn("[Memory] {}", e.toString());
            }
        }
        // 2. Determine query text for downstream heuristics
        String qText = (query != null && query.text() != null) ? query.text().trim() : "";
        // 3. Self‑Ask stage (only when gate signals it)
        boolean needSelf = false;
        try {
            needSelf = gate != null && gate.needsSelfAsk(qText);
        } catch (Exception e) {
            log.warn("[SelfAskGate] {}", e.toString());
        }
        if (needSelf) {
            try {
                add(accumulator, selfAsk.retrieve(query));
                // Early‑cut removed: continue gathering evidence instead of returning
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
                // Early‑cut removed: continue gathering evidence instead of returning
            } catch (Exception e) {
                log.warn("[Analyze] {}", e.toString());
            }
        }
        // 5. Adaptive web search stage
        if (adaptiveWeb != null) {
            try {
                adaptiveWeb.handle(query, accumulator);
                // Early‑cut removed: do not return here; allow subsequent stages
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
            sse.emit("ORDER_DECISION", new SseEventPublisher.Payload()
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
            // Early‑cut removed: do not break when accumulator reaches topK; continue through all sources
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
            var fused = fuser.fuse(sources, topK);
            add(accumulator, fused);
            try {
                sse.emit("FUSION_APPLIED", new SseEventPublisher.Payload()
                    .kv("sizes", java.util.Map.of("web", webRes.size(), "vec", vecRes.size(), "kg", kgRes.size()))
                    .kv("topK", topK).build());
            } catch (Exception ignore) {}
        }
// 7. Repair stage (post‑processing)
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

}