package app.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
@Configuration
public class WebInfraConfig {
    @Bean
    public Semaphore onnxLimiter(@Value("${zsys.onnx.max-concurrency:2}") int max) {
        return new Semaphore(Math.max(1, max));
    }
}
