package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.boot.embedding.MatryoshkaEmbeddingModelPostProcessor;
import ai.abandonware.nova.boot.exec.CancelShieldExecutorServicePostProcessor;
import ai.abandonware.nova.boot.reactor.NovaReactorDroppedErrorHook;
import ai.abandonware.nova.orch.aop.HybridWebSearchEmptyFallbackAspect;
import ai.abandonware.nova.orch.aop.ProviderRateLimitBackoffAspect;
import ai.abandonware.nova.orch.web.brave.BraveRateLimitState;
import ai.abandonware.nova.orch.web.RateLimitBackoffCoordinator;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;

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
    @ConditionalOnClass(name = "java.util.concurrent.ExecutorService")
    @ConditionalOnProperty(name = "nova.orch.interrupt-hygiene.cancel-shield.enabled", havingValue = "true", matchIfMissing = true)
    public CancelShieldExecutorServicePostProcessor cancelShieldExecutorServicePostProcessor(
            Environment env,
            ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        return new CancelShieldExecutorServicePostProcessor(env, debugEventStoreProvider);
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
    public MatryoshkaEmbeddingModelPostProcessor matryoshkaEmbeddingModelPostProcessor(Environment env) {
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
            ObjectProvider<FaultMaskingLayerMonitor> faultMaskMonitorProvider) {
        return new HybridWebSearchEmptyFallbackAspect(
                env,
                naverSearchServiceProvider,
                braveSearchServiceProvider,
                searchIoExecutorProvider,
                nightmareBreakerProvider,
                faultMaskMonitorProvider);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.web.failsoft.ratelimit-backoff.enabled", havingValue = "true", matchIfMissing = true)
    public RateLimitBackoffCoordinator rateLimitBackoffCoordinator(Environment env) {
        return new RateLimitBackoffCoordinator(env);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.web.failsoft.ratelimit-backoff.enabled", havingValue = "true", matchIfMissing = true)
    public ProviderRateLimitBackoffAspect providerRateLimitBackoffAspect(
            RateLimitBackoffCoordinator backoffCoordinator,
            BraveRateLimitState braveRateLimitState) {
        return new ProviderRateLimitBackoffAspect(backoffCoordinator, braveRateLimitState);
    }
}
