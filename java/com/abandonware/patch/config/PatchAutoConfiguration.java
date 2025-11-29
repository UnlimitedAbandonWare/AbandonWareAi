package com.abandonware.patch.config;

import com.abandonware.patch.fusion.CvarAggregator;
import com.abandonware.patch.fusion.IdentityScoreCalibrator;
import com.abandonware.patch.fusion.ScoreCalibrator;
import com.abandonware.patch.guard.CitationGate;
import com.abandonware.patch.guard.FinalSigmoidGate;
import com.abandonware.patch.infra.cache.SingleFlightAspect;
import com.abandonware.patch.infra.cache.SingleFlightExecutor;
import com.abandonware.patch.plan.PlannerNexus;
import com.abandonware.patch.plan.RuleBreakInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PatchAutoConfiguration implements WebMvcConfigurer {

    @Bean @ConditionalOnMissingBean
    public ScoreCalibrator scoreCalibrator() { return new IdentityScoreCalibrator(); }

    @Bean @ConditionalOnMissingBean
    public CvarAggregator cvarAggregator() { return new CvarAggregator(); }

    @Bean
    @ConditionalOnProperty(name = "gate.finalSigmoid.enabled", havingValue = "true", matchIfMissing = false)
    public FinalSigmoidGate finalSigmoidGate(
            @Value("${gate.finalSigmoid.k:12.0}") double k,
            @Value("${gate.finalSigmoid.x0:0.0}") double x0) {
        return new FinalSigmoidGate(k, x0);
    }

    @Bean
    @ConditionalOnProperty(name = "gate.citation.enabled", havingValue = "true", matchIfMissing = false)
    public CitationGate citationGate(@Value("${gate.citation.min:3}") int minCitations) {
        return new CitationGate(minCitations);
    }

    @Bean @ConditionalOnMissingBean
    public SingleFlightExecutor singleFlightExecutor() { return new SingleFlightExecutor(); }

    @Bean @ConditionalOnMissingBean
    public SingleFlightAspect singleFlightAspect(SingleFlightExecutor executor) { return new SingleFlightAspect(executor); }

    @Bean
    @ConditionalOnProperty(name = "patch.plan.enabled", havingValue = "true", matchIfMissing = true)
    public PlannerNexus plannerNexus() { return new PlannerNexus(); }

    @Bean
    @ConditionalOnProperty(name = "patch.plan.enabled", havingValue = "true", matchIfMissing = true)
    public RuleBreakInterceptor ruleBreakInterceptor() { return new RuleBreakInterceptor(); }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        try { registry.addInterceptor(ruleBreakInterceptor()); } catch (Exception ignore) {}
    }
}