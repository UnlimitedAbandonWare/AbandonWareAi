package com.abandonware.ai.agent.orchestrator.subagent;

import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubagentProviderConfigurationTest {

    @Test
    void configuredGlmModelAndRoleEffortReachTheResponsesAdapter() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.subagent.glm.model", "zai/glm-5.3-flash")
                .withProperty("agent.subagent.glm.reasoning-effort", "low")
                .withProperty("agent.subagent.glm.review-model", "zai/glm-5.3")
                .withProperty("agent.subagent.glm.review-reasoning-effort", "medium");
        java.util.List<java.util.List<?>> arguments = new java.util.ArrayList<>();
        try (var ignored = org.mockito.Mockito.mockConstruction(
                ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel.class,
                (model, context) -> {
                    arguments.add(context.arguments());
                    when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                            .aiMessage(AiMessage.from("bounded answer")).build());
                })) {
            var invoke = SubagentProviderConfiguration.class.getDeclaredMethod("invokeGlmResponses",
                    org.springframework.core.env.Environment.class, String.class, SubagentTask.class,
                    long.class, ModelRuntimeHealthTracker.class);
            invoke.setAccessible(true);
            for (String role : java.util.List.of("analysis", "review")) {
                assertThat(invoke.invoke(null, environment, "credential-sentinel",
                        new SubagentTask("test", 0, "test", role, "public fixture", Map.of()),
                        9_000L, new ModelRuntimeHealthTracker())).isEqualTo("bounded answer");
            }
        }
        assertThat(arguments).hasSize(2);
        assertThat(arguments.get(0).get(0)).isEqualTo("https://ai-gateway.vercel.sh/v1");
        assertThat(arguments.get(0).get(2)).isEqualTo("zai/glm-5.3-flash");
        assertThat(arguments.get(0).get(7)).isEqualTo("low");
        assertThat(arguments.get(1).get(2)).isEqualTo("zai/glm-5.3");
        assertThat(arguments.get(1).get(7)).isEqualTo("medium");
    }

    @Test
    void glmConfigCannotSelectASubscriptionCliModelOrAnArbitraryEndpoint() {
        for (String model : java.util.List.of("swe-2-medium", "https://other.invalid/model", "${MODEL}")) {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                    SubagentProviderConfiguration.glmModelSelection(new MockEnvironment()
                            .withProperty("agent.subagent.glm.model", model), null)))
                    .isInstanceOf(com.example.lms.llm.gateway.LlmGatewayException.class);
        }
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                SubagentProviderConfiguration.glmModelSelection(new MockEnvironment()
                        .withProperty("agent.subagent.glm.reasoning-effort", "unbounded"), null)))
                .isInstanceOf(com.example.lms.llm.gateway.LlmGatewayException.class);
    }

    @Test
    void externalReadinessDominatesKeyReadsAndWireAvailability() {
        AtomicInteger keyReads = new AtomicInteger();
        AtomicInteger wireCalls = new AtomicInteger();
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmActivationStateMachine activation = new GlmActivationStateMachine(
                () -> true, () -> false, () -> {
                    keyReads.incrementAndGet();
                    return true;
                }, metrics);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.subagent.glm.enabled", "true")
                .withProperty("GLM_EXTERNAL_READY", "false");
        SubagentProvider provider = SubagentProviderConfiguration.glmProvider(
                environment,
                () -> {
                    keyReads.incrementAndGet();
                    return "credential-sentinel";
                },
                (apiKey, task, timeoutMs) -> {
                    wireCalls.incrementAndGet();
                    return "unexpected";
                },
                activation);

        GlmActivationStateMachine.Snapshot snapshot = activation.refresh("closed");

        assertThat(snapshot.state())
                .isEqualTo(GlmActivationStateMachine.State.WAITING_FOR_CREDIT_CONFIRMATION);
        assertThat(provider.availability())
                .extracting(SubagentProvider.Availability::available,
                        SubagentProvider.Availability::reasonCode)
                .containsExactly(false, "external_ready_not_confirmed");
        assertThat(keyReads).hasValue(0);
        assertThat(wireCalls).hasValue(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void subSecondFactoryBudgetRoundsUpToOneSecondWithoutChangingTheOuterDeadlineContract()
            throws Exception {
        DynamicChatModelFactory modelFactory = mock(DynamicChatModelFactory.class);
        ChatModel chatModel = mock(ChatModel.class);
        ObjectProvider<DynamicChatModelFactory> modelFactoryProvider = mock(ObjectProvider.class);
        when(modelFactoryProvider.getIfAvailable()).thenReturn(modelFactory);
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("bounded answer"))
                .build());
        when(modelFactory.lcWithTimeout(
                "openai-characterization", 0.0d, null, null, null, 512, 1, 0))
                .thenReturn(chatModel);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("llmrouter.models.openai-balanced.enabled", "true")
                .withProperty("llmrouter.models.openai-balanced.name", "openai-characterization");
        when(modelFactory.canServeQuietly("openai-characterization")).thenReturn(true);
        SubagentProvider provider = new SubagentProviderConfiguration()
                .openAiSubagentProvider(environment, modelFactoryProvider);

        assertThat(provider.availability().available()).isTrue();
        assertThat(provider.execute(
                new SubagentTask("c", 0, "t", "analysis", "bounded", Map.of()), 999L))
                .isEqualTo("bounded answer");
        verify(modelFactory).lcWithTimeout(
                "openai-characterization", 0.0d, null, null, null, 512, 1, 0);
    }

    @Test
    void limitedSpringContextsKeepOptionalFactoryProvidersDisabledInsteadOfFailingBoot() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agent.subagent.glm.enabled", "false");
        properties.put("llmrouter.models.openai-balanced.enabled", "true");
        properties.put("llmrouter.models.light.enabled", "true");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("limited-subagent-test", properties));
            ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
            context.getBeanFactory().registerSingleton("modelRuntimeHealthTracker", tracker);
            context.register(SubagentProviderConfiguration.class);
            context.refresh();

            Map<String, SubagentProvider> providers = context.getBeansOfType(SubagentProvider.class);
            assertThat(providers.get("openAiSubagentProvider").availability())
                    .extracting(SubagentProvider.Availability::available,
                            SubagentProvider.Availability::reasonCode)
                    .containsExactly(false, "provider_unavailable");
            assertThat(providers.get("ollamaSubagentProvider").availability())
                    .extracting(SubagentProvider.Availability::available,
                            SubagentProvider.Availability::reasonCode)
                    .containsExactly(false, "provider_unavailable");
            assertThat(context.getBean(SubagentProviderChain.class)).isNotNull();
            assertThat(context.getBean(GlmAgentCore.class).metrics())
                    .isSameAs(context.getBean(SubagentAgentMetrics.class));
            assertThat(context.getBean(ModelRuntimeHealthTracker.class)).isSameAs(tracker);
            assertThat(context.getBean(GlmAgentCore.class).status())
                    .containsEntry("requestTimelineEnabled", true);
        }
    }

    @Test
    void springWiringRegistersExactProviderOrderAndUsesTheExistingFastOllamaModel() {
        DynamicChatModelFactory modelFactory = mock(DynamicChatModelFactory.class);
        when(modelFactory.canServeQuietly(anyString())).thenReturn(true);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agent.subagent.glm.enabled", "false");
        properties.put("llmrouter.models.openai-balanced.enabled", "true");
        properties.put("llmrouter.models.openai-balanced.name", "openai-test-model");
        properties.put("llmrouter.models.light.enabled", "true");
        properties.put("llmrouter.models.light.name", "must-not-select-light-route");
        properties.put("llm.fast.model", "ollama-fast-test-model");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("subagent-test", properties));
            context.getBeanFactory().registerSingleton(
                    "modelRuntimeHealthTracker", new ModelRuntimeHealthTracker());
            context.getBeanFactory().registerSingleton("dynamicChatModelFactory", modelFactory);
            context.registerBean(LlmGatewayFailureClassifier.class, LlmGatewayFailureClassifier::new);
            context.register(SubagentProviderConfiguration.class);
            context.refresh();

            Map<String, SubagentProvider> providers = context.getBeansOfType(SubagentProvider.class);
            assertThat(providers).containsKeys(
                    "glmSubagentProvider", "openAiSubagentProvider", "ollamaSubagentProvider");
            assertThat(providers.values())
                    .extracting(SubagentProvider::id, SubagentProvider::order)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("vercel-glm", 10),
                            org.assertj.core.groups.Tuple.tuple("openai", 20),
                            org.assertj.core.groups.Tuple.tuple("ollama", 30));
            assertThat(context.getBean(SubagentProviderChain.class)).isNotNull();

            assertThat(providers.get("glmSubagentProvider").availability().available()).isFalse();
            assertThat(providers.get("openAiSubagentProvider").availability().available()).isTrue();
            assertThat(providers.get("ollamaSubagentProvider").availability().available()).isTrue();
            verify(modelFactory).canServeQuietly("openai-test-model");
            verify(modelFactory).canServeQuietly("ollama-fast-test-model");
            verify(modelFactory, never()).canServeQuietly("must-not-select-light-route");
        }
    }
}
