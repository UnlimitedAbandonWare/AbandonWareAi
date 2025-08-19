package com.example.lms.config;

import com.example.lms.service.rag.*;
import com.example.lms.service.rag.fusion.ReciprocalRankFuser; // Fuser import
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary; // ★ 추가
// ❗ 중요: HybridRetriever 내부의 RetrievalHandler를 import 합니다.
import com.example.lms.service.rag.handler.RetrievalHandler;
import com.example.lms.service.rag.handler.EvidenceRepairHandler;
import com.example.lms.service.rag.handler.MemoryHandler;
import com.example.lms.service.rag.handler.DefaultRetrievalHandlerChain;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.service.subject.SubjectResolver;
@Configuration
public class RetrieverChainConfig {

    @Bean
    @Primary
    public RetrievalHandler retrievalHandler(
            MemoryHandler memoryHandler,
            SelfAskWebSearchRetriever selfAsk,
            AnalyzeWebSearchRetriever analyze,
            AdaptiveWebSearchHandler adaptiveWeb,
            WebSearchRetriever web,
            LangChainRAGService rag,
            EvidenceRepairHandler evidenceRepairHandler,
            QueryComplexityGate gate) {
        return new DefaultRetrievalHandlerChain(
                memoryHandler,
                selfAsk,
                analyze,
                adaptiveWeb,
                web,
                rag,
                evidenceRepairHandler,
                gate
        );
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