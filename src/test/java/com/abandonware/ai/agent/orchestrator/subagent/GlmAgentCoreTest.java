package com.abandonware.ai.agent.orchestrator.subagent;

import ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlmAgentCoreTest {

    @Test
    void sameThreadTimelineRetainsOnlyRedactedClientHttpEvidenceAndRestoresOuterContext()
            throws Exception {
        String rawPrompt = "RAW_CORE_TIMELINE_PROMPT_SENTINEL";
        String rawResponse = "RAW_CORE_TIMELINE_RESPONSE_SENTINEL";
        String credentialMarker = "unit-credential-" + "x".repeat(24);
        byte[] responseBody = ("{\"output_text\":\"" + rawResponse + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (var output = exchange.getResponseBody()) {
                output.write(responseBody);
            }
        });
        server.start();

        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        AtomicReference<String> observedTimelineId = new AtomicReference<>();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        SubagentProvider provider = new SubagentProvider() {
            @Override
            public String id() {
                return "responses-test";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public Availability availability() {
                return Availability.enabled();
            }

            @Override
            public String execute(SubagentTask task, long timeoutMs) {
                observedTimelineId.set(String.valueOf(TraceStore.get(
                        ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY)));
                OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(
                        baseUrl,
                        credentialMarker,
                        "zai/glm-5.2",
                        timeoutMs,
                        tracker,
                        "primary",
                        tracker.redactedRequestAttemptRoute(
                                "subagent_glm", "zai/glm-5.2", baseUrl + "/responses",
                                "openai_responses"),
                        "high");
                return model.chat(List.of(UserMessage.from(task.prompt()))).aiMessage().text();
            }
        };
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(List.of(provider), new LlmGatewayFailureClassifier()),
                new SubagentAgentMetrics(),
                tracker);
        SubagentTask task = new SubagentTask(
                "correlation-timeline", 0, "task-timeline", "analysis", rawPrompt, Map.of());

        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, "outer-timeline");
        try {
            SubagentResult result = core.execute(
                    task,
                    new SubagentProviderChain.AttemptBudget(),
                    System.nanoTime() + 5_000_000_000L,
                    ignored -> { });

            assertThat(result.succeeded()).isTrue();
            assertThat(observedTimelineId.get()).isNotBlank().isNotEqualTo("outer-timeline");
            assertThat(TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY))
                    .isEqualTo("outer-timeline");

            Map<String, Object> status = core.status();
            assertThat(status).containsEntry("requestTimelineEnabled", true);
            assertThat(recentRequestTimeline(status))
                    .extracting(row -> row.get("phase"))
                    .containsExactly("dispatch", "pending", "terminal");
            assertThat(recentRequestTimeline(status))
                    .allSatisfy(row -> assertThat(row)
                            .containsOnlyKeys("requestHash", "phase", "terminalClass"));

            List<Map<String, Object>> clientLedger = recentClientHttpLedger(status);
            assertThat(clientLedger).hasSize(1);
            assertThat(clientLedger.get(0))
                    .containsOnlyKeys(
                            "requestHash", "promptHash", "responseHash",
                            "httpRequestBodyHash", "httpResponseBodyHash",
                            "outcome", "failureClass", "terminalClass",
                            "modelAdapterAttemptObserved", "clientHttpExchangeObserved",
                            "clientHttpResponseObserved", "providerAttemptObserved",
                            "wireAttemptObserved", "responseObserved")
                    .containsEntry("outcome", "success")
                    .containsEntry("failureClass", "none")
                    .containsEntry("terminalClass", "success")
                    .containsEntry("modelAdapterAttemptObserved", true)
                    .containsEntry("clientHttpExchangeObserved", true)
                    .containsEntry("clientHttpResponseObserved", true)
                    .containsEntry("providerAttemptObserved", false)
                    .containsEntry("wireAttemptObserved", false)
                    .containsEntry("responseObserved", true);
            assertThat(List.of(
                    "requestHash", "promptHash", "responseHash",
                    "httpRequestBodyHash", "httpResponseBodyHash"))
                    .allSatisfy(key -> assertThat(String.valueOf(clientLedger.get(0).get(key)))
                            .matches("(?:hash|sha256):[A-Za-z0-9]+"));
            assertThat(String.valueOf(clientLedger.get(0).get("httpRequestBodyHash")))
                    .startsWith("sha256:");
            assertThat(String.valueOf(clientLedger.get(0).get("httpResponseBodyHash")))
                    .startsWith("sha256:");
            assertThat(status.toString())
                    .doesNotContain(rawPrompt)
                    .doesNotContain(rawResponse)
                    .doesNotContain(credentialMarker);
            assertThat(tracker.redactedRequestAttemptLedger(observedTimelineId.get())).hasSize(1);
        } finally {
            TraceStore.clear();
            server.stop(0);
        }
    }

    @Test
    void statusIsReadOnlySecretFreeAndShowsFallbackAndCircuitState() {
        AtomicInteger generationCalls = new AtomicInteger();
        SubagentProvider glm = provider(
                "vercel-glm", 10, SubagentProvider.Availability.disabled("blocked_external"),
                generationCalls, "must-not-run");
        SubagentProvider ollama = provider(
                "ollama", 30, SubagentProvider.Availability.enabled(),
                generationCalls, "local answer");
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(List.of(glm, ollama), new LlmGatewayFailureClassifier()),
                metrics);

        Map<String, Object> status = core.status();

        assertThat(generationCalls).hasValue(0);
        assertThat(status).containsEntry("generationAttempted", false);
        assertThat(status).containsEntry("fallbackProviders", List.of("ollama"));
        assertThat(providerRows(status)).anySatisfy(row -> assertThat(row)
                .containsEntry("provider", "vercel-glm")
                .containsEntry("available", false)
                .containsEntry("reasonCode", "blocked_external")
                .containsEntry("circuitState", "closed"));
        assertThat(status.toString())
                .doesNotContain("must-not-run")
                .doesNotContain("authorization")
                .doesNotContain("apiKey");
    }

    @Test
    void statusRedactsASecretShapedAvailabilityReason() {
        String secretShapedReason = "sk-" + "x".repeat(32);
        SubagentProvider provider = provider(
                "vercel-glm", 10, SubagentProvider.Availability.disabled(secretShapedReason),
                new AtomicInteger(), "must-not-run");
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(List.of(provider), new LlmGatewayFailureClassifier()),
                new SubagentAgentMetrics());

        Map<String, Object> status = core.status();

        assertThat(status.toString()).doesNotContain(secretShapedReason);
        assertThat(providerRows(status).get(0)).containsEntry("reasonCode", "redacted");
    }

    @Test
    void statusReportsRecentCategoricalErrorWithoutExceptionText() {
        SubagentProvider failing = new SubagentProvider() {
            @Override
            public String id() {
                return "vercel-glm";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public Availability availability() {
                return Availability.enabled();
            }

            @Override
            public String execute(SubagentTask task, long timeoutMs) {
                throw new LlmGatewayException("RAW_ERROR_SENTINEL", LlmFailureClass.PROVIDER_ERROR);
            }
        };
        SubagentProvider fallback = provider(
                "ollama", 30, SubagentProvider.Availability.enabled(),
                new AtomicInteger(), "local answer");
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(List.of(failing, fallback), new LlmGatewayFailureClassifier()),
                new SubagentAgentMetrics());
        SubagentTask task = new SubagentTask(
                "correlation-error", 0, "task-error", "review", "bounded", Map.of());

        core.execute(task, new SubagentProviderChain.AttemptBudget(),
                System.nanoTime() + 5_000_000_000L, ignored -> { });
        Map<String, Object> status = core.status();

        assertThat(recentErrorClasses(status)).containsEntry("vercel-glm", "PROVIDER_ERROR");
        assertThat(metrics(status)).containsEntry(
                "recentErrorClasses", Map.of("vercel-glm", "PROVIDER_ERROR"));
        assertThat(status.toString()).doesNotContain("RAW_ERROR_SENTINEL");
    }

    @Test
    void delegatesToProviderChainAndReturnsCompleteStructuredSchemaAndMetrics() {
        AtomicInteger generationCalls = new AtomicInteger();
        SubagentProvider glm = provider(
                "vercel-glm", 10, SubagentProvider.Availability.disabled("blocked_external"),
                generationCalls, "must-not-run");
        SubagentProvider ollama = provider(
                "ollama", 30, SubagentProvider.Availability.enabled(),
                generationCalls, "local answer");
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(List.of(glm, ollama), new LlmGatewayFailureClassifier()),
                metrics);
        SubagentTask task = new SubagentTask(
                "correlation-a", 0, "task-a", "analysis", "RAW_PROMPT_SENTINEL", Map.of());

        SubagentResult result = core.execute(
                task,
                new SubagentProviderChain.AttemptBudget(),
                System.nanoTime() + 5_000_000_000L,
                ignored -> { });

        assertThat(generationCalls).hasValue(1);
        assertThat(result.structuredView())
                .containsEntry("taskId", "task-a")
                .containsEntry("correlationId", "correlation-a")
                .containsEntry("role", "analysis")
                .containsEntry("provider", "ollama")
                .containsEntry("status", "SUCCESS")
                .containsEntry("output", "local answer")
                .containsEntry("fallbackUsed", true)
                .containsEntry("attemptedProviders", List.of("ollama"))
                .containsEntry("errorClass", "NONE")
                .containsEntry("retryable", false)
                .containsEntry("blockedExternal", true);
        assertThat(result.warnings()).contains("blocked_external");
        assertThat(result.evidence()).isNotEmpty();
        assertThat(result.structuredView().toString()).doesNotContain("RAW_PROMPT_SENTINEL");

        SubagentAgentMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.callCount()).isEqualTo(1L);
        assertThat(snapshot.fallbackCount()).isEqualTo(1L);
        assertThat(snapshot.providerMetrics()).containsKey("ollama");
        assertThat(snapshot.providerMetrics().get("ollama").successCount()).isEqualTo(1L);
        assertThat(snapshot.averageElapsedMs()).isGreaterThanOrEqualTo(0.0d);
        assertThat(snapshot.p95ElapsedMs()).isGreaterThanOrEqualTo(0L);
        assertThat(snapshot.asMap().toString()).doesNotContain("RAW_PROMPT_SENTINEL");
    }

    @Test
    void firstMcpDelegateClaimsTheOnlyProbeAndClientOnlySuccessStaysDegraded() {
        AtomicInteger glmCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmActivationStateMachine activation = new GlmActivationStateMachine(
                () -> true, () -> true, () -> true, metrics);
        activation.refresh("closed");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.subagent.glm.enabled", "true")
                .withProperty("GLM_EXTERNAL_READY", "true");
        SubagentProvider glm = SubagentProviderConfiguration.glmProvider(
                environment,
                () -> "credential-sentinel",
                (apiKey, task, timeoutMs) -> {
                    glmCalls.incrementAndGet();
                    return "bounded GLM answer";
                },
                activation);
        SubagentProvider fallback = provider(
                "ollama", 30, SubagentProvider.Availability.enabled(),
                fallbackCalls, "local fallback");
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(List.of(glm, fallback), new LlmGatewayFailureClassifier()),
                metrics,
                null,
                activation);

        SubagentResult first = core.executeMcpDelegate(
                new SubagentTask("probe-correlation-1", 0, "probe-task-1", "analysis",
                        "bounded probe", Map.of()),
                new SubagentProviderChain.AttemptBudget(),
                System.nanoTime() + 5_000_000_000L,
                ignored -> { });
        SubagentResult second = core.executeMcpDelegate(
                new SubagentTask("probe-correlation-2", 0, "probe-task-2", "analysis",
                        "bounded follow-up", Map.of()),
                new SubagentProviderChain.AttemptBudget(),
                System.nanoTime() + 5_000_000_000L,
                ignored -> { });

        assertThat(first.selectedProvider()).isEqualTo("vercel-glm");
        assertThat(second.selectedProvider()).isEqualTo("ollama");
        assertThat(glmCalls).hasValue(1);
        assertThat(fallbackCalls).hasValue(1);
        assertThat(activation.snapshot().state())
                .isEqualTo(GlmActivationStateMachine.State.GLM_DEGRADED);
        assertThat(core.status())
                .containsEntry("generationAttempted", true)
                .containsEntry("activationState", "GLM_DEGRADED")
                .containsEntry("liveProbeSucceeded", false)
                .containsEntry("providerAttemptObserved", false)
                .containsEntry("wireAttemptObserved", false);
    }

    @Test
    void deadlineTooShortToInvokeGlmDoesNotConsumeTheOneShotProbe() {
        AtomicInteger glmCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmActivationStateMachine activation = new GlmActivationStateMachine(
                () -> true, () -> true, () -> true, metrics);
        activation.refresh("closed");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.subagent.glm.enabled", "true")
                .withProperty("GLM_EXTERNAL_READY", "true");
        SubagentProvider glm = SubagentProviderConfiguration.glmProvider(
                environment,
                () -> "credential-sentinel",
                (apiKey, task, timeoutMs) -> {
                    glmCalls.incrementAndGet();
                    return "bounded GLM answer";
                },
                activation);
        SubagentProvider fallback = provider(
                "ollama", 30, SubagentProvider.Availability.enabled(),
                fallbackCalls, "local fallback");
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(List.of(glm, fallback), new LlmGatewayFailureClassifier()),
                metrics,
                null,
                activation);

        SubagentResult tooShort = core.executeMcpDelegate(
                new SubagentTask("probe-short", 0, "probe-short", "analysis",
                        "bounded short deadline", Map.of()),
                new SubagentProviderChain.AttemptBudget(),
                System.nanoTime() + 1_000_000_000L,
                ignored -> { });

        assertThat(tooShort.selectedProvider()).isEqualTo("ollama");
        assertThat(glmCalls).hasValue(0);
        assertThat(fallbackCalls).hasValue(1);
        assertThat(activation.snapshot().state())
                .isEqualTo(GlmActivationStateMachine.State.READY_TO_PROBE);
        assertThat(activation.snapshot().liveProbeAttempted()).isFalse();

        SubagentResult valid = core.executeMcpDelegate(
                new SubagentTask("probe-valid", 0, "probe-valid", "analysis",
                        "bounded valid deadline", Map.of()),
                new SubagentProviderChain.AttemptBudget(),
                System.nanoTime() + 10_000_000_000L,
                ignored -> { });

        assertThat(valid.selectedProvider()).isEqualTo("vercel-glm");
        assertThat(glmCalls).hasValue(1);
        assertThat(fallbackCalls).hasValue(1);
        assertThat(activation.snapshot().liveProbeAttempted()).isTrue();
    }

    @Test
    void fallbackSuccessAfterGlm403RemainsExternallyBlockedAndDistinct() {
        AtomicInteger glmCalls = new AtomicInteger();
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmActivationStateMachine activation = new GlmActivationStateMachine(
                () -> true, () -> true, () -> true, metrics);
        activation.refresh("closed");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.subagent.glm.enabled", "true")
                .withProperty("GLM_EXTERNAL_READY", "true");
        SubagentProvider glm = SubagentProviderConfiguration.glmProvider(
                environment,
                () -> "credential-sentinel",
                (apiKey, task, timeoutMs) -> {
                    glmCalls.incrementAndGet();
                    throw new LlmGatewayException(
                            "categorical failure",
                            LlmFailureClass.AUTH_MISSING,
                            "responses_http_403");
                },
                activation);
        SubagentProvider fallback = provider(
                "ollama", 30, SubagentProvider.Availability.enabled(),
                new AtomicInteger(), "local fallback");
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(List.of(glm, fallback), new LlmGatewayFailureClassifier()),
                metrics,
                null,
                activation);

        SubagentResult result = core.executeMcpDelegate(
                new SubagentTask("probe-403", 0, "probe-403", "analysis",
                        "bounded probe", Map.of()),
                new SubagentProviderChain.AttemptBudget(),
                System.nanoTime() + 10_000_000_000L,
                ignored -> { });

        assertThat(result.status()).isEqualTo(SubagentResult.Status.SUCCESS);
        assertThat(result.selectedProvider()).isEqualTo("ollama");
        assertThat(glmCalls).hasValue(1);
        assertThat(core.status())
                .containsEntry("activationState", "BLOCKED_EXTERNAL")
                .containsEntry("activationReasonCode", "responses_http_403")
                .containsEntry("liveProbeSucceeded", false)
                .containsEntry("liveProbeFallbackUsed", true)
                .containsEntry("aggregateProvider", "ollama");
    }

    @Test
    void unexpectedProbeEscapeNeverLeavesActivationStuckInProbing() {
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmActivationStateMachine activation = new GlmActivationStateMachine(
                () -> true, () -> true, () -> true, metrics);
        SubagentProvider escapingProvider = new SubagentProvider() {
            @Override
            public String id() {
                return "vercel-glm";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public Availability availability() {
                return Availability.enabled();
            }

            @Override
            public String execute(SubagentTask task, long timeoutMs) {
                throw new AssertionError("probe_escape_sentinel");
            }
        };
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(
                        List.of(escapingProvider), new LlmGatewayFailureClassifier()),
                metrics,
                null,
                activation);

        assertThatThrownBy(() -> core.executeMcpDelegate(
                new SubagentTask("probe-escape", 0, "probe-escape", "analysis",
                        "bounded probe", Map.of()),
                new SubagentProviderChain.AttemptBudget(),
                System.nanoTime() + 10_000_000_000L,
                ignored -> { }))
                .isInstanceOf(AssertionError.class);

        assertThat(activation.snapshot().state())
                .isNotEqualTo(GlmActivationStateMachine.State.PROBING);
    }

    @Test
    void ordinaryExecutionCannotInvokeAReplacementGlmBeforeProbeClaim() {
        AtomicInteger glmCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        GlmActivationStateMachine activation = new GlmActivationStateMachine(
                () -> true, () -> true, () -> true, new SubagentAgentMetrics());
        activation.refresh("closed");
        SubagentProvider glm = provider(
                "vercel-glm", 10, SubagentProvider.Availability.enabled(), glmCalls, "glm output");
        SubagentProvider fallback = provider(
                "ollama", 30, SubagentProvider.Availability.enabled(), fallbackCalls, "fallback output");
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(List.of(glm, fallback), new LlmGatewayFailureClassifier()),
                new SubagentAgentMetrics(),
                null,
                activation);

        SubagentResult result = core.execute(
                new SubagentTask("ordinary", 0, "ordinary", "analysis",
                        "ordinary task", Map.of()),
                new SubagentProviderChain.AttemptBudget(),
                System.nanoTime() + 5_000_000_000L,
                ignored -> { });

        assertThat(result.selectedProvider()).isEqualTo("ollama");
        assertThat(glmCalls).hasValue(0);
        assertThat(fallbackCalls).hasValue(1);
        assertThat(result.attempts()).anySatisfy(attempt -> assertThat(attempt)
                .returns("vercel-glm", SubagentResult.Attempt::provider)
                .returns(SubagentResult.AttemptOutcome.SKIPPED, SubagentResult.Attempt::outcome)
                .returns("activation_not_allowed", SubagentResult.Attempt::reasonCode));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> providerRows(Map<String, Object> status) {
        return (List<Map<String, Object>>) status.get("providers");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> recentErrorClasses(Map<String, Object> status) {
        return (Map<String, String>) status.get("recentErrorClasses");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metrics(Map<String, Object> status) {
        return (Map<String, Object>) status.get("metrics");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> recentRequestTimeline(Map<String, Object> status) {
        return (List<Map<String, Object>>) status.get("recentRequestTimeline");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> recentClientHttpLedger(Map<String, Object> status) {
        return (List<Map<String, Object>>) status.get("recentClientHttpLedger");
    }

    private static SubagentProvider provider(String id,
                                             int order,
                                             SubagentProvider.Availability availability,
                                             AtomicInteger calls,
                                             String output) {
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
                return availability;
            }

            @Override
            public String execute(SubagentTask task, long timeoutMs) {
                calls.incrementAndGet();
                return output;
            }
        };
    }
}
