
package com.example.lms.config;

import com.example.lms.service.infra.cache.SingleFlightExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SingleFlightConfig {
    @Bean
    public SingleFlightExecutor singleFlightExecutor() {
        return new SingleFlightExecutor();
    }
}
