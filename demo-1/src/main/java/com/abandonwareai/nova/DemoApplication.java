package com.abandonwareai.nova;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ai.abandonware.nova.config.LlmRouterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({LlmRouterProperties.class})
@EnableScheduling
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
