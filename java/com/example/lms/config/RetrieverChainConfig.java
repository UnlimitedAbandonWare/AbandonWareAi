package com.example.lms.config;

import com.example.lms.service.rag.*;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.lms.service.rag.handler.RetrievalHandler;
import com.example.lms.service.rag.handler.EvidenceRepairHandler;
import com.example.lms.service.rag.handler.MemoryHandler;
import com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain;
import com.example.lms.service.rag.handler.DefaultRetrievalHandlerChain;
import com.example.lms.location.LocationService;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.service.rag.handler.KnowledgeGraphHandler;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.telemetry.SseEventPublisher;
import com.example.lms.service.subject.SubjectResolver;


import com.example.lms.service.rag.fusion.ReciprocalRankFuser; // Fuser import
import org.springframework.context.annotation.Primary; // ★ 추가
// ❗ 중요: HybridRetriever 내부의 RetrievalHandler를 import 합니다.
@Configuration
public class RetrieverChainConfig {

    @Bean
    @Primary
    public RetrievalHandler retrievalHandler(
            MemoryHandler memoryHandler,
            SelfAskWebSearchRetriever selfAsk,
            AnalyzeWebSearchRetriever analyze,
            WebSearchRetriever web,
            LangChainRAGService rag,
            EvidenceRepairHandler evidenceRepairHandler,
            QueryComplexityGate gate,
            KnowledgeGraphHandler kg,
            RetrievalOrderService orderService,
            SseEventPublisher sse,
            LocationService locationService,
            @Value("${pinecone.index.name:}") String pineconeIndexName,
            @Value("${router.moe.relief.threshold-tokens:12000}") int thresholdTokens) {
        /*
         * Construct a fixed retrieval chain in the required order for MOE evaluation.
         *
         * The desired sequence of handlers is:
         *   SelfAskHandler → AnalyzeHandler → WebHandler → VectorDbHandler
         * Optionally prefix the chain with a MemoryHandler to load session history
         * before performing any retrieval.  Each handler is linked via the
         * responsibility chain pattern using {@link AbstractRetrievalHandler#linkWith}.
         */
        // Create individual handlers wrapping the appropriate retrievers.
        var h1 = new com.example.lms.service.rag.handler.SelfAskHandler(selfAsk);
        var h2 = new com.example.lms.service.rag.handler.AnalyzeHandler(analyze);
        var h3 = new com.example.lms.service.rag.handler.WebHandler(web);
        var h4 = new com.example.lms.service.rag.handler.VectorDbHandler(rag, pineconeIndexName);
        // Insert a search cost guard between analyze and web to log relief hints when token budget is large.
        var costGuard = new com.example.lms.service.rag.handler.SearchCostGuardHandler(
                text -> Math.min(16000, text.length() / 3),
                thresholdTokens,
                msg -> org.slf4j.LoggerFactory.getLogger(RetrieverChainConfig.class).info(msg)
        );

        // Link handlers: SelfAsk → Analyze → (CostGuard) → Web → Vector.
        h1.linkWith(h2).linkWith(costGuard).linkWith(h3).linkWith(h4);



        // Return the head of the chain.  Downstream callers should invoke handle() on this bean.
        return h1;
    }

    /**
     * 증거 보수 핸들러 빈 등록: 도메인과 선호 도메인은 설정에서 주입한다.
     */
    @Bean
    public EvidenceRepairHandler evidenceRepairHandler(
            WebSearchRetriever web,
            SubjectResolver subjectResolver,
            @Value("${retrieval.repair.domain:}") String domain,
            @Value("${retrieval.repair.preferred-domains:}") String preferred) {
        return new EvidenceRepairHandler(web, subjectResolver, domain, preferred);
    }
}