package com.abandonware.ai.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ai.abandonware.nova.config.LlmRouterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableConfigurationProperties({LlmRouterProperties.class})
public class Demo1Application {
    public static void main(String[] args) {
        SpringApplication.run(Demo1Application.class, args);
    }
}