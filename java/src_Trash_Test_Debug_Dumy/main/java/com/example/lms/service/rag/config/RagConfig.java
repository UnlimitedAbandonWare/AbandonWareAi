package com.example.lms.service.rag.config;

import com.example.lms.service.rag.handler.PreconditionCheckHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(PreconditionCheckHandler.class)
    public PreconditionCheckHandler preconditionCheckHandler() {
        return new PreconditionCheckHandler();
    }
}