package com.example.lms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.Semaphore;

/**
 * Configuration for controlling the concurrency of ONNX cross-encoder
 * reranker execution.  A simple {@link Semaphore} is exposed as a bean so
 * that downstream components can coordinate access to the limited GPU
 * resources used by ONNX.  The semaphore permits count is configured via
 * {@code zsys.onnx.max-concurrency} in {@code application.yml}.
 */
@Configuration
public class OnnxConfig {
    @Bean
    public Semaphore onnxLimiter(@Value("${zsys.onnx.max-concurrency:2}") int max) {
        return new Semaphore(Math.max(1, max));
    }
}