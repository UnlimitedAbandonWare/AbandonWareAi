package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.boot.embedding.MatryoshkaEmbeddingModelPostProcessor;
import ai.abandonware.nova.boot.exec.CancelShieldExecutorServicePostProcessor;
import ai.abandonware.nova.boot.exec.zombie.CleanPoolFactory;
import ai.abandonware.nova.boot.exec.zombie.DefaultZombieBreederDetector;
import ai.abandonware.nova.boot.exec.zombie.ZombieBreederContainmentAspect;
import ai.abandonware.nova.boot.exec.zombie.ZombieBreederDetector;
import ai.abandonware.nova.boot.exec.zombie.ZombieBreederProperties;
import ai.abandonware.nova.boot.exec.zombie.ZombieContainmentMigrator;
import ai.abandonware.nova.boot.reactor.NovaReactorDroppedErrorHook;
import ai.abandonware.nova.orch.aop.HybridWebSearchEmptyFallbackAspect;
import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import ai.abandonware.nova.orch.aop.ProviderRateLimitBackoffAspect;
import ai.abandonware.nova.orch.web.brave.BraveRateLimitState;
import ai.abandonware.nova.orch.web.RateLimitBackoffCoordinator;
import com.example.lms.cfvm.CfvmFailureRecoveryHandler;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Operational stabilization layer for bootRun/soak.
 *
 * <p>
 * Goals:
 * <ul>
 *   <li>Prevent interrupt-poisoning caused by {@code Future.cancel(true)} on pooled executors</li>
 *   <li>Suppress/record Reactor {@code onErrorDropped} cancellation noise and log useful bodies</li>
 *   <li>Provide last-resort Hybrid(Brave+Naver) empty-result bypass for cancellation scenarios</li>
 * </ul>
 */
@AutoConfiguration(afterName = {
        "ai.abandonware.nova.autoconfig.NovaOrchestrationAutoConfiguration",
        "ai.abandonware.nova.autoconfig.NovaDebugPortAutoConfiguration"
})
@ConditionalOnProperty(name = "nova.orch.ops.stabilization.enabled", havingValue = "true", matchIfMissing = true)
public class NovaOpsStabilizationAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "nova.zombie")
    @ConditionalOnProperty(prefix = "nova.zombie", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public ZombieBreederProperties zombieBreederProperties(Environment env) {
        ZombieBreederProperties props = new ZombieBreederProperties();
        return Binder.get(env).bind("nova.zombie", Bindable.ofInstance(props)).orElse(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.zombie", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public DefaultZombieBreederDetector zombieBreederDetector(ZombieBreederProperties props) {
        return new DefaultZombieBreederDetector(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.zombie", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public CleanPoolFactory cleanPoolFactory(ZombieBreederProperties props) {
        return new CleanPoolFactory(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.zombie", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public ZombieContainmentMigrator zombieContainmentMigrator(ZombieBreederProperties props) {
        return new ZombieContainmentMigrator(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nova.zombie", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public ZombieBreederContainmentAspect zombieBreederContainmentAspect(
            ZombieBreederDetector detector,
            CleanPoolFactory cleanPoolFactory,
            ZombieContainmentMigrator migrator,
            ZombieBreederProperties props) {
        return new ZombieBreederContainmentAspect(detector, cleanPoolFactory, migrator, props);
    }

    @Bean
    @ConditionalOnClass(name = "java.util.concurrent.ExecutorService")
    @ConditionalOnProperty(name = "nova.orch.interrupt-hygiene.cancel-shield.enabled", havingValue = "true", matchIfMissing = true)
    public static CancelShieldExecutorServicePostProcessor cancelShieldExecutorServicePostProcessor(
            Environment env,
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            ObjectProvider<CfvmFailureRecoveryHandler> recoveryHandlerProvider) {
        return new CancelShieldExecutorServicePostProcessor(env, debugEventStoreProvider, recoveryHandlerProvider);
    }

    @Bean
    @ConditionalOnClass(name = "reactor.core.publisher.Hooks")
    @ConditionalOnProperty(name = "nova.orch.debug.reactor-onErrorDropped.enabled", havingValue = "true", matchIfMissing = true)
    public NovaReactorDroppedErrorHook novaReactorDroppedErrorHook(
            Environment env,
            ObjectProvider<FaultMaskingLayerMonitor> faultMaskMonitorProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        return new NovaReactorDroppedErrorHook(env, faultMaskMonitorProvider, debugEventStoreProvider);
    }

    @Bean
    @ConditionalOnClass(name = "dev.langchain4j.model.embedding.EmbeddingModel")
    @ConditionalOnProperty(name = "nova.orch.embedding.matryoshka-shield.enabled", havingValue = "true", matchIfMissing = true)
    public static MatryoshkaEmbeddingModelPostProcessor matryoshkaEmbeddingModelPostProcessor(Environment env) {
        return new MatryoshkaEmbeddingModelPostProcessor(env);
    }

    @Bean
    @ConditionalOnClass(NaverSearchService.class)
    @ConditionalOnProperty(name = "nova.orch.web.failsoft.hybrid-empty-fallback.enabled", havingValue = "true", matchIfMissing = true)
    public HybridWebSearchEmptyFallbackAspect hybridWebSearchEmptyFallbackAspect(
            Environment env,
            ObjectProvider<NaverSearchService> naverSearchServiceProvider,
            ObjectProvider<BraveSearchService> braveSearchServiceProvider,
            @Qualifier("searchIoExecutor") ObjectProvider<java.util.concurrent.ExecutorService> searchIoExecutorProvider,
            ObjectProvider<NightmareBreaker> nightmareBreakerProvider,
            ObjectProvider<FaultMaskingLayerMonitor> faultMaskMonitorProvider,
            ObjectProvider<FailurePatternOrchestrator> failurePatternOrchestratorProvider) {
        return new HybridWebSearchEmptyFallbackAspect(
                env,
                naverSearchServiceProvider,
                braveSearchServiceProvider,
                searchIoExecutorProvider,
                nightmareBreakerProvider,
                faultMaskMonitorProvider,
                failurePatternOrchestratorProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.web.failsoft.ratelimit-backoff.enabled", havingValue = "true", matchIfMissing = true)
    public RateLimitBackoffCoordinator rateLimitBackoffCoordinator(Environment env) {
        return new RateLimitBackoffCoordinator(env);
    }

    @Bean
    @ConditionalOnClass(WebClient.class)
    @ConditionalOnProperty(name = "nova.orch.web.failsoft.ratelimit-backoff.enabled", havingValue = "true", matchIfMissing = true)
    public ProviderRateLimitBackoffAspect providerRateLimitBackoffAspect(
            RateLimitBackoffCoordinator backoffCoordinator,
            ObjectProvider<BraveRateLimitState> braveRateLimitStateProvider) {
        return new ProviderRateLimitBackoffAspect(backoffCoordinator, braveRateLimitStateProvider.getIfAvailable());
    }
}
