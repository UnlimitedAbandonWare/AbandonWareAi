// src/main/java/com/example/lms/rerank/RerankerConfig.java
package com.example.lms.rerank;

import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

// ⬇ 기본 비활성 + 빈 이름 변경(충돌 방지)
@Configuration("legacyRerankerConfig")
@Profile("legacy-reranker")
public class RerankerConfig {

    @Bean(name = "onnxCrossEncoderRerankerLegacy") // 개선: 혹시 모를 빈 이름 충돌 회피
    @ConditionalOnProperty(name = "abandonware.reranker.backend", havingValue = "onnx-runtime")
    public CrossEncoderReranker onnxReranker(
            com.example.lms.service.onnx.OnnxRuntimeService onnx // 수정: 필요한 의존성 주입
    ) {
        return new com.example.lms.service.onnx.OnnxCrossEncoderReranker(onnx); // 생성자 일치
    }
}
