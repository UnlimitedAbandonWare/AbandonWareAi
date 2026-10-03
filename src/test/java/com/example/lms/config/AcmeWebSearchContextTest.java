package com.example.lms.config;
import com.abandonware.ai.agent.integrations.*;
import com.abandonware.ai.agent.tool.*;
import com.example.lms.routing.ApiRoutingPolicySnapshot;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.service.NaverSearchService;
import com.example.lms.infra.upstash.*;
import com.acme.aicore.domain.ports.RankingPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
class AcmeWebSearchContextTest {
 @Test void realAdapterGraphRegistersExactlyOneGatewayAndFlagControlsRegistry() {
  for(String enabled:new String[]{"false","true"}) {
   new ApplicationContextRunner().withUserConfiguration(AgentToolOpsConfig.class,AcmeWebSearchAdapterConfig.class,
       ApiRoutingPolicySnapshot.class,ProviderCredentialResolver.class)
    .withPropertyValues("adapter.acme-websearch.enabled=true","agent.tools.web-search.enabled="+enabled)
    .withBean(WebClient.Builder.class,WebClient::builder)
    .withInitializer(ctx->{
     ctx.getBeanFactory().registerSingleton("naverSearchService",mock(NaverSearchService.class));
     ctx.getBeanFactory().registerSingleton("upstashBackedWebCache",mock(UpstashBackedWebCache.class));
     ctx.getBeanFactory().registerSingleton("upstashRateLimiter",mock(UpstashRateLimiter.class));
    })
    .withBean(HybridRetriever.class,()->mock(HybridRetriever.class))
    .withBean(ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService.class,()->mock(ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService.class))
    .withBean(com.example.lms.search.probe.CausalProbeTriggerService.class,()->mock(com.example.lms.search.probe.CausalProbeTriggerService.class))
    .run(ctx->{
     assertThat(ctx).hasNotFailed().hasSingleBean(WebSearchGateway.class).hasSingleBean(RankingPort.class);
     assertThat(ctx.getBean(ToolRegistry.class).get("web.search").isPresent()).isEqualTo(Boolean.parseBoolean(enabled));
     assertThat(ctx.getBean(ToolRegistry.class).duplicateToolIdCounts()).isEmpty();
    });
  }
 }
}
