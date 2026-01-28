package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import ai.abandonware.nova.orch.failpattern.FailurePatternCooldownRegistry;
import ai.abandonware.nova.orch.failpattern.FailurePatternDetector;
import ai.abandonware.nova.orch.failpattern.FailurePatternJsonlWriter;
import ai.abandonware.nova.orch.failpattern.FailurePatternMetrics;
import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import ai.abandonware.nova.orch.failpattern.PolicyAdjuster;
import ai.abandonware.nova.orch.failpattern.aop.FailurePatternCooldownDiagnosticsAspect;
import ai.abandonware.nova.orch.failpattern.aop.RetrievalOrderFeedbackAspect;
import ai.abandonware.nova.orch.failpattern.log.FailurePatternLogAppenderInstaller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Nova Overlay – failure pattern → metrics + weak feedback loop.
 *
 * <p>
 * Design goals:
 * <ul>
 * <li>Do not touch core retrieval logic</li>
 * <li>Observe already-emitted logs to detect failure patterns</li>
 * <li>Expose Micrometer counters + optionally persist JSONL</li>
 * <li>Feed back into retrieval ordering with a soft, configurable policy</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(NovaFailurePatternProperties.class)
@ConditionalOnExpression("${nova.orch.enabled:true} && ${nova.orch.failure.enabled:true}")
public class NovaFailurePatternAutoConfiguration {

    @Bean
    public FailurePatternDetector failurePatternDetector() {
        return new FailurePatternDetector();
    }

    @Bean
    public FailurePatternCooldownRegistry failurePatternCooldownRegistry() {
        return new FailurePatternCooldownRegistry();
    }

    @Bean
    public FailurePatternMetrics failurePatternMetrics(ObjectProvider<MeterRegistry> registryProvider,
            NovaFailurePatternProperties props) {
        return new FailurePatternMetrics(registryProvider.getIfAvailable(), props);
    }

    @Bean
    public FailurePatternJsonlWriter failurePatternJsonlWriter(ObjectMapper om, NovaFailurePatternProperties props) {
        return new FailurePatternJsonlWriter(om, props);
    }

    @Bean
    public FailurePatternOrchestrator failurePatternOrchestrator(FailurePatternDetector detector,
            FailurePatternMetrics metrics,
            FailurePatternJsonlWriter jsonlWriter,
            FailurePatternCooldownRegistry cooldownRegistry,
            ObjectMapper om,
            NovaFailurePatternProperties props) {
        return new FailurePatternOrchestrator(detector, metrics, jsonlWriter, cooldownRegistry, om, props);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.failure.log-appender-enabled", havingValue = "true", matchIfMissing = true)
    public FailurePatternLogAppenderInstaller failurePatternLogAppenderInstaller(
            FailurePatternOrchestrator orchestrator) {
        return new FailurePatternLogAppenderInstaller(orchestrator);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.failure.feedback.enabled", havingValue = "true", matchIfMissing = true)
    public PolicyAdjuster policyAdjuster(FailurePatternOrchestrator orchestrator, NovaFailurePatternProperties props) {
        return new PolicyAdjuster(orchestrator, props);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.failure.feedback.enabled", havingValue = "true", matchIfMissing = true)
    public RetrievalOrderFeedbackAspect retrievalOrderFeedbackAspect(PolicyAdjuster adjuster) {
        return new RetrievalOrderFeedbackAspect(adjuster);
    }

    /**
     * Optional: Resilience4j state transition events (OPEN) counter.
     *
     * <p>
     * Enabled via: nova.orch.failure.resilience4j-events.enabled=true
     */
    @Bean
    @ConditionalOnClass(CircuitBreaker.class)
    @ConditionalOnProperty(name = "nova.orch.failure.resilience4j-events.enabled", havingValue = "true", matchIfMissing = false)
    public RegistryEventConsumer<CircuitBreaker> novaCircuitBreakerRegistryEventConsumer(
            FailurePatternOrchestrator orchestrator) {
        return orchestrator.resilience4jRegistryEventConsumer();
    }


    @Bean
    @ConditionalOnProperty(name = "nova.orch.failure.cooldown-diagnostics.enabled", havingValue = "true", matchIfMissing = true)
    public FailurePatternCooldownDiagnosticsAspect failurePatternCooldownDiagnosticsAspect(
            FailurePatternOrchestrator orchestrator,
            NovaFailurePatternProperties props) {
        return new FailurePatternCooldownDiagnosticsAspect(orchestrator, props);
    }

}
