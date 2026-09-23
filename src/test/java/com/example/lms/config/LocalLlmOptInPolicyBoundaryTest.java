package com.example.lms.config;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LocalLlmOptInPolicyBoundaryTest {
    @TempDir Path fixture;
    private static final List<String> FILES = List.of("application.properties", "application.yml",
            "application-dev.yml", "application-local.yml", "application-llm.yaml");
    @AfterEach void clearTrace() { TraceStore.clear(); }

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({"authored,absent", "authored,enabled_only", "authored,autostart_only", "authored,both",
            "proposal,absent", "proposal,enabled_only", "proposal,autostart_only", "proposal,both"})
    void configDataAndActualManagerBindingPreserveExplicitOverrides(String variant, String inputs) throws Exception {
        for (String name : FILES) {
            byte[] original = Files.readAllBytes(Path.of("main/resources", name));
            byte[] selected = original;
            if (name.equals("application-llm.yaml")) {
                String text = new String(original, StandardCharsets.UTF_8);
                String enabled = "enabled: ${LOCAL_LLM_ENABLED:true}";
                String autostart = "autostart: ${LOCAL_LLM_AUTOSTART:true}";
                assertEquals(1, occurrences(text, enabled), "exact enabled preimage");
                assertEquals(1, occurrences(text, autostart), "exact autostart preimage");
                if (variant.equals("proposal")) {
                    String replacement = text.replace(enabled, "enabled: ${LOCAL_LLM_ENABLED:false}")
                            .replace(autostart, "autostart: ${LOCAL_LLM_AUTOSTART:false}");
                    String reversed = replacement.replace("enabled: ${LOCAL_LLM_ENABLED:false}", enabled)
                            .replace("autostart: ${LOCAL_LLM_AUTOSTART:false}", autostart);
                    assertTrue(text.equals(reversed), "proposal changed unrelated bytes");
                    selected = replacement.getBytes(StandardCharsets.UTF_8);
                }
            }
            Files.write(fixture.resolve(name), selected);
            if (!name.equals("application-llm.yaml") || variant.equals("authored"))
                assertArrayEquals(original, Files.readAllBytes(fixture.resolve(name)), "exact fixture resource bytes");
        }
        // Single-flag cases explicitly deny the other flag, proving both overrides survive.
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("APP_CONFIG_IMPORT", "");
        if (!inputs.equals("absent")) {
            environment.put("LOCAL_LLM_ENABLED", inputs.equals("autostart_only") ? "false" : "true");
            environment.put("LOCAL_LLM_AUTOSTART", inputs.equals("enabled_only") ? "false" : "true");
        }
        boolean expectedEnabled = inputs.equals("absent") ? variant.equals("authored") : !inputs.equals("autostart_only");
        boolean expectedAutostart = inputs.equals("absent") ? variant.equals("authored") : !inputs.equals("enabled_only");
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.addFirst(new SystemEnvironmentPropertySource("optInSyntheticEnvironment", environment));
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .withPropertyValues("spring.profiles.active=llm", "spring.config.location=" + fixture.toUri())
                .run(context -> {
                    assertTrue(context.getStartupFailure() == null, "isolated ConfigData startup failed");
                    var env = context.getEnvironment();
                    env.setConversionService(new ApplicationConversionService());
                    assertEquals(expectedEnabled, env.getProperty("local-llm.enabled", Boolean.class));
                    assertEquals(expectedAutostart, env.getProperty("local-llm.autostart", Boolean.class));
                    assertNull(env.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME));
                    assertNull(env.getPropertySources().get(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME));
                    var runtime = mock(LocalLlmProcessManager.StartupRuntime.class);
                    var manager = new LocalLlmProcessManager(env, runtime);
                    ReflectionTestUtils.invokeMethod(manager, "loadFromEnvironment");
                    assertEquals(expectedEnabled, ReflectionTestUtils.getField(manager, "enabled"));
                    assertEquals(expectedAutostart, ReflectionTestUtils.getField(manager, "autostart"));
                    verifyNoInteractions(runtime);
                    System.out.println("LLM_OPTIN_BOUNDARY variant=" + variant + " inputs=" + inputs
                            + " bindingMatches=true unrelatedBytesPreserved=true lifecycleInvoked=false runtimeInteractions=0");
                });
    }
    private static int occurrences(String text, String fragment) {
        return (text.length() - text.replace(fragment, "").length()) / fragment.length();
    }
}

