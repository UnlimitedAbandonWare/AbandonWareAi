package com.example.lms.llm.gateway;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestTotalInferenceBudgetTest {
    private final ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
    private final String timeline = tracker.beginRequestTimeline("synthetic-total-bound", "synthetic-session");
    @BeforeEach void bind() { TraceStore.put(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timeline); }
    @AfterEach void clear() { TraceStore.clear(); TimeBudgetContext.clear(); }
    private ChatResponse answer() { return ChatResponse.builder().aiMessage(AiMessage.from("grounded [doc-7]")).build(); }
    private RuntimeException failure() { return new LlmGatewayException("synthetic timeout", LlmFailureClass.TIMEOUT_SOFT); }
    private ChatModel tracked(Function<List<ChatMessage>, ChatResponse> fn) {
        return tracker.decorateRequestAttempt(new ChatModel() {
            @Override public ChatResponse chat(List<ChatMessage> messages) { return fn.apply(messages); }
        }, "primary", tracker.redactedRequestAttemptRoute("synthetic-route", "synthetic-model", "127.0.0.1", "openai_chat_completions"), Map.of("maxRetries", 0));
    }
    private FallbackAwareChatModel chain(ChatModel primary, ChatModel first, ChatModel second) {
        return new FallbackAwareChatModel(primary, (failure, used) ->
            new FallbackAwareChatModel.ResolvedFallback(used.contains("api-a") ? second : first,
                used.contains("api-a") ? "api-b" : "api-a", null),
            null, null, "local", tracker, timeline, null, 2);
    }
    @Test void expansionCannotRestartTheFourAttemptBudgetOrDeadline() {
        var calls = new AtomicInteger();
        List<List<ChatMessage>> seen = new ArrayList<>();
        TimeBudget budget = mock(TimeBudget.class);
        when(budget.remainingMillis()).thenReturn(10_000L);
        TimeBudgetContext.set(budget);
        ChatModel failing = tracked(messages -> {
            assertSame(budget, TimeBudgetContext.get()); seen.add(messages); calls.incrementAndGet(); throw failure();
        });
        ChatModel good = tracked(messages -> {
            assertSame(budget, TimeBudgetContext.get()); seen.add(messages); calls.incrementAndGet(); return answer();
        });
        var model = chain(failing, failing, good);
        var input = List.<ChatMessage>of(SystemMessage.from("source [doc-7]"), UserMessage.from("synthetic follow-up"));
        assertEquals("grounded [doc-7]", model.chat(input).aiMessage().text());
        assertEquals(3, calls.get());
        RuntimeException exhausted = assertThrows(RuntimeException.class, () -> model.chat(input));
        assertEquals(4, calls.get());
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(exhausted));
        assertTrue(seen.stream().allMatch(input::equals));
        assertSame(budget, TimeBudgetContext.get());
    }
    @Test void concurrentLogicalCallsShareOneAtomicReservation() throws Exception {
        var calls = new AtomicInteger();
        var start = new CountDownLatch(1);
        ChatModel good = tracked(messages -> { calls.incrementAndGet(); return answer(); });
        var model = chain(good, good, good);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i=0; i<8; i++) results.add(pool.submit(() -> {
                TraceStore.put(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY, timeline);
                try {
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    model.chat(List.of(UserMessage.from("synthetic")));
                    return true;
                } catch (LlmGatewayException expected) { return false; }
                finally { TraceStore.clear(); }
            }));
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) if (result.get(5, TimeUnit.SECONDS)) successes++;
            assertEquals(4, successes); assertEquals(4, calls.get());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
    }
    @Test void sdkHttpRetriesConsumeTheSameBudgetWithoutDoubleCountingTheAdapter() {
        var calls = new AtomicInteger();
        ChatModel retrying = tracked(messages -> {
            for (int i=0; i<5; i++) {
                try (var attempt = tracker.beginClientAttempt("primary")) {
                    attempt.started("http_client_execute");
                    calls.incrementAndGet();
                    attempt.finished(null);
                }
            }
            return answer();
        });
        RuntimeException exhausted = assertThrows(RuntimeException.class,
            () -> chain(retrying, retrying, retrying).chat(List.of(UserMessage.from("synthetic"))));
        assertEquals(4, calls.get());
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(exhausted));
    }
    @Test void openLocalPreselectionDoesNotConsumeTheLastApiAttempt() {
        var physical = new AtomicInteger();
        var localCalls = new AtomicInteger();
        ChatModel local = tracked(messages -> {
            if (localCalls.getAndIncrement() > 0)
                throw new LlmGatewayException("Local circuit open", LlmFailureClass.GPU_DEVICE_LOST, "local_endpoint_open");
            physical.incrementAndGet(); throw failure();
        });
        ChatModel first = tracked(messages -> { physical.incrementAndGet(); throw failure(); });
        ChatModel second = tracked(messages -> { physical.incrementAndGet(); return answer(); });
        var model = chain(local, first, second);
        assertEquals("grounded [doc-7]", model.chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());
        assertThrows(RuntimeException.class, () -> model.chat(List.of(UserMessage.from("synthetic"))));
        assertEquals(4, physical.get());
    }
    @Test void localOpenReasonAfterAClientStartCannotRefundRealWork() {
        var physical = new AtomicInteger();
        ChatModel forged = tracked(messages -> {
            try (var attempt = tracker.beginClientAttempt("primary")) {
                attempt.started("http_client_execute"); physical.incrementAndGet(); attempt.finished(null);
            }
            throw new LlmGatewayException("synthetic reason", LlmFailureClass.GPU_DEVICE_LOST, "local_endpoint_open");
        });
        var model = chain(forged, forged, forged);
        for (int i = 0; i < 2; i++)
            assertThrows(RuntimeException.class, () -> model.chat(List.of(UserMessage.from("synthetic"))));
        assertEquals(4, physical.get());
    }
    @Test void ordinaryModelsWithoutFailoverKeepTheirExistingPolicy() {
        var calls = new AtomicInteger();
        ChatModel ordinary = tracked(messages -> { calls.incrementAndGet(); return answer(); });
        for (int i=0; i<6; i++) ordinary.chat(List.of(UserMessage.from("synthetic")));
        assertEquals(6, calls.get());
    }
}
