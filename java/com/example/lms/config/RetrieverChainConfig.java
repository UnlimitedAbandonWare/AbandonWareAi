package com.example.lms.config;

import com.example.lms.service.rag.*;
import com.example.lms.service.rag.fusion.ReciprocalRankFuser; // Fuser import
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// ❗ 중요: HybridRetriever 내부의 RetrievalHandler를 import 합니다.
import com.example.lms.service.rag.HybridRetriever.RetrievalHandler;

@Configuration
public class RetrieverChainConfig {

    /**
     * 💡 새로운 방식의 RetrievalHandler Bean 입니다.
     * 각 리트리버를 순서대로 호출하는 람다(lambda) 형식으로 정의합니다.
     * 이 Bean은 HybridRetriever.RetrievalHandler 타입을 반환합니다.
     */
    @Bean
    public RetrievalHandler retrievalHandler(
            SelfAskWebSearchRetriever selfAsk,
            AnalyzeWebSearchRetriever analyze,
            WebSearchRetriever web,
            LangChainRAGService rag,
            @Value("${pinecone.index.name}") String indexName) {

        // 각 리트리버를 순차적으로 실행하는 로직
        return (query, out) -> {
            out.addAll(selfAsk.retrieve(query));
            // 결과가 부족할 경우 다음 리트리버를 실행
            if (out.size() < 5) out.addAll(analyze.retrieve(query));
            if (out.size() < 5) out.addAll(web.retrieve(query));
            out.addAll(rag.asContentRetriever(indexName).retrieve(query));
        };
    }

    /**
     * 💡 확장된 생성자를 사용하는 HybridRetriever Bean 입니다.
     * 위에서 정의한 handler와 fuser, 그리고 모든 리트리버 컴포넌트를 직접 주입받습니다.
     */
    @Bean
    public HybridRetriever hybridRetriever(
            RetrievalHandler handler,
            ReciprocalRankFuser fuser,
            SelfAskWebSearchRetriever selfAsk,
            AnalyzeWebSearchRetriever analyze,
            WebSearchRetriever web,
            QueryComplexityGate gate,
            LangChainRAGService ragService,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> store
    ) {
        // 새로운 생성자에 모든 의존성을 전달합니다.
        return new HybridRetriever(handler, fuser, selfAsk, analyze, web, gate, ragService, embeddingModel, store);
    }
}