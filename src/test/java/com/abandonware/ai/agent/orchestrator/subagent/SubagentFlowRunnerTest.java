package com.abandonware.ai.agent.orchestrator.subagent;

import com.abandonware.ai.agent.orchestrator.nodes.PlannerNode;
import com.abandonware.ai.agent.orchestrator.nodes.SynthNode;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubagentFlowRunnerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void plannerCreatesTwoDistinctImmutableTaskContexts() {
        PlannerNode planner = new PlannerNode();

        List<SubagentTask> tasks = planner.subagentTasks("request-a", Map.of("question", "verify this"));

        assertThat(tasks).extracting(SubagentTask::role).containsExactly("analysis", "verification");
        assertThat(tasks.get(0).context()).isNotSameAs(tasks.get(1).context());
        assertThat(tasks.get(0).context()).containsEntry("questionLength", 11);
        assertThat(tasks.get(1).context()).containsEntry("questionLength", 11);
        assertThatThrownBy(() -> tasks.get(0).context().put("leak", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(tasks.get(1).context()).doesNotContainKey("leak");
    }

    @Test
    void dispatchesBothTasksConcurrentlyAndSynthesizesInPlanOrder() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        SubagentProvider provider = provider(task -> {
            entered.countDown();
            if (!entered.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("tasks_not_concurrent");
            }
            return task.role() + " result";
        });

        try (SubagentFlowRunner runner = runner(provider, 2_000L, 1_500L)) {
            Map<String, Object> response = runner.run(
                    "subagent.v1", Map.of("question", "bounded request"), null);

            assertThat(response).containsEntry("subagent.status", "COMPLETE");
            assertThat(response).containsEntry("subagent.taskCount", 2);
            assertThat(response).containsEntry("subagent.successCount", 2);
            assertThat(String.valueOf(response.get("answer")))
                    .contains("analysis result", "verification result")
                    .matches("(?s).*analysis result.*verification result.*");
            assertThat(taskRows(response))
                    .extracting(row -> row.get("role"))
                    .containsExactly("analysis", "verification");
        }
    }

    @Test
    void oneFailedTaskPropagatesCategoricalErrorAndStillSynthesizesSuccess() throws Exception {
        SubagentProvider provider = provider(task -> {
            if ("verification".equals(task.role())) {
                throw new LlmGatewayException("unverified", LlmFailureClass.RESPONSE_MODEL_UNVERIFIED);
            }
            return "analysis survives";
        });

        try (SubagentFlowRunner runner = runner(provider, 2_000L, 1_500L)) {
            Map<String, Object> response = runner.run(
                    "subagent.v1", Map.of("question", "partial request"), null);

            assertThat(response).containsEntry("subagent.status", "DEGRADED");
            assertThat(response).containsEntry("subagent.successCount", 1);
            assertThat(response).containsEntry("subagent.failureCount", 1);
            assertThat(String.valueOf(response.get("answer"))).contains("analysis survives");
            assertThat(taskRows(response))
                    .anySatisfy(row -> {
                        assertThat(row).containsEntry("role", "verification");
                        assertThat(row).containsEntry("failureClass", "RESPONSE_MODEL_UNVERIFIED");
                    });
        }
    }

    @Test
    void taskTimeoutCancelsWorkersAndReturnsStableAllFailedAnswer() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        SubagentProvider provider = provider(task -> {
            try {
                Thread.sleep(5_000L);
                return "late";
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                throw cancelled;
            }
        });

        long started = System.nanoTime();
        try (SubagentFlowRunner runner = runner(provider, 1_000L, 75L)) {
            Map<String, Object> response = runner.run(
                    "subagent.v1", Map.of("question", "timeout request"), null);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(response).containsEntry("subagent.status", "FAILED");
            assertThat(response).containsEntry("subagent.successCount", 0);
            assertThat(response).containsEntry("subagent.failureCount", 2);
            assertThat(String.valueOf(response.get("answer")))
                    .isEqualTo("서브에이전트 실행이 완료되지 않았습니다. 사용 가능한 provider를 확인해 주세요.");
            assertThat(taskRows(response))
                    .allSatisfy(row -> assertThat(row).containsEntry("failureClass", "TIMEOUT_SOFT"));
            assertThat(elapsedMs).as("outer nanosecond deadline remains authoritative").isLessThan(1_000L);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void globalDeadlineWinsOverLongerTaskTimeout() throws Exception {
        SubagentProvider provider = provider(task -> {
            Thread.sleep(5_000L);
            return "late";
        });

        long started = System.nanoTime();
        try (SubagentFlowRunner runner = runner(provider, 80L, 2_000L)) {
            Map<String, Object> response = runner.run(
                    "subagent.v1", Map.of("question", "deadline request"), null);

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(response).containsEntry("subagent.status", "FAILED");
            assertThat(elapsedMs).isLessThan(1_000L);
            assertThat(taskRows(response))
                    .allSatisfy(row -> assertThat(row).containsEntry("timeoutReason", "global_deadline"));
        }
    }

    @Test
    void providerCancellationRemainsCancelledAndSkipsSynthesis() throws Exception {
        SubagentProvider provider = provider(task -> {
            throw new CancellationException("synthetic cancellation");
        });

        try (SubagentFlowRunner runner = runner(provider, 2_000L, 1_500L)) {
            Map<String, Object> response = runner.run(
                    "subagent.v1", Map.of("question", "cancelled provider"), null);

            assertThat(response).containsEntry("subagent.status", "CANCELLED");
            assertThat(response).containsEntry("subagent.cancelledCount", 2);
            assertThat(response).containsEntry("subagent.failureCount", 0);
            assertThat(response).doesNotContainKeys("answer", "synthesis.status");
            assertThat(taskRows(response)).allSatisfy(row -> assertThat(row)
                    .containsEntry("status", "CANCELLED")
                    .containsEntry("failureClass", "CANCELLED_NEUTRAL"));
            assertThat(metrics(response)).containsEntry("synthesisCount", 0L);
        }
    }

    @Test
    void coordinatorInterruptionReturnsCancelledWithoutSynthesis() throws Exception {
        CountDownLatch workersEntered = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        SubagentProvider provider = provider(task -> {
            workersEntered.countDown();
            releaseWorkers.await(5, TimeUnit.SECONDS);
            return "late-" + task.role();
        });
        AtomicReference<Map<String, Object>> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> failureRef = new AtomicReference<>();

        try (SubagentFlowRunner runner = runner(provider, 5_000L, 4_000L)) {
            Thread coordinator = new Thread(() -> {
                try {
                    responseRef.set(runner.run(
                            "subagent.v1", Map.of("question", "interrupt coordinator"), null));
                } catch (Throwable failure) {
                    failureRef.set(failure);
                }
            }, "subagent-coordinator-interruption-test");
            coordinator.start();
            try {
                assertThat(workersEntered.await(2, TimeUnit.SECONDS)).isTrue();
                coordinator.interrupt();
                coordinator.join(2_000L);

                assertThat(coordinator.isAlive()).isFalse();
                assertThat(coordinator.isInterrupted()).isTrue();
                assertThat(failureRef.get()).isNull();
                Map<String, Object> response = responseRef.get();
                assertThat(response).isNotNull();
                assertThat(response).containsEntry("subagent.status", "CANCELLED");
                assertThat(response).containsEntry("subagent.cancelledCount", 2);
                assertThat(response).containsEntry("subagent.failureCount", 0);
                assertThat(response).doesNotContainKeys("answer", "synthesis.status");
                assertThat(taskRows(response)).allSatisfy(row -> assertThat(row)
                        .containsEntry("status", "CANCELLED")
                        .containsEntry("failureClass", "CANCELLED_NEUTRAL"));
                assertThat(metrics(response)).containsEntry("synthesisCount", 0L);
            } finally {
                releaseWorkers.countDown();
                if (coordinator.isAlive()) {
                    coordinator.interrupt();
                    coordinator.join(2_000L);
                }
            }
        }
    }

    @Test
    void traceContainsBranchProviderOutcomeAndTimingButNoRawPromptOrResponse() throws Exception {
        TraceStore.clear();
        SubagentProvider provider = provider(task -> "RAW_RESPONSE_SENTINEL");

        try (SubagentFlowRunner runner = runner(provider, 2_000L, 1_500L)) {
            runner.run("subagent.v1", Map.of(
                    "question", "RAW_PROMPT_SENTINEL",
                    "authorization", "credential-shaped-sentinel"), null);

            String trace = String.valueOf(TraceStore.get("agent.subagent.events"));
            assertThat(trace)
                    .contains("event=subagent.plan.created", "event=subagent.dispatch",
                            "event=provider.selected", "event=provider.attempt",
                            "event=subagent.result.received", "event=subagent.synthesis.completed")
                    .contains("provider=ollama", "elapsedMs=")
                    .doesNotContain("RAW_PROMPT_SENTINEL")
                    .doesNotContain("RAW_RESPONSE_SENTINEL")
                    .doesNotContain("credential-shaped-sentinel");
        }
    }

    @Test
    void disabledAvailabilityFailureEmitsAttemptWithoutCircuitOpenEvent() throws Exception {
        TraceStore.clear();
        SubagentProvider provider = new SubagentProvider() {
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
                throw new LlmGatewayException("route_disabled", LlmFailureClass.DISABLED);
            }

            @Override
            public String execute(SubagentTask task, long timeoutMs) {
                throw new AssertionError("disabled availability must not execute the provider");
            }
        };

        try (SubagentFlowRunner runner = runner(provider, 2_000L, 1_500L)) {
            runner.run("subagent.v1", Map.of("question", "availability failure"), null);

            String trace = String.valueOf(TraceStore.get("agent.subagent.events"));
            assertThat(trace)
                    .contains("event=provider.attempt", "errorClass=DISABLED",
                            "reasonCode=availability_failed_no_circuit")
                    .doesNotContain("event=provider.circuit.open");
        }
    }

    @Test
    void concurrentTasksShareOneAtomicGlmAttemptBudget() throws Exception {
        AtomicInteger glmCalls = new AtomicInteger();
        CountDownLatch glmEntered = new CountDownLatch(1);
        CountDownLatch fallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseGlm = new CountDownLatch(1);
        SubagentProvider glm = new SubagentProvider() {
            @Override
            public String id() {
                return "vercel-glm";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public boolean singleAttemptPerFlow() {
                return true;
            }

            @Override
            public Availability availability() {
                return Availability.enabled();
            }

            @Override
            public String execute(SubagentTask task, long timeoutMs) throws Exception {
                glmCalls.incrementAndGet();
                glmEntered.countDown();
                if (!releaseGlm.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("fallback_did_not_enter");
                }
                throw new LlmGatewayException("glm_unavailable", LlmFailureClass.PROVIDER_ERROR);
            }
        };
        SubagentProvider openai = new SubagentProvider() {
            @Override
            public String id() {
                return "openai";
            }

            @Override
            public int order() {
                return 20;
            }

            @Override
            public Availability availability() {
                return Availability.enabled();
            }

            @Override
            public String execute(SubagentTask task, long timeoutMs) throws Exception {
                if (!glmEntered.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("glm_did_not_enter");
                }
                fallbackEntered.countDown();
                releaseGlm.countDown();
                return "openai-" + task.role();
            }
        };
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(glm, openai), new LlmGatewayFailureClassifier());

        try (SubagentFlowRunner runner = new SubagentFlowRunner(
                new PlannerNode(), new SynthNode(), chain,
                2_000L, 1_500L, Executors.newFixedThreadPool(2))) {
            Map<String, Object> response = runner.run(
                    "subagent.v1", Map.of("question", "concurrent attempt"), null);

            assertThat(response).containsEntry("subagent.status", "COMPLETE");
            assertThat(glmCalls).hasValue(1);
            assertThat(fallbackEntered.getCount()).isZero();
            long flowLimitSkips = taskRows(response).stream()
                    .flatMap(row -> attemptRows(row).stream())
                    .filter(attempt -> "flow_attempt_limit".equals(attempt.get("reasonCode")))
                    .count();
            assertThat(flowLimitSkips).isEqualTo(1L);
        }
    }

    @Test
    void completedFuturesAreCollectedBeforeTheirExpiredDeadlineIsClassified() throws Exception {
        SubagentProvider provider = provider(task -> "ready-" + task.role());
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(provider), new LlmGatewayFailureClassifier());

        try (SubagentFlowRunner runner = new SubagentFlowRunner(
                new PlannerNode(), new SynthNode(), chain,
                100L, 1L, new DelayedReturnDirectExecutor(10L))) {
            Map<String, Object> response = runner.run(
                    "subagent.v1", Map.of("question", "already complete"), null);

            assertThat(response).containsEntry("subagent.status", "COMPLETE");
            assertThat(response).containsEntry("subagent.successCount", 2);
        }
    }

    @Test
    void completionRacingTheTimeoutCancellationIsCollectedWhenCancellationLoses() throws Exception {
        SubagentProvider provider = provider(task -> "raced-" + task.role());
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(provider), new LlmGatewayFailureClassifier());

        try (SubagentFlowRunner runner = new SubagentFlowRunner(
                new PlannerNode(), new SynthNode(), chain,
                2_000L, 100L, new CompletionRaceDirectExecutor(200L))) {
            Map<String, Object> response = runner.run(
                    "subagent.v1", Map.of("question", "completion race"), null);

            assertThat(response).containsEntry("subagent.status", "COMPLETE");
            assertThat(response).containsEntry("subagent.successCount", 2);
        }
    }

    @Test
    void reusableWorkerThreadsStartAndFinishWithAnEmptyTraceContext() throws Exception {
        AtomicInteger contaminatedStarts = new AtomicInteger();
        SubagentProvider provider = provider(task -> {
            if (TraceStore.get("worker.sentinel") != null) {
                contaminatedStarts.incrementAndGet();
            }
            TraceStore.put("worker.sentinel", "worker-only");
            return "clean-" + task.role();
        });

        try (SubagentFlowRunner runner = runner(provider, 2_000L, 1_500L)) {
            runner.run("subagent.v1", Map.of("question", "first"), null);
            runner.run("subagent.v1", Map.of("question", "second"), null);

            assertThat(contaminatedStarts).hasValue(0);
        }
    }

    @Test
    void responseExposesRequiredStructuredResultFieldsAndQuantitativeMetrics() throws Exception {
        SubagentProvider provider = provider(task -> "answer-" + task.role());

        try (SubagentFlowRunner runner = runner(provider, 2_000L, 1_500L)) {
            Map<String, Object> response = runner.run(
                    "subagent.v1", Map.of("question", "schema request"), null);

            assertThat(taskRows(response)).allSatisfy(row -> assertThat(row)
                    .containsKeys("taskId", "correlationId", "role", "provider", "status",
                            "elapsedMs", "fallbackUsed", "attemptedProviders", "errorClass",
                            "retryable", "evidence", "warnings", "blockedExternal"));
            assertThat(response).containsKey("subagent.metrics");
            assertThat(metrics(response))
                    .containsEntry("callCount", 2L)
                    .containsEntry("synthesisCount", 1L)
                    .containsEntry("synthesisSuccessCount", 1L)
                    .containsKeys("averageElapsedMs", "p95ElapsedMs", "providerMetrics");
        }
    }

    @Test
    void environmentConstructorUsesTheInjectedSharedCoreAndMetrics() throws Exception {
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(List.of(provider(task -> "shared-" + task.role())),
                        new LlmGatewayFailureClassifier()),
                metrics);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.subagent.deadline-ms", "2000")
                .withProperty("agent.subagent.task-timeout-ms", "1500");

        try (SubagentFlowRunner runner = new SubagentFlowRunner(core, environment)) {
            Map<String, Object> response = runner.run(
                    "subagent.v1", Map.of("question", "shared core"), null);

            assertThat(response).containsEntry("subagent.status", "COMPLETE");
            assertThat(core.metrics()).isSameAs(metrics);
            assertThat(metrics.snapshot().callCount()).isEqualTo(2L);
            assertThat(metrics(response)).containsEntry("callCount", 2L);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> taskRows(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("subagent.tasks");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attemptRows(Map<String, Object> taskRow) {
        return (List<Map<String, Object>>) taskRow.get("attempts");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metrics(Map<String, Object> response) {
        return (Map<String, Object>) response.get("subagent.metrics");
    }

    private static SubagentFlowRunner runner(SubagentProvider provider,
                                             long deadlineMs,
                                             long taskTimeoutMs) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(provider), new LlmGatewayFailureClassifier());
        return new SubagentFlowRunner(
                new PlannerNode(), new SynthNode(), chain,
                deadlineMs, taskTimeoutMs, executor);
    }

    private static SubagentProvider provider(Invocation invocation) {
        return new SubagentProvider() {
            private final AtomicInteger calls = new AtomicInteger();

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
            public String execute(SubagentTask task, long timeoutMs) throws Exception {
                calls.incrementAndGet();
                return invocation.call(task);
            }
        };
    }

    @FunctionalInterface
    private interface Invocation {
        String call(SubagentTask task) throws Exception;
    }

    private static class DelayedReturnDirectExecutor extends AbstractExecutorService {
        private final long delayNanos;
        private volatile boolean shutdown;

        private DelayedReturnDirectExecutor(long delayMs) {
            this.delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMs);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
            LockSupport.parkNanos(delayNanos);
        }
    }

    private static final class CompletionRaceDirectExecutor extends DelayedReturnDirectExecutor {

        private CompletionRaceDirectExecutor(long delayMs) {
            super(delayMs);
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
            return new FalseOnceDoneFuture<>(callable);
        }
    }

    private static final class FalseOnceDoneFuture<T> extends FutureTask<T> {
        private final AtomicBoolean firstDoneProbe = new AtomicBoolean(true);

        private FalseOnceDoneFuture(Callable<T> callable) {
            super(callable);
        }

        @Override
        public boolean isDone() {
            if (firstDoneProbe.compareAndSet(true, false)) {
                return false;
            }
            return super.isDone();
        }
    }
}
