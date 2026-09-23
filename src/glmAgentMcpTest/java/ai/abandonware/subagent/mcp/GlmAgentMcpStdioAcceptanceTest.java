package ai.abandonware.subagent.mcp;

import com.abandonware.ai.agent.orchestrator.subagent.GlmAgentCore;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentProvider;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentProviderChain;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentResult;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentTask;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GlmAgentMcpStdioAcceptanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(15);
    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "glm_delegate_task",
            "glm_review_change",
            "glm_consensus_check",
            "glm_agent_status");

    @Test
    void initializeNegotiatesAndListsExactlyFourReadOnlyToolsWithoutStdoutNoise() throws Exception {
        RunningServer server = startServer(0L);
        try (BufferedWriter stdin = server.stdin();
             BufferedReader stdout = server.stdout()) {
            initialize(stdin, stdout);

            writeFrame(stdin, Map.of(
                    "jsonrpc", "2.0",
                    "id", 2,
                    "method", "tools/list",
                    "params", Map.of()));

            JsonNode listed = readFrame(stdout);
            assertThat(listed.path("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(listed.path("id").asInt()).isEqualTo(2);
            Set<String> names = new LinkedHashSet<>();
            listed.path("result").path("tools").forEach(tool -> {
                names.add(tool.path("name").asText());
                assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
                assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isFalse();
                assertThat(tool.path("inputSchema").path("additionalProperties").asBoolean(true)).isFalse();
            });
            assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
            JsonNode tools = listed.path("result").path("tools");
            assertThat(findTool(tools, "glm_delegate_task").path("inputSchema")
                    .path("properties").has("constraints")).isTrue();
            assertThat(findTool(tools, "glm_delegate_task").path("inputSchema")
                    .path("properties").has("expectedOutputSchema")).isTrue();
            assertThat(findTool(tools, "glm_delegate_task").path("inputSchema")
                    .path("properties").has("validateExecutionPacket")).isTrue();
            String expectedOutputSchemaDescription = findTool(tools, "glm_delegate_task")
                    .path("inputSchema").path("properties").path("expectedOutputSchema")
                    .path("description").asText();
            assertThat(expectedOutputSchemaDescription)
                    .containsIgnoringCase("advisory bounded prompt hint")
                    .containsIgnoringCase("not post-generation validation");
            assertThat(findTool(tools, "glm_review_change").path("inputSchema")
                    .path("properties").has("reviewRubric")).isTrue();
            assertThat(findTool(tools, "glm_review_change").path("outputSchema")
                    .path("properties").has("recommendedPatch")).isTrue();
            assertThat(findTool(tools, "glm_consensus_check").path("outputSchema")
                    .path("properties").has("neutralVerdict")).isTrue();
            assertThat(findTool(tools, "glm_consensus_check").path("outputSchema")
                    .path("properties").has("neutralDecision")).isTrue();
            assertThat(findTool(tools, "glm_agent_status").path("outputSchema")
                    .path("properties").has("recentErrorClasses")).isTrue();
            assertThat(findTool(tools, "glm_agent_status").path("outputSchema")
                    .path("properties").has("activationState")).isTrue();
            assertThat(findTool(tools, "glm_agent_status").path("outputSchema")
                    .path("properties").has("recentRequestTimeline")).isTrue();
            assertThat(findTool(tools, "glm_agent_status").path("outputSchema")
                    .path("properties").has("recentClientHttpLedger")).isTrue();

            stdin.close();
            assertThat(server.process().waitFor(3, TimeUnit.SECONDS)).isTrue();
            String trailingLine;
            while ((trailingLine = stdout.readLine()) != null) {
                assertThat(JSON.readTree(trailingLine).path("jsonrpc").asText())
                        .as("every stdout line remains a JSON-RPC frame")
                        .isEqualTo("2.0");
            }
        } finally {
            server.stop();
        }

        assertThat(server.stderr()).contains("event=mcp.server.started");
        assertThat(server.stderr()).contains("event=mcp.protocol.negotiated");
        assertThat(server.stderr()).contains("timestamp=");
        assertThat(server.stderr()).contains("transport=stdio");
        assertThat(server.stderr()).contains("status=negotiated");
    }

    @Test
    void invokesEachReadOnlyToolWithStructuredResultsThroughTheSharedCore() throws Exception {
        String secretMarker = "SENSITIVE-PROMPT-MUST-NOT-BE-LOGGED";
        RunningServer server = startServer(0L);
        try (BufferedWriter stdin = server.stdin();
             BufferedReader stdout = server.stdout()) {
            initialize(stdin, stdout);

            JsonNode delegated = callTool(stdin, stdout, 10, "glm_delegate_task", Map.of(
                    "task", "Analyze this supplied statement: " + secretMarker,
                    "role", "analysis",
                    "context", Map.of("source", "acceptance-test"),
                    "constraints", List.of("Use only supplied context", "Return categorical evidence"),
                    "expectedOutputSchema", Map.of(
                            "type", "object",
                            "required", List.of("summary")),
                    "timeoutMs", 5_000));
            assertSuccessful(delegated);
            JsonNode delegatedBody = delegated.path("result").path("structuredContent");
            assertThat(delegatedBody.path("provider").asText()).isEqualTo("deterministic");
            assertThat(delegatedBody.path("correlationId").asText()).isNotBlank();
            assertThat(delegatedBody.path("attemptedProviders").isArray()).isTrue();
            assertThat(delegatedBody.path("evidence").isArray()).isTrue();
            assertThat(delegatedBody.path("warnings").isArray()).isTrue();

            JsonNode reviewed = callTool(stdin, stdout, 11, "glm_review_change", Map.of(
                    "diff", "@@ supplied-only @@\n-old\n+new",
                    "reviewRubric", "Check safety and reversibility.",
                    "timeoutMs", 5_000));
            assertSuccessful(reviewed);
            JsonNode reviewedBody = reviewed.path("result").path("structuredContent");
            assertThat(reviewedBody.path("role").asText()).isEqualTo("review");
            assertThat(reviewedBody.path("findings").isArray()).isTrue();
            assertThat(reviewedBody.path("severity").asText()).isNotBlank();
            assertThat(reviewedBody.path("evidence").isArray()).isTrue();
            assertThat(reviewedBody.path("recommendedPatch").isTextual()).isTrue();

            JsonNode consensus = callTool(stdin, stdout, 12, "glm_consensus_check", Map.of(
                    "question", "Is the supplied proposal internally consistent?",
                    "context", Map.of("scope", "supplied-input-only"),
                    "timeoutMs", 5_000));
            assertSuccessful(consensus);
            JsonNode consensusBody = consensus.path("result").path("structuredContent");
            assertThat(consensusBody.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(consensusBody.path("results")).hasSize(3);
            assertThat(consensusBody.path("optimisticFindings").isArray()).isTrue();
            assertThat(consensusBody.path("criticalFindings").isArray()).isTrue();
            assertThat(consensusBody.path("neutralVerdict").isTextual()).isTrue();
            assertThat(consensusBody.path("counterexamples").isArray()).isTrue();
            assertThat(consensusBody.path("unknowns").isArray()).isTrue();
            consensusBody.path("results").forEach(result ->
                    assertThat(result.path("provider").asText()).isEqualTo("deterministic"));

            JsonNode status = callTool(stdin, stdout, 13, "glm_agent_status", Map.of());
            assertSuccessful(status);
            JsonNode statusBody = status.path("result").path("structuredContent");
            assertThat(statusBody.path("generationAttempted").asBoolean()).isFalse();
            assertThat(statusBody.path("activationState").asText())
                    .isEqualTo("BLOCKED_EXTERNAL");
            assertThat(statusBody.path("activationReasonCode").asText())
                    .isEqualTo("auto_activation_disabled");
            assertThat(statusBody.path("liveProbeAttempted").asBoolean()).isFalse();
            assertThat(statusBody.path("liveProbeSucceeded").asBoolean()).isFalse();
            assertThat(statusBody.path("providerAttemptObserved").asBoolean()).isFalse();
            assertThat(statusBody.path("wireAttemptObserved").asBoolean()).isFalse();
            assertThat(statusBody.path("recentErrorClasses").isObject()).isTrue();
            assertThat(statusBody.path("recentRequestTimeline").isArray()).isTrue();
            assertThat(statusBody.path("recentClientHttpLedger").isArray()).isTrue();
            assertThat(statusBody.path("metrics").path("callCount").asLong()).isEqualTo(5L);
        } finally {
            server.stop();
        }

        assertThat(server.stderr()).contains("event=mcp.tool.started");
        assertThat(server.stderr()).contains("event=mcp.tool.completed");
        assertThat(server.stderr()).contains("taskIdHash=");
        assertThat(server.stderr()).contains("provider=deterministic");
        assertThat(server.stderr()).contains("fallbackUsed=");
        assertThat(server.stderr()).contains("circuitState=none");
        assertThat(server.stderr()).doesNotContain(secretMarker);
    }

    @Test
    void allDisabledConsensusPreservesDisabledAsNonRetryable() throws Exception {
        RunningServer server = startServer(0L, false, "stdio");
        try (BufferedWriter stdin = server.stdin();
             BufferedReader stdout = server.stdout()) {
            initialize(stdin, stdout);

            JsonNode consensus = callTool(stdin, stdout, 14, "glm_consensus_check", Map.of(
                    "question", "Can any disabled provider evaluate this supplied claim?",
                    "timeoutMs", 5_000));
            assertThat(consensus.path("result").path("isError").asBoolean()).isTrue();
            JsonNode body = consensus.path("result").path("structuredContent");
            assertThat(body.path("status").asText()).isEqualTo("FAILED");
            assertThat(body.path("errorClass").asText()).isEqualTo("DISABLED");
            assertThat(body.path("retryable").asBoolean(true)).isFalse();
            assertThat(body.path("results")).hasSize(3);
            body.path("results").forEach(result -> {
                assertThat(result.path("errorClass").asText()).isEqualTo("DISABLED");
                assertThat(result.path("retryable").asBoolean(true)).isFalse();
            });
        } finally {
            server.stop();
        }
    }

    @Test
    void consensusTimeoutPreservesCompletedPerspectiveAndTimesOutOnlyUnfinishedRows() {
        SubagentProvider provider = roleDelayedProvider(0L, 5_000L, 5_000L);
        GlmAgentCore core = new GlmAgentCore(new SubagentProviderChain(
                List.of(provider), new LlmGatewayFailureClassifier()));

        try (GlmAgentMcpTools tools = new GlmAgentMcpTools(core)) {
            long started = System.nanoTime();
            DirectToolResult result = callDirectTool(tools, "glm_consensus_check", Map.of(
                    "question", "Exercise partial consensus deadline preservation.",
                    "timeoutMs", 500));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            JsonNode body = JSON.valueToTree(result.structuredContent());

            assertThat(result.error()).isFalse();
            assertThat(body.path("status").asText()).isEqualTo("PARTIAL");
            assertThat(body.path("errorClass").asText()).isEqualTo("TIMEOUT_SOFT");
            assertThat(body.path("retryable").asBoolean()).isTrue();
            assertThat(body.path("successfulPerspectives").asInt()).isEqualTo(1);
            assertThat(body.path("totalPerspectives").asInt()).isEqualTo(3);
            assertThat(body.path("results")).hasSize(3);
            assertPerspective(body.path("results").get(0),
                    "support", "SUCCESS", "NONE", false, "deterministic");
            assertPerspective(body.path("results").get(1),
                    "falsify", "TIMEOUT", "TIMEOUT_SOFT", true, "none");
            assertPerspective(body.path("results").get(2),
                    "neutral", "TIMEOUT", "TIMEOUT_SOFT", true, "none");
            assertThat(body.path("optimisticFindings")).isNotEmpty();
            assertThat(body.path("criticalFindings")).isEmpty();
            assertThat(body.path("neutralVerdict").asText()).isEmpty();
            assertThat(body.path("unknowns")).extracting(JsonNode::asText)
                    .containsExactly(
                            "falsify:TIMEOUT_SOFT",
                            "neutral:TIMEOUT_SOFT",
                            "neutral_contract_invalid");
            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        }
    }

    @Test
    void consensusSubSecondDeadlineRemainsMillisecondBounded() {
        SubagentProvider provider = roleDelayedProvider(5_000L, 5_000L, 5_000L);
        GlmAgentCore core = new GlmAgentCore(new SubagentProviderChain(
                List.of(provider), new LlmGatewayFailureClassifier()));

        try (GlmAgentMcpTools tools = new GlmAgentMcpTools(core)) {
            long started = System.nanoTime();
            DirectToolResult result = callDirectTool(tools, "glm_consensus_check", Map.of(
                    "question", "Characterize the sub-second consensus deadline.",
                    "timeoutMs", 125));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            JsonNode body = JSON.valueToTree(result.structuredContent());

            assertThat(result.error()).isTrue();
            assertThat(body.path("status").asText()).isEqualTo("TIMEOUT");
            assertThat(body.path("errorClass").asText()).isEqualTo("TIMEOUT_SOFT");
            assertThat(body.path("retryable").asBoolean()).isTrue();
            assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void consensusPartialResultSurvivesToolExecutorQueueDelay() throws Exception {
        CountDownLatch blockersStarted = new CountDownLatch(4);
        CountDownLatch releaseBlockers = new CountDownLatch(1);
        SubagentProvider provider = queueDelayedProvider(blockersStarted, releaseBlockers);
        GlmAgentCore core = new GlmAgentCore(new SubagentProviderChain(
                List.of(provider), new LlmGatewayFailureClassifier()));

        try (GlmAgentMcpTools tools = new GlmAgentMcpTools(core)) {
            ExecutorService blockerCallers = Executors.newFixedThreadPool(4);
            List<CompletableFuture<DirectToolResult>> blockers = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                int ordinal = index;
                blockers.add(CompletableFuture.supplyAsync(() -> callDirectTool(
                        tools,
                        "glm_delegate_task",
                        Map.of(
                                "task", "Occupy tool worker " + ordinal + ".",
                                "role", "queue-blocker",
                                "timeoutMs", 2_000)), blockerCallers));
            }
            assertThat(blockersStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread delayedRelease = new Thread(() -> {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    releaseBlockers.countDown();
                }
            }, "glm-agent-mcp-test-release");
            delayedRelease.setDaemon(true);
            delayedRelease.start();

            try {
                DirectToolResult result = callDirectTool(tools, "glm_consensus_check", Map.of(
                        "question", "Preserve partial evidence after bounded queue delay.",
                        "timeoutMs", 500));
                JsonNode body = JSON.valueToTree(result.structuredContent());

                assertThat(result.error()).isFalse();
                assertThat(body.path("status").asText()).isEqualTo("PARTIAL");
                assertThat(body.path("successfulPerspectives").asInt()).isEqualTo(1);
                assertPerspective(body.path("results").get(0),
                        "support", "SUCCESS", "NONE", false, "deterministic");
                assertPerspective(body.path("results").get(1),
                        "falsify", "TIMEOUT", "TIMEOUT_SOFT", true, "none");
                assertPerspective(body.path("results").get(2),
                        "neutral", "TIMEOUT", "TIMEOUT_SOFT", true, "none");
            } finally {
                releaseBlockers.countDown();
                delayedRelease.join(1_000L);
                blockers.forEach(CompletableFuture::join);
                blockerCallers.shutdownNow();
                assertThat(blockerCallers.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void consensusCancellationRemainsNonRetryableWhenAnotherPerspectiveTimesOut() {
        SubagentProvider provider = mixedCancellationProvider();
        GlmAgentCore core = new GlmAgentCore(new SubagentProviderChain(
                List.of(provider), new LlmGatewayFailureClassifier()));

        try (GlmAgentMcpTools tools = new GlmAgentMcpTools(core)) {
            DirectToolResult result = callDirectTool(tools, "glm_consensus_check", Map.of(
                    "question", "Preserve cancellation precedence over timeout.",
                    "timeoutMs", 500));
            JsonNode body = JSON.valueToTree(result.structuredContent());

            assertThat(result.error()).isFalse();
            assertThat(body.path("status").asText()).isEqualTo("PARTIAL");
            assertThat(body.path("errorClass").asText()).isEqualTo("CANCELLED_NEUTRAL");
            assertThat(body.path("retryable").asBoolean(true)).isFalse();
            assertPerspective(body.path("results").get(0),
                    "support", "SUCCESS", "NONE", false, "deterministic");
            assertPerspective(body.path("results").get(1),
                    "falsify", "CANCELLED", "CANCELLED_NEUTRAL", false, "none");
            assertPerspective(body.path("results").get(2),
                    "neutral", "TIMEOUT", "TIMEOUT_SOFT", true, "none");
        }
    }

    @Test
    void preCancelledFutureRemainsCancelledWhileOnlyUnfinishedFutureBecomesTimeout()
            throws Exception {
        CompletableFuture<SubagentResult> alreadyHandled = CompletableFuture.completedFuture(null);
        FutureTask<SubagentResult> preCancelled = new FutureTask<>(() -> null);
        preCancelled.cancel(true);
        FutureTask<SubagentResult> unfinished = new FutureTask<>(() -> null);
        List<Future<SubagentResult>> futures = List.of(alreadyHandled, preCancelled, unfinished);
        List<Map<String, Object>> results = new ArrayList<>(Collections.nCopies(3, null));
        results.set(0, Map.of(
                "perspective", "support",
                "status", "SUCCESS",
                "errorClass", "NONE"));

        var preservation = GlmAgentMcpTools.class.getDeclaredMethod(
                "preserveCompletedAndTimeoutUnfinished", List.class, List.class);
        preservation.setAccessible(true);
        preservation.invoke(null, futures, results);

        assertPerspective(results.get(1),
                "falsify", "CANCELLED", "CANCELLED_NEUTRAL", false, "none");
        assertPerspective(results.get(2),
                "neutral", "TIMEOUT", "TIMEOUT_SOFT", true, "none");
    }

    @Test
    void neutralRunsAfterAndReceivesCompletedSupportAndFalsifyPackets() {
        AtomicReference<String> neutralPrompt = new AtomicReference<>("");
        SubagentProvider provider = new SubagentProvider() {
            @Override
            public String id() {
                return "deterministic";
            }

            @Override
            public int order() {
                return 15;
            }

            @Override
            public Availability availability() {
                return Availability.enabled();
            }

            @Override
            public String execute(SubagentTask task, long timeoutMs) {
                return switch (task.role()) {
                    case "support" -> "SUPPORT_PACKET_SENTINEL";
                    case "falsify" -> "FALSIFY_PACKET_SENTINEL";
                    default -> {
                        neutralPrompt.set(task.prompt());
                        yield "{\"verdict\":\"APPLY\",\"counterexamples\":[\"bounded\"],"
                                + "\"unknowns\":[],\"recommendedNextTest\":\"focused neutral test\"}";
                    }
                };
            }
        };
        GlmAgentCore core = new GlmAgentCore(new SubagentProviderChain(
                List.of(provider), new LlmGatewayFailureClassifier()));

        try (GlmAgentMcpTools tools = new GlmAgentMcpTools(core)) {
            DirectToolResult result = callDirectTool(tools, "glm_consensus_check", Map.of(
                    "question", "Adjudicate only the supplied bounded claim.",
                    "timeoutMs", 5_000));
            JsonNode body = JSON.valueToTree(result.structuredContent());

            assertThat(result.error()).isFalse();
            assertThat(neutralPrompt.get())
                    .contains("SUPPORT_PACKET_SENTINEL")
                    .contains("FALSIFY_PACKET_SENTINEL");
            assertThat(body.path("neutralDecision").asText()).isEqualTo("APPLY");
            assertThat(body.path("recommendedNextTest").asText())
                    .isEqualTo("focused neutral test");
            assertThat(body.path("hostMutationAllowed").asBoolean()).isTrue();
        }
    }

    @Test
    void delegateTypedPacketValidationIsSeparateFromProviderSuccess() {
        String validPacket = "{"
                + "\"goal\":\"Apply one bounded repair\","
                + "\"evidence\":[\"focused RED\"],"
                + "\"affectedFiles\":[\"main/java/example/Target.java\"],"
                + "\"nonGoals\":[\"no provider changes\"],"
                + "\"proposedPatch\":\"bounded patch description\","
                + "\"redTest\":\"TargetTest reproduces the defect\","
                + "\"greenTests\":[\"TargetTest\"],"
                + "\"regressionTests\":[\"focused suite\"],"
                + "\"rollbackCondition\":\"focused regression fails\","
                + "\"securityChecks\":[\"secret scan\"],"
                + "\"unknowns\":[]}";
        AtomicReference<String> output = new AtomicReference<>(validPacket);
        SubagentProvider provider = simpleProvider(output);
        GlmAgentCore core = new GlmAgentCore(new SubagentProviderChain(
                List.of(provider), new LlmGatewayFailureClassifier()));

        try (GlmAgentMcpTools tools = new GlmAgentMcpTools(core)) {
            DirectToolResult valid = callDirectTool(tools, "glm_delegate_task", Map.of(
                    "task", "Return a bounded execution packet.",
                    "validateExecutionPacket", true,
                    "timeoutMs", 5_000));
            JsonNode validBody = JSON.valueToTree(valid.structuredContent());

            assertThat(valid.error()).isFalse();
            assertThat(validBody.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(validBody.path("executionPacketValidation").path("status").asText())
                    .isEqualTo("VALID");
            assertThat(validBody.path("hostMutationAllowed").asBoolean()).isTrue();

            output.set("not-json");
            DirectToolResult invalid = callDirectTool(tools, "glm_delegate_task", Map.of(
                    "task", "Return another bounded execution packet.",
                    "validateExecutionPacket", true,
                    "timeoutMs", 5_000));
            JsonNode invalidBody = JSON.valueToTree(invalid.structuredContent());

            assertThat(invalid.error()).isFalse();
            assertThat(invalidBody.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(invalidBody.path("executionPacketValidation").path("status").asText())
                    .isEqualTo("INVALID");
            assertThat(invalidBody.path("hostMutationAllowed").asBoolean()).isFalse();
        }
    }

    @Test
    void unsupportedTransportExitsNonZeroWithoutStdoutProtocolFrames() throws Exception {
        RunningServer server = startServer(0L, false, "unsupported");
        try {
            assertThat(server.process().waitFor(3, TimeUnit.SECONDS)).isTrue();
            assertThat(server.process().exitValue()).isNotZero();
            assertThat(server.stdout().readLine()).isNull();
        } finally {
            server.stop();
        }

        assertThat(server.stderr()).contains("event=mcp.server.failed");
        assertThat(server.stderr()).contains("transport=unsupported");
        assertThat(server.stderr()).contains("status=failed");
        assertThat(server.stderr()).contains("errorClass=UNSUPPORTED_TRANSPORT");
    }

    @Test
    void rejectsInvalidInputBeforeAnyProviderCall() throws Exception {
        RunningServer server = startServer(0L);
        try (BufferedWriter stdin = server.stdin();
             BufferedReader stdout = server.stdout()) {
            initialize(stdin, stdout);

            JsonNode invalid = callTool(stdin, stdout, 20, "glm_delegate_task", Map.of(
                    "timeoutMs", 5_000));
            assertThat(invalid.path("error").isObject()
                    || invalid.path("result").path("isError").asBoolean()).isTrue();

            JsonNode status = callTool(stdin, stdout, 21, "glm_agent_status", Map.of());
            assertSuccessful(status);
            assertThat(status.path("result").path("structuredContent")
                    .path("metrics").path("callCount").asLong()).isZero();
        } finally {
            server.stop();
        }
    }

    @Test
    void convertsToolDeadlineIntoStructuredTimeoutAndInterruptsWork() throws Exception {
        RunningServer server = startServer(10_000L);
        Duration callElapsed;
        try (BufferedWriter stdin = server.stdin();
             BufferedReader stdout = server.stdout()) {
            initialize(stdin, stdout);

            long started = System.nanoTime();
            JsonNode timedOut = callTool(stdin, stdout, 30, "glm_delegate_task", Map.of(
                    "task", "Exercise the deterministic timeout path.",
                    "timeoutMs", 100));
            callElapsed = Duration.ofNanos(System.nanoTime() - started);
            assertThat(timedOut.path("result").path("isError").asBoolean()).isTrue();
            JsonNode body = timedOut.path("result").path("structuredContent");
            assertThat(body.path("status").asText()).isEqualTo("TIMEOUT");
            assertThat(body.path("errorClass").asText()).isEqualTo("TIMEOUT_SOFT");
        } finally {
            server.stop();
        }

        assertThat(callElapsed).isLessThan(Duration.ofSeconds(3));
        assertThat(server.stderr()).contains("event=mcp.tool.failed");
        assertThat(server.stderr()).contains("errorClass=TIMEOUT_SOFT");
    }

    @Test
    void eofCancelsActiveWorkAndShutsDownCleanlyWithoutWaitingForProviderDelay() throws Exception {
        RunningServer server = startServer(30_000L);
        try {
            initialize(server.stdin(), server.stdout());
            writeFrame(server.stdin(), Map.of(
                    "jsonrpc", "2.0",
                    "id", 40,
                    "method", "tools/call",
                    "params", Map.of(
                            "name", "glm_delegate_task",
                            "arguments", Map.of(
                                    "task", "Remain active until stdin reaches EOF.",
                                    "timeoutMs", 60_000))));
            assertThat(server.awaitStderr("event=mcp.tool.started", Duration.ofSeconds(3))).isTrue();

            long eofStarted = System.nanoTime();
            server.stdin().close();
            assertThat(server.process().waitFor(3, TimeUnit.SECONDS)).isTrue();
            assertThat(server.process().exitValue()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - eofStarted)).isLessThan(Duration.ofSeconds(3));
        } finally {
            server.stop();
        }
    }

    private static JsonNode initialize(BufferedWriter stdin, BufferedReader stdout) throws Exception {
        writeFrame(stdin, Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2025-11-25",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "awx-mcp-test", "version", "1.0"))));

        JsonNode initialized = readFrame(stdout);
        assertThat(initialized.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(initialized.path("id").asInt()).isEqualTo(1);
        assertThat(initialized.path("result").path("protocolVersion").asText()).isNotBlank();
        writeFrame(stdin, Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized"));
        return initialized;
    }

    private static JsonNode callTool(BufferedWriter stdin,
                                     BufferedReader stdout,
                                     int id,
                                     String name,
                                     Map<String, Object> arguments) throws Exception {
        writeFrame(stdin, Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", "tools/call",
                "params", Map.of("name", name, "arguments", arguments)));
        JsonNode response = readFrame(stdout);
        assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.path("id").asInt()).isEqualTo(id);
        return response;
    }

    private static JsonNode findTool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("missing tool: " + name);
    }

    private static void assertSuccessful(JsonNode response) {
        assertThat(response.path("error").isMissingNode()).isTrue();
        assertThat(response.path("result").path("isError").asBoolean(false)).isFalse();
        assertThat(response.path("result").path("structuredContent").isObject()).isTrue();
        assertThat(response.path("result").path("content").isArray()).isTrue();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static DirectToolResult callDirectTool(GlmAgentMcpTools tools,
                                                    String name,
                                                    Map<String, Object> arguments) {
        try {
            Object rawSpecifications = GlmAgentMcpTools.class
                    .getMethod("specifications")
                    .invoke(tools);
            Object selected = null;
            for (Object candidate : (List<?>) rawSpecifications) {
                Object tool = candidate.getClass().getMethod("tool").invoke(candidate);
                String candidateName = String.valueOf(
                        tool.getClass().getMethod("name").invoke(tool));
                if (name.equals(candidateName)) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                throw new IllegalArgumentException("missing tool: " + name);
            }

            Object handler = selected.getClass().getMethod("callHandler").invoke(selected);
            Class<?> requestType = Class.forName(
                    "io.modelcontextprotocol.spec.McpSchema$CallToolRequest");
            Object request = requestType
                    .getConstructor(String.class, Map.class)
                    .newInstance(name, arguments);
            Object publisher = ((java.util.function.BiFunction) handler).apply(null, request);
            Object callResult = publisher.getClass()
                    .getMethod("block", Duration.class)
                    .invoke(publisher, Duration.ofSeconds(3));
            Object structuredContent = callResult.getClass()
                    .getMethod("structuredContent")
                    .invoke(callResult);
            boolean error = Boolean.TRUE.equals(callResult.getClass()
                    .getMethod("isError")
                    .invoke(callResult));
            return new DirectToolResult(structuredContent, error);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("direct MCP tool invocation failed", exception);
        }
    }

    private record DirectToolResult(Object structuredContent, boolean error) {
    }

    private static SubagentProvider simpleProvider(AtomicReference<String> output) {
        return new SubagentProvider() {
            @Override
            public String id() {
                return "deterministic";
            }

            @Override
            public int order() {
                return 15;
            }

            @Override
            public Availability availability() {
                return Availability.enabled();
            }

            @Override
            public String execute(SubagentTask task, long timeoutMs) {
                return output.get();
            }
        };
    }

    private static SubagentProvider roleDelayedProvider(long supportDelayMs,
                                                         long falsifyDelayMs,
                                                         long neutralDelayMs) {
        return new SubagentProvider() {
            @Override
            public String id() {
                return "deterministic";
            }

            @Override
            public int order() {
                return 15;
            }

            @Override
            public Availability availability() {
                return Availability.enabled();
            }

            @Override
            public String execute(SubagentTask task, long timeoutMs) throws Exception {
                long delayMs = switch (task.role()) {
                    case "support" -> supportDelayMs;
                    case "falsify" -> falsifyDelayMs;
                    default -> neutralDelayMs;
                };
                if (delayMs > 0L) {
                    Thread.sleep(delayMs);
                }
                return "Deterministic read-only result for role " + task.role() + ".";
            }
        };
    }

    private static SubagentProvider queueDelayedProvider(CountDownLatch blockersStarted,
                                                          CountDownLatch releaseBlockers) {
        return new SubagentProvider() {
            @Override
            public String id() {
                return "deterministic";
            }

            @Override
            public int order() {
                return 15;
            }

            @Override
            public Availability availability() {
                return Availability.enabled();
            }

            @Override
            public String execute(SubagentTask task, long timeoutMs) throws Exception {
                if ("queue-blocker".equals(task.role())) {
                    blockersStarted.countDown();
                    releaseBlockers.await();
                    return "Released queue blocker.";
                }
                if ("support".equals(task.role())) {
                    return "Completed support perspective.";
                }
                Thread.sleep(5_000L);
                return "Unexpected delayed completion.";
            }
        };
    }

    private static SubagentProvider mixedCancellationProvider() {
        return new SubagentProvider() {
            @Override
            public String id() {
                return "deterministic";
            }

            @Override
            public int order() {
                return 15;
            }

            @Override
            public Availability availability() {
                return Availability.enabled();
            }

            @Override
            public String execute(SubagentTask task, long timeoutMs) throws Exception {
                if ("support".equals(task.role())) {
                    return "Completed support perspective.";
                }
                if ("falsify".equals(task.role())) {
                    throw new java.util.concurrent.CancellationException("deterministic_cancel");
                }
                Thread.sleep(5_000L);
                return "Unexpected delayed completion.";
            }
        };
    }

    private static void assertPerspective(JsonNode result,
                                          String perspective,
                                          String status,
                                          String errorClass,
                                          boolean retryable,
                                          String provider) {
        assertThat(result.path("perspective").asText()).isEqualTo(perspective);
        assertThat(result.path("status").asText()).isEqualTo(status);
        assertThat(result.path("errorClass").asText()).isEqualTo(errorClass);
        assertThat(result.path("retryable").asBoolean()).isEqualTo(retryable);
        assertThat(result.path("provider").asText()).isEqualTo(provider);
    }

    private static void assertPerspective(Map<String, Object> result,
                                          String perspective,
                                          String status,
                                          String errorClass,
                                          boolean retryable,
                                          String provider) {
        assertThat(result)
                .containsEntry("perspective", perspective)
                .containsEntry("status", status)
                .containsEntry("errorClass", errorClass)
                .containsEntry("retryable", retryable)
                .containsEntry("provider", provider);
    }

    private static RunningServer startServer(long deterministicDelayMs) throws Exception {
        return startServer(deterministicDelayMs, true, "stdio");
    }

    private static RunningServer startServer(long deterministicDelayMs,
                                             boolean deterministicProvider,
                                             String transport) throws Exception {
        String jarProperty = System.getProperty("glm.agent.mcp.jar");
        assertThat(jarProperty).as("glm.agent.mcp.jar test property").isNotBlank();
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        ProcessBuilder builder = new ProcessBuilder(
                java.toAbsolutePath().toString(),
                "-Dglm.agent.mcp.deterministic-provider=" + deterministicProvider,
                "-Dglm.agent.mcp.deterministic-delay-ms=" + deterministicDelayMs,
                "-Dagent.subagent.glm.enabled=false",
                "-Dllmrouter.models.openai-balanced.enabled=false",
                "-Dllmrouter.models.light.enabled=false",
                "-jar",
                Path.of(jarProperty).toAbsolutePath().toString(),
                "--transport=" + transport);
        builder.environment().remove("AI_GATEWAY_API_KEY");
        return new RunningServer(builder.start());
    }

    private static void writeFrame(BufferedWriter writer, Map<String, Object> frame) throws Exception {
        writer.write(JSON.writeValueAsString(frame));
        writer.newLine();
        writer.flush();
    }

    private static JsonNode readFrame(BufferedReader reader) throws Exception {
        String line = CompletableFuture.supplyAsync(() -> {
            try {
                return reader.readLine();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }).get(FRAME_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(line).as("one JSON-RPC frame on stdout").isNotNull().isNotBlank();
        return JSON.readTree(line);
    }

    private static final class RunningServer {
        private final Process process;
        private final BufferedWriter stdin;
        private final BufferedReader stdout;
        private final StringBuilder stderr = new StringBuilder();
        private final Thread stderrCollector;

        private RunningServer(Process process) {
            this.process = process;
            this.stdin = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            this.stderrCollector = new Thread(this::collectStderr, "glm-agent-mcp-test-stderr");
            this.stderrCollector.setDaemon(true);
            this.stderrCollector.start();
        }

        private Process process() {
            return process;
        }

        private BufferedWriter stdin() {
            return stdin;
        }

        private BufferedReader stdout() {
            return stdout;
        }

        private String stderr() {
            synchronized (stderr) {
                return stderr.toString();
            }
        }

        private boolean awaitStderr(String marker, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (stderr().contains(marker)) {
                    return true;
                }
                Thread.sleep(10L);
            }
            return stderr().contains(marker);
        }

        private void collectStderr() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderr) {
                        stderr.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
                // Process teardown can close the pipe while the collector is reading.
            }
        }

        private void stop() throws Exception {
            try {
                stdin.close();
            } catch (Exception ignored) {
                // Already closed by the EOF test.
            }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy();
            }
            if (process.isAlive() && !process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            stderrCollector.join(1_000L);
        }
    }
}
