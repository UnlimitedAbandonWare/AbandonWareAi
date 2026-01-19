package com.example.lms.config;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.example.lms.location.LocationService;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.handler.AnalyzeHandler;
import com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain;
import com.example.lms.service.rag.handler.EvidenceRepairHandler;
import com.example.lms.service.rag.handler.KnowledgeGraphHandler;
import com.example.lms.service.rag.handler.MemoryHandler;
import com.example.lms.service.rag.handler.OrchestrationGate;
import com.example.lms.service.rag.handler.RetrievalHandler;
import com.example.lms.service.rag.handler.SearchCostGuardHandler;
import com.example.lms.service.rag.handler.SelfAskHandler;
import com.example.lms.service.rag.handler.VectorDbHandler;
import com.example.lms.service.rag.handler.WebHandler;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.telemetry.SseEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RetrieverChainConfig {

    @Bean
    @Primary
    public RetrievalHandler retrievalHandler(
            ObjectProvider<DynamicRetrievalHandlerChain> dynProvider,
            ObjectProvider<FailurePatternOrchestrator> orchestratorProvider,
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
            @Value("${router.moe.relief.threshold-tokens:12000}") int thresholdTokens,
            @Value("${retrieval.chain.mode:fixed}") String mode) {

        if ("dynamic".equalsIgnoreCase(mode)) {
            DynamicRetrievalHandlerChain dyn = dynProvider.getIfAvailable();
            if (dyn != null) {
                return dyn;
            }
        }

        // Fixed-chain: SELF_ASK → ANALYZE → (CostGuard) → WEB → VECTOR
        // NOTE: OrchestrationGate is critical here so fixed-chain can honor metaHints.
        OrchestrationGate orchGate = new OrchestrationGate(orchestratorProvider.getIfAvailable());

        var h1 = new SelfAskHandler(selfAsk, orchGate);
        var h2 = new AnalyzeHandler(analyze, orchGate);
        var h3 = new WebHandler(web, orchGate);
        var h4 = new VectorDbHandler(rag, pineconeIndexName, orchGate);

        // Insert a search cost guard between analyze and web to log relief hints when token budget is large.
        var costGuard = new SearchCostGuardHandler(
                text -> Math.min(16000, text.length() / 3),
                thresholdTokens,
                msg -> org.slf4j.LoggerFactory.getLogger(RetrieverChainConfig.class).info(msg)
        );

        h1.linkWith(h2).linkWith(costGuard).linkWith(h3).linkWith(h4);
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
