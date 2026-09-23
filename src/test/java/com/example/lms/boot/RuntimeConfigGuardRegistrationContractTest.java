package com.example.lms.boot;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigGuardRegistrationContractTest {

    @Test
    void environmentPostProcessorIsRegisteredInActiveResources() throws Exception {
        String factories = Files.readString(Path.of("main/resources/META-INF/spring.factories"));

        assertTrue(factories.contains("org.springframework.boot.env.EnvironmentPostProcessor"));
        assertTrue(factories.contains("com.example.lms.boot.RuntimeConfigGuard"));
    }

    @Test
    void productionFamiliesForceStrictEnabledEvaluation() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/boot/RuntimeConfigGuard.java"));

        assertTrue(source.contains("boolean productionProfile = profiles.stream().anyMatch(RuntimeConfigGuard::isProductionProfile)"));
        assertTrue(source.contains("boolean enabled = productionProfile || requestedEnabled"));
        assertTrue(source.contains("boolean strict = productionProfile || bool(env, \"runtime.config.guard.strict\", false)"));
        assertTrue(source.contains("profile.equals(\"prod\")"));
        assertTrue(source.contains("profile.equals(\"production\")"));
        assertTrue(source.contains("profile.equals(\"live\")"));
        assertTrue(source.contains("guard_disable_ignored_in_production"));
    }
}
