package com.abandonware.patch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Semaphore;

@Configuration
public class OnnxLimiterConfiguration {

    @Bean(name = "onnxLimiter")
    public Semaphore onnxLimiter(@Value("${zsys.onnx.max-concurrency:2}") int maxConcurrency) {
        return new Semaphore(Math.max(1, maxConcurrency));
    }
}