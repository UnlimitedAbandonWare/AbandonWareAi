package com.example.lms.config;

import com.theokanning.openai.service.OpenAiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class OpenAiConfig {

    @Value("${openai.api.key}")
    private String openAiKey;

    /**
     * ✨ [개선] OpenAI 서비스에 대한 연결 및 응답 타임아웃을 60초로 넉넉하게 설정합니다.
     * 이를 통해 SocketTimeoutException 발생 가능성을 크게 줄일 수 있습니다.
     */
    @Bean
    public OpenAiService openAiService() {
        if (openAiKey == null || openAiKey.isBlank()) {
            // API 키가 없는 경우, 빈을 생성하지 않거나 예외를 발생시켜 문제를 빠르게 인지하도록 할 수 있습니다.
            // 여기서는 로깅만 하고 null을 반환하여 다른 곳에서 NullPointerException이 발생하도록 합니다.
            // 실제 운영 환경에서는 더 강력한 예외 처리가 필요합니다.
            System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            System.err.println("!!! OpenAI API Key is not configured. !!!");
            System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            return null;
        }
        return new OpenAiService(openAiKey, Duration.ofSeconds(60));
    }
}
