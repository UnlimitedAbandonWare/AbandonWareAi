package com.example.lms.ensemble;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.guard.KeyResolver;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.prompt.PromptBuilder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EnsembleComponentWiringContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnsembleScanConfig.class)
            .withPropertyValues("ensemble.sampling.enabled=false");

    @Test
    void ensemblePackageScanRegistersRuntimeServices() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(StochasticParamSampler.class);
            assertThat(context).hasSingleBean(DiverseSamplingOrchestrator.class);
            assertThat(context).hasSingleBean(EnsembleJudgeService.class);
            assertThat(context).hasSingleBean(EnsembleFinalAnswerService.class);
            assertThat(context).hasSingleBean(ApiTriadRoutePreflight.class);
        });
    }

    @Configuration
    @ComponentScan(basePackageClasses = StochasticParamSampler.class)
    static class EnsembleScanConfig {

        @Bean
        DynamicChatModelFactory dynamicChatModelFactory() {
            return new StubFactory();
        }

        @Bean
        PromptBuilder promptBuilder() {
            return (contexts, question) -> "prompt";
        }

        @Bean
        FinalSigmoidGate finalSigmoidGate() {
            return new FinalSigmoidGate(3.0d, 2.0d, 1.5d, 0.5d, 0.70d, "standard", "soft");
        }

        @Bean
        LlmRouterProperties llmRouterProperties() {
            return new LlmRouterProperties();
        }

        @Bean
        HybridLlmGatewayProbeService hybridLlmGatewayProbeService() {
            return mock(HybridLlmGatewayProbeService.class);
        }

        @Bean
        KeyResolver keyResolver() {
            return mock(KeyResolver.class);
        }
    }

    private static final class StubFactory extends DynamicChatModelFactory {
        private StubFactory() {
            super(null, null);
        }

        @Override
        public ChatModel lcWithTimeout(String modelName, Double temperature, Double topP,
                            Double frequencyPenalty, Double presencePenalty, Integer maxTokens,
                            int timeoutSeconds) {
            return new StubModel();
        }
    }

    private record StubModel() implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("stub"))
                    .build();
        }
    }
}
