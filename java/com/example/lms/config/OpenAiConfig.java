package com.example.lms.config;

import com.example.lms.service.FactVerifierService;
import com.theokanning.openai.service.OpenAiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import com.example.lms.service.verification.SourceAnalyzerService;
import java.time.Duration;

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

    /** 사실 검증용 서비스 */
    public FactVerifierService factVerifierService(OpenAiService openAiService,
                                                   SourceAnalyzerService sourceAnalyzer) {
        // 2-인자 생성자: FactStatusClassifier는 내부에서 new 로 생성됨
        return new FactVerifierService(openAiService, sourceAnalyzer);
    }
}
