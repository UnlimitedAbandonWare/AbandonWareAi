package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NaverPlanHintBoostOnlyOverlayProperties;
import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.config.NovaBraveAdaptiveQpsProperties;
import ai.abandonware.nova.config.NovaWebFailSoftProperties;
import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.aop.ExtremeZBurstAspect;
import ai.abandonware.nova.orch.aop.FallbackBannerAspect;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import ai.abandonware.nova.orch.aop.OpenAiChatModelGuardAspect;
import ai.abandonware.nova.orch.aop.LlmCallTraceAspect;
import ai.abandonware.nova.orch.aop.GuardDebugTraceAspect;
import ai.abandonware.nova.orch.aop.BraveQueryBurstAspect;
import ai.abandonware.nova.orch.aop.FailSoftQueryAugmentAspect;
import ai.abandonware.nova.orch.aop.WebFailSoftSearchAspect;
import ai.abandonware.nova.orch.aop.KnowledgeBasePersistenceAspect;
import ai.abandonware.nova.orch.aop.UawAutolearnStrictRequestAspect;
import ai.abandonware.nova.orch.aop.UawIdleAutoTrainingPipelineAspect;
import ai.abandonware.nova.orch.aop.UawPipelineAblationBridge;
import com.example.lms.learning.gemini.GeminiGateway;
import ai.abandonware.nova.orch.aop.UawAblationFinalizeAspect;
import ai.abandonware.nova.orch.aop.FaultMaskAblationPenaltyAspect;
import ai.abandonware.nova.orch.aop.FaultMaskIrregularityCapAspect;
import ai.abandonware.nova.orch.aop.GuardrailQueryPreprocessorAnchorTailAspect;
import ai.abandonware.nova.orch.aop.OptionalIrregularityCapAspect;
import ai.abandonware.nova.orch.aop.QueryAnalysisAnchorTailAspect;
import ai.abandonware.nova.orch.aop.KeywordSelectionAnchorTailAspect;
import ai.abandonware.nova.orch.aop.KeywordSelectionForceMinMustAspect;
import ai.abandonware.nova.orch.aop.QueryTransformerAnchorTailAspect;
import ai.abandonware.nova.orch.aop.EvidenceListTraceInjectionAspect;
import ai.abandonware.nova.orch.aop.EvidenceListSnippetFallbackAspect;
import ai.abandonware.nova.orch.aop.CleanOutputRedactionAspect;
import ai.abandonware.nova.orch.aop.ChatWorkflowFastBailoutMinHitsPostProcessor;
import ai.abandonware.nova.orch.aop.SettingsControllerSecretMaskAspect;
import ai.abandonware.nova.orch.aop.MemoryDegradedAspect;
import ai.abandonware.nova.orch.aop.NaverInterruptHygieneAspect;
import ai.abandonware.nova.orch.aop.NaverPlanHintBoostOnlyOverlayAspect;
import ai.abandonware.nova.orch.aop.HybridWebSearchInterruptHygieneAspect;
import ai.abandonware.nova.orch.aop.WebProviderStructuredLogAspect;
import ai.abandonware.nova.orch.aop.BraveOperationalGateAspect;
import ai.abandonware.nova.orch.aop.NightmareBreakerWebRateLimitPropagatorAspect;
import ai.abandonware.nova.orch.aop.ChunkRollingSummaryAspect;
import ai.abandonware.nova.orch.aop.RollingSummaryHistoryAspect;
import ai.abandonware.nova.orch.aop.ConversationBreadcrumbAspect;
import ai.abandonware.nova.orch.aop.UawTickTraceSeedAspect;
import ai.abandonware.nova.orch.aop.RagCompressionAspect;
import ai.abandonware.nova.orch.aop.NaverDomainProfileHatchAspect;
import ai.abandonware.nova.orch.aop.WorkflowPlanMisrouteHatchAspect;
import ai.abandonware.nova.orch.compress.AnchorProbeHandler;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import ai.abandonware.nova.orch.ecosystem.EcosystemBufferPool;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.storage.DegradedStorage;
import ai.abandonware.nova.orch.storage.DegradedStorageDrainer;
import ai.abandonware.nova.orch.storage.FileDegradedStorage;
import ai.abandonware.nova.orch.storage.OutboxMicrometerMetrics;
import ai.abandonware.nova.orch.web.RuleBasedQueryAugmenter;
import ai.abandonware.nova.orch.web.WebFailSoftDomainStageReportService;
import ai.abandonware.nova.orch.web.brave.BraveAdaptiveQpsInstaller;
import ai.abandonware.nova.orch.web.brave.BraveRateLimitState;
import ai.abandonware.nova.orch.web.brave.BraveRestTemplateTimeoutOverrideInstaller;
import ai.abandonware.nova.orch.adapters.NovaAnalyzeWebSearchRetriever;

