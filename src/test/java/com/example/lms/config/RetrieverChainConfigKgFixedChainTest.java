package com.example.lms.config;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.location.LocationService;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.RelevanceScoringService;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.extract.PageContentScraper;
import com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain;
import com.example.lms.service.rag.handler.EvidenceRepairHandler;
import com.example.lms.service.rag.handler.KnowledgeGraphHandler;
import com.example.lms.service.rag.handler.RetrievalHandler;
import com.example.lms.service.rag.knowledge.UniversalContextLexicon;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import com.example.lms.service.rag.auth.DomainWhitelist;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.telemetry.SseEventPublisher;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrieverChainConfigKgFixedChainTest {

    @Test
    void fixedChainInvokesKnowledgeGraphBetweenWebAndVector() {
        List<String> calls = new ArrayList<>();
        RetrievalHandler chain = chain(calls, false);
        List<Content> out = new ArrayList<>();

        chain.handle(query(), out);

        assertEquals(List.of("web", "kg", "vector", "repair"), calls);
        assertEquals(List.of("web-doc", "kg-doc", "vector-doc"), out.stream()
                .map(c -> c.textSegment().text())
                .toList());
    }

    @Test
    void fixedChainContinuesToVectorWhenKnowledgeGraphFails() {
        List<String> calls = new ArrayList<>();
        RetrievalHandler chain = chain(calls, true);
        List<Content> out = new ArrayList<>();

        chain.handle(query(), out);

        assertEquals(List.of("web", "kg", "vector", "repair"), calls);
        assertTrue(out.stream().map(c -> c.textSegment().text()).toList().contains("vector-doc"));
    }

    @SuppressWarnings("unchecked")
    private static RetrievalHandler chain(List<String> calls, boolean kgThrows) {
        ObjectProvider<DynamicRetrievalHandlerChain> dynProvider = mock(ObjectProvider.class);
        when(dynProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<FailurePatternOrchestrator> orchestratorProvider = mock(ObjectProvider.class);
        when(orchestratorProvider.getIfAvailable()).thenReturn(null);

        SelfAskWebSearchRetriever selfAsk = mock(SelfAskWebSearchRetriever.class);
        AnalyzeWebSearchRetriever analyze = mock(AnalyzeWebSearchRetriever.class);
        AdaptiveWebSearchHandler adaptive = new AdaptiveWebSearchHandler(
                mock(SearchDecisionService.class),
                List.of(),
                mock(PageContentScraper.class),
                mock(RelevanceScoringService.class),
                mock(DomainProfileLoader.class));

        WebSearchRetriever web = mock(WebSearchRetriever.class);
        when(web.retrieve(any())).thenAnswer(inv -> {
            calls.add("web");
            return List.of(content("web-doc"));
        });

        KnowledgeGraphHandler kg = mock(KnowledgeGraphHandler.class);
        when(kg.retrieve(any())).thenAnswer(inv -> {
            calls.add("kg");
            if (kgThrows) {
                throw new IllegalStateException("kg unavailable");
            }
            return List.of(content("kg-doc"));
        });

        ContentRetriever vectorRetriever = mock(ContentRetriever.class);
        when(vectorRetriever.retrieve(any())).thenAnswer(inv -> {
            calls.add("vector");
            return List.of(content("vector-doc"));
        });
        LangChainRAGService rag = mock(LangChainRAGService.class);
        when(rag.asContentRetriever("idx")).thenReturn(vectorRetriever);

        WebSearchRetriever repairWeb = mock(WebSearchRetriever.class);
        when(repairWeb.retrieve(any())).thenAnswer(inv -> {
            calls.add("repair");
            return List.of();
        });
        EvidenceRepairHandler repair = new EvidenceRepairHandler(repairWeb, mock(SubjectResolver.class), "", "");
        ReflectionTestUtils.setField(repair, "criticEnabled", true);

        return new RetrieverChainConfig().retrievalHandler(
                dynProvider,
                orchestratorProvider,
                mock(com.example.lms.service.rag.handler.MemoryHandler.class),
                selfAsk,
                analyze,
                adaptive,
                web,
                rag,
                repair,
                mock(QueryComplexityGate.class),
                kg,
                mock(RetrievalOrderService.class),
                mock(SseEventPublisher.class),
                mock(LocationService.class),
                "idx",
                12_000,
                "fixed");
    }

    private static Query query() {
        return QueryUtils.buildQuery("GraphRAG", Map.of(
                "enableSelfAsk", "false",
                "enableAnalyze", "false",
                "useWebSearch", "false",
                "allowWeb", "true",
                "allowRag", "true"));
    }

    private static Content content(String value) {
        return Content.from(TextSegment.from(value, Metadata.from(Map.of("doc_id", value))));
    }
}
