package com.example.lms;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class Phase2ApplicationCapabilityTest extends Phase2ApplicationTestSupport {
    private static final String DATABASE = "jdbc:h2:mem:phase2-capability-" + UUID.randomUUID()
            + ";MODE=MariaDB;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
    private static final String CAPABILITY = "phase2-synthetic-capability";
    @Autowired DebugEventStore debugEvents;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE);
        registry.add("domain.allowlist.admin-token", () -> CAPABILITY);
        registry.add("domain.allowlist.admin-token.required", () -> "true");
    }

    @Test void fullApplicationConnectsInventoryInvocationDiagnosticsAndSse() throws Exception {
        String username = account("ROLE_ADMIN");
        var admin = login(username);
        assertThat(get(client(), "/internal/agent/tools", null).statusCode()).isEqualTo(403);
        assertThat(get(admin, "/internal/agent/tools", "synthetic-wrong-capability").statusCode()).isEqualTo(403);
        var inventory = get(admin, "/internal/agent/tools", CAPABILITY);
        assertJson(inventory);
        var config = java.util.stream.StreamSupport.stream(mapper.readTree(inventory.body()).path("tools").spliterator(), false)
                .filter(row -> row.path("id").asText().equals("config.inspect")).findFirst().orElseThrow();
        assertThat(config.path("registered").asBoolean()).isTrue();
        assertThat(config.path("readOnly").asBoolean()).isTrue();
        var invocation = admin.send(request("/internal/agent/tools/config.inspect:invoke", CAPABILITY)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertJson(invocation);
        var result = mapper.readTree(invocation.body());
        assertThat(result.path("toolId").asText()).isEqualTo("config.inspect");
        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("policyDecision").asText()).isEqualTo("ALLOW");
        assertThat(result.path("authorizationSource").asText()).isEqualTo("ADMIN_TOKEN");
        assertThat(result.path("resultValidation").asText()).isEqualTo("PASSED");
        assertThat(invocation.body()).doesNotContain(CAPABILITY, FIXTURE_PASSWORD);
        debugEvents.emit(DebugProbeType.GENERIC, DebugEventLevel.INFO, "phase2-fixture",
                "synthetic application verification", Map.of("count", 1), null);
        for (String path : DIAGNOSTICS) assertJson(get(admin, path, CAPABILITY));
        assertThat(get(admin, "/admin/debug-events", CAPABILITY).body()).contains("id=\"triadicJson\"");
        for (int connection = 0; connection < 2; connection++) {
            var stream = admin.send(request("/api/diagnostics/debug/events/stream", CAPABILITY)
                    .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(stream.statusCode()).isEqualTo(200);
            assertThat(stream.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
            try (var reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                assertThat(reader.readLine()).startsWith("event:hello");
            }
        }
        if (Boolean.parseBoolean(System.getenv("AWX_PHASE2_BROWSER_PROOF"))) {
            var process = new ProcessBuilder("node", "scripts/phase2_full_app_browser_tests.js", origin())
                    .redirectErrorStream(true).redirectOutput(Path.of("build", "phase2-browser-summary.log").toFile()).start();
            try {
                process.getOutputStream().write(mapper.writeValueAsBytes(Map.of("username", username,
                        "password", FIXTURE_PASSWORD, "capability", CAPABILITY)));
                process.getOutputStream().close();
                assertThat(process.waitFor(90, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isZero();
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        }
    }
}
