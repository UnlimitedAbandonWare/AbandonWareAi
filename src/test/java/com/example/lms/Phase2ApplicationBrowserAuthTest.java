package com.example.lms;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real-browser proof on an Agent-Port lease; the production auth configuration is unchanged. */
@EnabledIfEnvironmentVariable(named = "AWX_AGENT_PORT", matches = "[0-9]{4,5}")
class Phase2ApplicationBrowserAuthTest extends Phase2ApplicationTestSupport {
    private static final String DATABASE = "jdbc:h2:mem:phase2-browser-" + UUID.randomUUID()
            + ";MODE=MariaDB;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
    @Autowired Environment environment;

    @DynamicPropertySource static void browserProperties(DynamicPropertyRegistry registry) {
        int leasedPort = Integer.parseInt(System.getenv("AWX_AGENT_PORT"));
        if (leasedPort < 1024 || leasedPort > 65535 || leasedPort >= 18180 && leasedPort <= 18182)
            throw new IllegalStateException("auth_browser_port_not_isolated");
        registry.add("server.port", () -> leasedPort);
        registry.add("management.server.port", () -> leasedPort);
        registry.add("spring.datasource.url", () -> DATABASE);
        registry.add("demo.auth.proto-open", () -> "false");
        registry.add("demo.interview.enabled", () -> "false");
        registry.add("domain.allowlist.admin-token", () -> "");
        registry.add("domain.allowlist.admin-token.required", () -> "false");
        registry.add("llm.owner-token", () -> "");
    }

    @Test void freshBrowserUsesActualDaoLoginAndBlocksInvalidAccountAndLogout() throws Exception {
        assertThat(environment.getProperty("demo.auth.proto-open", Boolean.class)).isFalse();
        assertThat(environment.getProperty("demo.interview.enabled", Boolean.class)).isFalse();
        assertThat(port).isEqualTo(Integer.parseInt(System.getenv("AWX_AGENT_PORT")));
        Path root = Path.of("").toAbsolutePath().normalize();
        Path output = Path.of(System.getenv("AWX_AUTH_BROWSER_EVIDENCE")).toAbsolutePath().normalize();
        assertThat(output.startsWith(root.resolve("data/agent-handoff/codex-autonomy"))).isTrue();
        assertThat(output.getFileName().toString()).isEqualTo("browser-auth-evidence.json");
        Files.createDirectories(output.getParent());

        String username = account("ROLE_ADMIN");
        Process child = new ProcessBuilder("node", root.resolve("scripts/phase2_browser_auth_verify.cjs").toString())
                .directory(root.toFile()).redirectErrorStream(true).start();
        try {
            // Synthetic fixture credentials exist only in child stdin/memory, never args/env/output/state files.
            try (var input = child.getOutputStream()) {
                input.write(mapper.writeValueAsBytes(Map.of("base", origin(), "username", username,
                        "password", FIXTURE_PASSWORD, "output", output.toString())));
            }
            assertThat(child.waitFor(120, TimeUnit.SECONDS)).isTrue();
            String console = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(console.contains(username) || console.contains(FIXTURE_PASSWORD))
                    .as("fixture_console_sensitive_data").isFalse();
            assertThat(child.exitValue()).isZero();
            JsonNode evidence = mapper.readTree(Files.readString(output));
            assertThat(evidence.path("verdict").asText()).isEqualTo("PASS");
            assertThat(evidence.path("generationPosts").asInt()).isZero();
            assertThat(evidence.path("freshContexts").asInt()).isEqualTo(2);
            assertThat(evidence.path("restoredAuthState").asBoolean()).isFalse();
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }
}
