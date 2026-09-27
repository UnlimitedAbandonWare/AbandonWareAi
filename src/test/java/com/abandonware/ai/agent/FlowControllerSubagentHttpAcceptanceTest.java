package com.abandonware.ai.agent;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.orchestrator.Orchestrator;
import com.abandonware.ai.agent.orchestrator.nodes.CriticNode;
import com.abandonware.ai.agent.orchestrator.nodes.PlannerNode;
import com.abandonware.ai.agent.orchestrator.nodes.SynthNode;
import com.abandonware.ai.agent.orchestrator.recovery.DefaultRecoveryExecutor;
import com.abandonware.ai.agent.orchestrator.recovery.RecoveryPolicy;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentFlowRunner;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentProvider;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentProviderChain;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentTask;
import com.abandonware.ai.agent.tool.request.ToolContextFactory;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = FlowControllerSubagentHttpAcceptanceTest.AcceptanceApplication.class,
        properties = {
                "spring.main.banner-mode=off",
                "logging.level.root=WARN",
                "logging.level.com.abandonware.ai.agent.orchestrator.subagent=INFO"
        })
class FlowControllerSubagentHttpAcceptanceTest {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProviderProbe probe;

    @Test
    void realHttpRequestDecomposesFallsBackCollectsAndSynthesizes() throws Exception {
        int openAiBefore = probe.openAiCalls.get();
        int ollamaBefore = probe.ollamaCalls.get();
        String rawPrompt = "RAW_HTTP_PROMPT_SENTINEL";
        String credentialSentinel = "credential-shaped-sentinel";

        Logger applicationLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        applicationLogger.addAppender(logCapture);
        HttpResponse<String> response;
        try {
            response = post(Map.of(
                    "question", rawPrompt,
                    "roomId", "room-a",
                    "authorization", credentialSentinel));
        } finally {
            applicationLogger.detachAppender(logCapture);
            logCapture.stop();
        }
        Map<String, Object> body = objectMapper.readValue(response.body(), MAP);
        String capturedLogs = logCapture.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body).containsEntry("flow", "subagent.v1");
        assertThat(body).containsEntry("subagent.status", "COMPLETE");
        assertThat(body).containsEntry("subagent.taskCount", 2);
        assertThat(body).containsEntry("subagent.successCount", 2);
        assertThat(String.valueOf(body.get("answer")))
                .contains("local-analysis", "local-verification")
                .matches("(?s).*local-analysis.*local-verification.*");
        assertThat(taskRows(body))
                .extracting(row -> row.get("role"))
                .containsExactly("analysis", "verification");
        assertThat(taskRows(body))
                .allSatisfy(row -> assertThat(attemptRows(row))
                        .extracting(attempt -> attempt.get("provider"), attempt -> attempt.get("outcome"))
                        .containsSubsequence(
                                org.assertj.core.groups.Tuple.tuple("vercel-glm", "SKIPPED"),
                                org.assertj.core.groups.Tuple.tuple("openai", "SELECTED"),
                                org.assertj.core.groups.Tuple.tuple("openai", "FAILED"),
                                org.assertj.core.groups.Tuple.tuple("ollama", "SELECTED"),
                                org.assertj.core.groups.Tuple.tuple("ollama", "SUCCESS")));
        assertThat(probe.glmCalls).hasValue(0);
        assertThat(probe.openAiCalls.get() - openAiBefore).isEqualTo(2);
        assertThat(probe.ollamaCalls.get() - ollamaBefore).isEqualTo(2);
        assertThat(response.body())
                .doesNotContain(rawPrompt)
                .doesNotContain(credentialSentinel);
        assertThat(capturedLogs)
                .contains("event=provider", "fallbackUsed=true", "elapsedMs=")
                .doesNotContain(rawPrompt)
                .doesNotContain(credentialSentinel);
    }

    @Test
    void realHttpRequestPropagatesTaskTimeoutWithoutLeakingRequestText() throws Exception {
        String rawPrompt = "FORCE_TIMEOUT_RAW_SENTINEL";

        HttpResponse<String> response = post(Map.of("question", rawPrompt, "roomId", "room-b"));
        Map<String, Object> body = objectMapper.readValue(response.body(), MAP);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body).containsEntry("subagent.status", "FAILED");
        assertThat(body).containsEntry("subagent.successCount", 0);
        assertThat(taskRows(body))
                .allSatisfy(row -> {
                    assertThat(row).containsEntry("status", "TIMEOUT");
                    assertThat(row).containsEntry("failureClass", "TIMEOUT_SOFT");
                    assertThat(row).containsEntry("timeoutReason", "task_timeout");
                });
        assertThat(response.body()).doesNotContain(rawPrompt);
    }

    private HttpResponse<String> post(Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/flows/subagent.v1:run?trace=on"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> taskRows(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("subagent.tasks");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attemptRows(Map<String, Object> taskRow) {
        return (List<Map<String, Object>>) taskRow.get("attempts");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
            "ai.abandonware.nova.autoconfig.NovaDebugPortAutoConfiguration",
            "ai.abandonware.nova.autoconfig.NovaFailurePatternAutoConfiguration",
            "ai.abandonware.nova.autoconfig.NovaOrchestrationAutoConfiguration",
            "ai.abandonware.nova.autoconfig.NovaOpsStabilizationAutoConfiguration",
            "ai.abandonware.nova.autoconfig.NovaZero100AutoConfiguration",
            "com.example.lms.agent.context.AgentDbContextAutoConfiguration"
    })
    @Configuration(proxyBeanMethods = false)
    static class AcceptanceApplication {

        @Bean
        ProviderProbe providerProbe() {
            return new ProviderProbe();
        }

        @Bean
        SubagentProvider glmDouble(ProviderProbe probe) {
            return provider("vercel-glm", 10, task -> {
                probe.glmCalls.incrementAndGet();
                return "unexpected";
            }, false);
        }

        @Bean
        SubagentProvider openAiDouble(ProviderProbe probe) {
            return provider("openai", 20, task -> {
                probe.openAiCalls.incrementAndGet();
                throw new LlmGatewayException("test_openai_unverified",
                        LlmFailureClass.RESPONSE_MODEL_UNVERIFIED);
            }, true);
        }

        @Bean
        SubagentProvider ollamaDouble(ProviderProbe probe) {
            return provider("ollama", 30, task -> {
                probe.ollamaCalls.incrementAndGet();
                if (task.prompt().contains("FORCE_TIMEOUT_RAW_SENTINEL")) {
                    Thread.sleep(5_000L);
                }
                return "local-" + task.role();
            }, true);
        }

        @Bean
        LlmGatewayFailureClassifier failureClassifier() {
            return new LlmGatewayFailureClassifier();
        }

        @Bean
        SubagentProviderChain providerChain(List<SubagentProvider> providers,
                                            LlmGatewayFailureClassifier classifier) {
            return new SubagentProviderChain(providers, classifier, 1_000L, System::nanoTime);
        }

        @Bean(destroyMethod = "close")
        SubagentFlowRunner subagentFlowRunner(SubagentProviderChain chain) {
            return new SubagentFlowRunner(
                    new PlannerNode(), new SynthNode(), chain,
                    1_000L, 200L, Executors.newFixedThreadPool(2));
        }

        @Bean
        RecoveryPolicy recoveryPolicy() {
            return RecoveryPolicy.load();
        }

        @Bean
        CriticNode criticNode(RecoveryPolicy policy) {
            return new CriticNode(policy);
        }

        @Bean
        DefaultRecoveryExecutor recoveryExecutor(RecoveryPolicy policy) {
            return new DefaultRecoveryExecutor(policy, null);
        }

        @Bean
        Orchestrator orchestrator(DefaultRecoveryExecutor recoveryExecutor,
                                  CriticNode criticNode,
                                  RecoveryPolicy policy,
                                  ObjectProvider<FailurePatternMemoryService> failurePatternMemory,
                                  SubagentFlowRunner runner) {
            return new Orchestrator(recoveryExecutor, criticNode, policy, failurePatternMemory, runner);
        }

        @Bean
        ContextBridge contextBridge() {
            return new ContextBridge();
        }

        @Bean
        ToolContextFactory toolContextFactory(ContextBridge contextBridge) {
            return new ToolContextFactory(contextBridge);
        }

        @Bean
        FlowController flowController(Orchestrator orchestrator,
                                      ToolContextFactory contextFactory) {
            return new FlowController(orchestrator, contextFactory);
        }

        private static SubagentProvider provider(String id,
                                                 int order,
                                                 Invocation invocation,
                                                 boolean enabled) {
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
                public Availability availability() {
                    return enabled ? Availability.enabled() : Availability.disabled("blocked_external");
                }

                @Override
                public String execute(SubagentTask task, long timeoutMs) throws Exception {
                    return invocation.complete(task);
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
    private interface Invocation {
        String complete(SubagentTask task) throws Exception;
    }
}
