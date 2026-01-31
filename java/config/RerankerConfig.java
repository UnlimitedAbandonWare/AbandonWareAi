package config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Guarded bean factory for ONNX Cross-Encoder reranker
@Configuration
public class RerankerConfig {
    @Bean
    @ConditionalOnProperty(name="onnx.enabled", havingValue="true")
    public Object onnxCrossEncoderReranker() {
        // Lazily provide a placeholder bean to avoid NoSuchBean
        // Replace with actual OnnxCrossEncoderReranker wiring if class is present
        try {
            Class<?> clazz = Class.forName("service.onnx.OnnxCrossEncoderReranker");
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            // Provide a harmless no-op as fallback to prevent startup failure
            return new Object();
        }
    }
}