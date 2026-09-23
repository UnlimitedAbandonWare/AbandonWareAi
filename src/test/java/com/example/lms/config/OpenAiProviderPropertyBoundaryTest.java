package com.example.lms.config;

import okhttp3.OkHttpClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import retrofit2.Retrofit;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiProviderPropertyBoundaryTest {
    @ParameterizedTest(name = "global-provider:{0}")
    @ValueSource(strings = {"absent", "local", "openai"})
    void globalProviderConditionControlsLocalRetrofitConstruction(String provider) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                })
                .withUserConfiguration(OpenAiConfig.class)
                .withPropertyValues("openai.api.key=", "local-llm.enabled=true", "local-llm.base-url=http://127.0.0.1:1/v1");
        if (!"absent".equals(provider)) runner = runner.withPropertyValues("llm.provider=" + provider);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("OPENAI_API_KEY")).isNull();
            assertThat(context.getEnvironment().getProperty("llm.provider"))
                    .isEqualTo("absent".equals(provider) ? null : provider);
            if ("openai".equals(provider)) {
                assertThat(context).hasSingleBean(OpenAiConfig.class);
                assertThat(context).hasBean("openAiService");
                Retrofit service = (Retrofit) context.getBean("openAiService");
                assertThat(service.baseUrl().toString()).isEqualTo("http://127.0.0.1:1/v1/");
                OkHttpClient client = (OkHttpClient) service.callFactory();
                assertThat(client.dispatcher().runningCallsCount()).isZero();
                assertThat(client.dispatcher().queuedCallsCount()).isZero();
                assertThat(client.connectionPool().connectionCount()).isZero();
            } else {
                assertThat(context).doesNotHaveBean(OpenAiConfig.class);
                assertThat(context).doesNotHaveBean("openAiService");
            }
            System.out.printf("TBL07_GLOBAL_PROVIDER provider=%s beanPresent=%s explicitConfiguration=true inheritedEnvironmentRemoved=true requestExecutionInvoked=false%n",
                    provider, "openai".equals(provider));
        });
    }
}
