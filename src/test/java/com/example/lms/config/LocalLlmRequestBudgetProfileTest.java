package com.example.lms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.assertThat;

class LocalLlmRequestBudgetProfileTest {
    @Test void interactiveDeadlineIsLocalProfileScopedAndDoesNotRaiseHeaderMaximum() throws Exception {
        var sources = new YamlPropertySourceLoader().load("local-llm",
                new FileSystemResource("main/resources/application-local-llm.yml"));
        var profile = sources.get(0);
        assertThat(profile.getProperty("spring.config.activate.on-profile")).isEqualTo("local-llm");
        assertThat(profile.getProperty("addons.budget.default-ms")).isEqualTo(30000);
        assertThat(profile.getProperty("public.request-budget.max-time-budget-ms")).isNull();
    }
}
