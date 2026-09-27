package com.example.lms.uaw.autolearn;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class UawLearningAgentHandoffWriterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(UawAutolearnProperties.class)
            .withUserConfiguration(UawLearningAgentHandoffWriter.class);

    @Test
    void springUsesTheProductionPropertiesConstructorWhenTestSeamAlsoExists() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(UawLearningAgentHandoffWriter.class);
        });
    }
}
