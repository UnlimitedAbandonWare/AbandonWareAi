package com.example.lms.scheduler;

import com.example.lms.repository.VectorQuarantineDlqRepository;
import com.example.lms.service.vector.VectorQuarantineDlqService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VectorDlqRedriveSchedulerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void enabledDlqCannotAppearHealthyWhenRedriveIsDisabled() {
        contextRunner
                .withPropertyValues(
                        "vector.dlq.enabled=true",
                        "vector.dlq.redrive.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VectorQuarantineDlqService.class);
                    assertThat(context).hasSingleBean(VectorDlqRedriveScheduler.class);

                    Map<String, Object> health = context.getBean(VectorQuarantineDlqService.class).stats();
                    assertThat(health)
                            .containsEntry("enabled", true)
                            .containsEntry("redriveEnabled", false)
                            .containsEntry("disabledReason", "redrive_disabled");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(VectorDlqRedriveScheduler.class)
    static class TestConfiguration {

        @Bean
        VectorQuarantineDlqService vectorQuarantineDlqService(
                VectorQuarantineDlqRepository repository,
                ObjectMapper objectMapper,
                EmbeddingModel embeddingModel,
                EmbeddingStore<TextSegment> embeddingStore,
                PlatformTransactionManager transactionManager) {
            return new VectorQuarantineDlqService(
                    repository,
                    objectMapper,
                    embeddingModel,
                    embeddingStore,
                    transactionManager);
        }

        @Bean
        VectorQuarantineDlqRepository vectorQuarantineDlqRepository() {
            return mock(VectorQuarantineDlqRepository.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return mock(EmbeddingModel.class);
        }

        @Bean(name = "federatedEmbeddingStore")
        EmbeddingStore<TextSegment> federatedEmbeddingStore() {
            return mockEmbeddingStore();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static EmbeddingStore<TextSegment> mockEmbeddingStore() {
        return mock(EmbeddingStore.class);
    }
}
