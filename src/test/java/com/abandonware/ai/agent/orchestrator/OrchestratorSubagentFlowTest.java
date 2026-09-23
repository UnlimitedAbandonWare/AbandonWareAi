package com.abandonware.ai.agent.orchestrator;

import com.abandonware.ai.agent.orchestrator.nodes.CriticNode;
import com.abandonware.ai.agent.orchestrator.nodes.PlannerNode;
import com.abandonware.ai.agent.orchestrator.nodes.SynthNode;
import com.abandonware.ai.agent.orchestrator.recovery.DefaultRecoveryExecutor;
import com.abandonware.ai.agent.orchestrator.recovery.RecoveryAction;
import com.abandonware.ai.agent.orchestrator.recovery.RecoveryPolicy;
import com.abandonware.ai.agent.orchestrator.recovery.Verdict;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentFlowRunner;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentProvider;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentProviderChain;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorSubagentFlowTest {

    @Test
    void exactSubagentFlowUsesRunnerAndReturnsSynthesizedState() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        try (SubagentFlowRunner runner = runner(providerCalls)) {
            Orchestrator orchestrator = orchestrator(runner);

            Map<String, Object> response = orchestrator.execute(
                    "subagent.v1", Map.of("question", "orchestrate this"), null);

            assertThat(providerCalls).hasValue(2);
            assertThat(response).containsEntry("flow", "subagent.v1");
            assertThat(response).containsEntry("subagent.status", "COMPLETE");
            assertThat(String.valueOf(response.get("answer"))).contains("analysis", "verification");
        }
    }

    @Test
    void otherFlowsKeepExistingCriticPathAndNeverDispatchSubagents() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        try (SubagentFlowRunner runner = runner(providerCalls)) {
            Orchestrator orchestrator = orchestrator(runner);

            Map<String, Object> response = orchestrator.execute(
                    "safe.v1",
                    Map.of("question", "existing flow", "verdict", Verdict.accept(1.0d, "accepted")),
                    null);

            assertThat(providerCalls).hasValue(0);
            assertThat(response).containsEntry("flow", "safe.v1");
            assertThat(response).doesNotContainKey("subagent.status");
        }
    }

    @Test
    void allFailedSubagentFlowEntersExistingRecoveryExactlyOnce() throws Exception {
        RecoveryPolicy policy = RecoveryPolicy.load();
        CountingRecoveryExecutor recovery = new CountingRecoveryExecutor(policy);
        SubagentProvider provider = new SubagentProvider() {
            @Override
            public String id() {
                return "ollama";
            }

            @Override
            public int order() {
                return 30;
            }

            @Override
            public Availability availability() {
                return Availability.enabled();
            }

            @Override
            public String execute(com.abandonware.ai.agent.orchestrator.subagent.SubagentTask task,
                                  long timeoutMs) {
                throw new LlmGatewayException(
                        "categorical_failure", LlmFailureClass.RESPONSE_MODEL_UNVERIFIED);
            }
        };
        try (SubagentFlowRunner runner = new SubagentFlowRunner(
                new PlannerNode(),
                new SynthNode(),
                new SubagentProviderChain(List.of(provider), new LlmGatewayFailureClassifier()),
                2_000L,
                1_500L,
                Executors.newFixedThreadPool(2))) {
            Orchestrator orchestrator = new Orchestrator(
                    recovery, new CriticNode(policy), policy, null, runner);

            Map<String, Object> response = orchestrator.execute(
                    "subagent.v1", Map.of("question", "all fail"), null);

            assertThat(response).containsEntry("subagent.status", "FAILED");
            assertThat(response).containsKey("recovery.action");
            assertThat(response).containsEntry("recovery.round", 1);
            assertThat(recovery.applyCalls).hasValue(1);
        }
    }

    private static Orchestrator orchestrator(SubagentFlowRunner runner) {
        RecoveryPolicy policy = RecoveryPolicy.load();
        return new Orchestrator(
                new DefaultRecoveryExecutor(policy, null),
                new CriticNode(policy),
                policy,
                null,
                runner);
    }

    private static SubagentFlowRunner runner(AtomicInteger providerCalls) {
        SubagentProvider provider = new SubagentProvider() {
            @Override
            public String id() {
                return "ollama";
            }

            @Override
            public int order() {
                return 30;
            }

            @Override
            public Availability availability() {
                return Availability.enabled();
            }

            @Override
            public String execute(com.abandonware.ai.agent.orchestrator.subagent.SubagentTask task,
                                  long timeoutMs) {
                providerCalls.incrementAndGet();
                return task.role() + " output";
            }
        };
        return new SubagentFlowRunner(
                new PlannerNode(),
                new SynthNode(),
                new SubagentProviderChain(List.of(provider), new LlmGatewayFailureClassifier()),
                2_000L,
                1_500L,
                Executors.newFixedThreadPool(2));
    }

    private static final class CountingRecoveryExecutor extends DefaultRecoveryExecutor {
        private final AtomicInteger applyCalls = new AtomicInteger();

        private CountingRecoveryExecutor(RecoveryPolicy policy) {
            super(policy, null);
        }

        @Override
        public Map<String, Object> apply(RecoveryAction action,
                                         Verdict verdict,
                                         Map<String, Object> state,
                                         ToolContext context) {
            applyCalls.incrementAndGet();
            return super.apply(action, verdict, state, context);
        }
    }
}
