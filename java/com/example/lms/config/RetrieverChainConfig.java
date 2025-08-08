package com.example.lms.config;

import com.example.lms.service.rag.*;
import com.example.lms.service.rag.handler.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetrieverChainConfig {

    @Bean
    public RetrievalHandler retrievalHandlerChain(
            SelfAskWebSearchRetriever selfAsk,
            AnalyzeWebSearchRetriever analyze,
            WebSearchRetriever web,
            LangChainRAGService ragSvc,
            @org.springframework.beans.factory.annotation.Value("${pinecone.index.name}") String idx){

        SelfAskHandler   h1 = new SelfAskHandler(selfAsk);
        AnalyzeHandler   h2 = new AnalyzeHandler(analyze);
        WebSearchHandler h3 = new WebSearchHandler(web);
        VectorDbHandler  h4 = new VectorDbHandler(ragSvc, idx);

        return h1.linkWith(h2).linkWith(h3).linkWith(h4);   // 체인 연결
    }

    @Bean
    public HybridRetriever hybridRetriever(RetrievalHandler chain) {
        return new HybridRetriever(chain);  // 생성자 교체
    }
}
