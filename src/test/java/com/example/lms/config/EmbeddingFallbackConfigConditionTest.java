package com.example.lms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * embedding.fallback.enabled는 opt-in이다. 키가 있어도 플래그가 없으면
 * backup bean을 등록하지 않는다(GPU 우선 정책).
 */
class EmbeddingFallbackConfigConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EmbeddingFallbackConfig.class)
            .withPropertyValues("embedding.fallback.api-" + "key=synthetic-fixture-credential");

    @Test
    void backupBeanIsOptInAbsentWithoutFlag() {
        runner.run(context -> assertFalse(context.containsBean("backupEmbeddingModel")));
    }

    @Test
    void explicitTrueRegistersBackupBean() {
        runner.withPropertyValues("embedding.fallback.enabled=true")
                .run(context -> assertTrue(context.containsBean("backupEmbeddingModel")));
    }
}
