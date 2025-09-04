package com.example.lms.config;

import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.handler.VectorDbHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorDbHandlerConfig {

    @Bean
    public VectorDbHandler vectorDbHandler(
            LangChainRAGService ragSvc,
            @Value("${pinecone.index.name:}") String pineconeIndexName
    ) {
        return new VectorDbHandler(ragSvc, pineconeIndexName);
    }
}