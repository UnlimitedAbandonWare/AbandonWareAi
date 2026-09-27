package ai.abandonware.subagent.mcp;

import com.abandonware.ai.agent.orchestrator.subagent.SubagentProvider;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentTask;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** MCP-only adapter to the user's existing Free-only, public-input Devin CLI bridge. */
final class DevinCliProvider implements SubagentProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long PREFLIGHT_AND_CLEANUP_MS = 50_000L;
    private static final Set<String> CHILD_ENV = Set.of("systemroot", "windir", "path", "localappdata",
            "appdata", "userprofile", "homedrive", "homepath", "temp", "tmp", "comspec",
            "programfiles", "programfiles(x86)", "programdata", "processor_architecture",
            "number_of_processors", "os");
    private static final Pattern PRIVATE_PATTERN = Pattern.compile(
            "(?i)(?:\\b(?:cog_|apk_|apk_user_|sk-|ghp_)[A-Za-z0-9_-]{12,}|"
                    + "-----BEGIN .*PRIVATE KEY|authorization\\s*:|bearer\\s+\\S+|"
                    + "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|"
                    + "\\b(?:api[_-]?key|password|client[_-]?secret)\\s*[=:]\\s*\\S+)");

    private final Supplier<Availability> readiness;
    private final BridgeRunner bridge;

    DevinCliProvider(Environment environment) {
        Path script = Path.of(environment.getProperty("agent.subagent.devin.bridge-path",
                Path.of(System.getProperty("user.home"), ".codex", "skills", "devin-research-delegation",
                        "scripts", "devin_cli.py").toString())).toAbsolutePath().normalize();
        String python = environment.getProperty("agent.subagent.devin.python", "python");
        String localAppData = System.getenv("LOCALAPPDATA");
        this.readiness = () -> {
            if (!environment.getProperty("agent.subagent.devin.enabled", Boolean.class, true))
                return Availability.disabled("route_disabled");
            if (localAppData == null || !Files.isRegularFile(Path.of(localAppData, "devin", "cli", "bin", "devin.exe")))
                return Availability.disabled("devin_cli_missing");
            if (!safeRegularFile(script)) return Availability.disabled("devin_bridge_unavailable");
            return new Availability(true, "cli_present_catalog_unchecked");
        };
        this.bridge = (model, packet, timeoutMs) -> runBridge(python, script, model, packet, timeoutMs);
    }

    DevinCliProvider(Supplier<Availability> readiness, BridgeRunner bridge) {
        this.readiness = readiness;
        this.bridge = bridge;
    }

    @Override public String id() { return "devin-cli"; }
    @Override public int order() { return 5; }
    @Override public boolean singleAttemptPerFlow() { return true; }
    @Override public boolean supportsSubscriptionOnly() { return true; }
    @Override public boolean retryAllowed() { return false; }
    @Override public Availability availability() { return readiness.get(); }

    @Override public Availability availability(SubagentTask task) {
        if (task == null || !Boolean.TRUE.equals(task.context().get("publicCodeReview")))
            return Availability.disabled("public_input_required");
        if (!Set.of("review", "counterexample_search").contains(task.role()))
            return Availability.disabled("devin_task_kind_unsupported");
        if (!"subscription_only".equals(task.context().get("costPolicy")))
            return Availability.disabled("subscription_policy_required");
        Object effort = task.context().getOrDefault("devinEffort", "medium");
        if (!(effort instanceof String) || !Set.of("medium", "high", "max").contains(effort))
            return Availability.disabled("devin_effort_invalid");
        if (task.prompt().isBlank() || task.prompt().getBytes(StandardCharsets.UTF_8).length > 12_000
                || PRIVATE_PATTERN.matcher(task.prompt()).find())
            return Availability.disabled("public_data_screen_failed");
        return availability();
    }

    @Override public String execute(SubagentTask task, long timeoutMs) throws Exception {
        Availability admitted = availability(task);
        if (!admitted.available()) throw disabled(admitted.reasonCode());
        if (timeoutMs < PREFLIGHT_AND_CLEANUP_MS + 1_000L) throw disabled("devin_deadline_too_short");
        String model = "swe-2-" + task.context().getOrDefault("devinEffort", "medium");
        Map<String, Object> packet = Map.of(
                "objective", "Review the public input and preserve every supplied requirement.",
                "code", task.prompt(),
                "constraints", List.of("Use only supplied public input; no tools or workspace access.",
                        "The parent agent owns verification, decisions and edits."),
                "acceptance", List.of("Return proposal and concrete counterexamples as advisory JSON."));
        String raw = bridge.run(model, JSON.writeValueAsBytes(packet), timeoutMs);
        if (raw == null || raw.length() > 65_536) throw disabled("devin_response_invalid");
        JsonNode receipt;
        try { receipt = JSON.readTree(raw); }
        catch (IOException invalid) { throw disabled("devin_response_invalid"); }
        if (receipt == null || !receipt.isObject()) throw disabled("devin_response_invalid");
        if (!"response_verified".equals(receipt.path("status").asText())) {
            String reason = receipt.path("reason").asText("devin_review_unavailable");
            throw disabled(reason.matches("[a-z][a-z0-9_]{0,55}") ? reason : "devin_review_unavailable");
        }
        JsonNode result = receipt.path("result");
        if (!model.equals(receipt.path("modelId").asText())
                || !"Free".equals(receipt.path("catalogCostTier").asText())
                || receipt.path("generationRequests").asInt() != 1
                || receipt.path("exitCode").asInt(-1) != 0
                || !result.isObject() || !result.path("proposal").isTextual()
                || result.path("proposal").asText().isBlank()
                || !result.path("counterexamples").isArray()
                || result.path("counterexamples").isEmpty() || result.path("counterexamples").size() > 10)
            throw disabled("devin_receipt_unverified");
        for (JsonNode item : result.path("counterexamples")) {
            if (!item.isTextual() || item.asText().isBlank()) throw disabled("devin_receipt_unverified");
        }
        if (PRIVATE_PATTERN.matcher(result.toString()).find()) throw disabled("devin_response_screen_failed");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("proposal", result.path("proposal").asText());
        output.put("counterexamples", JSON.convertValue(result.path("counterexamples"), List.class));
        output.put("selectedModel", model);
        output.put("billingEvidence", "catalog_Free");
        output.put("servedModel", "not_observed");
        output.put("usage", "not_observed");
        return JSON.writeValueAsString(output);
    }

    private static String runBridge(String python, Path script, String model, byte[] packet,
                                    long timeoutMs) throws Exception {
        if (!safeRegularFile(script) || packet.length > 16_000) throw disabled("devin_bridge_unavailable");
        Path packetFile = Files.createTempFile("codex-public-review-", ".json");
        try {
            Files.write(packetFile, packet);
            long generationSeconds = Math.min(90L, (timeoutMs - PREFLIGHT_AND_CLEANUP_MS) / 1_000L);
            return runCommand(List.of(python, "-B", script.toString(), "review", "--execute",
                    "--model", model, "--packet", packetFile.toString(), "--run-id",
                    UUID.randomUUID().toString().replace("-", ""), "--reason", "code_analysis",
                    "--timeout", Long.toString(generationSeconds)), timeoutMs);
        } finally {
            Files.deleteIfExists(packetFile);
        }
    }

    static String runCommand(List<String> command, long timeoutMs) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        // Run outside the source checkout; the bridge itself selects its trusted empty workspace.
        builder.directory(Path.of(System.getProperty("java.io.tmpdir")).toFile());
        builder.environment().keySet().removeIf(key -> !CHILD_ENV.contains(key.toLowerCase(Locale.ROOT)));
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process = builder.start();
        ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "devin-cli-output"); thread.setDaemon(true); return thread;
        });
        Set<ProcessHandle> descendants = new LinkedHashSet<>();
        Future<byte[]> output = reader.submit(() -> {
            byte[] bytes = process.getInputStream().readNBytes(65_537);
            if (bytes.length > 65_536) throw new IOException("devin_output_limit");
            return bytes;
        });
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            process.getOutputStream().close();
            while (process.isAlive()) {
                process.descendants().forEach(descendants::add);
                if (output.isDone()) output.get();
                if (System.nanoTime() >= deadline) throw disabled("devin_process_timeout");
                process.waitFor(50L, TimeUnit.MILLISECONDS);
            }
            String raw = new String(output.get(1L, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                JsonNode failure = JSON.readTree(raw);
                if (failure == null || !"evidence_needed".equals(failure.path("status").asText()))
                    throw disabled("devin_process_failed");
            }
            return raw;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (LlmGatewayException failure) {
            throw failure;
        } catch (Exception failure) {
            throw disabled("devin_process_failed");
        } finally {
            process.descendants().forEach(descendants::add);
            if (process.isAlive()) process.destroyForcibly();
            List<ProcessHandle> children = new ArrayList<>(descendants);
            for (int i = children.size() - 1; i >= 0; i--) {
                if (children.get(i).isAlive()) children.get(i).destroyForcibly();
            }
            output.cancel(true);
            reader.shutdownNow();
            try { process.getInputStream().close(); } catch (IOException ignored) { /* already closed */ }
        }
    }

    private static boolean safeRegularFile(Path path) {
        try {
            for (Path part = path; part != null; part = part.getParent()) {
                BasicFileAttributes attributes = Files.readAttributes(part, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) return false;
            }
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unavailable) { return false; }
    }

    private static LlmGatewayException disabled(String reason) {
        return new LlmGatewayException("devin_cli_unavailable", LlmFailureClass.DISABLED, reason);
    }

    @FunctionalInterface interface BridgeRunner {
        String run(String model, byte[] packet, long timeoutMs) throws Exception;
    }
}
