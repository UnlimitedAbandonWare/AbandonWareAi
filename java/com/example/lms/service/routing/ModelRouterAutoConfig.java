package com.example.lms.service.routing;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Auto-configuration that guarantees a default ModelRouter bean exists.
 * It returns whichever ChatModel bean is already present (pass-through).
 */
@Configuration
public class ModelRouterAutoConfig {

    @Bean
    @Primary
    @ConditionalOnMissingBean(ModelRouter.class)
    public ModelRouter modelRouter(ObjectProvider<ChatModel> chatModelProvider) {
        return new ModelRouter() {

            private ChatModel getOrFail() {
                ChatModel cm = chatModelProvider.getIfAvailable();
                if (cm == null) {
                    throw new IllegalStateException("No ChatModel bean available; cannot route");
                }
                return cm;
            }

            @Override
            public ChatModel route(RouteSignal sig) {
                return getOrFail();
            }

            @Override
            public ChatModel route(String intent, String riskLevel, String verbosityHint, Integer targetMaxTokens) {
                return getOrFail();
            }

            @Override
            public ChatModel escalate(RouteSignal sig) {
                return getOrFail();
            }

            @Override
            public String resolveModelName(ChatModel model) {
                return (model != null) ? model.getClass().getSimpleName() : "unknown";
            }
        };
    }
}
