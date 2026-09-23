package com.abandonware.ai.agent.orchestrator.subagent;

import com.example.lms.llm.gateway.LlmFailureClass;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Low-cardinality in-memory metrics with an optional Micrometer binding. */
@Component
public final class SubagentAgentMetrics implements MeterBinder {

    private static final int MAX_ELAPSED_SAMPLES = 512;

    private final AtomicLong callCount = new AtomicLong();
    private final AtomicLong fallbackCount = new AtomicLong();
    private final AtomicLong timeoutCount = new AtomicLong();
    private final AtomicLong circuitOpenCount = new AtomicLong();
    private final AtomicLong synthesisCount = new AtomicLong();
    private final AtomicLong synthesisSuccessCount = new AtomicLong();
    private final AtomicLong glmActivationAttemptCount = new AtomicLong();
    private final AtomicLong glmLiveProbeAttemptCount = new AtomicLong();
    private final AtomicLong glmLiveProbeSuccessCount = new AtomicLong();
    private final AtomicLong glmLiveProbeFailureCount = new AtomicLong();
    private final AtomicLong glmProbeFallbackCount = new AtomicLong();
    private final AtomicLong queueDiscoveredCount = new AtomicLong();
    private final AtomicLong queueCompletedCount = new AtomicLong();
    private final AtomicLong queueFailedCount = new AtomicLong();
    private final AtomicLong queueHoldCount = new AtomicLong();
    private final AtomicLong perspectiveCompletedCount = new AtomicLong();
    private final AtomicLong perspectiveCancelledCount = new AtomicLong();
    private final AtomicLong perspectiveTimedOutCount = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> glmFailureClassCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MutableProviderMetrics> providerMetrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MutableToolMetrics> toolMetrics = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> elapsedSamples = new ArrayDeque<>();
    private final AtomicBoolean bound = new AtomicBoolean();

    public void recordCall() {
        callCount.incrementAndGet();
    }

    public void recordAttempt(SubagentResult.Attempt attempt) {
        if (attempt == null) {
            return;
        }
        MutableProviderMetrics provider = providerMetrics.computeIfAbsent(
                safeLabel(attempt.provider()), ignored -> new MutableProviderMetrics());
        if (attempt.failureClass() != null
                && attempt.failureClass() != LlmFailureClass.NONE
                && attempt.outcome() != SubagentResult.AttemptOutcome.SELECTED
                && attempt.outcome() != SubagentResult.AttemptOutcome.SUCCESS) {
            provider.lastErrorClass.set(attempt.failureClass().name());
        }
        if (attempt.outcome() == SubagentResult.AttemptOutcome.SELECTED) {
            provider.attemptCount.incrementAndGet();
            if (attempt.fallback() && !"retry_selected".equals(attempt.reasonCode())) {
                fallbackCount.incrementAndGet();
            }
        } else if (attempt.outcome() == SubagentResult.AttemptOutcome.SUCCESS) {
            provider.successCount.incrementAndGet();
        } else if (attempt.outcome() == SubagentResult.AttemptOutcome.TIMEOUT) {
            timeoutCount.incrementAndGet();
        }
    }

    public void recordCircuitOpen() {
        circuitOpenCount.incrementAndGet();
    }

    public void recordResult(SubagentResult result) {
        if (result == null) {
            return;
        }
        synchronized (elapsedSamples) {
            if (elapsedSamples.size() == MAX_ELAPSED_SAMPLES) {
                elapsedSamples.removeFirst();
            }
            elapsedSamples.addLast(Math.max(0L, result.elapsedMs()));
        }
    }

    public void recordSynthesis(boolean succeeded) {
        synthesisCount.incrementAndGet();
        if (succeeded) {
            synthesisSuccessCount.incrementAndGet();
        }
    }

    public void recordGlmActivationAttempt() {
        glmActivationAttemptCount.incrementAndGet();
    }

    public void recordGlmProbeCompletion(boolean generationAttempted,
                                         boolean active,
                                         boolean fallbackUsed,
                                         LlmFailureClass failureClass) {
        if (!generationAttempted) {
            return;
        }
        glmLiveProbeAttemptCount.incrementAndGet();
        if (active) {
            glmLiveProbeSuccessCount.incrementAndGet();
        } else {
            glmLiveProbeFailureCount.incrementAndGet();
        }
        if (fallbackUsed) {
            glmProbeFallbackCount.incrementAndGet();
        }
        LlmFailureClass safeFailure = failureClass == null ? LlmFailureClass.UNKNOWN : failureClass;
        if (safeFailure != LlmFailureClass.NONE) {
            glmFailureClassCounts.computeIfAbsent(
                    safeFailure.name(), ignored -> new AtomicLong()).incrementAndGet();
        }
    }

