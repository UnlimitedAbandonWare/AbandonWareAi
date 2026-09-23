package com.example.lms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DevProfileActivationConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void devProfileLoadsWithoutLegacyMetadataAndAppliesItsOverrides() {
        contextRunner
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("probe.search.enabled", Boolean.class))
                            .isTrue();
                    assertThat(context.getEnvironment().getProperty("retrieval.routing.thresholds.topKComplex"))
                            .isEqualTo("20");
                });
    }
}
