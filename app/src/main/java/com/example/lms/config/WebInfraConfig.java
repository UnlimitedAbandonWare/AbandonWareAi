
package com.example.lms.config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.Semaphore;
@Configuration
public class WebInfraConfig {
    @Bean
    public Semaphore onnxLimiter(@Value("${onnx.max-concurrency:2}") int max) {
        return new Semaphore(Math.max(1, max));
    }
}
