package com.example.lms.config.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.lms.guard.FinalQualityGate;
import com.example.lms.guard.SigmoidFinalQualityGate;
import com.example.lms.service.rag.clamp.SensitivityClamp;
import com.example.lms.service.rag.fusion.WpmFusion;

@Configuration
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.config.rag.RagPipelineConfig
 * Role: config
 * Dependencies: com.example.lms.guard.FinalQualityGate, com.example.lms.guard.SigmoidFinalQualityGate, com.example.lms.service.rag.clamp.SensitivityClamp, +1 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.config.rag.RagPipelineConfig
role: config
*/
public class RagPipelineConfig {

    @Bean
    @ConditionalOnProperty(prefix = "gate.final", name = "enabled", havingValue = "true")
    public FinalQualityGate finalQualityGate() {
        return new SigmoidFinalQualityGate();
    }

    @Bean
    @ConditionalOnProperty(prefix = "fusion.wpm", name = "enabled", havingValue = "true")
    public WpmFusion wpmFusion() {
        double p = 1.0; // default; read from configuration later if needed
        return new WpmFusion(p);
    }

    @Bean
    @ConditionalOnProperty(prefix = "score.clamp", name = "enabled", havingValue = "true")
    public SensitivityClamp sensitivityClamp() {
        return new SensitivityClamp(SensitivityClamp.Kind.TANH, 2.5);
    }
}