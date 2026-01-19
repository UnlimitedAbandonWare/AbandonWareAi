package com.abandonware.ai.agent.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.observability.AgentMetrics
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.agent.observability.AgentMetrics
role: config
*/
public class AgentMetrics {
    private final MeterRegistry registry; // may be null
    private final Map<String, AtomicLong> stepCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> toolCounts = new ConcurrentHashMap<>();
    /**
     * Per-tool error counters.  When a tool invocation throws an exception
     * the orchestrator calls {@link #incrementToolError(String)} which
     * increments a Micrometer counter named {@code agent_tool_errors_total}
     * with a {@code tool} tag.  A ConcurrentHashMap is used to lazily
     * register counters on-demand.
     */
    private final ConcurrentHashMap<String, Counter> toolError = new ConcurrentHashMap<>();

    private DistributionSummary tokenCostSummary(String model){
        return registry == null ? null
                : DistributionSummary.builder("token_cost_sum")
                .description("Aggregated token cost (USD)")
                .tag("model", model).register(registry);
    }

    private Timer latencyTimer(String op, String name) {
        return registry == null ? null
                : Timer.builder(name).tag("operation", op).register(registry);
    }

    public AgentMetrics() { this.registry = null; }
    public AgentMetrics(MeterRegistry registry) { this.registry = registry; }

    /** Increment step counter and publish Micrometer metric. */
    public void incrementStep(String step) {
        stepCounts.computeIfAbsent(step, k -> new AtomicLong()).incrementAndGet();
        if (registry != null) {
            registry.counter("agent_steps_total", "step", step).increment();
        }
    }

    /** Increment tool counter and publish Micrometer metrics (two names for compatibility). */
    public void incrementTool(String tool) {
        toolCounts.computeIfAbsent(tool, k -> new AtomicLong()).incrementAndGet();
        if (registry != null) {
            registry.counter("agent_tool_invocations_total", "tool", tool).increment();
            registry.counter("agent_agent_tool_invocations_total", "tool", tool).increment();
        }
    }

    /** Record latency for an operation (in milliseconds). */
    public void recordLatency(String operation, long millis) {
        if (registry != null) {
            latencyTimer(operation, "latency_ms").record(java.time.Duration.ofMillis(millis));
            latencyTimer(operation, "agent_latency_ms").record(java.time.Duration.ofMillis(millis));
        }
    }

    /** Add dollar cost for a model to 'token_cost_sum'. */
    public void addTokenCost(String model, double usd){
        if (registry != null) {
            tokenCostSummary(model).record(usd);
        }
    }

    public Map<String, Long> snapshotSteps() {
        return stepCounts.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    public Map<String, Long> snapshotTools() {
        return toolCounts.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    /**
     * Increment the per-tool error counter.  When a tool invocation fails
     * this method should be called by the orchestrator.  A unique counter
     * will be registered for each tool id the first time it is encountered.
     *
     * @param toolId the identifier of the tool that failed
     */
    public void incrementToolError(String toolId) {
        if (registry == null) return;
        toolError.computeIfAbsent(toolId, id ->
                Counter.builder("agent_tool_errors_total").tag("tool", id).register(registry)
        ).increment();
    }
}