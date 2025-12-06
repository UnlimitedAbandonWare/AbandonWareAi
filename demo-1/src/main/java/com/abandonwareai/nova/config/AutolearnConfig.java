package com.abandonwareai.nova.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ IdleTrainProperties.class })
@ConditionalOnProperty(prefix = "autolearn", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AutolearnConfig {
}