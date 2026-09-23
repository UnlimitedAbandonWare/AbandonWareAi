package com.example.lms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jBeansPlaceholderTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LangChain4jBeans.class)
            .withPropertyValues("legacy.langchain4j-beans.enabled=true");

    @Test
    void legacyBeansUseConcreteDefaultsWhenLlmBaseUrlIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("defaultChatModel");
            assertThat(context).hasBean("highModel");
            assertThat(context).hasBean("localChatModel");
        });
    }
}
