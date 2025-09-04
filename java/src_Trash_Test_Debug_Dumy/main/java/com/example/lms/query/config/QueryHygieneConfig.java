package com.example.lms.query.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration class that enables property binding for {@link AiQueryProperties}
 * and scheduling required by {@link com.example.lms.query.config.AliasYamlHotReloader}.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AiQueryProperties.class)
public class QueryHygieneConfig {
    // no additional beans; configuration annotations are sufficient
}