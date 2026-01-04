package com.example.lms.config;

import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RerankerConfig {
  @Bean
  public Semaphore onnxLimiter(@Value("${zsys.onnx.max-concurrency:2}") int max) {
    return new Semaphore(Math.max(1, max));
  }
}
