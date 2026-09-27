package com.example.lms.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Resolve properties without host environment variables or any provider calls.
class LocalGpuEndpointDefaultsTest {
    @ParameterizedTest
    @ValueSource(strings = {"application-llm.yaml", "application-local-llm.yml", "application-desktop-gpu-node.yml"})
    void primaryDefaultsTo3090LaneAndEmbeddingStaysOnAuxiliaryLane(String profile) throws IOException {
        var env = environment(profile, Map.of());
        assertThat(env.getProperty("llm.base-url")).isEqualTo("http://127.0.0.1:11434/v1");
        assertThat(env.getProperty("local-llm.ollama-host")).isEqualTo("127.0.0.1:11434");
        assertThat(env.getProperty("local-llm.health-check-url"))
                .isEqualTo("http://127.0.0.1:11434/api/version");
        assertThat(env.getProperty("embedding.base-url")).isEqualTo("http://127.0.0.1:11435/api/embed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application-llm.yaml", "application-local-llm.yml", "application-desktop-gpu-node.yml"})
    void explicitRoleEndpointsKeepTheirExistingPrecedence(String profile) throws IOException {
        var env = environment(profile, Map.of(
                "LLM_BASE_URL", "http://primary.test:12001/v1",
                "LLM_3090_BASE_URL", "http://primary-role.test:12002/v1",
                "LLM_FAST_BASE_URL", "http://fast.test:12003/v1",
                "LLM_3060_BASE_URL", "http://aux-role.test:12004/v1",
                "EMBED_BASE_URL", "http://embed.test:12005/api/embed"));
        assertThat(env.getProperty("llm.base-url")).isEqualTo("http://primary.test:12001/v1");
        assertThat(env.getProperty("llm.fast.base-url")).isEqualTo("http://fast.test:12003/v1");
        assertThat(env.getProperty("embedding.base-url")).isEqualTo("http://embed.test:12005/api/embed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application-llm.yaml", "application-local-llm.yml", "application-desktop-gpu-node.yml"})
    void roleSpecificPrimaryOverrideStillWorks(String profile) throws IOException {
        var env = environment(profile, Map.of("LLM_3090_BASE_URL", "http://primary-role.test:12002/v1"));
        assertThat(env.getProperty("llm.base-url")).isEqualTo("http://primary-role.test:12002/v1");
    }

    @Test
    void localProfilePrimaryCatalogAndAliasesUseTheSamePrimaryDefault() throws IOException {
        var env = environment("application-local-llm.yml", Map.of());
        for (String model : new String[]{"gemma4_26b", "qwen3_30b", "qwen3_coder_30b"}) {
            assertThat(env.getProperty("llm.models." + model + ".endpoint"))
                    .isEqualTo("http://127.0.0.1:11434/v1");
            assertThat(env.getProperty("llm.routing.model-to-endpoint." + model))
                    .isEqualTo("http://127.0.0.1:11434/v1");
        }
    }

    @Test
    void desktopDefaultsSeparatePrimaryAndAuxiliaryGatewayRoles() throws IOException {
        var env = environment("application-desktop-gpu-node.yml", Map.of());
        assertThat(env.getProperty("awx.gpu-gateway.primary-chat-base-url"))
                .isEqualTo("http://127.0.0.1:11434/v1");
        assertThat(env.getProperty("awx.gpu-gateway.fast-base-url"))
                .isEqualTo("http://127.0.0.1:11435/v1");
        assertThat(env.getProperty("awx.gpu-gateway.embedding-base-url"))
                .isEqualTo("http://127.0.0.1:11435/api/embed");
    }

    @Test
    void desktopGatewayFollowsEffectivePropertiesInsteadOfOverridingThem() throws IOException {
        var env = environment("application-desktop-gpu-node.yml", Map.of(
                "llm.base-url", "http://primary.test:12001/v1",
                "llm.fast.base-url", "http://fast.test:12003/v1",
                "embedding.base-url", "http://embed.test:12005/api/embed"));
        assertThat(env.getProperty("awx.gpu-gateway.primary-chat-base-url"))
                .isEqualTo(env.getProperty("llm.base-url"));
        assertThat(env.getProperty("awx.gpu-gateway.fast-base-url"))
                .isEqualTo(env.getProperty("llm.fast.base-url"));
        assertThat(env.getProperty("awx.gpu-gateway.embedding-base-url"))
                .isEqualTo(env.getProperty("embedding.base-url"));
    }

    private MockEnvironment environment(String profile, Map<String, Object> overrides) throws IOException {
        var env = new MockEnvironment();
        var loader = new YamlPropertySourceLoader();
        for (String file : new String[]{"application-llm.yaml", profile}) {
            for (var source : loader.load(file, new FileSystemResource("main/resources/" + file))) {
                env.getPropertySources().addFirst(source);
            }
        }
        env.getPropertySources().addFirst(new MapPropertySource("synthetic-overrides", overrides));
        return env;
    }
}
