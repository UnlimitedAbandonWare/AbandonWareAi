package com.example.lms.config;

import com.theokanning.openai.service.OpenAiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import com.example.lms.service.verification.SourceAnalyzerService;
import java.time.Duration;
import com.example.lms.service.verification.FactStatusClassifier;
import com.example.lms.service.FactVerifierService;
import org.springframework.beans.factory.ObjectProvider;   // ✅ 추가
@Configuration
public class OpenAiConfig {

    /** 환경 변수나 application.yml 에 openai.api.key=YOUR_KEY 저장 */
    @Value("${openai.api.key}")
    private String openAiKey;

    /** OpenAI‑Java SDK Client – 60 초 타임아웃 */
    @Bean
    @Primary                       // 👉 유일·우선 빈
    public OpenAiService openAiService() {
        if (openAiKey == null || openAiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is missing");
        }
        return new OpenAiService(openAiKey, Duration.ofSeconds(60));
    }

    // FactStatusClassifier is now annotated with @Service and directly injects
    // an ObjectProvider<ChatModel>.  A separate @Bean definition is no longer
    // necessary here.
}
