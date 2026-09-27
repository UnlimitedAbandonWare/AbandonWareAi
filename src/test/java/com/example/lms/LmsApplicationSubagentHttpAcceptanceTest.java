package com.example.lms;

import com.abandonware.ai.agent.orchestrator.subagent.SubagentProvider;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentTask;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("local")
@SpringBootTest(
        classes = LmsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.banner-mode=off",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.task.scheduling.enabled=false",
                "vectorstore.flush.scheduler.enabled=false",
                "netty.enabled=false",
                "agent.subagent.glm.enabled=false",
                "llmrouter.models.openai-balanced.enabled=false",
                "llmrouter.models.light.enabled=false",
                "agent.subagent.deadline-ms=2000",
                "agent.subagent.task-timeout-ms=1500",
                "logging.level.root=WARN",
                "logging.level.com.abandonware.ai.agent.orchestrator.subagent=INFO"
        })
@Import(LmsApplicationSubagentHttpAcceptanceTest.TestOverrides.class)
class LmsApplicationSubagentHttpAcceptanceTest {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final String TEST_ADMIN_CAPABILITY = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void adminCapability(DynamicPropertyRegistry registry) {
        registry.add("domain.allowlist.admin-token", () -> TEST_ADMIN_CAPABILITY);
        registry.add("domain.allowlist.admin-token.required", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProviderProbe probe;

    @Test
    void productionApplicationRegistersAndExecutesTheSubagentHttpPath() throws Exception {
        String rawPrompt = "PRODUCTION_HTTP_RAW_PROMPT_SENTINEL";
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .cookieHandler(cookies)
                .build();
        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/login"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String csrfToken = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                .map(cookie -> URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8))
                .findFirst()
                .orElse("");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(csrfToken).isNotBlank();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/flows/subagent.v1:run?trace=on"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Admin-Token", TEST_ADMIN_CAPABILITY)
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("question", rawPrompt, "roomId", "production-http"))))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = objectMapper.readValue(response.body(), MAP);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body).containsEntry("flow", "subagent.v1");
        assertThat(body).containsEntry("subagent.status", "COMPLETE");
        assertThat(body).containsEntry("subagent.taskCount", 2);
        assertThat(body).containsEntry("subagent.successCount", 2);
        assertThat(String.valueOf(body.get("answer")))
                .contains("production-local-analysis", "production-local-verification");
        assertThat(probe.glmCalls).hasValue(0);
        assertThat(probe.openAiCalls).hasValue(2);
        assertThat(probe.ollamaCalls).hasValue(2);
        assertThat(response.body()).doesNotContain(rawPrompt);

        HttpRequest otherFlow = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/flows/unchanged.v1:run"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Admin-Token", TEST_ADMIN_CAPABILITY)
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> otherFlowResponse = client.send(
                otherFlow, HttpResponse.BodyHandlers.ofString());
        assertThat(otherFlowResponse.statusCode()).isEqualTo(404);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestOverrides {

        @Bean
        ProviderProbe providerProbe() {
            return new ProviderProbe();
        }

        @Bean(name = "glmSubagentProvider")
        SubagentProvider glmSubagentProvider(ProviderProbe probe) {
            return provider("vercel-glm", 10, true, false, task -> {
                probe.glmCalls.incrementAndGet();
                return "unexpected";
            });
        }

        @Bean(name = "openAiSubagentProvider")
        SubagentProvider openAiSubagentProvider(ProviderProbe probe) {
            return provider("openai", 20, false, true, task -> {
                probe.openAiCalls.incrementAndGet();
                throw new LlmGatewayException("test_openai_unverified",
                        LlmFailureClass.RESPONSE_MODEL_UNVERIFIED);
            });
        }

        @Bean(name = "ollamaSubagentProvider")
        SubagentProvider ollamaSubagentProvider(ProviderProbe probe) {
            return provider("ollama", 30, false, true, task -> {
                probe.ollamaCalls.incrementAndGet();
                return "production-local-" + task.role();
            });
        }

        private static SubagentProvider provider(String id,
                                                 int order,
                                                 boolean singleAttempt,
                                                 boolean enabled,
                                                 ProviderCall call) {
            return new SubagentProvider() {
                @Override
                public String id() {
                    return id;
                }

                @Override
                public int order() {
                    return order;
                }

                @Override
                public boolean singleAttemptPerFlow() {
                    return singleAttempt;
                }

                @Override
                public Availability availability() {
                    return enabled ? Availability.enabled() : Availability.disabled("blocked_external");
                }

                @Override
                public String execute(SubagentTask task, long timeoutMs) throws Exception {
                    return call.complete(task);
                }
            };
        }
    }

    static final class ProviderProbe {
        private final AtomicInteger glmCalls = new AtomicInteger();
        private final AtomicInteger openAiCalls = new AtomicInteger();
        private final AtomicInteger ollamaCalls = new AtomicInteger();
    }

    @FunctionalInterface
    private interface ProviderCall {
        String complete(SubagentTask task) throws Exception;
    }
}
