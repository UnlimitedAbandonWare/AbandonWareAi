package com.abandonware.ai.service.rag.handler;

import com.abandonware.ai.agent.service.rag.bm25.Bm25LocalRetriever;
import com.abandonware.ai.service.rag.AnalyzeWebSearchRetriever;
import com.abandonware.ai.service.rag.RerankOrchestrator;
import com.abandonware.ai.service.rag.model.ContextSlice;
import com.abandonware.ai.strategy.RetrievalOrderService;
import com.example.lms.telemetry.LoggingSseEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * com.abandonware.* 쪽에서 사용되는 "가벼운" Retrieval 체인.
 *
 * - RetrievalOrderService 가 내놓는 소스 문자열을 기준으로 (web / vector / kg) 를 순서대로 실행합니다.
 * - 이 네임스페이스에는 벡터 스토어 리트리버가 없으므로, "vector" 는 BM25 로컬 리트리버로 매핑합니다.
 * - 결과는 URL(or id) 기준으로 dedup 하고, 옵션으로 rerank 합니다.
 */
@Primary
@Component
public class DynamicRetrievalHandlerChain {

    private static final Logger log = LoggerFactory.getLogger(DynamicRetrievalHandlerChain.class);

    private final RetrievalOrderService orderService;

    @Autowired(required = false)
    private AnalyzeWebSearchRetriever web;

    @Autowired(required = false)
    private Bm25LocalRetriever bm25;

    @Autowired(required = false)
    private KnowledgeGraphHandler kg;

    @Autowired(required = false)
    private RerankOrchestrator rerank;

    @Autowired(required = false)
    private LoggingSseEventPublisher sse;

    @Value("${retrieval.k.web:6}")
    private int webK;

    @Value("${retrieval.k.bm25:6}")
    private int bm25K;

    @Value("${retrieval.k.kg:6}")
    private int kgK;

    @Value("${retrieval.k.final:12}")
    private int finalK;

    @Value("${retrieval.rerank.enabled:true}")
    private boolean rerankEnabled;

    public DynamicRetrievalHandlerChain(RetrievalOrderService orderService) {
        this.orderService = orderService;
    }

    public List<ContextSlice> retrieve(String query) {
        final String q = query == null ? "" : query.trim();
        if (q.isEmpty())
            return List.of();

        List<String> order = List.of("web", "vector", "kg");
        try {
            order = orderService.decide("auto", q);
        } catch (Exception e) {
            log.warn("orderService.decide failed; fallback order used. cause={}", e.toString());
        }

        if (sse != null) {
            try {
                sse.emit("abandonware.retrieval.order", order);
            } catch (Exception ignore) {
            }
        }

        // 1) Gather
        List<ContextSlice> gathered = new ArrayList<>();
        for (String src : order) {
            if (src == null)
                continue;
            switch (src.toLowerCase(Locale.ROOT)) {
                case "web" -> {
                    if (web != null)
                        gathered.addAll(safe(() -> web.search(q, webK)));
                }
                case "vector" -> {
                    // In this namespace: treat "vector" as local BM25
                    if (bm25 != null)
                        gathered.addAll(safe(() -> bm25.retrieve(q, bm25K)));
                }
                case "kg" -> {
                    if (kg != null)
                        gathered.addAll(safe(() -> kg.lookup(q, kgK)));
                }
                default -> {
                    // ignore
                }
            }
        }

        if (gathered.isEmpty())
            return List.of();

        // 2) Dedup (keep first / higher score)
        Map<String, ContextSlice> dedup = new LinkedHashMap<>();
        for (ContextSlice cs : gathered) {
            if (cs == null)
                continue;
            String key = dedupKey(cs);
            ContextSlice prev = dedup.get(key);
            if (prev == null) {
                dedup.put(key, cs);
            } else {
                // prefer higher score if present
                if (cs.getScore() > prev.getScore())
                    dedup.put(key, cs);
            }
        }

        // candidates는 람다에서 사용되므로 재할당하지 않음 (effectively final)
        final List<ContextSlice> candidates = new ArrayList<>(dedup.values());

        // 3) Optional rerank
        List<ContextSlice> reranked;
        if (rerankEnabled && rerank != null && candidates.size() > 1) {
            reranked = safe(() -> rerank.rerank(candidates));
        } else {
            reranked = candidates;
        }

        // 4) Rank + trim
        int rank = 1;
        List<ContextSlice> trimmed = new ArrayList<>(Math.min(finalK, reranked.size()));
        for (ContextSlice cs : reranked) {
            if (cs == null)
                continue;
            cs.setRank(rank++);
            trimmed.add(cs);
            if (trimmed.size() >= finalK)
                break;
        }

        return trimmed;
    }

    private static String dedupKey(ContextSlice cs) {
        String id = cs.getId();
        if (id != null && !id.isBlank())
            return id.trim();
        String title = cs.getTitle() == null ? "" : cs.getTitle().trim();
        String snippet = cs.getSnippet() == null ? "" : cs.getSnippet().trim();
        return title + "\n" + snippet;
    }

    private static <T> T safe(SupplierWithEx<T> fn) {
        try {
            return fn.get();
        } catch (Exception e) {
            return (T) List.of();
        }
    }

    @FunctionalInterface
    private interface SupplierWithEx<T> {
        T get() throws Exception;
    }
}
