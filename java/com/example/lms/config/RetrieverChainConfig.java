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
@Configuration
public class RetrieverChainConfig {

    @Bean
    @Primary // ★ 추가
    public RetrievalHandler retrievalHandler(
            SelfAskWebSearchRetriever selfAsk,
            AnalyzeWebSearchRetriever analyze,
            WebSearchRetriever web,
            LangChainRAGService rag,
            @Value("${pinecone.index.name}") String indexName) {

        return (query, out) -> {
            out.addAll(selfAsk.retrieve(query));
            if (out.size() < 5) out.addAll(analyze.retrieve(query));
            if (out.size() < 5) out.addAll(web.retrieve(query));
            out.addAll(rag.asContentRetriever(indexName).retrieve(query));
        };
    }
}