package com.example.lms.resilience;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;



@Configuration("singleFlightConfig")
@EnableAspectJAutoProxy
@EnableConfigurationProperties(SingleFlightProperties.class)
public class SingleFlightConfig {

    @Bean
    @ConditionalOnMissingBean
    public SingleFlightManager singleFlightManager() {
        return new SingleFlightManager();
    }
}