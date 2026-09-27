package com.example.lms.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class VerificationIsolationGuardTest {
    @TempDir Path directory;

    private MockEnvironment safeEnvironment() throws Exception {
        Properties props = new Properties();
        try (var input = getClass().getResourceAsStream("/application-verification.properties")) {
            assertNotNull(input);
            props.load(input);
        }
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local", "verification");
        props.forEach((key, value) -> env.setProperty(key.toString(), value.toString()));
        env.setProperty("verification.run-id", "1234567890abcdef1234567890abcdef");
        env.setProperty("verification.fixture-port", "18095");
        env.setProperty("verification.server-port", "18096");
        env.setProperty("verification.root", directory.toString());
        Files.writeString(directory.resolve(".verification-owner"), env.getProperty("verification.run-id"));
        return env;
    }

    @Test
    void packagedProfileKeepsRouterAndSearchAndAllowsOwnedResources() throws Exception {
        var env = safeEnvironment();
        assertEquals("true", env.getProperty("llmrouter.enabled"));
        assertEquals("true", env.getProperty("gpt-search.brave.enabled"));
        assertEquals("true", env.getProperty("llm.gateway.cloud.enabled"));
        assertEquals("light", env.getProperty("llmrouter.models.gemma.fallback-key"));
        assertEquals(java.util.List.of(), RuntimeConfigGuard.verificationFindings(env, directory));
    }

    @ParameterizedTest
    @CsvSource({
            "spring.datasource.url,jdbc:mysql://production.invalid/live",
            "spring.datasource.driver-class-name,com.mysql.cj.jdbc.Driver",
            "spring.datasource.hikari.connection-init-sql,SELECT 1",
            "llm.base-url,http://127.0.0.1:11435/v1",
            "llmrouter.models.gemma.base-url,https://remote.invalid/v1",
            "llm.gateway.cloud.route-key,api3",
            "llmrouter.models.gemma.fallback-key,api3",
            "gpt-search.brave.base-url,http://127.0.0.1:18095/res?api_key=private",
            "local-llm.autostart,true",
            "llmrouter.models.api3.enabled,true",
            "vectorstore.flush.scheduler.enabled,true",
            "upstash.redis.rest-url,https://remote.invalid",
            "spring.config.import,optional:file:application-proj-override.yml",
            "llm.gateway.spec-registry.path,../operational.json",
            "server.address,0.0.0.0"
    })
    void rejectsEffectiveOverridesBeforeBeans(String property, String unsafe) throws Exception {
        var env = safeEnvironment();
        env.setProperty(property, unsafe);
        var failures = RuntimeConfigGuard.verificationFindings(env, directory);
        assertTrue(failures.stream().anyMatch(f -> f.startsWith(property+":")), failures.toString());
        assertFalse(failures.toString().contains(unsafe));
    }

    @Test
    void absentOptInPreservesExistingRuntime() {
        var env = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:mysql://production.invalid/live");
        assertTrue(RuntimeConfigGuard.verificationFindings(env, directory).isEmpty());
    }

    @Test
    void ownershipMarkerAndWorkingDirectoryMustMatch() throws Exception {
        var env = safeEnvironment();
        Files.writeString(directory.resolve(".verification-owner"), "someone-else");
        assertTrue(RuntimeConfigGuard.verificationFindings(env, directory).contains("verification.root:ownership_mismatch"));
    }

    @Test
    void unsafeVerificationDatasourceStopsBeforeApplicationContextCreation() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local", "verification");
        env.setProperty("spring.datasource.url", "jdbc:mysql://production.invalid/live");
        env.setProperty("runtime.config.guard.enabled", "false");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RuntimeConfigGuard().postProcessEnvironment(env, new SpringApplication()));
        assertTrue(failure.getMessage().contains("verification"));
        assertFalse(failure.getMessage().contains("production.invalid"));
    }
}
