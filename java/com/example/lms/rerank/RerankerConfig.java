// src/main/java/com/example/lms/rerank/RerankerConfig.java
package com.example.lms.rerank;

import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

// ⬇ 기본 비활성 + 빈 이름 변경(충돌 방지)
@Configuration("legacyRerankerConfig")
@Profile("legacy-reranker")
public class RerankerConfig {

    @Bean
    @ConditionalOnProperty(name = "abandonware.reranker.backend", havingValue = "onnx-runtime")
    public CrossEncoderReranker onnxReranker() {
        return new OnnxCrossEncoderReranker(); // 기존 구현 유지
    }
}
