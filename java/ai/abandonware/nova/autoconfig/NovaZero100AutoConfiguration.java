package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.config.Zero100EngineProperties;
import ai.abandonware.nova.orch.aop.Zero100SessionAspect;
import ai.abandonware.nova.orch.aop.Zero100WebTimeboxAspect;
import ai.abandonware.nova.orch.zero100.Zero100SessionRegistry;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.service.ChatService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.concurrent.ExecutorService;

@AutoConfiguration(afterName = {
        "ai.abandonware.nova.autoconfig.NovaOrchestrationAutoConfiguration",
        "ai.abandonware.nova.autoconfig.NovaOpsStabilizationAutoConfiguration"
})
@EnableConfigurationProperties({ Zero100EngineProperties.class })
@ConditionalOnProperty(name = "nova.orch.zero100.engine-enabled", havingValue = "true", matchIfMissing = true)
public class NovaZero100AutoConfiguration {

    @Bean
    public Zero100SessionRegistry zero100SessionRegistry(Zero100EngineProperties props) {
        return new Zero100SessionRegistry(props);
    }

    @Bean
    @ConditionalOnClass(ChatService.class)
    public Zero100SessionAspect zero100SessionAspect(
            Zero100EngineProperties props,
            Zero100SessionRegistry registry,
            Environment env) {
        return new Zero100SessionAspect(props, registry, env);
    }

    @Bean
    @ConditionalOnClass(HybridWebSearchProvider.class)
    public Zero100WebTimeboxAspect zero100WebTimeboxAspect(
            Zero100EngineProperties props,
            Zero100SessionRegistry registry,
            @Qualifier("searchIoExecutor") ObjectProvider<ExecutorService> searchIoExecutorProvider) {
        return new Zero100WebTimeboxAspect(props, registry, searchIoExecutorProvider);
    }
}
