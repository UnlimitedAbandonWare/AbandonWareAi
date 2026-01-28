package com.abandonware.ai.rag.config;

import com.abandonware.ai.rag.onnx.CrossEncoderConcurrencyGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.rag.config.RagPlanConfig
 * Role: config
 * Dependencies: com.abandonware.ai.rag.onnx.CrossEncoderConcurrencyGuard
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.rag.config.RagPlanConfig
role: config
*/
public class RagPlanConfig {

    @Bean
    public <T> CrossEncoderConcurrencyGuard<T> crossEncoderGuard(){
        CrossEncoderConcurrencyGuard.RerankFn<T> passthrough = (List<T> list) -> list;
        return new CrossEncoderConcurrencyGuard<>(passthrough, 4);
    }
}