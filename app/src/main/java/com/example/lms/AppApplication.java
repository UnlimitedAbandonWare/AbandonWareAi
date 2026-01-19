package com.example.lms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ai.abandonware.nova.config.LlmRouterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableConfigurationProperties({LlmRouterProperties.class})
public class AppApplication {
    public static void main(String[] args){
        SpringApplication.run(AppApplication.class, args);
    }
}