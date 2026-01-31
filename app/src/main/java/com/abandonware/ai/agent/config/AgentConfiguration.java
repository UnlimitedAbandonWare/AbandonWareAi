package com.abandonware.ai.agent.config;

import com.abandonware.ai.agent.consent.BasicConsentService;
import com.abandonware.ai.agent.consent.ConsentCardRenderer;
import com.abandonware.ai.agent.consent.ConsentService;
import com.abandonware.ai.agent.job.DurableJobService;
import com.abandonware.ai.agent.job.InMemoryJobQueue;
import com.abandonware.ai.agent.job.JobQueue;
import com.abandonware.ai.agent.orchestrator.FlowDefinitionLoader;
import com.abandonware.ai.agent.orchestrator.Orchestrator;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.impl.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;
import com.abandonware.ai.agent.contract.SchemaRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.config.AgentConfiguration
 * Role: config
 * Dependencies: com.abandonware.ai.agent.consent.BasicConsentService, com.abandonware.ai.agent.consent.ConsentCardRenderer, com.abandonware.ai.agent.consent.ConsentService, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.config.AgentConfiguration
role: config
*/
public class AgentConfiguration {

    @Bean
    public ConsentService consentService() {
        return new BasicConsentService();
    }

    @Bean
    public ConsentCardRenderer consentCardRenderer() {
        return new ConsentCardRenderer();
    }

    @Bean
    @ConditionalOnMissingBean(JobQueue.class)
    public JobQueue jobQueue() {
        return new InMemoryJobQueue();
    }

    @Bean
    public DurableJobService durableJobService(JobQueue queue) {
        return new DurableJobService(queue);
    }

    @Bean
    public ToolRegistry toolRegistry(KakaoPushTool kakao, N8nNotifyTool n8n,
                                     PlacesSearchTool places, GeoReverseTool geo,
                                     RagRetrieveTool rag, WebSearchTool web,
                                     JobsEnqueueTool jobs) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(kakao);
        registry.register(n8n);
        registry.register(places);
        registry.register(geo);
        registry.register(rag);
        registry.register(web);
        registry.register(jobs);
        return registry;
    }


    @Bean
    public com.abandonware.ai.agent.policy.BudgetGuard budgetGuard() {
        return new com.abandonware.ai.agent.policy.BudgetGuard();
    }

    @Bean
    public com.abandonware.ai.agent.policy.ToolPolicyEnforcer toolPolicyEnforcer() {
        return new com.abandonware.ai.agent.policy.ToolPolicyEnforcer();
    }

    @Bean
    public FlowDefinitionLoader flowDefinitionLoader() {
        return new FlowDefinitionLoader();
    }

    /**
     * Constructs the orchestrator.  The orchestrator coordinates flow
     * execution, invokes tools and enforces consent/budget policies.  A
     * number of supporting collaborators are injected via Spring.  Note that
     * the {@link Orchestrator} constructor accepts a {@link MeterRegistry} in
     * order to publish metrics.
     *
     * @param registry the tool registry
     * @param consentService consent service used to enforce scope grants
     * @param loader flow definition loader
     * @param meterRegistry the Micrometer registry
     * @return a new orchestrator instance
     */
    
@Bean
public Orchestrator orchestrator(ToolRegistry registry,
                                 ConsentService consentService,
                                 FlowDefinitionLoader loader,
                                 MeterRegistry meterRegistry,
                                 SchemaRegistry schemaRegistry) {
    return new Orchestrator(
        registry,
        consentService,
        loader,
        new com.abandonware.ai.agent.observability.AgentTracer(),
        new com.abandonware.ai.agent.observability.AgentMetrics(meterRegistry),
        new com.abandonware.ai.agent.policy.BudgetGuard(),
        new com.abandonware.ai.agent.policy.ToolPolicyEnforcer(),
        schemaRegistry
    );
}

}