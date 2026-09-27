package com.example.lms.service;

import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.repository.ModelInfoRepository;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModelFetchConditionalWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ModelFetchService.class, ModelSyncService.class)
            .withBean(ModelInfoRepository.class, () -> mock(ModelInfoRepository.class))
            .withBean(ModelEntityRepository.class, () -> mock(ModelEntityRepository.class));

    @Test
    void disabledPropertyRemovesScheduledModelFetchBeans() {
        contextRunner
                .withPropertyValues("modelfetch.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ModelFetchService.class);
                    assertThat(context).doesNotHaveBean(ModelSyncService.class);
                });
    }

    @Test
    void enabledPropertyRegistersModelFetchBeans() {
        contextRunner
                .withPropertyValues(
                        "modelfetch.enabled=true",
                        "openai.api.key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModelFetchService.class);
                    assertThat(context).hasSingleBean(ModelSyncService.class);
                });
    }

    @Test
    void legacyModelSyncDoesNotCompeteWithoutSeparateOwnerFlag() {
        ModelEntityRepository repository = mock(ModelEntityRepository.class);

        new ApplicationContextRunner()
                .withUserConfiguration(ModelSyncService.class)
                .withBean(ModelEntityRepository.class, () -> repository)
                .withPropertyValues(
                        "modelfetch.enabled=true",
                        "modelfetch.legacy-sync-enabled=false",
                        "openai.api.key=present-fixture",
                        "openai.api.url=https://models.example.test/v1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelSyncService service = context.getBean(ModelSyncService.class);
                    RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
                    assertThat(restTemplate).isNotNull();
                    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

                    TraceStore.clear();
                    try {
                        service.fetchAndStoreModels();

                        server.verify();
                        verifyNoInteractions(repository);
                        assertThat(TraceStore.get("model.sync.enabled")).isEqualTo(Boolean.FALSE);
                        assertThat(TraceStore.get("model.sync.disabledReason")).isEqualTo("legacy_sync_disabled");
                    } finally {
                        TraceStore.clear();
                    }
                });
    }

    @Test
    void explicitLegacyOwnerFlagAllowsOneMockedSync() {
        ModelEntityRepository repository = mock(ModelEntityRepository.class);

        new ApplicationContextRunner()
                .withUserConfiguration(ModelSyncService.class)
                .withBean(ModelEntityRepository.class, () -> repository)
                .withPropertyValues(
                        "modelfetch.enabled=true",
                        "modelfetch.legacy-sync-enabled=true",
                        "openai.api.key=present-fixture",
                        "openai.api.url=https://models.example.test/v1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelSyncService service = context.getBean(ModelSyncService.class);
                    RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
                    assertThat(restTemplate).isNotNull();
                    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
                    server.expect(requestTo("https://models.example.test/v1/models"))
                            .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

                    service.fetchAndStoreModels();

                    server.verify();
                    verify(repository).findAll();
                });
    }
}
