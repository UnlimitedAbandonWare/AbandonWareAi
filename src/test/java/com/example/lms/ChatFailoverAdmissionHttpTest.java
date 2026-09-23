package com.example.lms;

import com.example.lms.infra.upstash.UpstashRedisClient;
import com.example.lms.llm.gateway.LlmGatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import javax.sql.DataSource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Full HTTP/security/controller path; isolated SQL and explicitly simulated Redis/model dependencies. */
@org.springframework.test.context.ActiveProfiles(profiles = "verification-http", inheritProfiles = false)
class ChatFailoverAdmissionHttpTest extends Phase2ApplicationTestSupport {
    static final Fixture LOCAL = new Fixture(500, "fixture-local", "GPU is lost");
    static final Fixture FIRST = new Fixture(503, "fixture-api-a", "temporarily unavailable");
    static final Fixture SECOND = new Fixture(200, "fixture-api-b", "The synthetic reference reports seven units [source-7].");
    static final String RUN = UUID.randomUUID().toString().replace("-", "");
    @MockBean UpstashRedisClient redis;
    @Autowired DataSource dataSource;
    @Autowired LlmGatewayProperties gateway;
    @Autowired com.abandonware.ai.addons.config.AddonsProperties addons;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired com.example.lms.trace.TraceSnapshotStore snapshots;
    @Autowired ai.abandonware.nova.config.LlmRouterProperties routes;
    @Autowired com.example.lms.llm.gateway.HybridLlmGatewayProbeService probe;
    static String browserUsername;
    static int browserPort;

    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) throws Exception {
        var values = new Properties();
        try (var input = new ClassPathResource("application-verification.properties").getInputStream()) { values.load(input); }
        values.forEach((key, value) -> registry.add(key.toString(), () -> value));
        String contextRun = UUID.randomUUID().toString().replace("-", "");
        registry.add("verification.run-id", () -> contextRun);
        registry.add("verification.fixture-port", () -> LOCAL.port());
        registry.add("verification.server-port", () -> 0);
        registry.add("runtime.verification.enabled", () -> false);
        registry.add("demo.interview.enabled", () -> false);
        registry.add("demo.mode", () -> false);
        registry.add("gpt-search.brave.enabled", () -> false);
        registry.add("gemini.gateway.enabled", () -> false);
        registry.add("llm.gateway.enabled", () -> true);
        registry.add("llm.gateway.enforcement", () -> "ENFORCE");
        registry.add("llm.gateway.local-device-failover.enabled", () -> true);
        registry.add("llm.gateway.local-device-failover.enforcement", () -> "ENFORCE");
        registry.add("llm.gateway.cloud.route-key", () -> "openai-balanced");
        registry.add("llm.gateway.spec-registry.enabled", () -> false);
        registry.add("FAILOVER_FIXTURE_KEY", () -> "fixture-registered-only");
        registry.add("addons.budget.default-ms", () -> 15_000);
        for (String alias : List.of("OPENAI_API_KEY", "llm.api-key-openai", "llm.openai.api-key"))
            registry.add(alias, () -> "fixture-registered-only");
        for (String key : List.of("light", "gemma", "api3", "openai-premium", "openai-balanced", "gemini-pro", "mistral-medium", "macmini", "external", "judge", "coder", "vision"))
            registry.add("llmrouter.models." + key + ".enabled", () -> false);
        configure(registry, "gemma", "local", LOCAL, "openai-balanced");
        configure(registry, "openai-balanced", "openai", FIRST, "openai-premium");
        configure(registry, "openai-premium", "openai", SECOND, "");
    }

    private static void configure(DynamicPropertyRegistry r, String key, String provider, Fixture f, String next) {
        String prefix = "llmrouter.models." + key + ".";
        r.add(prefix + "enabled", () -> true); r.add(prefix + "provider", () -> provider);
        r.add(prefix + "name", () -> f.model); r.add(prefix + "base-url", f::base);
        r.add(prefix + "stage", () -> "chat"); r.add(prefix + "fallback-key", () -> next);
        r.add(prefix + "credential-env", () -> "FAILOVER_FIXTURE_KEY");
        r.add(prefix + "min-context-tokens", () -> 0); r.add(prefix + "managed-file-search", () -> false);
        r.add(prefix + "response-model-verification-required", () -> false);
    }

    @Override @AfterEach void removeOwnedFixtures() { /* Isolated H2 is destroyed with this context, including its HTTP-created session. */ }

    @BeforeEach void admissionFixture() {
        for (Fixture fixture : List.of(LOCAL, FIRST, SECOND)) {
            fixture.calls.set(0); fixture.messages.clear(); fixture.proofs.clear();
        }
        assertFalse(Arrays.asList(environment.getActiveProfiles()).contains("local"), "distributed_admission_profile");
        assertTrue(gateway.isEnforce()); assertTrue(gateway.getLocalDeviceFailover().isEnforce());
        assertTrue(gateway.getCloud().isEnabled());
        assertEquals(15_000L, addons.getBudget().getDefaultMs(), "effective_request_deadline");
        for (String route : List.of("openai-balanced", "openai-premium")) {
            var eligibility = probe.evaluate(route, routes.getModels().get(route), "chat");
            assertTrue(eligibility.eligible(), "fixture_cloud_eligible:" + eligibility.failureClasses());
        }
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V20260912_02__chat_requests.sql"),
                new ClassPathResource("db/migration/V20260912_04__chat_request_results.sql"),
                new ClassPathResource("db/migration/V20260912_05__chat_run_owners.sql")).execute(dataSource);
        when(redis.enabled()).thenReturn(true);
        when(redis.get(anyString())).thenReturn(Mono.empty());
        when(redis.setEx(anyString(), anyString(), any())).thenReturn(Mono.just(true));
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(Mono.just(List.of(1L, 0L)));
    }

    @Test void admittedHttpRequestFailsOverOnceAndDuplicatesNeverRegenerate() throws Exception {
        // Run expansion in a separate Gradle/JVM invocation to isolate application globals.
        boolean expand = "true".equals(System.getenv("FAILOVER_EXPANSION_PROOF"));
        browserUsername = account("ROLE_ADMIN"); browserPort = port;
        HttpClient client = login(browserUsername);
        String requestRun = UUID.randomUUID().toString().replace("-", "");
        String question = expand ? "Explain whether the synthetic reference reports seven units."
                : "In one sentence, state whether the synthetic reference reports seven units.";
        String body = mapper.writeValueAsString(Map.of("message", question + " Synthetic test run " + requestRun + ".",
                "model", "llmrouter.gemma", "memoryMode", "ephemeral", "useRag", false, "useWebSearch", false,
                "useVerification", false, "understandingEnabled", false, "temperature", 0.0, "maxTokens", 32));
        String key = "http-failover-" + requestRun;
        long startedNanos = System.nanoTime();
        var response = send(client, key, body);
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        java.nio.file.Files.writeString(java.nio.file.Path.of("data/agent-handoff/codex/report/gpu-service-failover-20260914/"
                        + (expand ? "http-expansion-13.json" : "http-attempts-13.json")),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("status", response.statusCode(),
                        "local", LOCAL.proofs, "firstApi", FIRST.proofs, "secondApi", SECOND.proofs,
                        "elapsedMs", elapsedMs, "effectiveDeadlineMs", addons.getBudget().getDefaultMs(),
                        "responseHash", org.apache.commons.codec.digest.DigestUtils.sha256Hex(response.body()),
                        "responseShape", shape(mapper.readTree(response.body()), "", new TreeMap<>()), "routingTrace", safeRoutingTrace())));
        assertEquals(200, response.statusCode(), "admitted_http_status");
        assertTrue(response.headers().firstValue("X-Admission-Mode").isEmpty(), "no_demo_admission");
        assertTrue(mapper.readTree(response.body()).path("content").asText().contains("The synthetic reference reports seven units"), "verified_final_meaning");
        assertEquals(1, LOCAL.calls.get(), "physical_local_calls");
        assertEquals(expand ? 2 : 1, FIRST.calls.get(), "physical_first_api_calls");
        assertEquals(1, SECOND.calls.get(), "physical_second_api_calls");
        assertEquals(expand ? 4 : 3, LOCAL.calls.get() + FIRST.calls.get() + SECOND.calls.get(), "request_total_attempt_bound");
        assertTrue(elapsedMs <= addons.getBudget().getDefaultMs() + 3_000L, "deadline_plus_http_overhead");
        assertTrue(LOCAL.messages.get(0).equals(FIRST.messages.get(0)), "same_frozen_messages_at_first_api");
        assertTrue(LOCAL.messages.get(0).equals(SECOND.messages.get(0)), "same_frozen_messages_at_second_api");

        var repeated = send(client, key, body);
        assertEquals(200, repeated.statusCode());
        assertEquals("true", repeated.headers().firstValue("X-Idempotent-Replay").orElse(""));
        assertTrue(mapper.readTree(response.body()).equals(mapper.readTree(repeated.body())), "same_stored_final_response");
        assertEquals(1, SECOND.calls.get(), "duplicate_does_not_regenerate");
        var jdbc = new JdbcTemplate(dataSource);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM awx_chat_requests", Integer.class));
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT state FROM awx_chat_requests", String.class));

        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(Mono.error(new IllegalStateException("synthetic_outage")));
        assertEquals(503, send(client, key + "-outage", body).statusCode());
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(Mono.just(List.of(0L, 1500L)));
        var limited = send(client, key + "-limited", body);
        assertEquals(429, limited.statusCode()); assertEquals("2", limited.headers().firstValue("Retry-After").orElse(""));
        assertEquals(1, SECOND.calls.get(), "rejected_requests_do_not_generate");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM awx_chat_requests", Integer.class));
        when(redis.eval(anyString(), anyList(), anyList())).thenReturn(Mono.just(List.of(1L, 0L)));
        if (!expand && "true".equals(System.getenv("FAILOVER_BROWSER_PROOF"))) {
            java.nio.file.Path done = java.nio.file.Path.of("data/agent-handoff/codex/report/gpu-service-failover-20260914/http-browser-" + RUN + ".done");
            java.nio.file.Files.writeString(java.nio.file.Path.of("data/agent-handoff/codex/report/gpu-service-failover-20260914/http-browser-ready-14.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("origin", origin(), "username", browserUsername,
                            "expiresAt", java.time.Instant.now().plusSeconds(180).toString(), "testAssertionsPassed", true, "donePath", done.toString(),
                            "localCalls", LOCAL.calls.get(), "firstApiCalls", FIRST.calls.get(), "secondApiCalls", SECOND.calls.get())));
            for (int second = 0; second < 180 && !java.nio.file.Files.exists(done); second++) Thread.sleep(1_000);
            java.nio.file.Files.writeString(java.nio.file.Path.of("data/agent-handoff/codex/report/gpu-service-failover-20260914/http-browser-counts-14.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("local", LOCAL.proofs, "firstApi", FIRST.proofs, "secondApi", SECOND.proofs,
                            "routingTrace", safeRoutingTrace(), "doneObserved", java.nio.file.Files.exists(done))));
        }
    }

    private Map<String, Object> safeRoutingTrace() {
        var out = new TreeMap<String, Object>();
        for (var summary : snapshots.listSummaries(12)) {
            snapshots.get(String.valueOf(summary.get("id"))).ifPresent(s -> {
                for (String key : List.of("llm.gateway.fallback.skippedReason", "llm.gateway.fallback.count",
                        "llm.gateway.fallback.remainingMs", "llm.gateway.fallbackAware.primaryFailure",
                        "llm.gateway.fallbackAware.routeResolutionFailureReason", "llm.final.skipped",
                        "finalAnswer.postprocess.reason", "finalAnswer.releaseReason", "llm.call.maxAttempts",
                        "llm.gateway.fallbackAware.sameRequestRetry", "chat.harmony.postprocess.shapeReason",
                        "llm.gateway.attemptCount", "answer.expansion.skipped")) {
                    var value = s.trace().get(key);
                    if (value instanceof Number || value instanceof Boolean || (value instanceof String text && text.matches("[A-Za-z0-9_:. -]{1,100}")))
                        out.put(key, value);
                }
            });
        }
        return out;
    }

    private static Map<String, Object> shape(JsonNode node, String path, Map<String, Object> result) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> shape(entry.getValue(), path + "." + entry.getKey(), result));
        else if (node.isArray()) result.put(path + ".size", node.size());
        else if (node.isNumber() || node.isBoolean()) result.put(path, node.toString());
        else if (node.isTextual()) result.put(path + ".textLength", node.asText().length());
        return result;
    }

    private HttpResponse<String> send(HttpClient client, String key, String body) throws Exception {
        // Login uses real CSRF; the existing /api/chat/** chain does not require a post-login CSRF cookie.
        return client.send(request("/api/chat", null).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @AfterAll static void stopOwnedFixtures() { LOCAL.close(); FIRST.close(); SECOND.close(); }
    private static final class Fixture {
        final HttpServer server; final int status; final String model; final String content;
        final AtomicInteger calls = new AtomicInteger(); final List<JsonNode> messages = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> proofs = new CopyOnWriteArrayList<>();
        Fixture(int status, String model, String content) {
            this.status = status; this.model = model; this.content = content;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", exchange -> {
                    var json = new ObjectMapper(); String path = exchange.getRequestURI().getPath();
                    boolean generate = exchange.getRequestMethod().equals("POST") && (path.equals("/api/chat") || path.endsWith("/chat/completions"));
                    Object payload; int code = 200;
                    if (generate) {
                        calls.incrementAndGet(); JsonNode request = json.readTree(exchange.getRequestBody()); messages.add(request.path("messages")); code = status;
                        proofs.add(Map.of("messagesHash", org.apache.commons.codec.digest.DigestUtils.sha256Hex(json.writeValueAsBytes(request.path("messages"))),
                                "bodyHash", org.apache.commons.codec.digest.DigestUtils.sha256Hex(json.writeValueAsBytes(request)),
                                "modelHash", org.apache.commons.codec.digest.DigestUtils.sha256Hex(request.path("model").asText()),
                                "maxTokens", request.path("max_tokens").asInt(request.path("max_completion_tokens").asInt()),
                                "observedAt", java.time.Instant.now().toString()));
                        payload = status != 200 ? Map.of("error", content) : Map.of("id", "fixture", "model", model,
                                "choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content))),
                                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 8, "total_tokens", 18));
                    } else payload = Map.of("version", "fixture", "models", List.of(Map.of("name", model)), "data", List.of(Map.of("id", model)));
                    byte[] bytes = json.writeValueAsBytes(payload); exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
                }); server.start();
            } catch (Exception failure) { throw new IllegalStateException("fixture_start_failed", failure); }
        }
        int port() { return server.getAddress().getPort(); }
        String base() { return "http://127.0.0.1:" + port() + "/v1"; }
        void close() { server.stop(0); }
    }
}
