package com.example.lms.llm;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TimedChatModelCallerAcceptedRunTest {
    @AfterEach void clean() {
        TimeBudgetContext.clear();
        assertTrue(TimedChatModelCaller.shutdownSharedExecutorForTest(1000));
    }

    @ParameterizedTest
    @ValueSource(ints = {12, 30, 90})
    void acceptedRunCompletesAfterLegacyOverallWaitExpires(int seconds) throws Exception {
        TimedChatModelCaller.resetSharedExecutorForTest(1, 1);
        var registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 300);
        var run = registry.beginOrJoin(1L).context();
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var timer = scheduler.schedule(release::countDown, seconds, TimeUnit.SECONDS);
        TimeBudgetContext.set(new TimeBudget(5));
        Thread.sleep(15);
        ChatModel provider = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("fixture stopped"); }
                return ChatResponse.builder().aiMessage(AiMessage.from("completed fixture")).build();
            }
        };
        try (var scope = ChatRunExecutionContext.bind(run)) {
            var result = TimedChatModelCaller.chat(provider, List.of(UserMessage.from("synthetic fixture")),
                    Duration.ofMillis(10), "chat_draft", "fixture-model");
            assertEquals("completed fixture", result.text());
            assertTrue(registry.markDone(run));
            assertEquals(ChatRunRegistry.Status.DONE, registry.describeExact(1L, run.clientToken()).orElseThrow().status());
            assertEquals(1, calls.get());
        } finally {
            release.countDown(); timer.cancel(false); scheduler.shutdownNow();
        }
    }

    @Test @Timeout(10)
    void explicitStopReachesWorkerOnceAndCannotStartAnotherAttempt() throws Exception {
        TimedChatModelCaller.resetSharedExecutorForTest(1, 1);
        var registry = registry();
        var run = registry.beginOrJoin(2L).context();
        var started = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var cancellationEvents = new AtomicInteger();
        var calls = new AtomicInteger();
        run.observeCancellation("fixture-observer", at -> cancellationEvents.incrementAndGet());
        ChatModel provider = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet(); started.countDown();
                try { new CountDownLatch(1).await(); throw new AssertionError("unreachable"); }
                catch (InterruptedException e) { throw new CancellationException("fixture stopped"); }
                finally { exited.countDown(); }
            }
        };
        var caller = Executors.newSingleThreadExecutor();
        try {
            var response = caller.submit(() -> {
                try (var scope = ChatRunExecutionContext.bind(run)) {
                    return TimedChatModelCaller.chat(provider, List.of(UserMessage.from("fixture")),
                            Duration.ofMillis(10), "chat_draft", "fixture-model");
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(registry.cancelExact(2L, run.clientToken()));
            assertFalse(registry.cancelExact(2L, run.clientToken()));
            assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertEquals(1, cancellationEvents.get());
            assertEquals(1, calls.get());
            try (var scope = ChatRunExecutionContext.bind(run)) {
                assertThrows(CancellationException.class, ChatRunExecutionContext::throwIfCancelled);
            }
        } finally { caller.shutdownNow(); }
    }

    @Test @Timeout(10)
    void acceptedTaskOutlivesIngressAndCarriesExecutionScopeToProvider() throws Exception {
        TimeBudgetContext.set(new TimeBudget(5));
        Thread.sleep(15);
        try (var task = ChatRunExecutionContext.bindAcceptedTask()) {
            ChatModel provider = new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    assertTrue(ChatRunExecutionContext.isAcceptedExecution());
                    assertNull(ChatRunExecutionContext.current());
                    try { Thread.sleep(50); }
                    catch (InterruptedException e) { throw new CancellationException("fixture stopped"); }
                    return ChatResponse.builder().aiMessage(AiMessage.from("task complete")).build();
                }
            };
            var answer = TimedChatModelCaller.chat(provider, List.of(UserMessage.from("fixture task")),
                    Duration.ofMillis(10), "task_ask", "fixture-model");
            assertEquals("task complete", answer.text());
        }
        assertFalse(ChatRunExecutionContext.isAcceptedExecution());
    }

    @Test @Timeout(10)
    void oauthHeartbeatProgressOutlivesTheOldTotalDeadline() throws Exception {
        withSseFixture(false, model -> assertEquals("ok",
                model.chat(List.of(UserMessage.from("fixture"))).aiMessage().text()));
    }

    @Test @Timeout(10)
    void oauthSilentReadStallRemainsAClassifiedTransportFailure() throws Exception {
        withSseFixture(true, model -> {
            var error = assertThrows(com.example.lms.llm.gateway.LlmResponseTerminalException.class,
                    () -> model.chat(List.of(UserMessage.from("fixture"))));
            assertEquals("chatgpt_oauth_read_stall", error.reasonCode());
        });
    }

    private static ChatRunRegistry registry() {
        var registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 300);
        return registry;
    }

    private static void withSseFixture(boolean stall,
            java.util.function.Consumer<ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel> assertion) throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var serverWorkers = Executors.newSingleThreadExecutor();
        server.setExecutor(serverWorkers);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                for (int i = 0; i < 30; i++) {
                    exchange.getResponseBody().write(": fixture heartbeat\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    Thread.sleep(stall ? 2000 : 80);
                }
                String completed = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_fixture\",\"status\":\"completed\",\"model\":\"fixture-gpt\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}}\n\n";
                exchange.getResponseBody().write(completed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var run = registry().beginOrJoin(3L).context();
        try (var scope = ChatRunExecutionContext.bind(run)) {
            var model = new ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture-gpt", 1000,
                    () -> "synthetic-oauth-fixture");
            assertion.accept(model);
        } finally { server.stop(0); serverWorkers.shutdownNow(); }
    }
}
