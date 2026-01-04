package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.config.NovaWebFailSoftProperties;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.aop.ExtremeZBurstAspect;
import ai.abandonware.nova.orch.aop.FallbackBannerAspect;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import ai.abandonware.nova.orch.aop.LlmCallTraceAspect;
import ai.abandonware.nova.orch.aop.GuardDebugTraceAspect;
import ai.abandonware.nova.orch.aop.BraveQueryBurstAspect;
import ai.abandonware.nova.orch.aop.WebFailSoftSearchAspect;
import ai.abandonware.nova.orch.aop.KnowledgeBasePersistenceAspect;
import ai.abandonware.nova.orch.aop.UawAutolearnStrictRequestAspect;
import ai.abandonware.nova.orch.aop.UawIdleAutoTrainingPipelineAspect;
import ai.abandonware.nova.orch.aop.UawPipelineAblationBridge;
import ai.abandonware.nova.orch.aop.UawAblationFinalizeAspect;
import ai.abandonware.nova.orch.aop.FaultMaskAblationPenaltyAspect;
import ai.abandonware.nova.orch.aop.MemoryDegradedAspect;
import ai.abandonware.nova.orch.aop.NaverInterruptHygieneAspect;
import ai.abandonware.nova.orch.aop.RagCompressionAspect;
import ai.abandonware.nova.orch.aop.NaverDomainProfileHatchAspect;
import ai.abandonware.nova.orch.aop.WorkflowPlanMisrouteHatchAspect;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.storage.DegradedStorage;
import ai.abandonware.nova.orch.storage.DegradedStorageDrainer;
import ai.abandonware.nova.orch.storage.FileDegradedStorage;
import ai.abandonware.nova.orch.storage.OutboxMicrometerMetrics;
import ai.abandonware.nova.orch.web.RuleBasedQueryAugmenter;
import ai.abandonware.nova.orch.web.WebFailSoftDomainStageReportService;

import ai.abandonware.nova.orch.uaw.InternalDatasetApiController;

