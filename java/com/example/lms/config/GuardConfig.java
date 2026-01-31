// src/main/java/com/example/lms/config/GuardConfig.java
package com.example.lms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.lms.service.guard.EvidenceAwareGuard;



@Configuration
public class GuardConfig {
    @Bean
    public EvidenceAwareGuard evidenceAwareGuard() {
        return new EvidenceAwareGuard();
    }
}