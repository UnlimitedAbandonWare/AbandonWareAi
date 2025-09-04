package com.example.lms.config;
import com.example.lms.service.rag.handler.SelfAskHandler;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.LangChainRAGService;
// /* TODO */ 필요한 것만 명시적으로 import
// SelfAskWebSearchRetriever is defined in com.example.lms.service.rag
// and SelfAskHandler resides in com.example.lms.service.rag.handler. Remove duplicate imports.
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
import com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain;
import com.example.lms.service.rag.handler.DefaultRetrievalHandlerChain;
import com.example.lms.location.LocationService;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.service.rag.handler.KnowledgeGraphHandler;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.telemetry.SseEventPublisher;
import com.example.lms.service.subject.SubjectResolver;
@Configuration
public class RetrieverChainConfig {
    // MLA-ANCHOR:CHAIN-ORDER v2
    // Fixed chain shape (Desktop-Only):
    // HybridRetriever → SelfAsk → Analyze → Web → VectorDb → Repair
    // Note: Assembly is orchestrated in HybridRetrieverImpl to avoid bean duplication.

    // The fixed retrieval chain has been migrated into HybridRetrieverImpl for MOE synergy.
    // Disable creation of the old retrievalHandler bean by renaming the factory method and
    // removing Spring annotations.  This prevents accidental injection of a stale chain
    // configuration and ensures that HybridRetrieverImpl orchestrates the chain internally.
    // The legacy retrievalHandlerDisabled factory method has been removed as it is unused.

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