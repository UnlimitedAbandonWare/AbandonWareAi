package com.example.lms.llm;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimedChatModelCallerTest {

    @BeforeEach
    void clearTraceBefore() {
        TraceStore.clear();
        TimeBudgetContext.clear();
        TimedChatModelCaller.resetSharedExecutorForTest(1, 1);
    }

    @AfterEach
    void clearTraceAfter() {
        TimeBudgetContext.clear();
        TraceStore.clear();
        assertTrue(TimedChatModelCaller.shutdownSharedExecutorForTest(1_000L),
                "the shared TimedChatModelCaller executor must terminate after released fixtures");
    }

    @Test
    void returnsAiMessageWhenModelRespondsWithinTimeout() throws Exception {
        AiMessage message = TimedChatModelCaller.chat(
                new StaticModel("ok"),
                List.of(UserMessage.from("hello")),
                Duration.ofSeconds(1),
                "chat_draft",
                "gemma4:26b");

        assertEquals("ok", message.text());
        assertNull(TraceStore.get("llm.call.timeout"));
    }

    @Test
    void sharedWorkerDoesNotInheritCallerContext() throws Exception {
        InheritableThreadLocal<String> privateCallerContext = new InheritableThreadLocal<>();
        AtomicReference<String> observed = new AtomicReference<>("not-called");
        privateCallerContext.set("private-caller-context");
        try {
            AiMessage message = TimedChatModelCaller.chat(
                    new ContextObservingModel(privateCallerContext, observed),
                    List.of(UserMessage.from("context isolation probe")),
                    Duration.ofSeconds(1),
                    "chat_draft",
                    "fixture-model");
            assertEquals("clean-worker", message.text());
        } finally {
            privateCallerContext.remove();
        }

        assertEquals(null, observed.get(), "model worker must not inherit caller context");
    }

    @Test
    void blankAiMessageIsClassifiedAsFailureAndTracedWithoutPromptLeak() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> TimedChatModelCaller.chat(
                new StaticModel("   "),
                List.of(UserMessage.from("private prompt that must not enter trace")),
                Duration.ofSeconds(1),
                "chat_draft",
                "qwen3:8b"));

        assertEquals("LLM blank response", failure.getMessage());
        assertEquals(Boolean.TRUE, TraceStore.get("llm.call.blank"));
        assertEquals("chat_draft", TraceStore.get("llm.call.blank.stage"));
        assertTrue(String.valueOf(TraceStore.get("llm.call.blank.modelHash")).startsWith("hash:"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("private prompt"), trace);
        assertFalse(trace.contains("qwen3:8b"), trace);
    }

    @Test
    void timeoutSoftCancelsProviderAndNextTaskReusesCleanWorkerContext() throws Exception {
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        AtomicReference<Thread> interruptedThread = new AtomicReference<>();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicReference<Map<String, Object>> timeoutTrace = new AtomicReference<>();
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        Future<AiMessage> call = callerExecutor.submit(() -> {
            callerThread.set(Thread.currentThread());
            try {
                TraceStore.put("private.previous.marker", "must-not-leak");
                return TimedChatModelCaller.chat(
                        new LatchBlockingModel(
                                workerEntered,
                                releaseWorker,
                                workerInterrupted,
                                workerThread,
                                interruptedThread),
                        List.of(UserMessage.from("raw-secret prompt that must not enter trace")),
                        Duration.ofSeconds(2),
                        "chat_draft",
                        "private-model-id:gemma4");
            } finally {
                timeoutTrace.set(TraceStore.getAll());
                TraceStore.clear();
            }
        });

        try {
            assertTrue(workerEntered.await(1, TimeUnit.SECONDS),
                    "the call-owned worker must start before the timeout deadline");
            ExecutionException callFailure = assertThrows(
                    ExecutionException.class,
                    () -> call.get(3, TimeUnit.SECONDS));
            assertTrue(callFailure.getCause() instanceof TimeoutException, String.valueOf(callFailure.getCause()));
            TimeoutException failure = (TimeoutException) callFailure.getCause();

            assertEquals("LLM call timed out", failure.getMessage());
            assertFalse(workerInterrupted.await(100, TimeUnit.MILLISECONDS),
                    "CancelShield must not interrupt a pooled provider worker");
            assertNull(interruptedThread.get());
            assertNotSame(callerThread.get(), workerThread.get());
            assertTrue(workerThread.get().getName().startsWith("awx-llm-hard-timeout-"));
            assertFalse(callerThread.get().isInterrupted(), "caller thread must remain unmodified");

            Map<String, Object> traceValues = timeoutTrace.get();
            assertNotNull(traceValues);
            assertEquals(Boolean.TRUE, traceValues.get("llm.call.timeout"));
            assertEquals("chat_draft", traceValues.get("llm.call.timeout.stage"));
            assertEquals(2_000L, ((Number) traceValues.get("llm.call.timeout.ms")).longValue());
            assertEquals(Boolean.FALSE, traceValues.get("llm.call.timeout.cancelInterrupt"));
            assertEquals(Boolean.FALSE, traceValues.get("llm.call.timeout.cancelMayInterruptIfRunning"));
            assertEquals(Boolean.TRUE, traceValues.get("llm.call.timeout.cancelRequestedInterrupt"));
            assertEquals(Boolean.TRUE, traceValues.get("llm.call.timeout.cancelAccepted"));
            assertEquals(Boolean.TRUE, traceValues.get("llm.call.timeout.futureCancelledState"));
            assertEquals("not_observed", traceValues.get("llm.call.timeout.workerTerminationEvidence"));
            assertEquals("provider_timeout", traceValues.get("llm.call.terminalReason"));
            assertTrue(String.valueOf(traceValues.get("llm.call.timeout.modelHash")).startsWith("hash:"));

            String trace = String.valueOf(traceValues);
            assertFalse(trace.contains("raw-secret prompt"), trace);
            assertFalse(trace.contains("private-model-id:gemma4"), trace);

            releaseWorker.countDown();
            assertTrue(TimedChatModelCaller.awaitSharedExecutorQuiescenceForTest(1_000L));
            AtomicBoolean nextInterrupted = new AtomicBoolean(true);
            AtomicReference<Object> nextBudget = new AtomicReference<>("unset");
            AtomicReference<Object> leakedTrace = new AtomicReference<>("unset");
            AiMessage next = callerExecutor.submit(() -> TimedChatModelCaller.chat(
                            new CleanStateModel(nextInterrupted, nextBudget, leakedTrace),
                            List.of(UserMessage.from("next")),
                            Duration.ofSeconds(1),
                            "chat_draft",
                            "next-model"))
                    .get(2, TimeUnit.SECONDS);
            assertEquals("next-call-ok", next.text());
            assertFalse(nextInterrupted.get(), "a timed-out request must not poison the reused worker");
            assertNull(nextBudget.get(), "request TimeBudget must be cleared before worker reuse");
            assertNull(leakedTrace.get(), "prior request trace markers must not leak to worker reuse");
        } finally {
            releaseWorker.countDown();
            call.cancel(true);
            callerExecutor.shutdownNow();
            callerExecutor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancelAcceptedAndFutureCancelledDoNotImplyInterruptIgnoringWorkerExit() throws Exception {
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch workerExited = new CountDownLatch(1);
        AtomicReference<Map<String, Object>> timeoutTrace = new AtomicReference<>();
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        Future<AiMessage> call = callerExecutor.submit(() -> {
            try {
                return TimedChatModelCaller.chat(
                        new InterruptIgnoringModel(
                                workerEntered,
                                releaseWorker,
                                workerInterrupted,
                                workerExited),
                        List.of(UserMessage.from("private interrupt probe prompt")),
                        Duration.ofSeconds(2),
                        "chat_draft",
                        "private-interrupt-probe-model");
            } finally {
                timeoutTrace.set(TraceStore.getAll());
                TraceStore.clear();
            }
        });

        try {
            assertTrue(workerEntered.await(1, TimeUnit.SECONDS));
            ExecutionException callFailure = assertThrows(
                    ExecutionException.class,
                    () -> call.get(3, TimeUnit.SECONDS));
            assertTrue(callFailure.getCause() instanceof TimeoutException, String.valueOf(callFailure.getCause()));
            TimeoutException failure = (TimeoutException) callFailure.getCause();

            assertEquals("LLM call timed out", failure.getMessage());
            assertFalse(workerInterrupted.await(100, TimeUnit.MILLISECONDS));
            assertEquals(1L, workerExited.getCount(),
                    "Future cancellation must not be reported as worker termination");
            Map<String, Object> traceValues = timeoutTrace.get();
            assertNotNull(traceValues);
            assertEquals(Boolean.TRUE, traceValues.get("llm.call.timeout.cancelAccepted"));
            assertEquals(Boolean.TRUE, traceValues.get("llm.call.timeout.futureCancelledState"));
            assertEquals("not_observed", traceValues.get("llm.call.timeout.workerTerminationEvidence"));

            String trace = String.valueOf(traceValues);
            assertFalse(trace.contains("private interrupt probe prompt"), trace);
            assertFalse(trace.contains("private-interrupt-probe-model"), trace);
        } finally {
            releaseWorker.countDown();
            boolean exited;
            try {
                exited = workerExited.await(1, TimeUnit.SECONDS);
            } finally {
                call.cancel(true);
                callerExecutor.shutdownNow();
                callerExecutor.awaitTermination(1, TimeUnit.SECONDS);
            }
            assertTrue(exited);
        }
    }

    @Test
    void requestDeadlineWinsOverLongerProviderTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        TimeBudget budget = new TimeBudget(150L);
        TimeBudgetContext.set(budget);
        try {
            assertThrows(TimeoutException.class, () -> TimedChatModelCaller.chat(
                    new InterruptIgnoringModel(entered, release, interrupted, new CountDownLatch(1)),
                    List.of(UserMessage.from("private deadline prompt")),
                    Duration.ofSeconds(5),
                    "chat_draft",
                    "private-deadline-model"));
            assertEquals("request_deadline_exhausted", TraceStore.get("llm.call.terminalReason"));
            assertFalse(interrupted.await(100, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            TimeBudgetContext.clear();
        }
    }

    @Test
    void boundedPoolRejectsThirdCallWithoutGrowingThreadsOrQueue() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        Future<?> first = callers.submit(() -> blockingCall(firstEntered, release));
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
        Future<?> second = callers.submit(() -> blockingCall(new CountDownLatch(1), release));
        assertTrue(awaitExecutorMetric("queued", 1, 1_000L));

        long started = System.nanoTime();
        RejectedExecutionException saturated = assertThrows(RejectedExecutionException.class,
                () -> TimedChatModelCaller.chat(
                        new StaticModel("must-not-run"),
                        List.of(UserMessage.from("third")),
                        Duration.ofSeconds(5),
                        "chat_draft",
                        "third-model"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        try {
            assertEquals("executor_saturated", saturated.getMessage());
            assertTrue(elapsedMs < 500L, "saturation must fail fast");
            Map<String, Integer> metrics = TimedChatModelCaller.sharedExecutorMetricsForTest();
            assertTrue(metrics.get("poolSize") <= 1, String.valueOf(metrics));
            assertTrue(metrics.get("queued") <= 1, String.valueOf(metrics));
            assertEquals("executor_saturated", TraceStore.get("llm.call.terminalReason"));
        } finally {
            release.countDown();
            first.cancel(true);
            second.cancel(true);
            callers.shutdownNow();
            callers.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void fourInterruptIgnoringCallsStayBoundedAndCooperativeWorkRunsAfterRelease() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        Future<?> first = callers.submit(() -> blockingCall(firstEntered, release));
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
        Future<?> second = callers.submit(() -> blockingCall(secondEntered, release));
        assertTrue(awaitExecutorMetric("queued", 1, 1_000L));

        try {
            for (int request = 3; request <= 4; request++) {
                String suffix = String.valueOf(request);
                RejectedExecutionException saturated = assertThrows(
                        RejectedExecutionException.class,
                        () -> TimedChatModelCaller.chat(
                                new InterruptIgnoringModel(
                                        new CountDownLatch(1),
                                        release,
                                        new CountDownLatch(1),
                                        new CountDownLatch(1)),
                                List.of(UserMessage.from("bounded-" + suffix)),
                                Duration.ofSeconds(5),
                                "chat_draft",
                                "bounded-model-" + suffix));
                assertEquals("executor_saturated", saturated.getMessage());
                Map<String, Integer> metrics = TimedChatModelCaller.sharedExecutorMetricsForTest();
                assertTrue(metrics.get("poolSize") <= 1, String.valueOf(metrics));
                assertTrue(metrics.get("queued") <= 1, String.valueOf(metrics));
            }

            release.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            assertTrue(TimedChatModelCaller.awaitSharedExecutorQuiescenceForTest(1_000L));

            AiMessage sentinel = TimedChatModelCaller.chat(
                    new StaticModel("cooperative-sentinel"),
                    List.of(UserMessage.from("sentinel")),
                    Duration.ofSeconds(1),
                    "chat_draft",
                    "sentinel-model");
            assertEquals("cooperative-sentinel", sentinel.text());
            Map<String, Integer> recovered = TimedChatModelCaller.sharedExecutorMetricsForTest();
            assertTrue(recovered.get("poolSize") <= 1, String.valueOf(recovered));
            assertEquals(0, recovered.get("queued"));
        } finally {
            release.countDown();
            first.cancel(true);
            second.cancel(true);
            callers.shutdownNow();
            callers.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void interruptedCallerKeepsInterruptAndDoesNotInterruptProviderWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean callerInterruptPreserved = new AtomicBoolean();
        AtomicReference<Map<String, Object>> terminalTrace = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                TimedChatModelCaller.chat(
                        new InterruptIgnoringModel(entered, release, workerInterrupted, new CountDownLatch(1)),
                        List.of(UserMessage.from("private caller-cancel prompt")),
                        Duration.ofSeconds(5),
                        "chat_draft",
                        "private-caller-cancel-model");
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                callerInterruptPreserved.set(Thread.currentThread().isInterrupted());
                terminalTrace.set(TraceStore.getAll());
            }
        }, "timed-caller-cancel-test");
        caller.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(1_000L);

        try {
            assertFalse(caller.isAlive(), "caller cancellation must exit promptly");
            assertTrue(failure.get() instanceof java.util.concurrent.CancellationException,
                    String.valueOf(failure.get()));
            assertTrue(callerInterruptPreserved.get());
            assertFalse(workerInterrupted.await(100, TimeUnit.MILLISECONDS));
            assertEquals("caller_cancelled", terminalTrace.get().get("llm.call.terminalReason"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void providerCancellationIsNotClassifiedAsHardFailure() {
        java.util.concurrent.CancellationException cancelled = assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> TimedChatModelCaller.chat(
                        new ProviderCancellingModel(),
                        List.of(UserMessage.from("private provider cancellation prompt")),
                        Duration.ofSeconds(1),
                        "chat_draft",
                        "private-provider-cancel-model"));

        assertEquals("provider stop", cancelled.getMessage());
        assertEquals("provider_cancelled", TraceStore.get("llm.call.terminalReason"));
        assertFalse("provider_hard_failure".equals(TraceStore.get("llm.call.terminalReason")));
    }

    @Test
    void timedOutQueuedCallIsPurgedBeforeTheNextAdmission() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        Future<?> active = callers.submit(() -> blockingCall(entered, release));
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        try {
            assertThrows(TimeoutException.class, () -> TimedChatModelCaller.chat(
                    new StaticModel("queued-never-runs"),
                    List.of(UserMessage.from("private queued timeout prompt")),
                    Duration.ofMillis(80),
                    "chat_draft",
                    "queued-timeout-model"));

            assertEquals(0, TimedChatModelCaller.sharedExecutorMetricsForTest().get("queued"),
                    "a cancelled queued FutureTask must not retain bounded queue capacity");

            Future<Throwable> next = callers.submit(() -> {
                try {
                    TimedChatModelCaller.chat(
                            new InterruptIgnoringModel(new CountDownLatch(0), release,
                                    new CountDownLatch(1), exited),
                            List.of(UserMessage.from("private next admission prompt")),
                            Duration.ofMillis(80),
                            "chat_draft",
                            "next-admission-model");
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            Throwable failure = next.get(1, TimeUnit.SECONDS);
            assertTrue(failure instanceof TimeoutException, String.valueOf(failure));
            assertFalse("executor_saturated".equals(failure.getMessage()));
            assertEquals(0, TimedChatModelCaller.sharedExecutorMetricsForTest().get("queued"));
        } finally {
            release.countDown();
            active.get(1, TimeUnit.SECONDS);
            callers.shutdownNow();
        }
    }

    private static void blockingCall(CountDownLatch entered, CountDownLatch release) {
        try {
            TimedChatModelCaller.chat(
                    new InterruptIgnoringModel(entered, release, new CountDownLatch(1), new CountDownLatch(1)),
                    List.of(UserMessage.from("blocking")),
                    Duration.ofSeconds(5),
                    "chat_draft",
                    "blocking-model");
        } catch (Exception ignored) {
            // The caller Future is cancelled during fixture cleanup.
        }
    }

    private static boolean awaitExecutorMetric(String key, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            Integer value = TimedChatModelCaller.sharedExecutorMetricsForTest().get(key);
            if (value != null && value >= expected) return true;
            Thread.sleep(10L);
        }
        return false;
    }

    @Test
    void workerBreadcrumbsRemainInRequestTraceWhenProviderTimesOut() {
        assertThrows(TimeoutException.class, () -> TimedChatModelCaller.chat(
                new TracingSlowModel(),
                List.of(UserMessage.from("private worker prompt must stay out of trace")),
                Duration.ofMillis(500),
                "chat_draft",
                "qwen3:8b"));

        assertEquals("runner_terminated_cpu_probe",
                TraceStore.get("llm.localSmoke.operatorAction.upstreamFailureClass"));
        assertEquals("inspect_ollama_runtime_capacity",
                TraceStore.get("llm.localSmoke.operatorAction.upstreamNextAction"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("private worker prompt"), trace);
        assertFalse(trace.contains("qwen3:8b"), trace);
    }

    private record StaticModel(String text) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }

    private record LatchBlockingModel(
            CountDownLatch entered,
            CountDownLatch release,
            CountDownLatch interrupted,
            AtomicReference<Thread> workerThread,
            AtomicReference<Thread> interruptedThread) implements ChatModel {

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            Thread current = Thread.currentThread();
            workerThread.set(current);
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException expected) {
                interruptedThread.set(current);
                interrupted.countDown();
                current.interrupt();
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("late"))
                    .build();
        }
    }

    private record ContextObservingModel(
            InheritableThreadLocal<String> context,
            AtomicReference<String> observed) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            observed.set(context.get());
            return ChatResponse.builder().aiMessage(AiMessage.from("clean-worker")).build();
        }
    }

    private static final class ProviderCancellingModel implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            throw new java.util.concurrent.CancellationException("provider stop");
        }
    }

    private record CleanStateModel(
            AtomicBoolean interrupted,
            AtomicReference<Object> budget,
            AtomicReference<Object> leakedTrace) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            interrupted.set(Thread.currentThread().isInterrupted());
            budget.set(TimeBudgetContext.get());
            leakedTrace.set(TraceStore.get("private.previous.marker"));
            return ChatResponse.builder().aiMessage(AiMessage.from("next-call-ok")).build();
        }
    }

    private record InterruptIgnoringModel(
            CountDownLatch entered,
            CountDownLatch release,
            CountDownLatch interrupted,
            CountDownLatch exited) implements ChatModel {

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            entered.countDown();
            try {
                while (release.getCount() > 0L) {
                    try {
                        release.await();
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                    }
                }
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("late"))
                        .build();
            } finally {
                exited.countDown();
            }
        }
    }

    private static final class TracingSlowModel implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            TraceStore.put("llm.localSmoke.operatorAction.upstreamFailureClass",
                    "runner_terminated_cpu_probe");
            TraceStore.put("llm.localSmoke.operatorAction.upstreamNextAction",
                    "inspect_ollama_runtime_capacity");
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("late"))
                    .build();
        }
    }
}
