package ai.abandonware.subagent;

import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.orchestrator.Orchestrator;
import com.abandonware.ai.agent.orchestrator.nodes.CriticNode;
import com.abandonware.ai.agent.orchestrator.recovery.DefaultRecoveryExecutor;
import com.abandonware.ai.agent.orchestrator.recovery.RecoveryPolicy;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentFlowRunner;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentProviderConfiguration;
import com.abandonware.ai.agent.tool.request.ToolContextFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Registers only the explicit {@code subagent.v1} flow in the LMS web application. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import({
        SubagentV1Controller.class,
        ToolContextFactory.class,
        Orchestrator.class,
        DefaultRecoveryExecutor.class,
        CriticNode.class,
        RecoveryPolicy.class,
        SubagentFlowRunner.class,
        SubagentProviderConfiguration.class
})
public class SubagentFlowRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean(ContextBridge.class)
    ContextBridge subagentContextBridge() {
        return new ContextBridge();
    }
}
