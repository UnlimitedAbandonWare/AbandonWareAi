package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import lombok.Data;



@Configuration
@ConfigurationProperties(prefix="nova.rulebreak")
@Data
public class RuleBreakConfig implements WebMvcConfigurer {

    @Autowired
    private com.example.lms.guard.rulebreak.RuleBreakInterceptor guardRuleBreakInterceptor;

    private boolean enabled = true;
    private String adminToken = "";
    private String sseChannel = "/events/rulebreak";
    private int ttlSeconds = 60;
    private int webTopKMax = 8;

    /* Managed by component scan: RuleBreakEvaluator bean defined via @Component */
    // @Bean
    // public com.example.lms.guard.rulebreak.RuleBreakEvaluator ruleBreakEvaluator() {
    //     return new com.example.lms.guard.rulebreak.RuleBreakEvaluator();
    // }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guardRuleBreakInterceptor);
    }
}