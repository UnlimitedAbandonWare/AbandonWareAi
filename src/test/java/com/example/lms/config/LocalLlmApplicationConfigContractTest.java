package com.example.lms.config;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class LocalLlmApplicationConfigContractTest {

    private static final String WINDOWS_FALLBACK_COMMAND =
            "start \"Ollama 127.0.0.1:11435\" cmd.exe /k \"set OLLAMA_HOST=127.0.0.1:11435&& ollama serve\"";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void activeLocalConfigResolvesThe11435AutostartContract() {
        contextRunner
                .withPropertyValues("LOCAL_LLM_START_COMMAND=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("local-llm.start-command")).isBlank();
                    assertThat(context.getEnvironment().getProperty("local-llm.ollama-host"))
                            .isEqualTo("127.0.0.1:11435");
                    assertThat(context.getEnvironment().getProperty("local-llm.enabled", Boolean.class)).isTrue();
                    assertThat(context.getEnvironment().getProperty("local-llm.autostart", Boolean.class)).isTrue();
                    assertThat(context.getEnvironment().getProperty("local-llm.warmup.enabled", Boolean.class))
                            .isTrue();
                    assertThat(context.getEnvironment().getProperty("local-llm.warmup.pull", Boolean.class))
                            .isFalse();
                    assertThat(context.getEnvironment().getProperty("local-llm.fail-fast", Boolean.class)).isTrue();
                    assertThat(context.getEnvironment().getProperty("local-llm.health-check-url"))
                            .isEqualTo("http://127.0.0.1:11435/api/version");
                    assertThat(context.getEnvironment().getProperty("llm.base-url"))
                            .isEqualTo("http://127.0.0.1:11435/v1");
                    assertThat(context.getEnvironment().getProperty("llm.fast.base-url"))
                            .isEqualTo("http://127.0.0.1:11435/v1");
                    assertThat(context.getEnvironment().getProperty("embedding.base-url"))
                            .isEqualTo("http://127.0.0.1:11435/api/embed");

                    LocalLlmProcessManager manager = managerFrom(context.getEnvironment());
                    String command = ReflectionTestUtils.invokeMethod(manager, "effectiveStartCommand");

                    assertThat(command).isEqualTo(WINDOWS_FALLBACK_COMMAND);
                });
    }

    @Test
    void explicitLocalLlmProfileResolvesThe11435AutostartContract() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local-llm",
                        "LOCAL_LLM_START_COMMAND=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("local-llm.enabled", Boolean.class)).isTrue();
                    assertThat(context.getEnvironment().getProperty("local-llm.autostart", Boolean.class)).isTrue();
                    assertThat(context.getEnvironment().getProperty("local-llm.ollama-host"))
                            .isEqualTo("127.0.0.1:11435");
                    assertThat(context.getEnvironment().getProperty("local-llm.health-check-url"))
                            .isEqualTo("http://127.0.0.1:11435/api/version");
                    assertThat(context.getEnvironment().getProperty("local-llm.fail-fast", Boolean.class)).isTrue();
                    assertThat(context.getEnvironment().getProperty("llm.base-url"))
                            .isEqualTo("http://127.0.0.1:11435/v1");
                    assertThat(context.getEnvironment().getProperty("llm.ollama.base-url"))
                            .isEqualTo("http://127.0.0.1:11435/v1");
                    assertThat(context.getEnvironment().getProperty("llm.models.gemma4_26b.endpoint"))
                            .isEqualTo("http://127.0.0.1:11435/v1");
                    assertThat(context.getEnvironment().getProperty("llm.models.qwen3_30b.endpoint"))
                            .isEqualTo("http://127.0.0.1:11435/v1");
                    assertThat(context.getEnvironment().getProperty("llm.models.qwen3_coder_30b.endpoint"))
                            .isEqualTo("http://127.0.0.1:11435/v1");
                    assertThat(context.getEnvironment().getProperty("llm.routing.model-to-endpoint.gemma4_26b"))
                            .isEqualTo("http://127.0.0.1:11435/v1");
                    assertThat(context.getEnvironment().getProperty("llm.routing.model-to-endpoint.qwen3_30b"))
                            .isEqualTo("http://127.0.0.1:11435/v1");
                    assertThat(context.getEnvironment().getProperty("llm.routing.model-to-endpoint.qwen3_coder_30b"))
                            .isEqualTo("http://127.0.0.1:11435/v1");
                    assertThat(context.getEnvironment().getProperty("embedding.base-url"))
                            .isEqualTo("http://127.0.0.1:11435/api/embed");
                });
    }

    @Test
    void activeConfigPreservesAnExplicitStartCommandOverride() {
        contextRunner
                .withPropertyValues("LOCAL_LLM_START_COMMAND=operator-managed-start")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("local-llm.start-command"))
                            .isEqualTo("operator-managed-start");

                    LocalLlmProcessManager manager = managerFrom(context.getEnvironment());
                    String command = ReflectionTestUtils.invokeMethod(manager, "effectiveStartCommand");

                    assertThat(command).isEqualTo("operator-managed-start");
                });
    }

    @Test
    void activeConfigKeepsEnabledButAutostartOffInert() {
        contextRunner
                .withPropertyValues(
                        "LOCAL_LLM_ENABLED=true",
                        "LOCAL_LLM_AUTOSTART=false",
                        "LOCAL_LLM_START_COMMAND=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LocalLlmProcessManager manager = managerFrom(context.getEnvironment());

                    manager.postProcessBeanFactory(new DefaultListableBeanFactory());

                    assertThat(manager.isRunning()).isTrue();
                    assertThat(TraceStore.get("localLlm.startup.enabled")).isEqualTo(Boolean.TRUE);
                    assertThat(TraceStore.get("localLlm.startup.autostart")).isEqualTo(Boolean.FALSE);
                    assertThat(TraceStore.get("localLlm.startup.status")).isEqualTo("skipped");
                    assertThat(TraceStore.get("localLlm.startup.reason"))
                            .isEqualTo("disabled_or_autostart_false");
                });
    }

    private static LocalLlmProcessManager managerFrom(Environment environment) {
        ((ConfigurableEnvironment) environment)
                .setConversionService(new ApplicationConversionService());
        LocalLlmProcessManager manager = new LocalLlmProcessManager(environment);
        ReflectionTestUtils.invokeMethod(manager, "loadFromEnvironment");
        return manager;
    }
}