    public void recordQueueDiscovered() {
        queueDiscoveredCount.incrementAndGet();
    }

    public void recordQueueCompleted() {
        queueCompletedCount.incrementAndGet();
    }

    public void recordQueueFailed() {
        queueFailedCount.incrementAndGet();
    }

    public void recordQueueHold() {
        queueHoldCount.incrementAndGet();
    }

    public void recordPerspective(SubagentResult.Status status) {
        if (status == SubagentResult.Status.CANCELLED) {
            perspectiveCancelledCount.incrementAndGet();
        } else if (status == SubagentResult.Status.TIMEOUT) {
            perspectiveTimedOutCount.incrementAndGet();
        } else {
            perspectiveCompletedCount.incrementAndGet();
        }
    }

    /** Shared with the MCP adapter; tool names are bounded categorical labels only. */
    public void recordMcpTool(String toolName, boolean succeeded) {
        MutableToolMetrics tool = toolMetrics.computeIfAbsent(
                safeLabel(toolName), ignored -> new MutableToolMetrics());
        tool.callCount.incrementAndGet();
        if (!succeeded) {
            tool.failureCount.incrementAndGet();
        }
    }

    public Snapshot snapshot() {
        List<Long> samples;
        synchronized (elapsedSamples) {
            samples = new ArrayList<>(elapsedSamples);
        }
        long elapsedTotal = samples.stream().mapToLong(Long::longValue).sum();
        double average = samples.isEmpty() ? 0.0d : (double) elapsedTotal / samples.size();
        long p95 = percentile95(samples);

        Map<String, ProviderSnapshot> providers = new LinkedHashMap<>();
        Map<String, String> recentErrors = new LinkedHashMap<>();
        providerMetrics.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    providers.put(entry.getKey(), entry.getValue().snapshot());
                    String errorClass = entry.getValue().lastErrorClass.get();
                    if (!"NONE".equals(errorClass)) {
                        recentErrors.put(entry.getKey(), errorClass);
                    }
                });
        Map<String, ToolSnapshot> tools = new LinkedHashMap<>();
        toolMetrics.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> tools.put(entry.getKey(), entry.getValue().snapshot()));
        Map<String, Long> glmFailures = new LinkedHashMap<>();
        glmFailureClassCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> glmFailures.put(entry.getKey(), entry.getValue().get()));
        return new Snapshot(
                callCount.get(),
                fallbackCount.get(),
                timeoutCount.get(),
                circuitOpenCount.get(),
                average,
                p95,
                synthesisCount.get(),
                synthesisSuccessCount.get(),
                glmActivationAttemptCount.get(),
                glmLiveProbeAttemptCount.get(),
                glmLiveProbeSuccessCount.get(),
                glmLiveProbeFailureCount.get(),
                glmProbeFallbackCount.get(),
                Map.copyOf(glmFailures),
                queueDiscoveredCount.get(),
                queueCompletedCount.get(),
                queueFailedCount.get(),
                queueHoldCount.get(),
                perspectiveCompletedCount.get(),
                perspectiveCancelledCount.get(),
                perspectiveTimedOutCount.get(),
                Map.copyOf(recentErrors),
                Map.copyOf(providers),
                Map.copyOf(tools));
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (registry == null || !bound.compareAndSet(false, true)) {
            return;
        }
        functionCounter(registry, "agent.subagent.calls", callCount);
        functionCounter(registry, "agent.subagent.fallbacks", fallbackCount);
        functionCounter(registry, "agent.subagent.timeouts", timeoutCount);
        functionCounter(registry, "agent.subagent.circuit.opens", circuitOpenCount);
        functionCounter(registry, "agent.subagent.synthesis.calls", synthesisCount);
        functionCounter(registry, "agent.subagent.synthesis.success", synthesisSuccessCount);
        functionCounter(registry, "agent.subagent.glm.activation.attempts", glmActivationAttemptCount);
        functionCounter(registry, "agent.subagent.glm.probe.attempts", glmLiveProbeAttemptCount);
        functionCounter(registry, "agent.subagent.glm.probe.success", glmLiveProbeSuccessCount);
        functionCounter(registry, "agent.subagent.glm.probe.failures", glmLiveProbeFailureCount);
        functionCounter(registry, "agent.subagent.glm.probe.fallbacks", glmProbeFallbackCount);
        Gauge.builder("agent.subagent.elapsed.average", this,
                        metrics -> metrics.snapshot().averageElapsedMs())
                .baseUnit("milliseconds")
                .register(registry);
        Gauge.builder("agent.subagent.elapsed.p95", this,
                        metrics -> metrics.snapshot().p95ElapsedMs())
                .baseUnit("milliseconds")
                .register(registry);
        Gauge.builder("agent.subagent.synthesis.success.ratio", this,
                        metrics -> ratio(metrics.synthesisSuccessCount.get(), metrics.synthesisCount.get()))
                .register(registry);
    }

    private static void functionCounter(MeterRegistry registry, String name, AtomicLong value) {
        FunctionCounter.builder(name, value, AtomicLong::get).register(registry);
    }

    private static long percentile95(List<Long> values) {
        if (values.isEmpty()) {
            return 0L;
        }
        values.sort(Comparator.naturalOrder());
        int index = Math.max(0, (int) Math.ceil(values.size() * 0.95d) - 1);
        return values.get(index);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0L ? 0.0d : (double) numerator / denominator;
    }

    private static String safeLabel(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            return "unknown";
        }
        return raw;
    }

    public record Snapshot(
            long callCount,
            long fallbackCount,
            long timeoutCount,
            long circuitOpenCount,
            double averageElapsedMs,
            long p95ElapsedMs,
            long synthesisCount,
            long synthesisSuccessCount,
            long glmActivationAttemptCount,
            long glmLiveProbeAttemptCount,
            long glmLiveProbeSuccessCount,
            long glmLiveProbeFailureCount,
            long glmProbeFallbackCount,
            Map<String, Long> glmFailureClassCounts,
            long queueDiscoveredCount,
            long queueCompletedCount,
            long queueFailedCount,
            long queueHoldCount,
            long perspectiveCompletedCount,
            long perspectiveCancelledCount,
            long perspectiveTimedOutCount,
            Map<String, String> recentErrorClasses,
            Map<String, ProviderSnapshot> providerMetrics,
            Map<String, ToolSnapshot> mcpToolMetrics) {

        public Map<String, Object> asMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("callCount", callCount);
            row.put("fallbackCount", fallbackCount);
            row.put("timeoutCount", timeoutCount);
            row.put("circuitOpenCount", circuitOpenCount);
            row.put("averageElapsedMs", averageElapsedMs);
            row.put("p95ElapsedMs", p95ElapsedMs);
            row.put("synthesisCount", synthesisCount);
            row.put("synthesisSuccessCount", synthesisSuccessCount);
            row.put("synthesisSuccessRate", ratio(synthesisSuccessCount, synthesisCount));
            row.put("glmActivationAttemptCount", glmActivationAttemptCount);
            row.put("glmLiveProbeAttemptCount", glmLiveProbeAttemptCount);
            row.put("glmLiveProbeSuccessCount", glmLiveProbeSuccessCount);
            row.put("glmLiveProbeFailureCount", glmLiveProbeFailureCount);
            row.put("glmProbeFallbackCount", glmProbeFallbackCount);
            row.put("glmFailureClassCounts", glmFailureClassCounts);
            row.put("queueDiscoveredCount", queueDiscoveredCount);
            row.put("queueCompletedCount", queueCompletedCount);
            row.put("queueFailedCount", queueFailedCount);
            row.put("queueHoldCount", queueHoldCount);
            row.put("perspectiveCompletedCount", perspectiveCompletedCount);
            row.put("perspectiveCancelledCount", perspectiveCancelledCount);
            row.put("perspectiveTimedOutCount", perspectiveTimedOutCount);
            row.put("recentErrorClasses", recentErrorClasses);
            row.put("providerMetrics", providerMetrics);
            row.put("mcpToolMetrics", mcpToolMetrics);
            return Map.copyOf(row);
        }
    }

    public record ProviderSnapshot(long attemptCount, long successCount, double successRate) {
    }

    public record ToolSnapshot(long callCount, long failureCount) {
    }

    private static final class MutableProviderMetrics {
        private final AtomicLong attemptCount = new AtomicLong();
        private final AtomicLong successCount = new AtomicLong();
        private final AtomicReference<String> lastErrorClass = new AtomicReference<>("NONE");

        private ProviderSnapshot snapshot() {
            long attempts = attemptCount.get();
            long successes = successCount.get();
            return new ProviderSnapshot(attempts, successes, ratio(successes, attempts));
        }
    }

    private static final class MutableToolMetrics {
        private final AtomicLong callCount = new AtomicLong();
        private final AtomicLong failureCount = new AtomicLong();

        private ToolSnapshot snapshot() {
            return new ToolSnapshot(callCount.get(), failureCount.get());
        }
    }
}
