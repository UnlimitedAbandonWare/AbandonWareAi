package com.abandonware.ai.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ai.abandonware.nova.config.LlmRouterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;



/**
 * Entry point for the K-CHAT / LMS multi-channel agent application.  This
 * application exposes a simple Spring Boot context with the tool layer,
 * consent management, durable jobs and orchestration infrastructure.  It
 * does not contain any business endpoints of its own; instead, it serves as
 * a foundation that other modules or controllers can build upon.  All
 * required components are registered as Spring beans via their package
 * annotations and configuration classes.
 */
@SpringBootApplication(
    scanBasePackages = {
        "com.abandonware.ai.agent",
        "com.example.lms",
        "com.example.risk"
    }
)
@EnableConfigurationProperties({LlmRouterProperties.class})
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}