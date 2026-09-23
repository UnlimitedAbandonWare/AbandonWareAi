package com.abandonware.ai.agent.orchestrator.subagent;

import com.abandonware.ai.agent.orchestrator.nodes.PlannerNode;
import com.abandonware.ai.agent.orchestrator.nodes.SynthNode;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded two-task runtime for the explicit {@code subagent.v1} flow. */
@Service
public final class SubagentFlowRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SubagentFlowRunner.class);
    private static final long DEFAULT_DEADLINE_MS = 15_000L;
    private static final long DEFAULT_TASK_TIMEOUT_MS = 10_000L;

    private final PlannerNode planner;
    private final SynthNode synth;
    private final GlmAgentCore agentCore;
    private final long deadlineMs;
    private final long taskTimeoutMs;
    private final ExecutorService executor;

    public SubagentFlowRunner(SubagentProviderChain providerChain, Environment environment) {
        this(new GlmAgentCore(providerChain), environment);
    }

    @Autowired
    public SubagentFlowRunner(GlmAgentCore agentCore, Environment environment) {
        this(new PlannerNode(), new SynthNode(), agentCore,
                positiveLong(environment, "agent.subagent.deadline-ms", DEFAULT_DEADLINE_MS),
                positiveLong(environment, "agent.subagent.task-timeout-ms", DEFAULT_TASK_TIMEOUT_MS),
                Executors.newFixedThreadPool(2, daemonThreadFactory()));
    }

    public SubagentFlowRunner(PlannerNode planner,
                              SynthNode synth,
                              SubagentProviderChain providerChain,
                              long deadlineMs,
                              long taskTimeoutMs,
                              ExecutorService executor) {
        this(planner, synth, new GlmAgentCore(providerChain), deadlineMs, taskTimeoutMs, executor);
    }

    public SubagentFlowRunner(PlannerNode planner,
                              SynthNode synth,
                              GlmAgentCore agentCore,
                              long deadlineMs,
                              long taskTimeoutMs,
                              ExecutorService executor) {
        this.planner = planner == null ? new PlannerNode() : planner;
        this.synth = synth == null ? new SynthNode() : synth;
        this.agentCore = java.util.Objects.requireNonNull(agentCore, "agentCore");
        this.deadlineMs = Math.max(1L, deadlineMs);
        this.taskTimeoutMs = Math.max(1L, taskTimeoutMs);
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
    }

    public Map<String, Object> run(String flowId, Map<String, Object> input, ToolContext toolContext) {
        long started = System.nanoTime();
        long globalDeadline = saturatingAdd(started, TimeUnit.MILLISECONDS.toNanos(deadlineMs));
        String requestId = "subagent-" + UUID.randomUUID();
        List<SubagentTask> tasks = planner.subagentTasks(requestId, input);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("flow", flowId == null ? "subagent.v1" : flowId);

        emitEvent(requestId, "subagent.plan.created", "none", "none", "none", "planned",
                LlmFailureClass.NONE, false, 0L, "closed", Map.of("taskCount", tasks.size()));
        SubagentProviderChain.AttemptBudget attemptBudget = new SubagentProviderChain.AttemptBudget();
        CompletionService<SubagentResult> completion = new ExecutorCompletionService<>(executor);
        Map<Future<SubagentResult>, TaskControl> pending = new LinkedHashMap<>();
        Map<Integer, SubagentResult> completed = new LinkedHashMap<>();

        for (SubagentTask task : tasks) {
            List<SubagentResult.Attempt> observed = new CopyOnWriteArrayList<>();
            long taskDeadline = Math.min(globalDeadline,
                    saturatingAdd(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(taskTimeoutMs)));
            Future<SubagentResult> future = completion.submit(
                    () -> {
                        TraceStore.clear();
                        try {
                            return agentCore.execute(task, attemptBudget, taskDeadline, observed::add);
                        } finally {
                            TraceStore.clear();
                        }
                    });
            pending.put(future, new TaskControl(task, taskDeadline, observed));
            emitEvent(requestId, "subagent.dispatch", task.taskId(), task.role(), "none", "dispatched",
                    LlmFailureClass.NONE, false, 0L, "closed", Map.of("ordinal", task.ordinal()));
        }

        boolean interrupted = false;
        while (!pending.isEmpty()) {
            List<Future<SubagentResult>> ready = pending.keySet().stream()
                    .filter(Future::isDone)
                    .toList();
            for (Future<SubagentResult> future : ready) {
                TaskControl control = pending.remove(future);
                if (control == null) {
                    continue;
                }
                try {
                    completed.put(control.task().ordinal(), future.get());
                } catch (CancellationException cancelled) {
                    completed.put(control.task().ordinal(), timeoutResult(control, "task_timeout", started));
                } catch (InterruptedException stop) {
                    completed.put(control.task().ordinal(), cancelledResult(control, started));
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    break;
                } catch (ExecutionException infrastructureFailure) {
                    completed.put(control.task().ordinal(), infrastructureFailure(control, started));
                }
            }
            if (interrupted || pending.isEmpty()) {
                break;
            }

            long now = System.nanoTime();
            List<Future<SubagentResult>> expired = new ArrayList<>();
            for (Map.Entry<Future<SubagentResult>, TaskControl> entry : pending.entrySet()) {
                if (now >= entry.getValue().deadlineNanos()) {
                    expired.add(entry.getKey());
                }
            }
            for (Future<SubagentResult> future : expired) {
                TaskControl control = pending.remove(future);
                String reason = now >= globalDeadline ? "global_deadline" : "task_timeout";
                if (future.cancel(true)) {
                    completed.put(control.task().ordinal(), timeoutResult(control, reason, started));
                    continue;
                }
                try {
                    completed.put(control.task().ordinal(), future.get());
                } catch (CancellationException cancelled) {
                    completed.put(control.task().ordinal(), timeoutResult(control, reason, started));
                } catch (InterruptedException stop) {
                    completed.put(control.task().ordinal(), cancelledResult(control, started));
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    break;
                } catch (ExecutionException infrastructureFailure) {
                    completed.put(control.task().ordinal(), infrastructureFailure(control, started));
                }
            }
            if (interrupted || pending.isEmpty()) {
                break;
            }

            long nearestDeadline = globalDeadline;
            for (TaskControl control : pending.values()) {
                nearestDeadline = Math.min(nearestDeadline, control.deadlineNanos());
            }
            long waitNanos = Math.max(1L, nearestDeadline - System.nanoTime());
            Future<SubagentResult> finished;
            try {
                finished = completion.poll(waitNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                interrupted = true;
                break;
            }
            if (finished == null) {
                continue;
            }
            TaskControl control = pending.remove(finished);
            if (control == null) {
                continue;
            }
            try {
                completed.put(control.task().ordinal(), finished.get());
            } catch (CancellationException cancelled) {
                completed.put(control.task().ordinal(), timeoutResult(control, "task_timeout", started));
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                interrupted = true;
                break;
            } catch (ExecutionException infrastructureFailure) {
                completed.put(control.task().ordinal(), infrastructureFailure(control, started));
            }
        }

        if (interrupted) {
            for (Map.Entry<Future<SubagentResult>, TaskControl> entry : pending.entrySet()) {
                entry.getKey().cancel(true);
                completed.put(entry.getValue().task().ordinal(),
                        cancelledResult(entry.getValue(), started));
            }
            pending.clear();
        }

        List<SubagentResult> results = tasks.stream()
                .map(task -> completed.getOrDefault(task.ordinal(), missingResult(task, started)))
                .sorted(Comparator.comparingInt(SubagentResult::ordinal))
                .toList();
        int successCount = (int) results.stream().filter(SubagentResult::succeeded).count();
        int cancelledCount = (int) results.stream()
                .filter(result -> result.status() == SubagentResult.Status.CANCELLED)
                .count();
        boolean cancellationTerminal = interrupted
                || (!results.isEmpty() && cancelledCount == results.size());
        String status = cancellationTerminal ? "CANCELLED"
                : successCount == results.size() ? "COMPLETE"
                : successCount > 0 ? "DEGRADED" : "FAILED";
        state.put("subagent.status", status);
        state.put("subagent.taskCount", results.size());
        state.put("subagent.successCount", successCount);
        state.put("subagent.failureCount", results.size() - successCount - cancelledCount);
        state.put("subagent.cancelledCount", cancelledCount);
        state.put("subagent.elapsedMs", elapsedMs(started));
        state.put("subagent.tasks", results.stream().map(SubagentFlowRunner::taskRow).toList());

        for (SubagentResult result : results) {
            for (SubagentResult.Attempt attempt : result.attempts()) {
                if (attempt.outcome() == SubagentResult.AttemptOutcome.SELECTED) {
                    emitEvent(requestId, "provider.selected", result.taskId(), result.role(), attempt.provider(),
                            "selected", attempt.failureClass(), attempt.fallback(), attempt.elapsedMs(),
                            "closed", Map.of("reasonCode", attempt.reasonCode()));
                    if (attempt.fallback() && !"retry_selected".equals(attempt.reasonCode())) {
                        emitEvent(requestId, "provider.fallback", result.taskId(), result.role(), attempt.provider(),
                                "selected", attempt.failureClass(), true, attempt.elapsedMs(),
                                "closed", Map.of("reasonCode", attempt.reasonCode()));
                    }
                } else if (attempt.outcome() != SubagentResult.AttemptOutcome.SKIPPED) {
                    String circuitState = circuitState(attempt);
                    emitEvent(requestId, "provider.attempt", result.taskId(), result.role(), attempt.provider(),
                            attempt.outcome().name().toLowerCase(), attempt.failureClass(),
                            attempt.fallback(), attempt.elapsedMs(), circuitState,
                            Map.of("reasonCode", attempt.reasonCode()));
                    if (attempt.outcome() == SubagentResult.AttemptOutcome.TIMEOUT) {
                        emitEvent(requestId, "provider.timeout", result.taskId(), result.role(), attempt.provider(),
                                "timeout", attempt.failureClass(), attempt.fallback(), attempt.elapsedMs(),
                                circuitState, Map.of("reasonCode", attempt.reasonCode()));
                    }
                    if (circuitOpenedBy(attempt)) {
                        emitEvent(requestId, "provider.circuit.open", result.taskId(), result.role(),
                                attempt.provider(), "open", attempt.failureClass(), attempt.fallback(),
                                attempt.elapsedMs(), circuitState,
                                Map.of("reasonCode", attempt.reasonCode()));
                    }
                }
            }
            emitEvent(requestId, "subagent.result.received", result.taskId(), result.role(), result.selectedProvider(),
                    result.status().name().toLowerCase(), result.failureClass(),
                    result.fallbackUsed(), result.elapsedMs(), "closed",
                    Map.of("attemptCount", result.attempts().size()));
        }

        if (cancellationTerminal) {
            state.put("subagent.metrics", agentCore.metrics().snapshot().asMap());
            emitEvent(requestId, "subagent.cancelled", "none", "none", "none",
                    "cancelled", LlmFailureClass.CANCELLED_NEUTRAL,
                    false, elapsedMs(started), "closed",
                    Map.of("cancelledCount", cancelledCount));
            return state;
        }

        Map<String, Object> synthesis = synth.run(state, results);
        state.putAll(synthesis);
        boolean synthesisSucceeded = successCount > 0;
        agentCore.metrics().recordSynthesis(synthesisSucceeded);
        state.put("subagent.metrics", agentCore.metrics().snapshot().asMap());
        emitEvent(requestId, "subagent.synthesis.completed", "none", "none", "none",
                String.valueOf(synthesis.get("synthesis.status")).toLowerCase(),
                successCount > 0 ? LlmFailureClass.NONE : terminalFailure(results),
                false, elapsedMs(started), "closed", Map.of("successCount", successCount));
        return state;
    }

    private static Map<String, Object> taskRow(SubagentResult result) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", result.taskId());
        row.put("correlationId", result.correlationId());
        row.put("ordinal", result.ordinal());
        row.put("role", result.role());
        row.put("status", result.status().name());
        row.put("provider", result.selectedProvider());
        row.put("failureClass", result.failureClass().name());
        row.put("errorClass", result.errorClass().name());
        row.put("elapsedMs", result.elapsedMs());
        row.put("fallbackUsed", result.fallbackUsed());
        row.put("attemptedProviders", result.attemptedProviders());
        row.put("retryable", result.retryable());
        row.put("evidence", result.evidence());
        row.put("warnings", result.warnings());
        row.put("blockedExternal", result.blockedExternal());
        row.put("attemptCount", result.attempts().size());
        row.put("timeoutReason", timeoutReason(result));
        row.put("attempts", result.attempts().stream().map(SubagentFlowRunner::attemptRow).toList());
        return Map.copyOf(row);
    }

    private static Map<String, Object> attemptRow(SubagentResult.Attempt attempt) {
        return Map.of(
                "provider", attempt.provider(),
                "outcome", attempt.outcome().name(),
                "failureClass", attempt.failureClass().name(),
                "elapsedMs", attempt.elapsedMs(),
                "fallback", attempt.fallback(),
                "reasonCode", attempt.reasonCode());
    }

    private static String timeoutReason(SubagentResult result) {
        if (result.status() != SubagentResult.Status.TIMEOUT) {
            return "none";
        }
        return result.attempts().stream()
                .filter(attempt -> attempt.outcome() == SubagentResult.AttemptOutcome.TIMEOUT)
                .reduce((first, second) -> second)
                .map(SubagentResult.Attempt::reasonCode)
                .orElse("task_timeout");
    }

    private static SubagentResult timeoutResult(TaskControl control, String reason, long flowStarted) {
        List<SubagentResult.Attempt> attempts = new ArrayList<>(control.observedAttempts());
        String provider = lastSelectedProvider(attempts);
        attempts.add(new SubagentResult.Attempt(provider,
                SubagentResult.AttemptOutcome.TIMEOUT,
                LlmFailureClass.TIMEOUT_SOFT,
                elapsedMs(flowStarted),
                attempts.stream().anyMatch(SubagentResult.Attempt::fallback),
                reason));
        SubagentTask task = control.task();
        return new SubagentResult(task.requestId(), task.ordinal(), task.taskId(), task.role(),
                SubagentResult.Status.TIMEOUT, null, "none", LlmFailureClass.TIMEOUT_SOFT,
                attempts, elapsedMs(flowStarted));
    }

    private static SubagentResult cancelledResult(TaskControl control, long flowStarted) {
        List<SubagentResult.Attempt> attempts = new ArrayList<>(control.observedAttempts());
        attempts.add(new SubagentResult.Attempt(lastSelectedProvider(attempts),
                SubagentResult.AttemptOutcome.CANCELLED,
                LlmFailureClass.CANCELLED_NEUTRAL,
                elapsedMs(flowStarted), false, "request_interrupted"));
        SubagentTask task = control.task();
        return new SubagentResult(task.requestId(), task.ordinal(), task.taskId(), task.role(),
                SubagentResult.Status.CANCELLED, null, "none", LlmFailureClass.CANCELLED_NEUTRAL,
                attempts, elapsedMs(flowStarted));
    }

    private static SubagentResult infrastructureFailure(TaskControl control, long flowStarted) {
        List<SubagentResult.Attempt> attempts = new ArrayList<>(control.observedAttempts());
        attempts.add(new SubagentResult.Attempt(lastSelectedProvider(attempts),
                SubagentResult.AttemptOutcome.FAILED,
                LlmFailureClass.UNKNOWN,
                elapsedMs(flowStarted), false, "runner_failure"));
        SubagentTask task = control.task();
        return new SubagentResult(task.requestId(), task.ordinal(), task.taskId(), task.role(),
                SubagentResult.Status.FAILED, null, "none", LlmFailureClass.UNKNOWN,
                attempts, elapsedMs(flowStarted));
    }

    private static SubagentResult missingResult(SubagentTask task, long flowStarted) {
        return new SubagentResult(task.requestId(), task.ordinal(), task.taskId(), task.role(),
                SubagentResult.Status.FAILED, null, "none", LlmFailureClass.UNKNOWN,
                List.of(new SubagentResult.Attempt("none", SubagentResult.AttemptOutcome.FAILED,
                        LlmFailureClass.UNKNOWN, elapsedMs(flowStarted), false, "result_missing")),
                elapsedMs(flowStarted));
    }

    private static String lastSelectedProvider(List<SubagentResult.Attempt> attempts) {
        for (int index = attempts.size() - 1; index >= 0; index--) {
            SubagentResult.Attempt attempt = attempts.get(index);
            if (attempt.outcome() == SubagentResult.AttemptOutcome.SELECTED) {
                return attempt.provider();
            }
        }
        return "none";
    }

    private static LlmFailureClass terminalFailure(List<SubagentResult> results) {
        return results.stream()
                .map(SubagentResult::failureClass)
                .filter(failure -> failure != LlmFailureClass.NONE)
                .findFirst()
                .orElse(LlmFailureClass.UNKNOWN);
    }

    private static boolean circuitOpenedBy(SubagentResult.Attempt attempt) {
        if (attempt == null
                || (attempt.outcome() != SubagentResult.AttemptOutcome.FAILED
                && attempt.outcome() != SubagentResult.AttemptOutcome.TIMEOUT)) {
            return false;
        }
        return "provider_failed".equals(attempt.reasonCode())
                || "availability_failed".equals(attempt.reasonCode())
                || "blank_output".equals(attempt.reasonCode());
    }

    private static String circuitState(SubagentResult.Attempt attempt) {
        if (!circuitOpenedBy(attempt)) {
            return "closed";
        }
        return attempt.failureClass() == LlmFailureClass.AUTH_MISSING ? "auth_blocked" : "open";
    }

    private static void emitEvent(String requestId,
                                  String event,
                                  String taskId,
                                  String role,
                                  String provider,
                                  String status,
                                  LlmFailureClass errorClass,
                                  boolean fallbackUsed,
                                  long elapsedMs,
                                  String circuitState,
                                  Map<String, Object> extra) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", Instant.now().toString());
        row.put("correlationId", SafeRedactor.hashValue(requestId));
        row.put("taskIdHash", SafeRedactor.hashValue(taskId));
        row.put("role", SafeRedactor.traceLabelOrFallback(role, "unknown"));
        row.put("event", event);
        row.put("provider", provider);
        row.put("status", status);
        row.put("elapsedMs", Math.max(0L, elapsedMs));
        row.put("fallbackUsed", fallbackUsed);
        row.put("errorClass", errorClass == null ? "UNKNOWN" : errorClass.name());
        row.put("circuitState", circuitState);
        if (extra != null) {
            row.putAll(extra);
        }
        try {
            TraceStore.append("agent.subagent.events", Map.copyOf(row));
        } catch (RuntimeException traceFailure) {
            log.debug("[AWX][subagent] event=trace_failed errorType={}",
                    SafeRedactor.traceLabelOrFallback(traceFailure.getClass().getSimpleName(), "unknown"));
        }
        log.info("[AWX][subagent] event={} correlationId={} taskIdHash={} role={} provider={} "
                        + "status={} elapsedMs={} fallbackUsed={} errorClass={} circuitState={}",
                event, row.get("correlationId"), row.get("taskIdHash"), row.get("role"),
                provider, status, Math.max(0L, elapsedMs), fallbackUsed,
                errorClass == null ? "UNKNOWN" : errorClass.name(), circuitState);
    }

    private static long positiveLong(Environment environment, String key, long fallback) {
        if (environment == null) {
            return fallback;
        }
        Long value = environment.getProperty(key, Long.class);
        return value == null || value <= 0L ? fallback : value;
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "subagent-v1-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static long saturatingAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private record TaskControl(
            SubagentTask task,
            long deadlineNanos,
            List<SubagentResult.Attempt> observedAttempts) {
    }
}
