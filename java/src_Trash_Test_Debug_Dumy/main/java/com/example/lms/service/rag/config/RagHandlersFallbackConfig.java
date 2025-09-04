package com.example.lms.service.rag.config;

import com.example.lms.service.rag.handler.SelfAskHandler;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagHandlersFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(SelfAskHandler.class)
    public SelfAskHandler selfAskHandler(SelfAskWebSearchRetriever retriever) {
        // 구현체가 없을 때만 기본 핸들러 제공
        return new SelfAskHandler(retriever);
    }
}
