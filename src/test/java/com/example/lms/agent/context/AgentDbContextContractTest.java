package com.example.lms.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentDbContextContractTest {

    @Test
    void endpointIsAdminOnlyAndFeatureFlagged() throws Exception {
        String security = Files.readString(Path.of("main/java/com/example/lms/config/AppSecurityConfig.java"));
        String autoConfiguration = Files.readString(Path.of("main/java/com/example/lms/agent/context/AgentDbContextAutoConfiguration.java"));
        String imports = Files.readString(Path.of("main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
        String localYaml = Files.readString(Path.of("main/resources/application-local.yml"));

        int dbContextRule = security.indexOf("\"/agent/db-context\"");
        int publicChatRule = security.indexOf("\"/api/chat/**\"");

        assertTrue(dbContextRule > 0);
        assertTrue(publicChatRule > dbContextRule);
        assertTrue(security.contains(".requestMatchers(\"/agent/db-context\", \"/agent/db-context/**\").hasRole(\"ADMIN\")"));
        assertTrue(autoConfiguration.contains("@ConditionalOnProperty(prefix = \"agent.db-context\", name = \"enabled\", havingValue = \"true\")"));
        assertTrue(imports.contains("com.example.lms.agent.context.AgentDbContextAutoConfiguration"));
        assertTrue(localYaml.contains("agent:"));
        assertTrue(localYaml.contains("db-context:"));
        assertTrue(localYaml.contains("enabled: true"));
    }

    @Test
    void providerDoesNotExposeRawMemoryPayloadFields() throws Exception {
        String provider = Files.readString(Path.of("main/java/com/example/lms/agent/context/AgentDbContextProvider.java"));

        assertTrue(provider.contains("contentHash"));
        assertTrue(provider.contains("queryLength"));
        assertTrue(provider.contains("getSourceHash()"));
        assertFalse(provider.contains("map.put(\"content\""));
        assertFalse(provider.contains("map.put(\"corrected\""));
        assertFalse(provider.contains("map.put(\"query\""));
        assertFalse(provider.contains("memory.getContent()"));
        assertFalse(provider.contains("memory.getCorrected()"));
    }

    @Test
    void promptAndMcpHooksUseAgentDbContextWithoutBypassingPromptBuilder() throws Exception {
        String chatWorkflow = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));
        String injector = Files.readString(Path.of("main/java/com/example/lms/agent/context/AgentDbContextPromptInjector.java"));
        String promptTraceAspect = Files.readString(Path.of("main/java/com/example/lms/trace/PromptTraceAspect.java"));
        String toolbox = Files.readString(Path.of("scripts/awx_mcp_toolbox.py"));

        assertTrue(chatWorkflow.contains("promptBuilder.build(ctx)"));
        assertFalse(chatWorkflow.contains("AgentDbContextPromptInjector"));
        assertFalse(chatWorkflow.contains("agentDbContextProvider.snapshot()"));
        assertTrue(promptTraceAspect.contains("PromptBuilder+.build(com.example.lms.prompt.PromptContext)"));
        assertTrue(promptTraceAspect.contains("agentDbContextPromptInjector.enrichContext(ctx)"));
        assertTrue(injector.contains("@ConditionalOnBean(AgentDbContextProvider.class)"));
        assertTrue(injector.contains("learningContextSummary("));
        assertFalse(injector.contains("promptBuilder.build("));
        assertTrue(toolbox.contains("\"agent_db_snapshot\": agent_db_snapshot"));
        assertTrue(toolbox.contains("AWX_ADMIN_TOKEN"));
        assertTrue(toolbox.contains("\"X-Admin-Token\""));
        assertTrue(toolbox.contains("/agent/db-context/"));
    }

    @Test
    void dbContextFailSoftCatchBlocksUseTraceSuppressedHelpers() throws Exception {
        String controller = Files.readString(Path.of("main/java/com/example/lms/agent/context/AgentDbContextController.java"));
        String health = Files.readString(Path.of("main/java/com/example/lms/agent/context/AgentPipelineHealthController.java"));

        assertTrue(controller.contains("traceSuppressed(endpoint, ex);"));
        assertTrue(controller.contains("TraceStore.put(\"agent.dbContext.\" + safe + \".failureClass\", failureClass(ex));"));
        assertTrue(health.contains("traceSuppressedDbContextFailSoft(\"memory\", ex);"));
        assertTrue(health.contains("traceSuppressedDbContextFailSoft(\"strategy\", ex);"));
        assertTrue(health.contains("traceSuppressedDbContextFailSoft(\"ledger\", ex);"));
        assertTrue(health.contains("TraceStore.put(\"agent.dbContext.\" + safeEndpoint + \".errorType\","));
    }

    @Test
    void autoConfigurationRegistersBeansOnlyWhenEnabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentDbContextAutoConfiguration.class))
                .withBean(com.example.lms.repository.TranslationMemoryRepository.class,
                        () -> mock(com.example.lms.repository.TranslationMemoryRepository.class))
                .withBean(com.example.lms.repository.RagOpsLedgerRepository.class,
                        () -> mock(com.example.lms.repository.RagOpsLedgerRepository.class))
                .withBean(com.example.lms.strategy.StrategyPerformanceRepository.class,
                        () -> mock(com.example.lms.strategy.StrategyPerformanceRepository.class));

        runner.run(context -> {
            assertFalse(context.containsBean("agentDbContextProvider"));
            assertFalse(context.containsBean("agentDbContextController"));
        });

        runner.withPropertyValues("agent.db-context.enabled=true")
                .run(context -> {
                    assertTrue(context.containsBean("agentDbContextProvider"));
                    assertTrue(context.containsBean("agentDbContextController"));
                    assertTrue(context.getBean(AgentDbContextProperties.class).isEnabled());
                });
    }
}
