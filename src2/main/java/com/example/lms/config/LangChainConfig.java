// 경로: src/main/java/com/example/lms/config/LangChainConfig.java
package com.example.lms.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j 관련 Bean들을 수동으로 생성하는 설정 파일입니다.
 * (주의: application.properties의 langchain4j.* 자동 설정과 중복 사용 불가)
 */
@Configuration
public class LangChainConfig {

    // application.properties에서 기존 OpenAI 키를 읽어옵니다.
    @Value("${openai.api.key}")
    private String openaiApiKey;

    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .apiKey(openaiApiKey)
                .modelName("gpt-4o-mini")
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(openaiApiKey)
                .modelName("text-embedding-3-small")
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}