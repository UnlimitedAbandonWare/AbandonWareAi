package com.abandonware.ai.agent.orchestrator;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.abandonware.ai.agent.orchestrator.nodes.CriticNode;
import com.abandonware.ai.agent.orchestrator.recovery.DefaultRecoveryExecutor;
import com.abandonware.ai.agent.orchestrator.recovery.RecoveryAction;
import com.abandonware.ai.agent.orchestrator.recovery.RecoveryPolicy;
import com.abandonware.ai.agent.orchestrator.recovery.RecoveryRound;
import com.abandonware.ai.agent.orchestrator.recovery.Verdict;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentFlowRunner;
import com.abandonware.ai.agent.trace.AgentBreadcrumbMemory;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class Orchestrator {
    private final DefaultRecoveryExecutor recoveryExecutor;
    private final CriticNode criticNode;
    private final RecoveryPolicy policy;
    private final ObjectProvider<FailurePatternMemoryService> failurePatternMemory;
    private final SubagentFlowRunner subagentFlowRunner;

    public Orchestrator() {
        this(RecoveryPolicy.load());
    }

    public Orchestrator(RecoveryPolicy policy) {
        this(new DefaultRecoveryExecutor(policy, null), new CriticNode(policy), policy);
    }

    public Orchestrator(DefaultRecoveryExecutor recoveryExecutor,
                        CriticNode criticNode,
                        RecoveryPolicy policy) {
        this(recoveryExecutor, criticNode, policy, null, null);
    }

    public Orchestrator(DefaultRecoveryExecutor recoveryExecutor,
                        CriticNode criticNode,
                        RecoveryPolicy policy,
                        ObjectProvider<FailurePatternMemoryService> failurePatternMemory) {
        this(recoveryExecutor, criticNode, policy, failurePatternMemory, null);
    }

    @Autowired
    public Orchestrator(DefaultRecoveryExecutor recoveryExecutor,
                        CriticNode criticNode,
                        RecoveryPolicy policy,
                        ObjectProvider<FailurePatternMemoryService> failurePatternMemory,
                        SubagentFlowRunner subagentFlowRunner) {
        this.recoveryExecutor = recoveryExecutor == null ? new DefaultRecoveryExecutor() : recoveryExecutor;
        this.policy = policy == null ? RecoveryPolicy.load() : policy;
        this.criticNode = criticNode == null ? new CriticNode(this.policy) : criticNode;
        this.failurePatternMemory = failurePatternMemory;
        this.subagentFlowRunner = subagentFlowRunner;
    }

    public Map<String, Object> run(String flowId, Map<String, Object> input) {
        return execute(flowId, input, null);
    }

    public java.util.Map<String, Object> execute(String flowId, java.util.Map<String, Object> input, com.abandonware.ai.agent.tool.request.ToolContext ctx) {
        Map<String, Object> state = new LinkedHashMap<>();
        if (input != null) state.putAll(input);
        state.putIfAbsent("flow", flowId == null ? "default.v1" : flowId);

        if ("subagent.v1".equals(flowId) && subagentFlowRunner != null) {
            Map<String, Object> subagentState = subagentFlowRunner.run(flowId, state, ctx);
            if (!"FAILED".equals(subagentState.get("subagent.status"))) {
                return subagentState;
            }
            state.clear();
            state.putAll(subagentState);
        }

        long started = Instant.now().toEpochMilli();
        Verdict verdict = state.get("verdict") instanceof Verdict v
                ? v
                : extractVerdict(criticNode.run(state));
        if (verdict == null || verdict.decision() == Verdict.Decision.ACCEPT) {
            if (verdict != null) state.put("verdict", verdict);
            AgentBreadcrumbMemory.judgment(flowId, verdict, null, 0,
                    Instant.now().toEpochMilli() - started, state, null, failurePatternMemory());
            return state;
        }

        int round = round(state.get("recovery.round")) + 1;
        int maxRounds = policy.maxRounds();
        RecoveryAction action = (round > maxRounds || verdict.decision() == Verdict.Decision.ABORT)
                ? RecoveryAction.ESCALATE
                : recoveryExecutor.resolve(verdict.failureClass());

        state.put("recovery.round", round);
        Map<String, Object> patch = recoveryExecutor.apply(action, verdict, state, ctx);
        if (patch != null) state.putAll(patch);
        state.put("verdict", verdict);
        state.put("recovery.round", round);
        AgentBreadcrumbMemory.judgment(flowId, verdict, action, round,
                Instant.now().toEpochMilli() - started, state, patch, failurePatternMemory());

        TraceContext.current().pushRecoveryRound(new RecoveryRound(
                round,
                "ORCHESTRATOR",
                verdict,
                action,
                Instant.now().toEpochMilli() - started,
                Instant.ofEpochMilli(started),
                new LinkedHashMap<>(state).keySet().stream()
                        .collect(Collectors.toMap(k -> k, k -> "snap",
                                (a, b) -> a, LinkedHashMap::new))));
        return state;
    }

    private Verdict extractVerdict(Map<String, Object> out) {
        return out != null && out.get("verdict") instanceof Verdict v ? v : null;
    }

    private static int round(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof CharSequence text) {
            try {
                return Math.max(0, Integer.parseInt(text.toString().trim()));
            } catch (NumberFormatException ex) {
                traceSuppressed("recovery.round", text, ex);
            }
        }
        return 0;
    }

    private FailurePatternMemoryService failurePatternMemory() {
        if (failurePatternMemory == null) {
            return null;
        }
        try {
            return failurePatternMemory.getIfAvailable();
        } catch (RuntimeException ex) {
            TraceStore.put("agent.breadcrumb.memory.stored", false);
            TraceStore.put("agent.breadcrumb.memory.skipReason", "memory_provider_failed");
            TraceStore.put("agent.breadcrumb.memory.errorType",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            return null;
        }
    }

    private static void traceSuppressed(String stage, Object value, Throwable error) {
        String raw = value == null ? null : String.valueOf(value);
        TraceStore.put("agent.orchestrator.suppressed", true);
        TraceStore.put("agent.orchestrator.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.orchestrator.suppressed.errorType", "invalid_number");
        TraceStore.put("agent.orchestrator.suppressed.valueHash", SafeRedactor.hashValue(raw));
        TraceStore.put("agent.orchestrator.suppressed.valueLength", raw == null ? 0 : raw.length());
    }

}