import ai.abandonware.nova.orch.uaw.InternalDatasetApiController;

import com.example.lms.search.provider.HybridWebSearchProvider;

import ai.abandonware.nova.orch.probe.WebSoakKpiProbeService;
import ai.abandonware.nova.orch.probe.WebSoakKpiLastStore;

import ai.abandonware.nova.orch.probe.WebSoakKpiProbeController;

import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.ModelRuntimeHealthTracker;

import com.example.lms.cfvm.CfvmFailureRecorder;
import com.example.lms.config.PromptPoseProperties;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.LlmGatewayBreadcrumbPublisher;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.prompt.pose.PromptPoseApplicationAspect;
import com.example.lms.prompt.pose.PromptPoseApplicationJudge;
import com.example.lms.prompt.pose.PromptPoseDraftGenerator;
import com.example.lms.prompt.pose.PromptPoseOrchestrator;
import com.example.lms.prompt.pose.PromptPosePlanSanitizer;
import com.example.lms.prompt.pose.PromptPoseRewardAspect;
import com.example.lms.prompt.pose.PromptPoseRoutingPlanAspect;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.search.TraceStore;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.pre.LongInputDistillationService;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.budget.RetrievalBudgetGovernor;
import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.service.rag.offline.OfflineTextureSnapshotLoader;
import com.example.lms.service.rag.overdrive.AngerOverdriveNarrower;
import com.example.lms.service.rag.overdrive.OverdriveGuard;
import com.example.lms.service.rag.learn.CfvmBanditStore;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.LearningSampleValidationMetadataBuilder;
import com.example.lms.uaw.autolearn.UawAutolearnQualityTracker;
import com.example.lms.uaw.autolearn.UawDatasetWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration"
})
@EnableConfigurationProperties({ NovaOrchestrationProperties.class, LlmRouterProperties.class,
        NovaWebFailSoftProperties.class,
        NovaModelGuardProperties.class, NovaBraveAdaptiveQpsProperties.class,
        NaverPlanHintBoostOnlyOverlayProperties.class,
        PromptPoseProperties.class })
@ConditionalOnProperty(name = "nova.orch.enabled", havingValue = "true", matchIfMissing = true)
public class NovaOrchestrationAutoConfiguration {
    private static final System.Logger LOG = System.getLogger(NovaOrchestrationAutoConfiguration.class.getName());

    @Bean
    public AnchorNarrower anchorNarrower() {
        return new AnchorNarrower();
    }


