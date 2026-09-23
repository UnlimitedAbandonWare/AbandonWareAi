package com.example.lms.graphdb;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GraphDbManualLearningProperties.class)
public class GraphDbManualLearningConfiguration {
}
