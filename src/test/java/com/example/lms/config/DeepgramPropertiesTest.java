package com.example.lms.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DeepgramPropertiesTest {
    @TempDir Path directory;
    @Configuration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan(basePackageClasses = ConfigValueGuards.class)
    static class ScannedConfiguration { }

    private ApplicationContextRunner runner(String profiles, Map<String, Object> environment) throws Exception {
        // Retain real import/activation declarations, excluding unrelated runtime settings.
        for (String file : List.of("application.yml", "application-deepgram-local.yml")) {
            var sources = new YamlPropertySourceLoader().load("canonical",
                    new FileSystemResource("main/resources/" + file));
            var config = new StringBuilder();
            for (var source : sources) {
                if (!config.isEmpty()) config.append("#---\n");
                for (String name : List.of("spring.profiles.default", "spring.config.activate.on-profile",
                        "spring.config.import", "deepgram.api-key", "deepgram.api-key-secondary")) {
                    Object value = source.getProperty(name);
                    if (value != null) config.append(name).append('=').append(value.toString()
                            .replace("file:.env[.properties]", directory.resolve(".env").toUri() + "[.properties]")
                            .replace("classpath:application-deepgram-local.yml", directory.resolve("deepgram-local.properties").toUri().toString()))
                            .append('\n');
                }
            }
            Files.writeString(directory.resolve(file.equals("application.yml") ? "application.properties"
                    : "deepgram-local.properties"), config);
        }
        return new ApplicationContextRunner().withUserConfiguration(ScannedConfiguration.class)
                .withInitializer(context -> {
                    var propertySources = context.getEnvironment().getPropertySources();
                    propertySources.remove("systemEnvironment");
                    propertySources.remove("systemProperties");
                    propertySources.addFirst(new SystemEnvironmentPropertySource("syntheticEnvironment", environment));
                }).withPropertyValues("spring.profiles.active=" + profiles,
                        "spring.config.location=" + directory.toUri())
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void developmentProfilesLoadDotEnv(String profile) throws Exception {
        Files.writeString(directory.resolve(".env"), "DEEPGRAM_API_KEY=synthetic-file-value\n");
        runner(profile, Map.of()).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("synthetic-file-value", context.getBean(DeepgramProperties.class).getApiKey());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "local,prod", "dev,production", "local,verification"})
    void deploymentAndVerificationNeverImportDotEnvEvenWithDevelopmentProfile(String profiles) throws Exception {
        Files.writeString(directory.resolve(".env"), "DEEPGRAM_API_KEY=synthetic-file-value\n");
        runner(profiles, Map.of()).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(DeepgramProperties.class).isConfigured());
            assertNull(context.getEnvironment().getProperty("DEEPGRAM_API_KEY"));
        });
    }

    @Test
    void productionStartsWithOnlyProcessEnvironmentAndNoDotEnv() throws Exception {
        assertFalse(Files.exists(directory.resolve(".env")));
        runner("prod", Map.of("DEEPGRAM_API_KEY", "synthetic-environment-value")).run(context -> {
            assertNull(context.getStartupFailure());
            var properties = context.getBean(DeepgramProperties.class);
            assertEquals("synthetic-environment-value", properties.getApiKey());
            assertTrue(properties.isConfigured());
        });
    }

    @Test
    void processEnvironmentOverridesLocalDotEnv() throws Exception {
        Files.writeString(directory.resolve(".env"), "DEEPGRAM_API_KEY=synthetic-file-value\n");
        runner("local", Map.of("DEEPGRAM_API_KEY", "synthetic-environment-value")).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("synthetic-environment-value", context.getBean(DeepgramProperties.class).getApiKey());
        });
    }

    @Test
    void explicitImportOverrideCanDisableLocalFileLoading() throws Exception {
        Files.writeString(directory.resolve(".env"), "DEEPGRAM_API_KEY=synthetic-file-value\n");
        runner("local", Map.of("APP_CONFIG_IMPORT", "")).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(DeepgramProperties.class).isConfigured());
        });
    }

    @Test
    void absentDotEnvAllowsLocalStartupWithDisabledProvider() throws Exception {
        runner("local", Map.of()).run(context -> {
            assertNull(context.getStartupFailure());
            var properties = context.getBean(DeepgramProperties.class);
            assertFalse(properties.isConfigured());
            assertEquals("missing_api_key", properties.getDisabledReason());
        });
    }

    @Test
    void placeholdersAreDisabledAndNeverRetainedAsCredentials() {
        for (String placeholder : new String[] { null, "", " ", "dummy", "test", "changeme", "sk-local",
                "${UNRESOLVED}", "your_deepgram_api_key_here" }) {
            var properties = new DeepgramProperties();
            properties.setApiKey(placeholder);
            assertFalse(properties.isConfigured());
            assertEquals("", properties.getApiKey());
        }
    }

    @Test
    void primaryEnvironmentKeyWinsOverSecondary() throws Exception {
        runner("prod", Map.of("DEEPGRAM_API_KEY", "synthetic-primary",
                "DEEPGRAM_API_KEY_SECONDARY", "synthetic-secondary")).run(context -> {
            assertNull(context.getStartupFailure());
            var properties = context.getBean(DeepgramProperties.class);
            assertEquals("synthetic-primary", properties.getApiKey());
            assertFalse(properties.toString().contains("synthetic-secondary"));
        });
    }

    @Test
    void secondaryEnvironmentKeyIsUsedWhenPrimaryIsMissing() throws Exception {
        runner("prod", Map.of("DEEPGRAM_API_KEY_SECONDARY", "synthetic-secondary")).run(context -> {
            assertNull(context.getStartupFailure());
            var properties = context.getBean(DeepgramProperties.class);
            assertTrue(properties.isConfigured());
            assertEquals("synthetic-secondary", properties.getApiKey());
            assertFalse(properties.toString().contains("synthetic-secondary"));
        });
    }

    @Test
    void blankPrimaryUsesSecondaryAndTemplateSecondaryStaysDisabled() throws Exception {
        runner("prod", Map.of("DEEPGRAM_API_KEY", " ",
                "DEEPGRAM_API_KEY_SECONDARY", "synthetic-secondary")).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("synthetic-secondary", context.getBean(DeepgramProperties.class).getApiKey());
        });
        runner("prod", Map.of("DEEPGRAM_API_KEY", "test",
                "DEEPGRAM_API_KEY_SECONDARY", "your_deepgram_api_key_here")).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(DeepgramProperties.class).isConfigured());
        });
    }

    @Test
    void trimsConfiguredKeyAndRedactsObjectRendering() {
        var properties = new DeepgramProperties();
        properties.setApiKey("  synthetic-trim-value  ");
        assertEquals("synthetic-trim-value", properties.getApiKey());
        assertEquals("", properties.getDisabledReason());
        assertFalse(properties.toString().contains("synthetic-trim-value"));
    }
}
