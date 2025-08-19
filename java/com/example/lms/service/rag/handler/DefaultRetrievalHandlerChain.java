package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.strategy.StrategySelectorService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;

/**
 * 기본 검색 체인 구현. 순서는 명시적으로 고정되어 있으며,
 * 필요에 따라 단계가 건너뛰어질 수 있다.
 *
 * 실행 순서: Memory → EntityDisambiguation → SelfAsk → Analyze → (Web|Vector|KG)* → Repair
 */
@RequiredArgsConstructor
public class DefaultRetrievalHandlerChain implements RetrievalHandler {

    private final com.example.lms.service.rag.handler.MemoryHandler memoryHandler;
    private final EntityDisambiguationHandler disambiguationHandler;
    private final SelfAskWebSearchRetriever selfAsk;
    private final AnalyzeWebSearchRetriever analyze;
    private final WebSearchRetriever web;
    private final LangChainRAGService rag;
    private final KnowledgeGraphHandler kgHandler;
    private final com.example.lms.service.rag.handler.EvidenceRepairHandler repair;
    private final QueryComplexityGate gate;
    private final StrategySelectorService strategySelector;

    @Value("${pinecone.index.name}")
    private String pineconeIndexName;

    @Value("${rag.search.top-k:5}")
    private int topK;

    @Override
    public void handle(Query query, List<Content> accumulator) {
        if (accumulator == null) {
            return;
        }
        // 1. 세션 메모리 로드
        Long sessionId = null;
        try {
            if (query != null && query.metadata() != null) {
                java.util.Map<String,Object> md = toMap(query.metadata());
                Object sidObj = md.get(LangChainRAGService.META_SID);
                if (sidObj != null) {
                    sessionId = Long.parseLong(String.valueOf(sidObj));
                }
            }
        } catch (Exception ignore) {
            // ignore
        }
        if (sessionId != null) {
            try {
                String hist = memoryHandler.loadForSession(sessionId);
                if (hist != null && !hist.isBlank()) {
                    accumulator.add(Content.from(hist));
                    if (accumulator.size() >= topK) return;
                }
            } catch (Exception ignore) {
                // ignore
            }
        }
        String q = (query != null && query.text() != null) ? query.text().trim() : "";
        // 2. Entity Disambiguation
        try {
            if (disambiguationHandler != null) {
                disambiguationHandler.handle(query, accumulator);
            }
        } catch (Exception ignore) {}
        // 3. SelfAsk: 복잡한 경우만
        boolean needSelf = false;
        try {
            needSelf = gate != null && gate.needsSelfAsk(q);
        } catch (Exception ignore) {}
        if (needSelf) {
            add(accumulator, selfAsk.retrieve(query));
            if (accumulator.size() >= topK) return;
        }
        // 4. Analyze: 모호 또는 복잡한 경우만
        boolean needAnalyze = false;
        try {
            needAnalyze = gate != null && gate.needsAnalyze(q);
        } catch (Exception ignore) {}
        if (needAnalyze) {
            add(accumulator, analyze.retrieve(query));
            if (accumulator.size() >= topK) return;
        }
        // 5. Dynamically ordered retrieval stages (Web / Vector / KG)
        StrategySelectorService.Strategy strat = null;
        try {
            strat = (strategySelector != null) ? strategySelector.selectForQuestion(q, null) : null;
        } catch (Exception ignore) {}
        java.util.List<java.util.function.Supplier<Void>> stages = new java.util.ArrayList<>();

        java.util.function.Supplier<Void> webStage = () -> { add(accumulator, web.retrieve(query)); return null; };
        java.util.function.Supplier<Void> vectorStage = () -> {
            ContentRetriever vector = rag.asContentRetriever(pineconeIndexName);
            add(accumulator, vector.retrieve(query));
            return null;
        };
        java.util.function.Supplier<Void> kgStage = () -> { kgHandler.handle(query, accumulator); return null; };

        if (strat == StrategySelectorService.Strategy.VECTOR_FIRST) {
            stages.add(vectorStage);
            stages.add(webStage);
            stages.add(kgStage);
        } else if (strat == StrategySelectorService.Strategy.WEB_VECTOR_FUSION) {
            stages.add(webStage);
            stages.add(kgStage);
            stages.add(vectorStage);
        } else {
            stages.add(webStage);
            stages.add(vectorStage);
            stages.add(kgStage);
        }

        for (java.util.function.Supplier<Void> s : stages) {
            s.get();
            if (accumulator.size() >= topK) return;
        }

        // 6. Repair
        try {
            if (repair != null) {
                add(accumulator, repair.retrieve(query));
            }
        } catch (Exception ignore) {
            // ignore
        }
    }

    private static void add(List<Content> target, List<Content> source) {
        if (source != null && !source.isEmpty()) {
            target.addAll(source);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String,Object> toMap(Object meta) {
        if (meta == null) return java.util.Map.of();
        try {
            java.lang.reflect.Method m = meta.getClass().getMethod("asMap");
            return (java.util.Map<String,Object>) m.invoke(meta);
        } catch (NoSuchMethodException e) {
            try {
                java.lang.reflect.Method m2 = meta.getClass().getMethod("map");
                return (java.util.Map<String,Object>) m2.invoke(meta);
            } catch (Exception ex) {
                return java.util.Map.of();
            }
        } catch (Exception ex) {
            return java.util.Map.of();
        }
    }
}