import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import com.example.lms.service.rag.auth.AuthorityScorer;
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
@EnableConfigurationProperties({ NovaOrchestrationProperties.class, LlmRouterProperties.class,
        NovaWebFailSoftProperties.class })
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
    @ConditionalOnProperty(name = "nova.orch.rag-compressor.enabled", havingValue = "true", matchIfMissing = false)
    public RagCompressionAspect ragCompressionAspect(
            DynamicContextCompressor compressor,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props,
            ObjectProvider<OverdriveGuard> overdriveGuardProvider) {
        return new RagCompressionAspect(compressor, anchorNarrower, props, overdriveGuardProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.extremez.enabled", havingValue = "true", matchIfMissing = true)
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

    // DROP hatches: prevent accidental strict domain filters / plan misroutes.
    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.hatch.naver-domain-profile", name = "enabled", havingValue = "true", matchIfMissing = true)
    public NaverDomainProfileHatchAspect naverDomainProfileHatchAspect() {
        return new NaverDomainProfileHatchAspect();
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.hatch.workflow-plan-misroute", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowPlanMisrouteHatchAspect workflowPlanMisrouteHatchAspect(Environment env) {
        return new WorkflowPlanMisrouteHatchAspect(env);
    }

    // MERGE_HOOK:PROJ_AGENT::OUTBOX_STORAGE_BEAN_ALIAS_V1
    @Bean(name = {"degradedStorage", "outboxStorage"})
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
    public FallbackBannerAspect fallbackBannerAspect(
            Environment env,
            ObjectProvider<LlmRouterBandit> routerProvider,
            ObjectProvider<DynamicChatModelFactory> chatModelFactoryProvider) {
        return new FallbackBannerAspect(env, routerProvider.getIfAvailable(),
                chatModelFactoryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.kb-persistence.enabled", havingValue = "true", matchIfMissing = false)
    @ConditionalOnBean(DomainKnowledgeRepository.class)
    public KnowledgeBasePersistenceAspect knowledgeBasePersistenceAspect(DomainKnowledgeRepository repo,
            ObjectMapper om) {
        return new KnowledgeBasePersistenceAspect(repo, om);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.brave-query-burst.enabled", havingValue = "true", matchIfMissing = true)
    public BraveQueryBurstAspect braveQueryBurstAspect(Environment env, RuleBasedQueryAugmenter augmenter) {
        return new BraveQueryBurstAspect(env, augmenter);
    }

    @Bean
    public RuleBasedQueryAugmenter ruleBasedQueryAugmenter(NovaWebFailSoftProperties props) {
        return new RuleBasedQueryAugmenter(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.web-failsoft.report", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WebFailSoftDomainStageReportService webFailSoftDomainStageReportService(
            NovaWebFailSoftProperties props,
            ObjectProvider<DomainProfileLoader> domainProfileLoaderProvider,
            ObjectProvider<AuthorityScorer> authorityScorerProvider) {
        return new WebFailSoftDomainStageReportService(
                props,
                domainProfileLoaderProvider.getIfAvailable(),
                authorityScorerProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.web-failsoft", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WebFailSoftSearchAspect webFailSoftSearchAspect(
            NovaWebFailSoftProperties props,
            RuleBasedQueryAugmenter augmenter,
            ObjectProvider<DomainProfileLoader> domainProfileLoaderProvider,
            ObjectProvider<AuthorityScorer> authorityScorerProvider,
            ObjectProvider<WebFailSoftDomainStageReportService> domainStageReportProvider,
            ObjectProvider<FaultMaskingLayerMonitor> faultMaskingLayerMonitorProvider,
            ObjectProvider<NightmareBreaker> nightmareBreakerProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new WebFailSoftSearchAspect(props, augmenter,
                domainProfileLoaderProvider.getIfAvailable(),
                authorityScorerProvider.getIfAvailable(),
                domainStageReportProvider.getIfAvailable(),
                faultMaskingLayerMonitorProvider.getIfAvailable(),
                nightmareBreakerProvider.getIfAvailable(),
                meterRegistryProvider.getIfAvailable());
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
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${uaw.autolearn.strict.enabled:false}' == 'true' && '${uaw.autolearn.pipeline.enabled:false}' == 'false'")
    public UawAutolearnStrictRequestAspect uawAutolearnStrictRequestAspect(
            Environment env,
            ObjectProvider<NightmareBreaker> nightmareBreakerProvider) {
        return new UawAutolearnStrictRequestAspect(env, nightmareBreakerProvider.getIfAvailable());
    }

    // DROP: UAW idle auto-training pipeline (preferred path).
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${uaw.autolearn.strict.enabled:false}' == 'true' && '${uaw.autolearn.pipeline.enabled:true}' == 'true'")
    public UawIdleAutoTrainingPipelineAspect uawIdleAutoTrainingPipelineAspect(
            Environment env,
            RuleBasedQueryAugmenter augmenter,
            ObjectProvider<NightmareBreaker> nightmareBreakerProvider) {
        return new UawIdleAutoTrainingPipelineAspect(env, augmenter, nightmareBreakerProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "uaw.autolearn.pipeline", name = "enabled", havingValue = "true", matchIfMissing = true)
    public UawPipelineAblationBridge uawPipelineAblationBridge(Environment env) {
        return new UawPipelineAblationBridge(env);
    }

    @Bean
    @ConditionalOnProperty(prefix = "uaw.autolearn.pipeline", name = "enabled", havingValue = "true", matchIfMissing = true)
    public UawAblationFinalizeAspect uawAblationFinalizeAspect(Environment env) {
        return new UawAblationFinalizeAspect(env);
    }

    @Bean
    @ConditionalOnProperty(prefix = "uaw.autolearn.pipeline", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FaultMaskAblationPenaltyAspect faultMaskAblationPenaltyAspect(Environment env) {
        return new FaultMaskAblationPenaltyAspect(env);
    }

    // ---- Debug / trace helpers (opt-in) ----

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.debug.llm-trace", name = "enabled", havingValue = "true", matchIfMissing = false)
    public LlmCallTraceAspect llmCallTraceAspect(Environment env) {
        return new LlmCallTraceAspect(env);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.debug.guard-trace", name = "enabled", havingValue = "true", matchIfMissing = false)
    public GuardDebugTraceAspect guardDebugTraceAspect(
            Environment env,
            ObjectProvider<NightmareBreaker> nightmareBreakerProvider) {
        return new GuardDebugTraceAspect(env, nightmareBreakerProvider);
    }
}
