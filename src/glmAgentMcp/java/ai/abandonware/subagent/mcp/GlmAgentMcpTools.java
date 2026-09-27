package ai.abandonware.subagent.mcp;

import com.abandonware.ai.agent.orchestrator.subagent.GlmAgentCore;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentProviderChain;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentResult;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentTask;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.trace.SafeRedactor;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Four bounded, read-only MCP adapters over the same {@link GlmAgentCore} used by
 * the in-process HTTP flow. This class never reads or writes workspace files.
 */
public final class GlmAgentMcpTools implements AutoCloseable {

    public static final String DELEGATE_TOOL = "glm_delegate_task";
    public static final String REVIEW_TOOL = "glm_review_change";
    public static final String CONSENSUS_TOOL = "glm_consensus_check";
    public static final String STATUS_TOOL = "glm_agent_status";

    private static final Logger log = LoggerFactory.getLogger(GlmAgentMcpTools.class);
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final long DEFAULT_CONSENSUS_TIMEOUT_MS = 45_000L;
    private static final long CONSENSUS_FINALIZATION_RESERVE_MS = 100L;
    private static final long MIN_TIMEOUT_MS = 100L;
    private static final long MAX_TIMEOUT_MS = 120_000L;
    private static final int MAX_CONTEXT_PROPERTIES = 16;
    private static final int MAX_CONTEXT_STRING_LENGTH = 1_024;
    private static final List<String> CONSENSUS_ROLES = List.of("support", "falsify", "neutral");
    private static final List<String> INDEPENDENT_CONSENSUS_ROLES = List.of("support", "falsify");
    private static final List<LlmFailureClass> CONSENSUS_ERROR_PRECEDENCE = List.of(
            LlmFailureClass.AUTH_MISSING,
            LlmFailureClass.DISABLED,
            LlmFailureClass.CANCELLED_NEUTRAL,
            LlmFailureClass.TIMEOUT_SOFT,
            LlmFailureClass.RATE_LIMIT_COOLDOWN,
            LlmFailureClass.HEALTH_DOWN,
            LlmFailureClass.PROVIDER_ERROR,
            LlmFailureClass.SOFT_CIRCUIT_OPEN,
            LlmFailureClass.STREAM_ERROR,
            LlmFailureClass.UNKNOWN);

    private final GlmAgentCore core;
    private final ExecutorService toolExecutor;
    private final ExecutorService consensusExecutor;
    private final Map<UUID, Future<?>> activeWork = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public GlmAgentMcpTools(GlmAgentCore core) {
        this.core = Objects.requireNonNull(core, "core");
        this.toolExecutor = Executors.newFixedThreadPool(4, daemonThreads("glm-agent-mcp-tool-"));
        this.consensusExecutor = Executors.newFixedThreadPool(3, daemonThreads("glm-agent-mcp-consensus-"));
    }

    public List<McpServerFeatures.AsyncToolSpecification> specifications() {
        return List.of(
                specification(
                        DELEGATE_TOOL,
                        "Delegate one bounded read-only reasoning task to the shared GLM agent core.",
                        delegateSchema(),
                        true,
                        arguments -> submitTool(DELEGATE_TOOL, timeout(arguments, DEFAULT_TIMEOUT_MS),
                                correlationId -> delegate(arguments, correlationId))),
                specification(
                        REVIEW_TOOL,
                        "Review a supplied change without workspace access. For parent-reviewed public code only, "
                                + "set context.publicCodeReview=true to prefer catalog-verified Free SWE-2 with "
                                + "local-only fallback; context.devinEffort is medium (default), high, or max. "
                                + "Use timeoutMs>=60000 for the bounded CLI authentication/catalog checks.",
                        reviewSchema(),
                        true,
                        arguments -> submitTool(REVIEW_TOOL, timeout(arguments, DEFAULT_TIMEOUT_MS),
                                correlationId -> review(arguments, correlationId))),
                specification(
                        CONSENSUS_TOOL,
                        "Run independent support and falsify perspectives, then a bounded neutral adjudication.",
                        consensusSchema(),
                        true,
                        arguments -> {
                            long timeoutMs = timeout(arguments, DEFAULT_CONSENSUS_TIMEOUT_MS);
                            long workDeadlineNanos = deadlineNanos(consensusWorkTimeout(timeoutMs));
                            return submitTool(CONSENSUS_TOOL, timeoutMs,
                                    correlationId -> consensus(
                                            arguments, correlationId, workDeadlineNanos));
                        }),
                specification(
                        STATUS_TOOL,
                        "Return provider, circuit, fallback, and metric status without a generation request.",
                        statusSchema(),
                        false,
                        arguments -> submitTool(STATUS_TOOL, 5_000L,
                                correlationId -> status(correlationId))));
    }

