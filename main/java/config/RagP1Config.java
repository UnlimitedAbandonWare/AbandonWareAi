// src/main/java/config/RagP1Config.java
package config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import service.rag.cache.SingleFlightExecutor;

import java.util.List;
import java.util.Map;

@Configuration
public class RagP1Config {

    // 웹 리트리버 전용 싱글-플라이트(반환 타입 지정)
    @Bean
    public SingleFlightExecutor<List<Map<String,Object>>> singleFlightWeb() {
        return new SingleFlightExecutor<>();
    }
}