    @Bean(name = "searchIoExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "searchIoExecutor")
    public java.util.concurrent.ExecutorService searchIoExecutorFallback(Environment env) {
        // Fallback so optional slices/tests don't fail boot when SearchExecutorConfig is absent.
        int core = env.getProperty("search.io.executor.corePoolSize", Integer.class, 8);
        int max = env.getProperty("search.io.executor.maxPoolSize", Integer.class, Math.max(8, core * 2));
        int queueCap = env.getProperty("search.io.executor.queueCapacity", Integer.class, 500);

        java.util.concurrent.ThreadFactory tf = new java.util.concurrent.ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("search-io-fallback-" + idx.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };

        java.util.concurrent.BlockingQueue<Runnable> q = new java.util.concurrent.LinkedBlockingQueue<>(Math.max(1, queueCap));
        java.util.concurrent.ThreadPoolExecutor ex = new java.util.concurrent.ThreadPoolExecutor(
                Math.max(1, core),
                Math.max(Math.max(1, core), max),
                60L,
                java.util.concurrent.TimeUnit.SECONDS,
                q,
                tf,
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }


    @Bean
    public AnchorProbeHandler anchorProbeHandler(AnchorNarrower anchorNarrower) {
        return new AnchorProbeHandler(anchorNarrower);
    }

    @Bean
    public DynamicContextCompressor compressor(
            NovaOrchestrationProperties props,
            AnchorProbeHandler anchorProbeHandler,
            Environment env) {
        if (props != null && props.getRagCompressor() != null && env != null
                && env.containsProperty("prompt.context.refiner.enabled")) {
            props.getRagCompressor().setContextRefinerEnabled(
                    env.getProperty("prompt.context.refiner.enabled", Boolean.class, false));
        }
        return new DynamicContextCompressor(props, anchorProbeHandler);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.rag-compressor.enabled", havingValue = "true", matchIfMissing = true)
    public RagCompressionAspect ragCompressionAspect(
            DynamicContextCompressor compressor,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props,
            ObjectProvider<OverdriveGuard> overdriveGuardProvider,
            ObjectProvider<AngerOverdriveNarrower> overdriveNarrowerProvider) {
        return new RagCompressionAspect(compressor, anchorNarrower, props,
                overdriveGuardProvider.getIfAvailable(), overdriveNarrowerProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.extremez.enabled", havingValue = "true", matchIfMissing = true)
    public ExtremeZBurstAspect extremeZBurstAspect(
            ObjectProvider<AnalyzeWebSearchRetriever> analyzeWebSearchRetrieverProvider,
            AnchorNarrower anchorNarrower,
            NovaOrchestrationProperties props,
            ObjectProvider<ContradictionScorer> contradictionScorerProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            ObjectProvider<RagFailureBlackboxService> blackboxProvider,
            ObjectProvider<RetrievalBudgetGovernor> budgetGovernorProvider,
            ObjectProvider<CfvmFailureRecorder> cfvmFailureRecorderProvider) {
        return new ExtremeZBurstAspect(analyzeWebSearchRetrieverProvider, anchorNarrower, props,
                contradictionScorerProvider, debugEventStoreProvider, blackboxProvider, budgetGovernorProvider,
                cfvmFailureRecorderProvider);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "nova.orch.web-analyze-retriever.override.enabled", havingValue = "true", matchIfMissing = true)
    public AnalyzeWebSearchRetriever novaAnalyzeWebSearchRetriever(
            org.apache.lucene.analysis.Analyzer analyzer,
            com.example.lms.search.provider.WebSearchProvider webSearchProvider,
            @org.springframework.beans.factory.annotation.Qualifier("guardrailQueryPreprocessor") com.example.lms.service.rag.pre.QueryContextPreprocessor preprocessor,
            com.example.lms.service.routing.plan.RoutingPlanService routingPlanService,
            com.example.lms.search.policy.SearchPolicyEngine searchPolicyEngine,
            @org.springframework.beans.factory.annotation.Qualifier("searchIoExecutor") java.util.concurrent.ExecutorService searchIoExecutor,
            ObjectMapper objectMapper) {
        return new NovaAnalyzeWebSearchRetriever(analyzer, webSearchProvider, preprocessor, routingPlanService, searchPolicyEngine, searchIoExecutor, objectMapper);
    }




    @Bean
    @ConditionalOnProperty(name = "nova.orch.interrupt-hygiene.enabled", havingValue = "true", matchIfMissing = true)
    public NaverInterruptHygieneAspect naverInterruptHygieneAspect() {
        return new NaverInterruptHygieneAspect();
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.interrupt-hygiene.enabled", havingValue = "true", matchIfMissing = true)
    public HybridWebSearchInterruptHygieneAspect hybridWebSearchInterruptHygieneAspect() {
        return new HybridWebSearchInterruptHygieneAspect();
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.web.provider-events.enabled", havingValue = "true", matchIfMissing = true)
    public WebProviderStructuredLogAspect webProviderStructuredLogAspect(
            ObjectProvider<NightmareBreaker> breakerProvider) {
        return new WebProviderStructuredLogAspect(breakerProvider);
    }

    @Bean
    public BraveRateLimitState braveRateLimitState() {
        return new BraveRateLimitState();
    }


    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.brave.adaptive-qps", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static BraveAdaptiveQpsInstaller braveAdaptiveQpsInstaller(
            ObjectProvider<NovaBraveAdaptiveQpsProperties> propsProvider,
            ObjectProvider<BraveRateLimitState> braveRateLimitStateProvider) {
        return new BraveAdaptiveQpsInstaller(propsProvider::getIfAvailable, braveRateLimitStateProvider::getIfAvailable);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.brave.rest-template.timeout-override.enabled", havingValue = "true", matchIfMissing = true)
    public static BraveRestTemplateTimeoutOverrideInstaller braveRestTemplateTimeoutOverrideInstaller(Environment env) {
        return new BraveRestTemplateTimeoutOverrideInstaller(env);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.brave.operational-gate.enabled", havingValue = "true", matchIfMissing = true)
    public BraveOperationalGateAspect braveOperationalGateAspect(
            ObjectProvider<com.example.lms.service.web.BraveSearchService> braveSearchServiceProvider,
            BraveRateLimitState braveRateLimitState,
            Environment env) {
        return new BraveOperationalGateAspect(braveSearchServiceProvider, braveRateLimitState, env);
    }


    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.web-rate-limit-propagation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public NightmareBreakerWebRateLimitPropagatorAspect nightmareBreakerWebRateLimitPropagatorAspect() {
        return new NightmareBreakerWebRateLimitPropagatorAspect();
    }


    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.breadcrumb", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ConversationBreadcrumbAspect conversationBreadcrumbAspect(NovaOrchestrationProperties props) {
        return new ConversationBreadcrumbAspect(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.chunking", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RollingSummaryHistoryAspect rollingSummaryHistoryAspect(
            NovaOrchestrationProperties props,
            ChatMessageRepository messageRepository) {
        return new RollingSummaryHistoryAspect(props, messageRepository);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.chunking", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ChunkRollingSummaryAspect chunkRollingSummaryAspect(
            NovaOrchestrationProperties props,
            ChatMessageRepository messageRepository,
            ChatHistoryService historyService,
            LongInputDistillationService distillationService,
            ObjectMapper objectMapper) {
        return new ChunkRollingSummaryAspect(props, messageRepository, historyService, distillationService, objectMapper);
    }

    // DROP hatches: prevent accidental strict domain filters / plan misroutes.
    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.hatch.naver-domain-profile", name = "enabled", havingValue = "true", matchIfMissing = true)
    public NaverDomainProfileHatchAspect naverDomainProfileHatchAspect() {
        return new NaverDomainProfileHatchAspect();
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.hatch.naver-planhint-boost-only", name = "enabled", havingValue = "true", matchIfMissing = true)
    public NaverPlanHintBoostOnlyOverlayAspect naverPlanHintBoostOnlyOverlayAspect(NaverPlanHintBoostOnlyOverlayProperties props) {
        return new NaverPlanHintBoostOnlyOverlayAspect(props);
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
    @ConditionalOnBean(DegradedStorage.class)
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
    public LlmRouterAspect llmRouterAspect(Environment env,
                                          LlmRouterProperties props,
                                          LlmRouterBandit bandit,
                                          NovaModelGuardProperties modelGuardProps,
                                          ObjectProvider<KeyResolver> keyResolverProvider,
                                          ObjectProvider<HybridLlmGatewayProbeService> gatewayProbeProvider,
                                          ObjectProvider<LlmGatewayBreadcrumbPublisher> gatewayBreadcrumbProvider,
                                          ObjectProvider<LlmGatewayFailureClassifier> gatewayFailureClassifierProvider,
                                          ObjectProvider<ModelRuntimeHealthTracker> modelRuntimeHealthTrackerProvider,
                                          ObjectProvider<GeminiGateway> geminiGatewayProvider) {
        return new LlmRouterAspect(env, props, bandit, modelGuardProps, keyResolverProvider,
                gatewayProbeProvider.getIfAvailable(),
                gatewayBreadcrumbProvider.getIfAvailable(),
                gatewayFailureClassifierProvider.getIfAvailable(),
                modelRuntimeHealthTrackerProvider.getIfAvailable(),
                geminiGatewayProvider.getIfAvailable());
    }


    @Bean
    public OpenAiChatModelGuardAspect openAiChatModelGuardAspect(NovaModelGuardProperties modelGuardProps,
                                                                 Environment env,
                                                                 ObjectProvider<KeyResolver> keyResolverProvider) {
        return new OpenAiChatModelGuardAspect(modelGuardProps, env, keyResolverProvider);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.fallback-banner.enabled", havingValue = "true", matchIfMissing = true)
    public FallbackBannerAspect fallbackBannerAspect(
            Environment env,
            ObjectProvider<LlmRouterBandit> routerProvider,
            ObjectProvider<DynamicChatModelFactory> chatModelFactoryProvider,
            ObjectProvider<PromptBuilder> promptBuilderProvider) {
        return new FallbackBannerAspect(env, routerProvider.getIfAvailable(),
                chatModelFactoryProvider.getIfAvailable(),
                promptBuilderProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "prompt-pose", name = "enabled", havingValue = "true", matchIfMissing = false)
    public PromptPosePlanSanitizer promptPosePlanSanitizer(PromptPoseProperties props) {
        return new PromptPosePlanSanitizer(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "prompt-pose", name = "enabled", havingValue = "true", matchIfMissing = false)
    public PromptPoseApplicationJudge promptPoseApplicationJudge(
            PromptPoseProperties props,
            ObjectProvider<CfvmBanditStore> cfvmBanditStoreProvider) {
        return new PromptPoseApplicationJudge(props,
                cfvmBanditStoreProvider == null ? null : cfvmBanditStoreProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "prompt-pose", name = "enabled", havingValue = "true", matchIfMissing = false)
    public PromptPoseDraftGenerator promptPoseDraftGenerator(
            PromptPoseProperties props,
            ObjectMapper objectMapper,
            ObjectProvider<DynamicChatModelFactory> chatModelFactoryProvider,
            ObjectProvider<PromptBuilder> promptBuilderProvider) {
        return new PromptPoseDraftGenerator(props, objectMapper, chatModelFactoryProvider, promptBuilderProvider);
    }

    @Bean
    @ConditionalOnProperty(prefix = "prompt-pose", name = "enabled", havingValue = "true", matchIfMissing = false)
    public PromptPoseOrchestrator promptPoseOrchestrator(
            PromptPoseProperties props,
            PromptPoseDraftGenerator draftGenerator,
            PromptPosePlanSanitizer planSanitizer,
            PromptPoseApplicationJudge applicationJudge) {
        return new PromptPoseOrchestrator(props, draftGenerator, planSanitizer, applicationJudge);
    }

    @Bean
    @ConditionalOnProperty(prefix = "prompt-pose", name = "enabled", havingValue = "true", matchIfMissing = false)
    public PromptPoseRoutingPlanAspect promptPoseRoutingPlanAspect(
            PromptPoseProperties props,
            PromptPoseOrchestrator orchestrator) {
        return new PromptPoseRoutingPlanAspect(props, orchestrator);
    }

    @Bean
    @ConditionalOnProperty(prefix = "prompt-pose", name = "enabled", havingValue = "true", matchIfMissing = false)
    public PromptPoseApplicationAspect promptPoseApplicationAspect(
            PromptPoseProperties props,
            PromptPoseApplicationJudge applicationJudge) {
        return new PromptPoseApplicationAspect(props, applicationJudge);
    }

    @Bean
    @ConditionalOnProperty(prefix = "prompt-pose", name = { "enabled", "reward.enabled" }, havingValue = "true", matchIfMissing = false)
    @ConditionalOnBean(CfvmBanditStore.class)
    public PromptPoseRewardAspect promptPoseRewardAspect(CfvmBanditStore cfvmBanditStore) {
        return new PromptPoseRewardAspect(cfvmBanditStore);
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
    public BraveQueryBurstAspect braveQueryBurstAspect(
            Environment env,
            RuleBasedQueryAugmenter augmenter,
            AnchorNarrower anchorNarrower,
            ObjectProvider<OfflineTextureSnapshotLoader> offlineTextureLoaderProvider,
            ObjectProvider<RetrievalBudgetGovernor> budgetGovernorProvider) {
        return new BraveQueryBurstAspect(env, augmenter, anchorNarrower,
                offlineTextureLoaderProvider == null ? null : offlineTextureLoaderProvider.getIfAvailable(),
                budgetGovernorProvider == null ? null : budgetGovernorProvider.getIfAvailable());
    }

    @Bean
    public RuleBasedQueryAugmenter ruleBasedQueryAugmenter(NovaWebFailSoftProperties props) {
        return new RuleBasedQueryAugmenter(props);
    }


    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.failsoft-query-augment", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FailSoftQueryAugmentAspect failSoftQueryAugmentAspect(Environment env, RuleBasedQueryAugmenter augmenter) {
        return new FailSoftQueryAugmentAspect(env, augmenter);
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
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "nova.ecosystem.enabled", havingValue = "true", matchIfMissing = true)
    public EcosystemBufferPool ecosystemBufferPool(Environment env) {
        int maxPoolSize = intProperty(env,
                "nova.ecosystem.pool.max-size",
                "nova.ecosystem.buffer.max-pool-size",
                64);
        long ttlMs = longProperty(env,
                "nova.ecosystem.pool.ttl-ms",
                "nova.ecosystem.buffer.ttl-ms",
                300_000L);
        int recirculateMax = intProperty(env,
                "nova.ecosystem.pool.recirculate-max",
                "nova.ecosystem.buffer.recirculate-max",
                8);
        double ammoniaThreshold = doubleProperty(env,
                "nova.ecosystem.pool.ammonia-threshold",
                "nova.ecosystem.ammonia.threshold",
                0.5d);
        String quarantineTag = stringProperty(env,
                "nova.ecosystem.pool.quarantine-tag",
                "nova.ecosystem.ammonia.quarantine-tag",
                "UNVERIFIED");
        return new EcosystemBufferPool(maxPoolSize, ttlMs, recirculateMax, ammoniaThreshold, quarantineTag);
    }

    private static int intProperty(Environment env, String primary, String legacy, int fallback) {
        Integer value = env.getProperty(primary, Integer.class);
        if (value != null) {
            return value;
        }
        return env.getProperty(legacy, Integer.class, fallback);
    }

    private static long longProperty(Environment env, String primary, String legacy, long fallback) {
        Long value = env.getProperty(primary, Long.class);
        if (value != null) {
            return value;
        }
        return env.getProperty(legacy, Long.class, fallback);
    }

    private static double doubleProperty(Environment env, String primary, String legacy, double fallback) {
        Double value = env.getProperty(primary, Double.class);
        if (value != null) {
            return value;
        }
        return env.getProperty(legacy, Double.class, fallback);
    }

    private static String stringProperty(Environment env, String primary, String legacy, String fallback) {
        String value = env.getProperty(primary);
        if (value != null && !value.isBlank()) {
            return value;
        }
        String legacyValue = env.getProperty(legacy);
        return legacyValue != null && !legacyValue.isBlank() ? legacyValue : fallback;
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
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            org.springframework.beans.factory.ObjectProvider<ai.abandonware.nova.orch.web.RateLimitBackoffCoordinator> rateLimitBackoffCoordinatorProvider) {
        return new WebFailSoftSearchAspect(props, augmenter,
                domainProfileLoaderProvider.getIfAvailable(),
                authorityScorerProvider.getIfAvailable(),
                domainStageReportProvider.getIfAvailable(),
                faultMaskingLayerMonitorProvider.getIfAvailable(),
                nightmareBreakerProvider.getIfAvailable(),
                debugEventStoreProvider.getIfAvailable(),
                meterRegistryProvider.getIfAvailable(),
                rateLimitBackoffCoordinatorProvider);
    }

    @Bean
    @ConditionalOnProperty(prefix = "uaw.dataset-api", name = "enabled", havingValue = "true", matchIfMissing = false)
    public InternalDatasetApiController internalDatasetApiController(
            UawDatasetWriter writer,
            UawAutolearnProperties autolearnProps,
            LearningSampleValidationMetadataBuilder validationMetadataBuilder,
            UawAutolearnQualityTracker qualityTracker,
            Environment env) {
        return new InternalDatasetApiController(writer, autolearnProps, validationMetadataBuilder, qualityTracker, env);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.uaw.tick-trace-seed", name = "enabled", havingValue = "true", matchIfMissing = true)
    public UawTickTraceSeedAspect uawTickTraceSeedAspect() {
        return new UawTickTraceSeedAspect();
    }


    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${uaw.autolearn.strict.enabled:false}' == 'true' && '${uaw.autolearn.pipeline.enabled:true}' == 'false'")
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
    public UawAblationFinalizeAspect uawAblationFinalizeAspect(
            Environment env,
            ObjectProvider<RagFailureBlackboxService> blackboxProvider) {
        return new UawAblationFinalizeAspect(env, blackboxProvider);
    }

    @Bean
    @ConditionalOnProperty(prefix = "uaw.autolearn.pipeline", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FaultMaskAblationPenaltyAspect faultMaskAblationPenaltyAspect(Environment env) {
        return new FaultMaskAblationPenaltyAspect(env);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.faultmask-irregularity-cap", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FaultMaskIrregularityCapAspect faultMaskIrregularityCapAspect(Environment env) {
        double maxDelta = env.getProperty("nova.orch.faultmask-irregularity-cap.maxDelta", Double.class, 0.04);
        return new FaultMaskIrregularityCapAspect(true, maxDelta);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.optional-irregularity-cap", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OptionalIrregularityCapAspect optionalIrregularityCapAspect(Environment env) {
        double deltaCap = env.getProperty("nova.orch.optional-irregularity-cap.deltaCap", Double.class, 0.08);
        double ceiling = env.getProperty("nova.orch.optional-irregularity-cap.ceiling", Double.class, 0.28);
        int maxEvents = env.getProperty("nova.orch.optional-irregularity-cap.maxEvents", Integer.class, 20);
        return new OptionalIrregularityCapAspect(true, deltaCap, ceiling, maxEvents);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.anchor-tail.guardrail", name = "enabled", havingValue = "true", matchIfMissing = true)
    public GuardrailQueryPreprocessorAnchorTailAspect guardrailQueryPreprocessorAnchorTailAspect(
            Environment env,
            AnchorNarrower anchorNarrower) {
        int maxLen = env.getProperty("nova.orch.anchor-tail.guardrail.maxLen", Integer.class, 120);
        int headroom = env.getProperty("nova.orch.anchor-tail.guardrail.headroom", Integer.class, 8);
        int triggerLen = env.getProperty("nova.orch.anchor-tail.guardrail.triggerLen", Integer.class, 240);
        return new GuardrailQueryPreprocessorAnchorTailAspect(true, maxLen, headroom, triggerLen, anchorNarrower);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.anchor-tail.query-analysis", name = "enabled", havingValue = "true", matchIfMissing = true)
    public QueryAnalysisAnchorTailAspect queryAnalysisAnchorTailAspect(
            Environment env,
            AnchorNarrower anchorNarrower) {
        int maxLen = env.getProperty("nova.orch.anchor-tail.query-analysis.maxLen", Integer.class, 900);
        int triggerLen = env.getProperty("nova.orch.anchor-tail.query-analysis.triggerLen", Integer.class, 1400);
        return new QueryAnalysisAnchorTailAspect(true, maxLen, triggerLen, anchorNarrower);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.anchor-tail.keyword-selection", name = "enabled", havingValue = "true", matchIfMissing = true)
    public KeywordSelectionAnchorTailAspect keywordSelectionAnchorTailAspect(
            Environment env,
            AnchorNarrower anchorNarrower) {
        int maxLen = env.getProperty("nova.orch.anchor-tail.keyword-selection.maxLen", Integer.class, 1400);
        int triggerLen = env.getProperty("nova.orch.anchor-tail.keyword-selection.triggerLen", Integer.class, 2200);
        return new KeywordSelectionAnchorTailAspect(true, maxLen, triggerLen, anchorNarrower);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.keyword-selection.force-min-must", name = "enabled", havingValue = "true", matchIfMissing = true)
    public KeywordSelectionForceMinMustAspect keywordSelectionForceMinMustAspect(Environment env) {
        return new KeywordSelectionForceMinMustAspect(env);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.anchor-tail.query-transformer", name = "enabled", havingValue = "true", matchIfMissing = true)
    public QueryTransformerAnchorTailAspect queryTransformerAnchorTailAspect(
            Environment env,
            AnchorNarrower anchorNarrower) {
        int maxLen = env.getProperty("nova.orch.anchor-tail.query-transformer.maxLen", Integer.class, 900);
        int triggerLen = env.getProperty("nova.orch.anchor-tail.query-transformer.triggerLen", Integer.class, 1400);
        return new QueryTransformerAnchorTailAspect(true, maxLen, triggerLen, anchorNarrower);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.evidence-list.trace-injection", name = "enabled", havingValue = "true", matchIfMissing = true)
    public EvidenceListTraceInjectionAspect evidenceListTraceInjectionAspect(Environment env) {
        return new EvidenceListTraceInjectionAspect(env);
    }

    /**
     * Output boundary guard: internal trace/diagnostic blocks must not leak into user-visible content.
     *
     * This is resilient even if trace injection is enabled accidentally.
     */
    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.output.clean", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CleanOutputRedactionAspect cleanOutputRedactionAspect(Environment env) {
        return new CleanOutputRedactionAspect(env);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.evidence-list.snippet-fallback", name = "enabled", havingValue = "true", matchIfMissing = true)
    public EvidenceListSnippetFallbackAspect evidenceListSnippetFallbackAspect(Environment env) {
        return new EvidenceListSnippetFallbackAspect(env);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.orch.chat-workflow-fast-bail-postprocessor", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static ChatWorkflowFastBailoutMinHitsPostProcessor chatWorkflowFastBailoutMinHitsPostProcessor(Environment env) {
        return new ChatWorkflowFastBailoutMinHitsPostProcessor(env);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.security.settings.mask", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SettingsControllerSecretMaskAspect settingsControllerSecretMaskAspect(Environment env) {
        return new SettingsControllerSecretMaskAspect(env);
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


    // ---- WebSoak KPI probe (opt-in) ----

    @Bean
    @ConditionalOnProperty(prefix = "probe.websoak-kpi", name = "enabled", havingValue = "true", matchIfMissing = false)
    public WebSoakKpiProbeService webSoakKpiProbeService(
            HybridWebSearchProvider hybridWebSearchProvider,
            Environment env,
            ObjectMapper objectMapper) {
        return new WebSoakKpiProbeService(hybridWebSearchProvider, env, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "probe.websoak-kpi", name = "enabled", havingValue = "true", matchIfMissing = false)
    public WebSoakKpiLastStore webSoakKpiLastStore(Environment env) {
        int maxRecent = WebSoakKpiLastStore.DEFAULT_MAX_RECENT;
        try {
            String v = env.getProperty("probe.websoak-kpi.last.max-recent");
            if (v != null && !v.isBlank()) {
                maxRecent = Integer.parseInt(v.trim());
            }
        } catch (NumberFormatException parseError) {
            traceWebSoakMaxRecentParseFallback(parseError);
            maxRecent = WebSoakKpiLastStore.DEFAULT_MAX_RECENT;
        }
        return new WebSoakKpiLastStore(maxRecent);
    }

    @Bean
    @ConditionalOnProperty(prefix = "probe.websoak-kpi", name = "enabled", havingValue = "true", matchIfMissing = false)
    public WebSoakKpiProbeController webSoakKpiProbeController(
            WebSoakKpiProbeService probeService,
            Environment env) {
        return new WebSoakKpiProbeController(probeService, env);
    }

    private static void traceWebSoakMaxRecentParseFallback(Throwable error) {
        try {
            TraceStore.inc("probe.websoakKpi.last.maxRecent.parseFallback.count");
            TraceStore.put("probe.websoakKpi.last.maxRecent.parseFallback.reason", "invalid_number");
            TraceStore.put("probe.websoakKpi.last.maxRecent.parseFallback.errorType", errorType(error));
        } catch (RuntimeException traceError) {
            LOG.log(System.Logger.Level.DEBUG,
                    "[AWX][websoak] trace skipped stage={0} errorType={1}",
                    "maxRecentParseFallback",
                    errorType(traceError));
        }
    }

    private static String errorType(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        if (error instanceof NumberFormatException) {
            return "invalid_number";
        }
        return error.getClass().getSimpleName();
    }

}