    private McpServerFeatures.AsyncToolSpecification specification(
            String name,
            String description,
            Map<String, Object> inputSchema,
            boolean openWorld,
            Function<Map<String, Object>, Mono<McpSchema.CallToolResult>> handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name)
                .title(name)
                .description(description)
                .inputSchema(inputSchema)
                .outputSchema(outputSchema(name))
                .annotations(McpSchema.ToolAnnotations.builder()
                        .title(name)
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(!openWorld)
                        .openWorldHint(openWorld)
                        .build())
                .build();
        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handler.apply(
                        request.arguments() == null ? Map.of() : request.arguments()))
                .build();
    }

    private ToolOutcome delegate(Map<String, Object> arguments, String correlationId) {
        String taskText = requiredString(arguments, "task", 12_000);
        String role = optionalLabel(arguments, "role", "analysis");
        Map<String, Object> context = reviewRoutingContext(context(arguments));
        List<String> constraints = constraints(arguments);
        Map<String, Object> expectedOutputSchema = boundedObject(
                arguments, "expectedOutputSchema", 32, 8_000);
        boolean validateExecutionPacket = optionalBoolean(
                arguments, "validateExecutionPacket", false);
        SubagentTask task = new SubagentTask(
                correlationId,
                0,
                "mcp-delegate",
                role,
                delegatePrompt(
                        taskText, constraints, expectedOutputSchema, validateExecutionPacket),
                context);
        SubagentResult result = core.executeMcpDelegate(
                task,
                new SubagentProviderChain.AttemptBudget(),
                deadlineNanos(timeout(arguments, DEFAULT_TIMEOUT_MS)),
                ignored -> { });
        GlmExecutionPacket.Validation validation = validateExecutionPacket
                ? GlmExecutionPacket.validate(result.output())
                : null;
        return ToolOutcome.from(result, validation);
    }

    private ToolOutcome review(Map<String, Object> arguments, String correlationId) {
        String diff = nullableString(arguments, "diff", 16_000);
        Map<String, Object> suppliedContext = context(arguments);
        if (diff == null && suppliedContext.isEmpty()) {
            throw new IllegalArgumentException("diff_or_context_required");
        }
        String reviewRubric = optionalString(arguments, "reviewRubric",
                "Check correctness, safety, reversibility, and missing evidence.", 4_000);
        String reviewSubject = diff == null
                ? serializeBounded(suppliedContext, 8_000, "invalid_context")
                : diff;
        String prompt = "Perform a read-only review using only the supplied input. "
                + "Do not inspect or mutate a workspace. Return named findings, severity, evidence, "
                + "and a recommendedPatch suggestion.\nSupplied change:\n" + reviewSubject
                + "\nReview rubric:\n" + reviewRubric;
        SubagentTask task = new SubagentTask(
                correlationId,
                0,
                "mcp-review",
                "review",
                prompt,
                reviewRoutingContext(suppliedContext));
        SubagentResult result = core.execute(
                task,
                new SubagentProviderChain.AttemptBudget(),
                deadlineNanos(timeout(arguments, DEFAULT_TIMEOUT_MS)),
                ignored -> { });
        return ToolOutcome.fromReview(result);
    }

    private static Map<String, Object> reviewRoutingContext(Map<String, Object> context) {
        if (!Boolean.TRUE.equals(context.get("publicCodeReview"))) return context;
        Map<String, Object> routed = new LinkedHashMap<>(context);
        routed.put("costPolicy", "subscription_only");
        return Map.copyOf(routed);
    }

    private ToolOutcome consensus(Map<String, Object> arguments,
                                  String correlationId,
                                  long workDeadlineNanos) throws Exception {
        String question = requiredString(arguments, "question", 8_000);
        Map<String, Object> baseContext = context(arguments);
        SubagentProviderChain.AttemptBudget attemptBudget = new SubagentProviderChain.AttemptBudget();
        List<Future<SubagentResult>> futures = new ArrayList<>(CONSENSUS_ROLES.size());
        List<Map<String, Object>> results = new ArrayList<>(
                Collections.nCopies(CONSENSUS_ROLES.size(), null));
        boolean deadlineReached = false;
        try {
            for (int ordinal = 0; ordinal < INDEPENDENT_CONSENSUS_ROLES.size(); ordinal++) {
                String perspective = INDEPENDENT_CONSENSUS_ROLES.get(ordinal);
                int taskOrdinal = ordinal;
                Map<String, Object> taskContext = new LinkedHashMap<>(baseContext);
                taskContext.put("perspective", perspective);
                SubagentTask task = new SubagentTask(
                        correlationId,
                        taskOrdinal,
                        "mcp-consensus-" + perspective,
                        perspective,
                        consensusPrompt(perspective, question),
                        Map.copyOf(taskContext));
                try {
                    futures.add(consensusExecutor.submit(() -> core.execute(
                            task,
                            attemptBudget,
                            workDeadlineNanos,
                            ignored -> { })));
                } catch (RejectedExecutionException rejected) {
                    throw new CancellationException("consensus_executor_closed");
                }
            }

            for (int index = 0; index < INDEPENDENT_CONSENSUS_ROLES.size(); index++) {
                long remainingNanos = workDeadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    deadlineReached = true;
                    break;
                }
                try {
                    SubagentResult result = futures.get(index).get(remainingNanos, TimeUnit.NANOSECONDS);
                    results.set(index, withPerspective(
                            result.structuredView(), CONSENSUS_ROLES.get(index)));
                } catch (TimeoutException timeout) {
                    deadlineReached = true;
                    break;
                } catch (ExecutionException failure) {
                    results.set(index, failedPerspective(CONSENSUS_ROLES.get(index), "UNKNOWN"));
                } catch (CancellationException cancelled) {
                    results.set(index, cancelledPerspective(CONSENSUS_ROLES.get(index)));
                }
            }
            if (deadlineReached) {
                preserveCompletedAndTimeoutUnfinished(futures, results);
                results.set(2, timeoutPerspective("neutral"));
            } else {
                Map<String, Object> neutralContext = new LinkedHashMap<>(baseContext);
                neutralContext.put("perspective", "neutral");
                SubagentTask neutralTask = new SubagentTask(
                        correlationId,
                        2,
                        "mcp-consensus-neutral",
                        "neutral",
                        neutralConsensusPrompt(question, results.get(0), results.get(1)),
                        Map.copyOf(neutralContext));
                try {
                    futures.add(consensusExecutor.submit(() -> core.execute(
                            neutralTask,
                            attemptBudget,
                            workDeadlineNanos,
                            ignored -> { })));
                } catch (RejectedExecutionException rejected) {
                    throw new CancellationException("consensus_executor_closed");
                }
                long remainingNanos = workDeadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    deadlineReached = true;
                } else {
                    try {
                        SubagentResult neutral = futures.get(2).get(
                                remainingNanos, TimeUnit.NANOSECONDS);
                        results.set(2, withPerspective(neutral.structuredView(), "neutral"));
                    } catch (TimeoutException timeout) {
                        deadlineReached = true;
                    } catch (ExecutionException failure) {
                        results.set(2, failedPerspective("neutral", "UNKNOWN"));
                    } catch (CancellationException cancelled) {
                        results.set(2, cancelledPerspective("neutral"));
                    }
                }
                if (deadlineReached) {
                    preserveCompletedAndTimeoutUnfinished(futures, results);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("consensus_cancelled");
        } finally {
            futures.forEach(future -> {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            });
        }

        for (int index = 0; index < results.size(); index++) {
            if (results.get(index) == null) {
                results.set(index, deadlineReached
                        ? timeoutPerspective(CONSENSUS_ROLES.get(index))
                        : failedPerspective(CONSENSUS_ROLES.get(index), "UNKNOWN"));
            }
            core.metrics().recordPerspective(perspectiveStatus(results.get(index)));
        }

        int succeeded = (int) results.stream()
                .filter(Objects::nonNull)
                .filter(result -> "SUCCESS".equals(String.valueOf(result.get("status"))))
                .count();
        LlmFailureClass errorClass = consensusErrorClass(results, succeeded);
        String status = consensusStatus(succeeded, errorClass);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("correlationId", correlationId);
        payload.put("status", status);
        payload.put("successfulPerspectives", succeeded);
        payload.put("totalPerspectives", CONSENSUS_ROLES.size());
        payload.put("results", List.copyOf(results));
        String optimisticOutput = perspectiveOutput(results, "support");
        String criticalOutput = perspectiveOutput(results, "falsify");
        String neutralOutput = perspectiveOutput(results, "neutral");
        NeutralAdjudication adjudication = NeutralAdjudication.parse(neutralOutput);
        payload.put("optimisticFindings", asFindingList(optimisticOutput));
        payload.put("criticalFindings", asFindingList(criticalOutput));
        payload.put("neutralVerdict", neutralOutput);
        payload.put("neutralDecision", adjudication.verdict());
        payload.put("neutralInputValidated", adjudication.valid());
        payload.put("counterexamples", adjudication.counterexamples().isEmpty()
                ? asFindingList(criticalOutput)
                : adjudication.counterexamples());
        List<String> unknowns = new ArrayList<>(consensusUnknowns(results));
        unknowns.addAll(adjudication.unknowns());
        payload.put("unknowns", List.copyOf(unknowns.stream().distinct().toList()));
        payload.put("recommendedNextTest", adjudication.recommendedNextTest());
        payload.put("hostMutationAllowed", adjudication.valid()
                && "APPLY".equals(adjudication.verdict())
                && succeeded == CONSENSUS_ROLES.size());
        payload.put("warnings", succeeded == CONSENSUS_ROLES.size()
                ? (adjudication.valid() ? List.of() : List.of("neutral_contract_invalid"))
                : List.of("consensus_degraded"));
        payload.put("errorClass", errorClass.name());
        payload.put("retryable", consensusRetryable(errorClass));
        payload.put("blockedExternal", results.stream().anyMatch(
                result -> Boolean.TRUE.equals(result.get("blockedExternal"))));
        return new ToolOutcome(Map.copyOf(payload), succeeded == 0, errorClass.name());
    }

    private ToolOutcome status(String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>(core.status());
        payload.put("correlationId", correlationId);
        payload.put("status", String.valueOf(
                payload.getOrDefault("activationState", "BLOCKED_EXTERNAL")));
        return new ToolOutcome(Map.copyOf(payload), false, "NONE");
    }

    private Mono<McpSchema.CallToolResult> submitTool(
            String toolName,
            long timeoutMs,
            ToolOperation operation) {
        String correlationId = UUID.randomUUID().toString();
        String correlationHash = SafeRedactor.hashValue(correlationId);
        String taskIdHash = SafeRedactor.hashValue(toolName + ":" + correlationId);
        long startedNanos = System.nanoTime();
        log.info("event=mcp.tool.started tool={} correlationId={} taskIdHash={} role=mcp provider=none "
                        + "status=started elapsedMs=0 fallbackUsed=false errorClass=NONE circuitState=none",
                toolName, correlationHash, taskIdHash);

        return cancellable(() -> operation.execute(correlationId))
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorResume(TimeoutException.class, failure -> Mono.just(
                        timeoutOutcome(correlationId, toolName)))
                .onErrorResume(CancellationException.class, failure -> Mono.just(
                        cancelledOutcome(correlationId, toolName)))
                .onErrorResume(Throwable.class, failure -> Mono.just(
                        failedOutcome(correlationId, toolName)))
                .map(this::toCallToolResult)
                .doOnSuccess(result -> {
                    boolean failed = Boolean.TRUE.equals(result.isError());
                    core.metrics().recordMcpTool(toolName, !failed);
                    long elapsedMs = elapsedMs(startedNanos);
                    String errorClass = errorClass(result);
                    log.info("event={} tool={} correlationId={} taskIdHash={} role={} provider={} status={} "
                                    + "elapsedMs={} fallbackUsed={} errorClass={} circuitState=none",
                            failed ? "mcp.tool.failed" : "mcp.tool.completed",
                            toolName,
                            correlationHash,
                            taskIdHash,
                            payloadLabel(result, "role", "mcp"),
                            payloadLabel(result, "provider", "none"),
                            payloadLabel(result, "status", failed ? "failed" : "success"),
                            elapsedMs,
                            payloadBoolean(result, "fallbackUsed"),
                            errorClass);
                })
                .doOnCancel(() -> {
                    core.metrics().recordMcpTool(toolName, false);
                    log.info("event=mcp.tool.failed tool={} correlationId={} taskIdHash={} role=mcp provider=none "
                                    + "status=cancelled elapsedMs={} fallbackUsed=false "
                                    + "errorClass=CANCELLED_NEUTRAL circuitState=none",
                            toolName, correlationHash, taskIdHash, elapsedMs(startedNanos));
                });
    }

    private <T> Mono<T> cancellable(Callable<T> operation) {
        return Mono.create(sink -> {
            if (closed.get()) {
                sink.error(new CancellationException("mcp_tools_closed"));
                return;
            }
            UUID workId = UUID.randomUUID();
            FutureTask<Void> work = new FutureTask<>(() -> {
                try {
                    sink.success(operation.call());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    sink.error(new CancellationException("mcp_tool_interrupted"));
                } catch (Throwable failure) {
                    sink.error(failure);
                } finally {
                    activeWork.remove(workId);
                }
                return null;
            });
            activeWork.put(workId, work);
            sink.onCancel(() -> {
                Future<?> active = activeWork.remove(workId);
                if (active != null) {
                    active.cancel(true);
                }
            });
            try {
                toolExecutor.execute(work);
            } catch (RejectedExecutionException rejected) {
                activeWork.remove(workId);
                sink.error(new CancellationException("mcp_executor_closed"));
            }
        });
    }

    private McpSchema.CallToolResult toCallToolResult(ToolOutcome outcome) {
        String text;
        try {
            text = McpJsonDefaults.getMapper().writeValueAsString(outcome.payload());
        } catch (Exception serializationFailure) {
            text = "{\"status\":\"FAILED\",\"errorClass\":\"UNKNOWN\"}";
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .structuredContent(outcome.payload())
                .isError(outcome.error())
                .build();
    }

    private static ToolOutcome timeoutOutcome(String correlationId, String toolName) {
        return errorOutcome(correlationId, toolName,
                "TIMEOUT", "TIMEOUT_SOFT", true, "tool_timeout");
    }

    private static ToolOutcome cancelledOutcome(String correlationId, String toolName) {
        return errorOutcome(correlationId, toolName,
                "CANCELLED", "CANCELLED_NEUTRAL", false, "tool_cancelled");
    }

    private static ToolOutcome failedOutcome(String correlationId, String toolName) {
        return errorOutcome(correlationId, toolName,
                "FAILED", "UNKNOWN", false, "tool_failed");
    }

    private static ToolOutcome errorOutcome(String correlationId,
                                            String toolName,
                                            String status,
                                            String errorClass,
                                            boolean retryable,
                                            String warning) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("correlationId", correlationId);
        payload.put("status", status);
        payload.put("provider", "none");
        payload.put("fallbackUsed", false);
        payload.put("attemptedProviders", List.of());
        payload.put("errorClass", errorClass);
        payload.put("retryable", retryable);
        payload.put("evidence", List.of());
        payload.put("warnings", List.of(warning));
        payload.put("blockedExternal", false);
        if (REVIEW_TOOL.equals(toolName)) {
            payload.put("findings", List.of());
            payload.put("severity", "UNKNOWN");
            payload.put("recommendedPatch", "");
        } else if (CONSENSUS_TOOL.equals(toolName)) {
            payload.put("optimisticFindings", List.of());
            payload.put("criticalFindings", List.of());
            payload.put("neutralVerdict", "");
            payload.put("neutralDecision", "HOLD");
            payload.put("neutralInputValidated", false);
            payload.put("counterexamples", List.of());
            payload.put("unknowns", List.of(warning));
            payload.put("recommendedNextTest", "retry_bounded_consensus");
            payload.put("hostMutationAllowed", false);
        } else if (STATUS_TOOL.equals(toolName)) {
            payload.put("generationAttempted", false);
            payload.put("activationState", "BLOCKED_EXTERNAL");
            payload.put("activationReasonCode", warning);
            payload.put("liveProbeAttempted", false);
            payload.put("liveProbeSucceeded", false);
            payload.put("providerAttemptObserved", false);
            payload.put("wireAttemptObserved", false);
            payload.put("requestTimelineEnabled", false);
            payload.put("providers", List.of());
            payload.put("fallbackProviders", List.of());
            payload.put("recentErrorClasses", Map.of());
            payload.put("recentRequestTimeline", List.of());
            payload.put("recentClientHttpLedger", List.of());
            payload.put("metrics", Map.of());
        } else {
            payload.put("executionPacketValidation", Map.of(
                    "status", "NOT_REQUESTED",
                    "reasonCodes", List.of(),
                    "packet", Map.of()));
            payload.put("hostMutationAllowed", false);
        }
        return new ToolOutcome(Map.copyOf(payload), true, errorClass);
    }

    private static String errorClass(McpSchema.CallToolResult result) {
        if (result == null || !(result.structuredContent() instanceof Map<?, ?> payload)) {
            return "UNKNOWN";
        }
        Object value = payload.get("errorClass");
        return value == null ? "NONE" : safeLabel(String.valueOf(value), "UNKNOWN");
    }

    private static String payloadLabel(McpSchema.CallToolResult result,
                                       String key,
                                       String fallback) {
        if (result == null || !(result.structuredContent() instanceof Map<?, ?> payload)) {
            return fallback;
        }
        Object value = payload.get(key);
        return value == null ? fallback : safeLabel(String.valueOf(value), fallback);
    }

    private static boolean payloadBoolean(McpSchema.CallToolResult result, String key) {
        if (result == null || !(result.structuredContent() instanceof Map<?, ?> payload)) {
            return false;
        }
        return Boolean.TRUE.equals(payload.get(key));
    }

    private static Map<String, Object> withPerspective(Map<String, Object> source, String perspective) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.put("perspective", perspective);
        if (copy.get("output") == null) {
            copy.put("output", "");
        }
        return Map.copyOf(copy);
    }

    private static Map<String, Object> failedPerspective(String perspective, String errorClass) {
        return Map.of(
                "perspective", perspective,
                "status", "FAILED",
                "provider", "none",
                "output", "",
                "errorClass", errorClass,
                "retryable", false,
                "blockedExternal", false,
                "warnings", List.of("perspective_failed"));
    }

    private static Map<String, Object> cancelledPerspective(String perspective) {
        return Map.of(
                "perspective", perspective,
                "status", "CANCELLED",
                "provider", "none",
                "output", "",
                "errorClass", "CANCELLED_NEUTRAL",
                "retryable", false,
                "blockedExternal", false,
                "warnings", List.of("perspective_cancelled"));
    }

    private static Map<String, Object> timeoutPerspective(String perspective) {
        return Map.of(
                "perspective", perspective,
                "status", "TIMEOUT",
                "provider", "none",
                "output", "",
                "errorClass", "TIMEOUT_SOFT",
                "retryable", true,
                "blockedExternal", false,
                "warnings", List.of("perspective_timeout"));
    }

    private static void preserveCompletedAndTimeoutUnfinished(
            List<Future<SubagentResult>> futures,
            List<Map<String, Object>> results) throws InterruptedException {
        for (int index = 0; index < futures.size(); index++) {
            if (results.get(index) != null) {
                continue;
            }
            Future<SubagentResult> future = futures.get(index);
            String perspective = CONSENSUS_ROLES.get(index);
            if (!future.isDone() && future.cancel(true)) {
                results.set(index, timeoutPerspective(perspective));
                continue;
            }
            try {
                SubagentResult result = future.get();
                results.set(index, withPerspective(result.structuredView(), perspective));
            } catch (ExecutionException failure) {
                results.set(index, failedPerspective(perspective, "UNKNOWN"));
            } catch (CancellationException cancelled) {
                results.set(index, cancelledPerspective(perspective));
            }
        }
    }

    private static LlmFailureClass consensusErrorClass(List<Map<String, Object>> results,
                                                       int succeeded) {
        if (succeeded == CONSENSUS_ROLES.size()) {
            return LlmFailureClass.NONE;
        }
        for (LlmFailureClass candidate : CONSENSUS_ERROR_PRECEDENCE) {
            if (results.stream().anyMatch(result -> candidate.name().equals(
                    String.valueOf(result.get("errorClass")).toUpperCase(Locale.ROOT)))) {
                return candidate;
            }
        }
        return results.stream()
                .map(result -> String.valueOf(result.get("errorClass")))
                .map(value -> {
                    try {
                        return LlmFailureClass.valueOf(value.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {
                        return LlmFailureClass.UNKNOWN;
                    }
                })
                .filter(candidate -> candidate != LlmFailureClass.NONE)
                .findFirst()
                .orElse(LlmFailureClass.UNKNOWN);
    }

    private static String consensusStatus(int succeeded, LlmFailureClass errorClass) {
        if (succeeded == CONSENSUS_ROLES.size()) {
            return "SUCCESS";
        }
        if (succeeded > 0) {
            return "PARTIAL";
        }
        return switch (errorClass) {
            case CANCELLED_NEUTRAL -> "CANCELLED";
            case TIMEOUT_SOFT -> "TIMEOUT";
            default -> "FAILED";
        };
    }

    private static boolean consensusRetryable(LlmFailureClass errorClass) {
        return errorClass == LlmFailureClass.RATE_LIMIT_COOLDOWN
                || errorClass == LlmFailureClass.HEALTH_DOWN
                || errorClass == LlmFailureClass.TIMEOUT_SOFT
                || errorClass == LlmFailureClass.SOFT_CIRCUIT_OPEN
                || errorClass == LlmFailureClass.PROVIDER_ERROR;
    }

    private static String consensusPrompt(String perspective, String question) {
        return switch (perspective) {
            case "support" -> "Build the strongest evidence-bounded case supporting the supplied claim.";
            case "falsify" -> "Search the supplied claim for counterexamples, gaps, and failure modes.";
            default -> "Adjudicate the supplied claim neutrally and state remaining uncertainty.";
        } + " Use only supplied input; do not inspect or mutate a workspace.\nClaim:\n" + question;
    }

    private static String neutralConsensusPrompt(String question,
                                                 Map<String, Object> support,
                                                 Map<String, Object> falsify) {
        List<Map<String, Object>> packets = List.of(
                boundedPerspectivePacket(support, "support"),
                boundedPerspectivePacket(falsify, "falsify"));
        return "Adjudicate only after comparing the completed SUPPORT and FALSIFY packets. "
                + "Return one JSON object with verdict (APPLY, HOLD, or NO_PATCH_NEEDED), "
                + "counterexamples, unknowns, and recommendedNextTest. Do not inspect or mutate "
                + "a workspace.\nClaim:\n" + question
                + "\nCompleted packets:\n"
                + serializeBounded(packets, 16_000, "invalid_consensus_packets");
    }

    private static Map<String, Object> boundedPerspectivePacket(Map<String, Object> source,
                                                                 String fallbackPerspective) {
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("perspective", safeLabel(
                String.valueOf(source.get("perspective")), fallbackPerspective));
        packet.put("status", safeLabel(String.valueOf(source.get("status")), "UNKNOWN"));
        packet.put("provider", safeLabel(String.valueOf(source.get("provider")), "none"));
        packet.put("errorClass", safeLabel(String.valueOf(source.get("errorClass")), "UNKNOWN"));
        packet.put("retryable", Boolean.TRUE.equals(source.get("retryable")));
        Object rawOutput = source.get("output");
        String output = rawOutput instanceof String text ? text : "";
        packet.put("output", output.length() <= 6_000 ? output : output.substring(0, 6_000));
        return Map.copyOf(packet);
    }

    private static String delegatePrompt(String task,
                                         List<String> constraints,
                                         Map<String, Object> expectedOutputSchema,
                                         boolean validateExecutionPacket) {
        StringBuilder prompt = new StringBuilder(task);
        if (!constraints.isEmpty()) {
            prompt.append("\nConstraints:\n");
            constraints.forEach(constraint -> prompt.append("- ").append(constraint).append('\n'));
        }
        if (!expectedOutputSchema.isEmpty()) {
            prompt.append("\nExpected output schema:\n")
                    .append(serializeBounded(expectedOutputSchema, 8_000, "invalid_expected_output_schema"));
        }
        if (validateExecutionPacket) {
            prompt.append("\nReturn only one JSON object for the host execution packet with exactly ")
                    .append("these fields: goal, evidence, affectedFiles, nonGoals, proposedPatch, ")
                    .append("redTest, greenTests, regressionTests, rollbackCondition, securityChecks, ")
                    .append("unknowns. Use repository-relative affectedFiles and no Markdown fence.");
        }
        return prompt.toString();
    }

    private static List<String> constraints(Map<String, Object> arguments) {
        Object raw = arguments.get("constraints");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> values) || values.size() > 16) {
            throw new IllegalArgumentException("invalid_constraints");
        }
        List<String> constraints = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank() || text.length() > 512) {
                throw new IllegalArgumentException("invalid_constraints");
            }
            constraints.add(text);
        }
        return List.copyOf(constraints);
    }

    private static Map<String, Object> boundedObject(Map<String, Object> arguments,
                                                     String key,
                                                     int maxProperties,
                                                     int maxSerializedLength) {
        Object raw = arguments.get(key);
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> input) || input.size() > maxProperties) {
            throw new IllegalArgumentException("invalid_" + key);
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String field)
                    || !field.matches("[A-Za-z$][A-Za-z0-9$._-]{0,63}")) {
                throw new IllegalArgumentException("invalid_" + key);
            }
            copy.put(field, entry.getValue());
        }
        serializeBounded(copy, maxSerializedLength, "invalid_" + key);
        return Collections.unmodifiableMap(copy);
    }

    private static String serializeBounded(Object value, int maximumLength, String reasonCode) {
        try {
            String serialized = McpJsonDefaults.getMapper().writeValueAsString(value);
            if (serialized.length() > maximumLength) {
                throw new IllegalArgumentException(reasonCode);
            }
            return serialized;
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception failure) {
            throw new IllegalArgumentException(reasonCode);
        }
    }

    private static String perspectiveOutput(List<Map<String, Object>> results, String perspective) {
        for (Map<String, Object> result : results) {
            if (perspective.equals(result.get("perspective"))) {
                Object output = result.get("output");
                return output instanceof String text ? text : "";
            }
        }
        return "";
    }

    private static List<String> asFindingList(String output) {
        return output == null || output.isBlank() ? List.of() : List.of(output);
    }

    private static List<String> consensusUnknowns(List<Map<String, Object>> results) {
        List<String> unknowns = new ArrayList<>();
        for (Map<String, Object> result : results) {
            if (!"SUCCESS".equals(String.valueOf(result.get("status")))) {
                String perspective = safeLabel(String.valueOf(result.get("perspective")), "unknown");
                String errorClass = safeLabel(String.valueOf(result.get("errorClass")), "UNKNOWN");
                unknowns.add(perspective + ":" + errorClass);
            }
        }
        return List.copyOf(unknowns);
    }

    private static SubagentResult.Status perspectiveStatus(Map<String, Object> result) {
        String status = String.valueOf(result.getOrDefault("status", "FAILED"))
                .toUpperCase(Locale.ROOT);
        return switch (status) {
            case "SUCCESS" -> SubagentResult.Status.SUCCESS;
            case "TIMEOUT" -> SubagentResult.Status.TIMEOUT;
            case "CANCELLED" -> SubagentResult.Status.CANCELLED;
            default -> SubagentResult.Status.FAILED;
        };
    }

    private static Map<String, Object> context(Map<String, Object> arguments) {
        Object raw = arguments.get("context");
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> input) || input.size() > MAX_CONTEXT_PROPERTIES) {
            throw new IllegalArgumentException("invalid_context");
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || !key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("invalid_context_key");
            }
            Object value = entry.getValue();
            if (value == null || value instanceof Boolean || value instanceof Number) {
                sanitized.put(key, value == null ? "null" : value);
            } else if (value instanceof String text && text.length() <= MAX_CONTEXT_STRING_LENGTH) {
                sanitized.put(key, text);
            } else {
                throw new IllegalArgumentException("invalid_context_value");
            }
        }
        return Map.copyOf(sanitized);
    }

    private static String requiredString(Map<String, Object> arguments, String key, int maxLength) {
        Object value = arguments.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength) {
            throw new IllegalArgumentException("invalid_" + key);
        }
        return text;
    }

    private static String optionalString(Map<String, Object> arguments,
                                         String key,
                                         String fallback,
                                         int maxLength) {
        Object value = arguments.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength) {
            throw new IllegalArgumentException("invalid_" + key);
        }
        return text;
    }

    private static String nullableString(Map<String, Object> arguments, String key, int maxLength) {
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength) {
            throw new IllegalArgumentException("invalid_" + key);
        }
        return text;
    }

    private static boolean optionalBoolean(Map<String, Object> arguments,
                                           String key,
                                           boolean fallback) {
        Object value = arguments.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException("invalid_" + key);
        }
        return flag;
    }

    private static String optionalLabel(Map<String, Object> arguments, String key, String fallback) {
        Object value = arguments.get(key);
        if (value == null) {
            return fallback;
        }
        return safeLabel(String.valueOf(value), fallback);
    }

    private static String safeLabel(String value, String fallback) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            return fallback;
        }
        return value;
    }

    private static long timeout(Map<String, Object> arguments, long fallback) {
        Object value = arguments.get("timeoutMs");
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("invalid_timeout");
        }
        long timeoutMs = number.longValue();
        if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("invalid_timeout");
        }
        return timeoutMs;
    }

    private static long consensusWorkTimeout(long timeoutMs) {
        long reserveMs = Math.min(CONSENSUS_FINALIZATION_RESERVE_MS, Math.max(1L, timeoutMs / 2L));
        return Math.max(1L, timeoutMs - reserveMs);
    }

    private static long deadlineNanos(long timeoutMs) {
        long now = System.nanoTime();
        long delta = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        return Long.MAX_VALUE - now < delta ? Long.MAX_VALUE : now + delta;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static Map<String, Object> delegateSchema() {
        return objectSchema(Map.of(
                "task", boundedString(1, 12_000),
                "role", Map.of("type", "string", "pattern", "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"),
                "context", contextSchema(),
                "constraints", Map.of(
                        "type", "array",
                        "maxItems", 16,
                        "items", boundedString(1, 512)),
                "expectedOutputSchema", Map.of(
                        "type", "object",
                        "maxProperties", 32,
                        "description", "Advisory bounded prompt hint only; not post-generation validation."),
                "validateExecutionPacket", Map.of(
                        "type", "boolean",
                        "description", "When true, validate the returned host execution packet separately from the advisory output hint."),
                "timeoutMs", timeoutSchema()), List.of("task"));
    }

    private static Map<String, Object> reviewSchema() {
        Map<String, Object> schema = new LinkedHashMap<>(objectSchema(Map.of(
                "diff", boundedString(1, 16_000),
                "reviewRubric", boundedString(1, 4_000),
                "context", contextSchema(),
                "timeoutMs", timeoutSchema()), List.of()));
        schema.put("anyOf", List.of(
                Map.of("required", List.of("diff")),
                Map.of("required", List.of("context"))));
        return Map.copyOf(schema);
    }

    private static Map<String, Object> consensusSchema() {
        return objectSchema(Map.of(
                "question", boundedString(1, 8_000),
                "context", contextSchema(),
                "timeoutMs", timeoutSchema()), List.of("question"));
    }

    private static Map<String, Object> statusSchema() {
        return objectSchema(Map.of(), List.of());
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties,
                                                    List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        schema.put("maxProperties", properties.size());
        return Map.copyOf(schema);
    }

    private static Map<String, Object> boundedString(int minimum, int maximum) {
        return Map.of(
                "type", "string",
                "minLength", minimum,
                "maxLength", maximum);
    }

    private static Map<String, Object> timeoutSchema() {
        return Map.of(
                "type", "integer",
                "minimum", MIN_TIMEOUT_MS,
                "maximum", MAX_TIMEOUT_MS);
    }

    private static Map<String, Object> contextSchema() {
        return Map.of(
                "type", "object",
                "maxProperties", MAX_CONTEXT_PROPERTIES,
                "additionalProperties", Map.of(
                        "type", List.of("string", "number", "integer", "boolean", "null")));
    }

    private static Map<String, Object> outputSchema(String toolName) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", Map.of("type", "string"));
        properties.put("correlationId", Map.of("type", "string"));
        properties.put("errorClass", Map.of("type", "string"));
        properties.put("retryable", Map.of("type", "boolean"));
        properties.put("blockedExternal", Map.of("type", "boolean"));

        List<String> required = new ArrayList<>();
        required.add("status");
        if (REVIEW_TOOL.equals(toolName)) {
            properties.put("findings", Map.of("type", "array"));
            properties.put("severity", Map.of("type", "string"));
            properties.put("evidence", Map.of("type", "array"));
            properties.put("recommendedPatch", Map.of("type", "string"));
            required.addAll(List.of("findings", "severity", "evidence", "recommendedPatch"));
        } else if (CONSENSUS_TOOL.equals(toolName)) {
            properties.put("optimisticFindings", Map.of("type", "array"));
            properties.put("criticalFindings", Map.of("type", "array"));
            properties.put("neutralVerdict", Map.of("type", "string"));
            properties.put("counterexamples", Map.of("type", "array"));
            properties.put("unknowns", Map.of("type", "array"));
            properties.put("neutralDecision", Map.of("type", "string"));
            properties.put("neutralInputValidated", Map.of("type", "boolean"));
            properties.put("recommendedNextTest", Map.of("type", "string"));
            properties.put("hostMutationAllowed", Map.of("type", "boolean"));
            required.addAll(List.of(
                    "optimisticFindings", "criticalFindings", "neutralVerdict",
                    "counterexamples", "unknowns", "neutralDecision",
                    "neutralInputValidated", "recommendedNextTest", "hostMutationAllowed"));
        } else if (STATUS_TOOL.equals(toolName)) {
            properties.put("generationAttempted", Map.of("type", "boolean"));
            properties.put("requestTimelineEnabled", Map.of("type", "boolean"));
            properties.put("providers", Map.of("type", "array"));
            properties.put("fallbackProviders", Map.of("type", "array"));
            properties.put("recentErrorClasses", Map.of("type", "object"));
            properties.put("recentRequestTimeline", Map.of("type", "array"));
            properties.put("recentClientHttpLedger", Map.of("type", "array"));
            properties.put("metrics", Map.of("type", "object"));
            properties.put("activationState", Map.of("type", "string"));
            properties.put("activationReasonCode", Map.of("type", "string"));
            properties.put("activationEpochOrdinal", Map.of("type", "integer"));
            properties.put("glmAutoActivateEnabled", Map.of("type", "boolean"));
            properties.put("glmExternalReady", Map.of("type", "boolean"));
            properties.put("glmKeyPresent", Map.of("type", "boolean"));
            properties.put("activationProbeClaimed", Map.of("type", "boolean"));
            properties.put("liveProbeAttempted", Map.of("type", "boolean"));
            properties.put("liveProbeSucceeded", Map.of("type", "boolean"));
            properties.put("liveProbeFailureClass", Map.of("type", "string"));
            properties.put("liveProbeRetryable", Map.of("type", "boolean"));
            properties.put("liveProbeProvider", Map.of("type", "string"));
            properties.put("liveProbeFallbackUsed", Map.of("type", "boolean"));
            properties.put("providerAttemptObserved", Map.of("type", "boolean"));
            properties.put("wireAttemptObserved", Map.of("type", "boolean"));
            required.addAll(List.of(
                    "generationAttempted", "requestTimelineEnabled", "providers", "fallbackProviders",
                    "recentErrorClasses", "recentRequestTimeline", "recentClientHttpLedger", "metrics",
                    "activationState", "activationReasonCode", "activationEpochOrdinal",
                    "glmAutoActivateEnabled", "glmExternalReady", "glmKeyPresent",
                    "activationProbeClaimed", "liveProbeAttempted", "liveProbeSucceeded",
                    "liveProbeFailureClass", "liveProbeRetryable", "liveProbeProvider",
                    "liveProbeFallbackUsed", "providerAttemptObserved", "wireAttemptObserved"));
        } else {
            properties.put("provider", Map.of("type", "string"));
            properties.put("fallbackUsed", Map.of("type", "boolean"));
            properties.put("attemptedProviders", Map.of("type", "array"));
            properties.put("evidence", Map.of("type", "array"));
            properties.put("warnings", Map.of("type", "array"));
            properties.put("executionPacketValidation", Map.of("type", "object"));
            properties.put("hostMutationAllowed", Map.of("type", "boolean"));
            required.addAll(List.of(
                    "correlationId", "provider", "fallbackUsed", "attemptedProviders",
                    "errorClass", "retryable", "evidence", "warnings", "blockedExternal",
                    "executionPacketValidation", "hostMutationAllowed"));
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.copyOf(required));
        schema.put("properties", Map.copyOf(properties));
        schema.put("additionalProperties", true);
        return Map.copyOf(schema);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activeWork.values().forEach(work -> work.cancel(true));
        activeWork.clear();
        consensusExecutor.shutdownNow();
        toolExecutor.shutdownNow();
        awaitTermination(consensusExecutor);
        awaitTermination(toolExecutor);
    }

    private static void awaitTermination(ExecutorService executor) {
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface ToolOperation {
        ToolOutcome execute(String correlationId) throws Exception;
    }

    private record ToolOutcome(Map<String, Object> payload, boolean error, String errorClass) {
        private ToolOutcome {
            payload = payload == null ? Map.of("status", "FAILED") : payload;
            errorClass = Objects.requireNonNullElse(errorClass, "UNKNOWN").toUpperCase(Locale.ROOT);
        }

        private static ToolOutcome from(SubagentResult result) {
            return from(result, null);
        }

        private static ToolOutcome from(SubagentResult result,
                                        GlmExecutionPacket.Validation validation) {
            Map<String, Object> payload = new LinkedHashMap<>(result.structuredView());
            if (payload.get("output") == null) {
                payload.put("output", "");
            }
            if (validation == null) {
                payload.put("executionPacketValidation", Map.of(
                        "status", "NOT_REQUESTED",
                        "reasonCodes", List.of(),
                        "packet", Map.of()));
                payload.put("hostMutationAllowed", false);
            } else {
                payload.put("executionPacketValidation", validation.structuredView());
                payload.put("hostMutationAllowed", validation.valid());
            }
            return new ToolOutcome(Map.copyOf(payload), !result.succeeded(), result.errorClass().name());
        }

        private static ToolOutcome fromReview(SubagentResult result) {
            Map<String, Object> payload = new LinkedHashMap<>(result.structuredView());
            String output = result.output() == null ? "" : result.output();
            payload.put("output", output);
            payload.put("findings", asFindingList(output));
            payload.put("severity", result.succeeded()
                    ? "INFO" : result.retryable() ? "WARNING" : "ERROR");
            payload.put("recommendedPatch", result.succeeded() ? output : "");
            return new ToolOutcome(Map.copyOf(payload), !result.succeeded(), result.errorClass().name());
        }
    }

    private record NeutralAdjudication(
            boolean valid,
            String verdict,
            List<String> counterexamples,
            List<String> unknowns,
            String recommendedNextTest) {

        private static NeutralAdjudication parse(String rawOutput) {
            if (rawOutput == null || rawOutput.isBlank() || rawOutput.length() > 16_000) {
                return invalid();
            }
            try {
                Object decoded = McpJsonDefaults.getMapper().readValue(rawOutput.trim(), Object.class);
                if (!(decoded instanceof Map<?, ?> values) || values.size() != 4) {
                    return invalid();
                }
                Object rawVerdict = values.get("verdict");
                Object rawNextTest = values.get("recommendedNextTest");
                if (!(rawVerdict instanceof String verdict)
                        || !(rawNextTest instanceof String nextTest)
                        || nextTest.isBlank()
                        || nextTest.length() > 2_000) {
                    return invalid();
                }
                String normalizedVerdict = verdict.toUpperCase(Locale.ROOT);
                if (!List.of("APPLY", "HOLD", "NO_PATCH_NEEDED").contains(normalizedVerdict)) {
                    return invalid();
                }
                List<String> counterexamples = boundedStringList(values.get("counterexamples"));
                List<String> unknowns = boundedStringList(values.get("unknowns"));
                if (counterexamples == null || unknowns == null) {
                    return invalid();
                }
                return new NeutralAdjudication(
                        true,
                        normalizedVerdict,
                        counterexamples,
                        unknowns,
                        nextTest.trim());
            } catch (Exception ignored) {
                return invalid();
            }
        }

        private static List<String> boundedStringList(Object raw) {
            if (!(raw instanceof List<?> values) || values.size() > 16) {
                return null;
            }
            List<String> copy = new ArrayList<>(values.size());
            for (Object value : values) {
                if (!(value instanceof String text) || text.isBlank() || text.length() > 1_000) {
                    return null;
                }
                copy.add(text.trim());
            }
            return List.copyOf(copy);
        }

        private static NeutralAdjudication invalid() {
            return new NeutralAdjudication(
                    false,
                    "HOLD",
                    List.of(),
                    List.of("neutral_contract_invalid"),
                    "validate_neutral_adjudication");
        }
    }
}
