// src/main/java/com/example/lms/config/GuardConfig.java
package com.example.lms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.guard.CitationGate;



@Configuration
public class GuardConfig {
    @Bean
    public EvidenceAwareGuard evidenceAwareGuard() {
        return new EvidenceAwareGuard();
    }

    @Bean
    public CitationGate citationGate(@Value("${gate.citation.log-only:false}") boolean logOnly) {
        return new CitationGate(logOnly);
    }
}
