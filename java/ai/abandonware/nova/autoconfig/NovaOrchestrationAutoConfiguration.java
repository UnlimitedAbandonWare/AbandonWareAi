package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.aop.ExtremeZBurstAspect;
import ai.abandonware.nova.orch.aop.FallbackBannerAspect;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import ai.abandonware.nova.orch.aop.BraveQueryBurstAspect;
import ai.abandonware.nova.orch.aop.KnowledgeBasePersistenceAspect;
import ai.abandonware.nova.orch.aop.UawAutolearnStrictRequestAspect;
import ai.abandonware.nova.orch.aop.MemoryDegradedAspect;
import ai.abandonware.nova.orch.aop.NaverInterruptHygieneAspect;
import ai.abandonware.nova.orch.aop.RagCompressionAspect;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.storage.DegradedStorage;
import ai.abandonware.nova.orch.storage.DegradedStorageDrainer;
import ai.abandonware.nova.orch.storage.FileDegradedStorage;
import ai.abandonware.nova.orch.storage.OutboxMicrometerMetrics;

import ai.abandonware.nova.orch.uaw.InternalDatasetApiController;

import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.overdrive.OverdriveGuard;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.UawDatasetWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@EnableConfigurationProperties({ NovaOrchestrationProperties.class, LlmRouterProperties.class })
@ConditionalOnProperty(name = "nova.orch.enabled", havingValue = "true", matchIfMissing = true)
public class NovaOrchestrationAutoConfiguration {

    @Bean
    public AnchorNarrower anchorNarrower() {
        return new AnchorNarrower();
    }

    @Bean
    public DynamicContextCompressor compressor(NovaOrchestrationProperties props) {
        return new DynamicContextCompressor(props);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.rag-compressor.enabled", havingValue = "true", matchIfMissing = true)
    public RagCompressionAspect ragCompressionAspect(
            DynamicContextCompressor compressor,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props,
            ObjectProvider<OverdriveGuard> overdriveGuardProvider) {
        return new RagCompressionAspect(compressor, anchorNarrower, props, overdriveGuardProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.extremez.enabled", havingValue = "true", matchIfMissing = false)
    public ExtremeZBurstAspect extremeZBurstAspect(
            ObjectProvider<AnalyzeWebSearchRetriever> analyzeWebSearchRetrieverProvider,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props) {
        return new ExtremeZBurstAspect(analyzeWebSearchRetrieverProvider, anchorNarrower, props);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.interrupt-hygiene.enabled", havingValue = "true", matchIfMissing = true)
    public NaverInterruptHygieneAspect naverInterruptHygieneAspect() {
        return new NaverInterruptHygieneAspect();
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.degraded-storage.enabled", havingValue = "true", matchIfMissing = true)
    public DegradedStorage degradedStorage(NovaOrchestrationProperties props, ObjectMapper om) {
        return new FileDegradedStorage(props, om);
    }

    // MERGE_HOOK:PROJ_AGENT::DEGRADED_OUTBOX_MICROMETER_BINDER_V1
    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean({ DegradedStorage.class, MeterRegistry.class })
    @ConditionalOnProperty(prefix = "nova.orch.degraded-storage.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OutboxMicrometerMetrics outboxMicrometerMetrics(
            ObjectProvider<DegradedStorage> storageProvider,
            NovaOrchestrationProperties props) {
        return new OutboxMicrometerMetrics(storageProvider, props);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.degraded-storage.drain.enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnBean({ DegradedStorage.class, MemoryReinforcementService.class })
    public DegradedStorageDrainer degradedStorageDrainer(
            DegradedStorage storage,
            MemoryReinforcementService memory,
            NovaOrchestrationProperties props,
            Environment env) {
        return new DegradedStorageDrainer(storage, memory, props, env);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.memory-degraded.enabled", havingValue = "true", matchIfMissing = true)
    public MemoryDegradedAspect memoryDegradedAspect(DegradedStorage storage) {
        return new MemoryDegradedAspect(storage);
    }

    @Bean
    @ConditionalOnProperty(name = "llmrouter.enabled", havingValue = "true", matchIfMissing = true)
    public LlmRouterBandit llmRouterBandit(LlmRouterProperties props) {
        return new LlmRouterBandit(props);
    }

    @Bean
    @ConditionalOnProperty(name = "llmrouter.enabled", havingValue = "true", matchIfMissing = true)
    public LlmRouterAspect llmRouterAspect(Environment env, LlmRouterProperties props, LlmRouterBandit bandit) {
        return new LlmRouterAspect(env, props, bandit);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.fallback-banner.enabled", havingValue = "true", matchIfMissing = true)
    public FallbackBannerAspect fallbackBannerAspect(Environment env, ObjectProvider<LlmRouterBandit> routerProvider) {
        return new FallbackBannerAspect(env, routerProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.kb-persistence.enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnBean(DomainKnowledgeRepository.class)
    public KnowledgeBasePersistenceAspect knowledgeBasePersistenceAspect(DomainKnowledgeRepository repo,
            ObjectMapper om) {
        return new KnowledgeBasePersistenceAspect(repo, om);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.brave-query-burst.enabled", havingValue = "true", matchIfMissing = false)
    public BraveQueryBurstAspect braveQueryBurstAspect(Environment env) {
        return new BraveQueryBurstAspect(env);
    }

    @Bean
    @ConditionalOnProperty(prefix = "uaw.dataset-api", name = "enabled", havingValue = "true", matchIfMissing = false)
    public InternalDatasetApiController internalDatasetApiController(
            UawDatasetWriter writer,
            UawAutolearnProperties autolearnProps,
            Environment env) {
        return new InternalDatasetApiController(writer, autolearnProps, env);
    }

    @Bean
    @ConditionalOnProperty(prefix = "uaw.autolearn.strict", name = "enabled", havingValue = "true", matchIfMissing = false)
    public UawAutolearnStrictRequestAspect uawAutolearnStrictRequestAspect(Environment env) {
        return new UawAutolearnStrictRequestAspect(env);
    }
}
