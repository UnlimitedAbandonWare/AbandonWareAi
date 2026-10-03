package com.example.lms.llm;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SelectedModelWarmupWaitTest {
    @AfterEach void cleanup() {
        TimeBudgetContext.clear();
        TraceStore.clear();
        TimedChatModelCaller.shutdownSharedExecutorForTest(1000);
    }

    private AiMessage call(ChatModel model, long timeout, CompletableFuture<Boolean> load) throws Exception {
        var method = TimedChatModelCaller.class.getMethod("chat", ChatModel.class, List.class,
                Duration.class, String.class, String.class, ChatUsageLedger.ModelAttempt.class,
                CompletableFuture.class);
        try {
            return (AiMessage) method.invoke(null, model, List.of(UserMessage.from("synthetic")),
                    Duration.ofMillis(timeout), "chat_draft", "qwen3.5:9b", null, load);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            throw wrapped;
        }
    }

    @Test void coldLoadCanExceedGenerationTimeoutWithinOriginalRequestBudget() throws Exception {
        TimeBudgetContext.set(new TimeBudget(1000));
        CompletableFuture<Boolean> load = new CompletableFuture<>();
        Thread loader = new Thread(() -> {
            try { Thread.sleep(150); load.complete(true); }
            catch (InterruptedException failure) { load.completeExceptionally(failure); }
        });
        loader.start();
        try {
            assertEquals("ok", call(model(0, new AtomicInteger()), 50, load).text());
        } finally { loader.join(1000); }
    }

    @Test void requestBudgetStopsWaitingWithoutCancellingLoadOrStartingGeneration() {
        TimeBudgetContext.set(new TimeBudget(50));
        CompletableFuture<Boolean> load = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        LlmGatewayException failure = assertThrows(LlmGatewayException.class,
                () -> call(model(0, calls), 12, load));
        assertEquals("model_loading_budget_exhausted", failure.reasonCode());
        assertEquals(0, calls.get());
        assertFalse(load.isCancelled());
        assertFalse(load.isDone());
        assertTrue(load.complete(true));
        assertEquals("model_loading_budget_exhausted", TraceStore.get("llm.call.terminalReason"));
    }

    @Test void warmModelKeepsTheConfiguredGenerationTimeout() {
        TimeBudgetContext.set(new TimeBudget(1000));
        assertThrows(java.util.concurrent.TimeoutException.class,
                () -> call(model(150, new AtomicInteger()), 30, CompletableFuture.completedFuture(true)));
    }

    @Test void interruptedLoadWaitCancelsOnlyCallerAndRestoresInterrupt() {
        CompletableFuture<Boolean> load = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class, () -> call(model(0, calls), 1000, load));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(load.isCancelled());
            assertFalse(load.isDone());
            assertEquals(0, calls.get());
        } finally { Thread.interrupted(); }
    }

    @Test void exactRunCancellationReleasesLoadWaitWithoutCancellingSharedLoad() throws Exception {
        var registry = new com.example.lms.service.chat.ChatRunRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        org.springframework.test.util.ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
        var run = registry.beginOrJoin(72901L).context();
        CompletableFuture<Boolean> load = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var started = new java.util.concurrent.CountDownLatch(1);
        Thread caller = new Thread(() -> {
            try (var binding = com.example.lms.service.chat.ChatRunExecutionContext.bind(run)) {
                started.countDown();
                call(model(0, calls), 5000, load);
            } catch (Throwable cancelled) { failure.set(cancelled); }
            finally { Thread.interrupted(); }
        });
        try {
            caller.start();
            assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(new com.example.lms.api.ChatCancellationCommandHandler()
                    .cancel(72901L, run.clientToken(), () -> true, registry, () -> {}).cancelled());
            caller.join(1000);
            assertFalse(caller.isAlive(), "exact cancellation must release the caller before the load deadline");
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure.get());
            assertEquals(0, calls.get());
            assertFalse(load.isCancelled());
            assertFalse(load.isDone());
        } finally {
            caller.interrupt(); caller.join(2000);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry, "shutdown");
        }
    }

    private ChatModel model(long wait, AtomicInteger calls) {
        return new ChatModel() {
            @Override public ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
                calls.incrementAndGet();
                try { Thread.sleep(wait); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            }
        };
    }
}
