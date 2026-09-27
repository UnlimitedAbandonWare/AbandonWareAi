package ai.abandonware.subagent.mcp;

import com.abandonware.ai.agent.orchestrator.subagent.SubagentProvider;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentTask;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevinCliProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @org.junit.jupiter.api.io.TempDir Path temporary;

    @Test
    void publicReviewPreservesTheFullTaskAndUsesExactlyTheRequestedFreeVariant() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> selected = new AtomicReference<>();
        AtomicReference<JsonNode> packet = new AtomicReference<>();
        DevinCliProvider provider = provider((model, bytes, timeout) -> {
            calls.incrementAndGet(); selected.set(model); packet.set(JSON.readTree(bytes));
            return receipt(model, "Free");
        });
        String prompt = "Public fixture: return sum(values) / len(values). Preserve the empty-list constraint.";
        JsonNode output = JSON.readTree(provider.execute(task(prompt, "high"), 90_000L));
        assertThat(calls).hasValue(1);
        assertThat(selected.get()).isEqualTo("swe-2-high");
        assertThat(packet.get().path("code").asText()).isEqualTo(prompt);
        assertThat(output.path("selectedModel").asText()).isEqualTo("swe-2-high");
        assertThat(output.path("billingEvidence").asText()).isEqualTo("catalog_Free");
        assertThat(output.path("servedModel").asText()).isEqualTo("not_observed");
        assertThat(output.path("usage").asText()).isEqualTo("not_observed");
        assertThat(output.path("counterexamples").get(0).asText()).isEqualTo("An empty list divides by zero.");
    }

    @Test
    void privateUnsupportedMalformedAndShortBudgetRequestsNeverStartTheBridge() {
        AtomicInteger calls = new AtomicInteger();
        DevinCliProvider provider = provider((model, bytes, timeout) -> {calls.incrementAndGet(); return "{}";});
        List<SubagentTask> rejected = List.of(
                new SubagentTask("x", 0, "x", "review", "private", Map.of()),
                new SubagentTask("x", 0, "x", "analysis", "public", Map.of("publicCodeReview", true, "costPolicy", "subscription_only")),
                task("public", "unknown"),
                task("authorization: never-send-this", "medium"),
                task("x".repeat(12_001), "medium"),
                new SubagentTask("x", 0, "x", "review", "public", Map.of("publicCodeReview", true)));
        for (SubagentTask task : rejected) {
            assertThatThrownBy(() -> provider.execute(task, 90_000L)).isInstanceOf(LlmGatewayException.class);
        }
        assertThatThrownBy(() -> provider.execute(task("public", "medium"), 50_999L))
                .isInstanceOf(LlmGatewayException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsPaidWrongModelEmptyAndMalformedBridgeResults() throws Exception {
        for (String raw : List.of(receipt("swe-2-medium", "Paid"), receipt("swe-2-max", "Free"),
                "", "[]", "not json", receipt("swe-2-medium", "Free").replace("response_verified", "evidence_needed"))) {
            DevinCliProvider provider = provider((model, bytes, timeout) -> raw);
            assertThatThrownBy(() -> provider.execute(task("public", "medium"), 90_000L))
                    .isInstanceOf(LlmGatewayException.class);
        }
    }

    @Test
    void unreadyStatusDoesNotGenerateOrReadTaskPayloads() {
        AtomicInteger calls = new AtomicInteger();
        DevinCliProvider provider = new DevinCliProvider(
                () -> SubagentProvider.Availability.disabled("devin_cli_missing"),
                (model, bytes, timeout) -> {calls.incrementAndGet(); return "{}";});
        assertThat(provider.availability().reasonCode()).isEqualTo("devin_cli_missing");
        assertThatThrownBy(() -> provider.execute(task("public", "medium"), 90_000L))
                .isInstanceOf(LlmGatewayException.class);
        assertThat(calls).hasValue(0);
        assertThat(provider.retryAllowed()).isFalse();
    }

    @Test
    void processRunnerBoundsOutputAndTimeoutWithoutExposingChildErrors() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.isRegularFile(Path.of(java)))
            java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final String executable = java;
        String classpath = Path.of(ChildFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        List<String> prefix = List.of(executable, "-cp", classpath, ChildFixture.class.getName());
        assertThat(DevinCliProvider.runCommand(command(prefix, "echo"), 5_000L)).isEqualTo("public-result");
        long started = System.nanoTime();
        assertThatThrownBy(() -> DevinCliProvider.runCommand(command(prefix, "sleep"), 300L))
                .isInstanceOf(LlmGatewayException.class);
        assertThat((System.nanoTime() - started) / 1_000_000L).isLessThan(5_000L);
        assertThatThrownBy(() -> DevinCliProvider.runCommand(command(prefix, "flood"), 5_000L))
                .isInstanceOf(LlmGatewayException.class);
        assertThatThrownBy(() -> DevinCliProvider.runCommand(command(prefix, "false-success"), 5_000L))
                .isInstanceOf(LlmGatewayException.class);
    }

    private static List<String> command(List<String> prefix, String mode) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(prefix); command.add(mode); return command;
    }

    @Test
    void timeoutTerminatesBothTheOwnedProcessAndItsCapturedChild() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        String classpath = Path.of(ChildFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path identities = temporary.resolve("owned-pids.txt");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> DevinCliProvider.runCommand(List.of(
                    executable, "-cp", classpath, ChildFixture.class.getName(), "spawn", identities.toString()), 4_000L));
            long waitUntil = System.nanoTime() + 3_000_000_000L;
            while ((!Files.exists(identities) || Files.size(identities) == 0) && System.nanoTime() < waitUntil)
                Thread.sleep(20L);
            assertThat(identities).exists();
            var handles = java.util.Arrays.stream(Files.readString(identities).trim().split(" "))
                    .map(Long::parseLong).map(pid -> ProcessHandle.of(pid).orElseThrow()).toList();
            assertThat(handles).hasSize(2);
            assertThatThrownBy(() -> result.get(6L, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
            long stoppedBy = System.nanoTime() + 2_000_000_000L;
            while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < stoppedBy)
                Thread.sleep(20L);
            assertThat(handles).allMatch(handle -> !handle.isAlive());
        } finally { executor.shutdownNow(); }
    }

    public static class ChildFixture {
        public static void main(String[] args) throws Exception {
            if ("spawn".equals(args[0])) {
                String executable = Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
                Process child = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                        ChildFixture.class.getName(), "sleep").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                Files.writeString(Path.of(args[1]), ProcessHandle.current().pid() + " " + child.pid());
                Thread.sleep(60_000L);
            } else if ("sleep".equals(args[0])) Thread.sleep(60_000L);
            else if ("flood".equals(args[0])) System.out.print("x".repeat(100_000));
            else if ("false-success".equals(args[0])) {
                System.out.print("{\"status\":\"response_verified\"}");
                System.exit(1);
            } else System.out.print("public-result");
        }
    }

    private static DevinCliProvider provider(DevinCliProvider.BridgeRunner runner) {
        return new DevinCliProvider(SubagentProvider.Availability::enabled, runner);
    }

    private static SubagentTask task(String prompt, String effort) {
        return new SubagentTask("public-test", 0, "public-test", "review", prompt,
                Map.of("publicCodeReview", true, "costPolicy", "subscription_only", "devinEffort", effort));
    }

    private static String receipt(String model, String costTier) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", "response_verified"); row.put("modelId", model); row.put("catalogCostTier", costTier);
        row.put("generationRequests", 1); row.put("exitCode", 0);
        row.put("result", Map.of("proposal", "Handle an empty list before dividing.",
                "counterexamples", List.of("An empty list divides by zero.")));
        return JSON.writeValueAsString(row);
    }
}
