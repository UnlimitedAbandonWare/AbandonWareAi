package com.example.lms.api;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.example.lms.api.internal.InternalAgentToolController;
import com.example.lms.config.AgentToolOpsConfig;
import com.example.lms.config.ChatUiViewConfig;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.probe.CausalProbeTriggerService;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.web.DebugEventsPageController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Real loopback servlet runtime; no component scan, provider requests, or live-memory writes. */
class StructuralRuntimeIntegrationTest {
    private static final String FIXTURE_TOKEN = "structural-fixture-capability";
    @TempDir Path temporaryRoot;

    @Test
    void registeredToolAndDebugDeliveryUseProductionSpringHttpPaths() throws Exception {
        DebugEventsSseRuntime runtime;
        try (var context = new AnnotationConfigServletWebServerApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "server.port=0", "server.address=127.0.0.1",
                    "agent.tools.api.enabled=true", "domain.allowlist.admin-token=" + FIXTURE_TOKEN,
                    "lms.debug.events.enabled=true", "abandonware.debug.ndjson.enabled=false",
                    "lms.debug.events.sse.timeout-ms=5000", "lms.debug.events.sse.max-clients=2");
            context.registerBeanDefinition("isolatedFailurePatternMemory",
                    BeanDefinitionBuilder.genericBeanDefinition(FailurePatternMemoryService.class,
                            () -> new FailurePatternMemoryService(context.getBean(ObjectMapper.class),
                                    context.getBean(ToolManifestCatalog.class), temporaryRoot,
                                    temporaryRoot.resolve("memory.jsonl"))).getBeanDefinition());
            context.register(RuntimeConfiguration.class);
            context.refresh();
            assertThat(context.getBeansOfType(FailurePatternMemoryService.class)).hasSize(1);
            runtime = context.getBean(DebugEventsSseRuntime.class);
            String origin = "http://127.0.0.1:" + context.getWebServer().getPort();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThat(get(client, origin, "/internal/agent/tools", false).statusCode()).isEqualTo(403);
            var inventory = get(client, origin, "/internal/agent/tools", true);
            assertThat(inventory.statusCode()).isEqualTo(200);
            JsonNode listed = mapper.readTree(inventory.body()).path("tools");
            JsonNode config = java.util.stream.StreamSupport.stream(listed.spliterator(), false)
                    .filter(row -> "config.inspect".equals(row.path("id").asText())).findFirst().orElseThrow();
            assertThat(config.path("registered").asBoolean()).isTrue();
            assertThat(config.path("readOnly").asBoolean()).isTrue();

            var invoked = client.send(HttpRequest.newBuilder(URI.create(origin + "/internal/agent/tools/config.inspect:invoke"))
                    .timeout(Duration.ofSeconds(5)).header("X-Admin-Token", FIXTURE_TOKEN)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(invoked.statusCode()).isEqualTo(200);
            JsonNode result = mapper.readTree(invoked.body());
            assertThat(result.path("toolId").asText()).isEqualTo(config.path("id").asText());
            assertThat(result.path("ok").asBoolean()).isTrue();
            assertThat(result.path("policyDecision").asText()).isEqualTo("ALLOW");
            assertThat(result.path("authorizationSource").asText()).isEqualTo("ADMIN_TOKEN");
            assertThat(result.path("resultValidation").asText()).isEqualTo("PASSED");
            assertThat(invoked.body()).doesNotContain(FIXTURE_TOKEN);

            var missing = client.send(HttpRequest.newBuilder(URI.create(origin + "/internal/agent/tools/fixture.unknown:invoke"))
                    .timeout(Duration.ofSeconds(5)).header("X-Admin-Token", FIXTURE_TOKEN)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(missing.statusCode()).isEqualTo(404);

            DebugEventStore store = context.getBean(DebugEventStore.class);
            store.emit(DebugProbeType.GENERIC, DebugEventLevel.INFO, "structural-http-fixture",
                    "structural delivery fixture", Map.of("count", 1), null);
            String eventId = store.list(1).get(0).id();
            var events = get(client, origin, "/api/diagnostics/debug/events", false);
            assertThat(events.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(events.body()).get(0).path("id").asText()).isEqualTo(eventId);
            assertThat(get(client, origin, "/api/diagnostics/debug/events/" + eventId, false).statusCode()).isEqualTo(200);
            assertThat(get(client, origin, "/api/diagnostics/debug/fingerprints", false).statusCode()).isEqualTo(200);
            var page = get(client, origin, "/admin/debug-events", false);
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("source.onopen = updateStatus", "LIVE: reconnecting...");

            readActualSse(client, origin, eventId, null);
            awaitIdle(runtime);
            readActualSse(client, origin, eventId, eventId); // Existing bounded snapshot replay on reconnect.
            awaitIdle(runtime);
            assertThat(runtime.completedTaskCount()).isGreaterThanOrEqualTo(2);
            assertThat(runtime.queueSize()).isZero();

            if (Boolean.parseBoolean(System.getenv("AWX_STRUCTURAL_BROWSER_PROOF"))) {
                runBrowser(origin);
                awaitIdle(runtime);
            }
        }
        assertThat(runtime.isShutdown()).isTrue();
        assertThat(runtime.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    private static HttpResponse<String> get(HttpClient client, String origin, String path, boolean authorized) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(5));
        request.header("Accept", path.startsWith("/admin/") ? "text/html" : "application/json");
        if (authorized) request.header("X-Admin-Token", FIXTURE_TOKEN);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void readActualSse(HttpClient client, String origin, String eventId, String lastId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + "/api/diagnostics/debug/events/stream?pollMs=200&heartbeatMs=3000"))
                .timeout(Duration.ofSeconds(8));
        if (lastId != null) request.header("Last-Event-ID", lastId);
        var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertTimeoutPreemptively(Duration.ofSeconds(6), () -> {
                boolean hello = false;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.equals("event:hello")) hello = true;
                    if (line.startsWith("data:") && line.contains(eventId)) {
                        assertThat(hello).isTrue();
                        assertThat(new ObjectMapper().readTree(line.substring(5)).path("id").asText()).isEqualTo(eventId);
                        return;
                    }
                }
                throw new AssertionError("expected production SSE event was not delivered");
            });
        }
    }

    private static void awaitIdle(DebugEventsSseRuntime runtime) {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            while (runtime.activeCount() != 0) Thread.sleep(20L);
        });
    }

    private static void runBrowser(String origin) throws Exception {
        Process child = new ProcessBuilder(List.of("node",
                Path.of("scripts/debug_events_spring_browser_tests.js").toAbsolutePath().toString(), origin))
                .inheritIO().start();
        try {
            assertThat(child.waitFor(45, TimeUnit.SECONDS)).as("bounded browser proof finished").isTrue();
            assertThat(child.exitValue()).as("real Spring browser proof").isZero();
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({PropertyPlaceholderAutoConfiguration.class, ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
            AgentToolOpsConfig.class, CausalProbeTriggerService.class, InternalAgentToolController.class,
            AdminTokenGuardInterceptor.class, DebugEventStore.class, DebugEventsSseRuntime.class,
            DebugEventsDiagnosticsController.class, DebugEventsPageController.class, ChatUiViewConfig.class})
    static class RuntimeConfiguration {
    }
}
