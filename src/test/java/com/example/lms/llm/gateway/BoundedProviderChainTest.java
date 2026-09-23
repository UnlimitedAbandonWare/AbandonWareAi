package com.example.lms.llm.gateway;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BoundedProviderChainTest {
    @AfterEach void clear() { TimeBudgetContext.clear(); TraceStore.clear(); }

    @Test
    void revokedFirstApiCredentialUsesNextAuthorizedRouteWithoutRetryingIt() {
        var calls=new AtomicInteger();var chain=chain(model(messages->{throw failure();}),(failure,used)->used.contains("api-a")?
            route(model(messages->{calls.incrementAndGet();return answer("next [doc-7]");}),"api-b"):
            route(model(messages->{calls.incrementAndGet();throw new LlmGatewayException("synthetic denied",LlmFailureClass.AUTH_MISSING);}),"api-a"),2);
        assertEquals("next [doc-7]",chain.chat(List.of(UserMessage.from("synthetic"))).aiMessage().text());assertEquals(2,calls.get());
    }

    @Test
    void blankCloudResponseContinuesWithTheSameEvidence() {
        var calls=new AtomicInteger();
        var chain=chain(model(messages->{calls.incrementAndGet();throw failure();}), (failure,used)->
                used.contains("api-a")?route(model(messages->{calls.incrementAndGet();return answer("valid [doc-7]");}),"api-b"):
                    route(model(messages->{calls.incrementAndGet();return null;}),"api-a"),2);
        assertEquals("valid [doc-7]",chain.chat(List.of(UserMessage.from("synthetic [doc-7]"))).aiMessage().text());
        assertEquals(3,calls.get());
    }

    @Test
    void expiredBudgetStartsNoPrimaryOrFallback() {
        var budget=mock(TimeBudget.class);when(budget.remainingMillis()).thenReturn(0L);TimeBudgetContext.set(budget);
        var calls=new AtomicInteger();var chain=chain(model(messages->{calls.incrementAndGet();return answer("late");}),
                (failure,used)->{calls.incrementAndGet();return null;},2);
        assertThrows(RuntimeException.class,()->chain.chat(List.of(UserMessage.from("synthetic"))));assertEquals(0,calls.get());
    }

    @Test
    void bothCloudsReceiveOneImmutableConversationAndTheOriginalSourceIds() {
        List<ChatMessage> input = new ArrayList<>(List.of(SystemMessage.from("Use [doc-7] and [web-2] only."),
                UserMessage.from("earlier question"), AiMessage.from("earlier answer [doc-7]"),
                UserMessage.from("synthetic follow-up")));
        List<List<ChatMessage>> observed = new ArrayList<>();
        ChatModel local = model(messages -> { observed.add(messages); input.clear(); throw failure(); });
        ChatModel first = model(messages -> { observed.add(messages); throw failure(); });
        ChatModel second = model(messages -> { observed.add(messages); return answer("grounded [doc-7] [web-2]"); });
        var chain = chain(local, (failure, used) -> used.contains("api-a")
                ? route(second, "api-b") : route(first, "api-a"), 2);
        assertEquals("grounded [doc-7] [web-2]", chain.chat(input).aiMessage().text());
        assertEquals(3, observed.size());
        assertSame(observed.get(0), observed.get(1));
        assertSame(observed.get(1), observed.get(2));
        assertEquals(4, observed.get(2).size());
        assertEquals("Use [doc-7] and [web-2] only.", ((SystemMessage) observed.get(2).get(0)).text());
        assertThrows(UnsupportedOperationException.class, () -> observed.get(2).clear());
    }

    @Test
    void repeatedRouteIsNeverCalledTwiceAndExhaustionPreventsOuterReplay() {
        AtomicInteger calls = new AtomicInteger();
        var chain = chain(model(messages -> { calls.incrementAndGet(); throw failure(); }),
                (failure, used) -> route(model(messages -> { calls.incrementAndGet(); throw failure(); }), "api-a"), 3);
        RuntimeException result = assertThrows(RuntimeException.class,
                () -> chain.chat(List.of(UserMessage.from("synthetic"))));
        assertEquals(2, calls.get());
        assertTrue(LlmGatewayFailureClassifier.hasNonReplayableReason(result));
    }

    @Test
    void anUnboundedResolverStillCannotCreateAFifthCall() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger next = new AtomicInteger();
        ChatModel failing = model(messages -> { calls.incrementAndGet(); throw failure(); });
        var chain = chain(failing, (failure, used) -> route(failing, "api-" + next.incrementAndGet()), 100);
        assertThrows(RuntimeException.class, () -> chain.chat(List.of(UserMessage.from("synthetic"))));
        assertEquals(4, calls.get());
    }

    @Test
    void deadlineExpiringAtFirstCloudFailurePreventsSecondCloudResolution() {
        TimeBudget budget = mock(TimeBudget.class);
        when(budget.remainingMillis()).thenReturn(1_000L);
        TimeBudgetContext.set(budget);
        AtomicInteger resolutions = new AtomicInteger();
        var chain = chain(model(messages -> { throw failure(); }), (failure, used) -> {
            resolutions.incrementAndGet();
            return route(model(messages -> { when(budget.remainingMillis()).thenReturn(0L); throw failure(); }), "api-a");
        }, 2);
        assertThrows(RuntimeException.class, () -> chain.chat(List.of(UserMessage.from("synthetic"))));
        assertEquals(1, resolutions.get());
    }

    @Test
    void cancellationOrExternalSideEffectOnCloudStopsAllSuccessors() {
        for (RuntimeException terminal : List.of(new CancellationException(),
                new LlmGatewayException("safe category", LlmFailureClass.TIMEOUT_SOFT, "tool_side_effect_started"),
                new LlmGatewayException("safe category", LlmFailureClass.STREAM_ERROR, "stream_error_after_partial"))) {
            AtomicInteger resolutions = new AtomicInteger();
            var chain = chain(model(messages -> { throw failure(); }), (failure, used) -> {
                resolutions.incrementAndGet();
                return route(model(messages -> { throw terminal; }), "api-a");
            }, 2);
            assertSame(terminal, assertThrows(RuntimeException.class,
                    () -> chain.chat(List.of(UserMessage.from("synthetic")))));
            assertEquals(1, resolutions.get());
        }
    }

    private static FallbackAwareChatModel chain(ChatModel primary,
            FallbackAwareChatModel.NextFallbackResolver resolver, int limit) {
        return new FallbackAwareChatModel(primary, resolver, null, null, "local", null, null, null, limit);
    }
    private static FallbackAwareChatModel.ResolvedFallback route(ChatModel model, String key) {
        return new FallbackAwareChatModel.ResolvedFallback(model, key, null);
    }
    private static ChatModel model(Function<List<ChatMessage>, ChatResponse> operation) {
        return new ChatModel() { @Override public ChatResponse chat(List<ChatMessage> messages) { return operation.apply(messages); } };
    }
    private static RuntimeException failure() { return new LlmGatewayException("synthetic timeout", LlmFailureClass.TIMEOUT_SOFT); }
    private static ChatResponse answer(String text) { return ChatResponse.builder().aiMessage(AiMessage.from(text)).build(); }
}
