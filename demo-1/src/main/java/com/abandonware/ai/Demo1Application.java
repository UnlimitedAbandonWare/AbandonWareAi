package com.abandonware.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ai.abandonware.nova.config.LlmRouterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableConfigurationProperties({LlmRouterProperties.class})
@RestController
public class Demo1Application {

    @GetMapping("/health")
    public String health() { return "OK"; }

    public static void main(String[] args) {
        SpringApplication.run(Demo1Application.class, args);
    }
}
