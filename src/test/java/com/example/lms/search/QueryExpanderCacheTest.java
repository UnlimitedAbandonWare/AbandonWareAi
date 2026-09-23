package com.example.lms.search;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryExpanderCacheTest {

    @Test
    void cacheKeyIsOpaqueAndDoesNotRetainRawQuestionOrSnippet() throws Exception {
        Fixture fixture = fixture();
        String rawQuestion = "private-query-customer-4711";
        String rawSnippet = "private-snippet-account-9922";

        fixture.expander().expand(rawQuestion, List.of(rawSnippet));

        assertThat(cacheOf(fixture.expander()).keySet())
                .singleElement()
                .asString()
                .matches("[0-9a-f]{64}")
                .doesNotContain(rawQuestion, rawSnippet);
    }

    @Test
    void cacheRetentionIsBoundedUnderDistinctInputs() throws Exception {
        Fixture fixture = fixture();

        for (int i = 0; i < 300; i++) {
            fixture.expander().expand("private-query-" + i, List.of("snippet-" + i));
        }

        assertThat(cacheOf(fixture.expander())).hasSizeLessThanOrEqualTo(256);
    }

    @Test
    void callerMutationCannotCorruptCachedExpansion() {
        Fixture fixture = fixture();
        String question = "stable question";

        List<String> first = fixture.expander().expand(question, List.of("stable snippet"));
        first.clear();
        List<String> second = fixture.expander().expand(question, List.of("stable snippet"));

        assertThat(second).contains(question, "supporting keyword");
    }

    @Test
    void repeatedInputUsesCachedExpansionWithoutAnotherModelCall() {
        Fixture fixture = fixture();

        fixture.expander().expand("same question", List.of("same snippet"));
        fixture.expander().expand("same question", List.of("same snippet"));

        verify(fixture.exploreChatModel(), times(1)).chat(anyList());
    }

    @Test
    void expiryBoundaryIsDeterministicAndExpiredLookupRemovesEntry() throws Exception {
        QueryExpander expander = fixture().expander();
        long storedAt = 1_000L;
        long ttl = 10 * 60 * 1_000L;
        ReflectionTestUtils.invokeMethod(expander, "cache", "synthetic-opaque-key",
                List.of("synthetic result"), storedAt);

        Object atBoundary = ReflectionTestUtils.invokeMethod(expander, "cached",
                "synthetic-opaque-key", storedAt + ttl);
        Object afterBoundary = ReflectionTestUtils.invokeMethod(expander, "cached",
                "synthetic-opaque-key", storedAt + ttl + 1);

        assertThat(atBoundary).isNotNull();
        assertThat(afterBoundary).isNull();
        assertThat(cacheOf(expander)).isEmpty();
    }

    @Test
    void expiredSameKeyIsRecomputedAndOnlyFreshEntryRemains() throws Exception {
        Fixture fixture = fixture();
        String question = "synthetic expiry question";
        List<String> snippets = List.of("synthetic expiry snippet");
        List<String> expected = fixture.expander().expand(question, snippets);
        String key = cacheOf(fixture.expander()).keySet().iterator().next();
        ReflectionTestUtils.invokeMethod(fixture.expander(), "cache", key,
                List.of("expired synthetic result"), 0L);

        assertThat(fixture.expander().expand(question, snippets)).isEqualTo(expected);
        verify(fixture.exploreChatModel(), times(2)).chat(anyList());
        assertThat(cacheOf(fixture.expander())).hasSize(1);
        Object fresh = ReflectionTestUtils.invokeMethod(fixture.expander(), "cached",
                key, System.currentTimeMillis());
        assertThat(fresh).isNotNull();
    }

    @Test
    void storingNewResultPurgesUnrelatedExpiredEntries() throws Exception {
        Fixture fixture = fixture();
        ReflectionTestUtils.invokeMethod(fixture.expander(), "cache", "expired-key-a",
                List.of("synthetic stale a"), 0L);
        ReflectionTestUtils.invokeMethod(fixture.expander(), "cache", "expired-key-b",
                List.of("synthetic stale b"), 0L);
        assertThat(cacheOf(fixture.expander())).hasSize(2);

        fixture.expander().expand("synthetic fresh question", List.of("synthetic snippet"));

        assertThat(cacheOf(fixture.expander())).hasSize(1)
                .doesNotContainKeys("expired-key-a", "expired-key-b");
        verify(fixture.exploreChatModel(), times(1)).chat(anyList());
    }

    @Test
    void concurrentSameKeyMissesMayComputeTwiceButRetainOneReusableEntry() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch bothModelCallsEntered = new CountDownLatch(2);
        CountDownLatch releaseModelCalls = new CountDownLatch(1);
        when(fixture.exploreChatModel().chat(anyList())).thenAnswer(invocation -> {
            bothModelCallsEntered.countDown();
            assertThat(releaseModelCalls.await(5, TimeUnit.SECONDS)).isTrue();
            return ChatResponse.builder().aiMessage(AiMessage.from("supporting keyword")).build();
        });
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<List<String>> first = callers.submit(() -> fixture.expander().expand(
                    "synthetic shared question", List.of("synthetic shared snippet")));
            Future<List<String>> second = callers.submit(() -> fixture.expander().expand(
                    "synthetic shared question", List.of("synthetic shared snippet")));
            assertThat(bothModelCallsEntered.await(2, TimeUnit.SECONDS)).isTrue();
            releaseModelCalls.countDown();
            List<String> firstResult = first.get(2, TimeUnit.SECONDS);
            assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo(firstResult);
            assertThat(fixture.expander().expand("synthetic shared question",
                    List.of("synthetic shared snippet"))).isEqualTo(firstResult);
            assertThat(cacheOf(fixture.expander())).hasSize(1);
            verify(fixture.exploreChatModel(), times(2)).chat(anyList());
        } finally {
            releaseModelCalls.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Fixture fixture() {
        KeyTermMiner miner = mock(KeyTermMiner.class);
        ChatModel exploreChatModel = mock(ChatModel.class);
        LlmKeywordSanitizer sanitizer = mock(LlmKeywordSanitizer.class);
        when(miner.topKeyTerms(anyList(), anyInt())).thenReturn(List.of());
        when(exploreChatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("supporting keyword"))
                .build());
        when(sanitizer.filter(anyString(), anyList(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        return new Fixture(
                new QueryExpander(miner, exploreChatModel, sanitizer),
                exploreChatModel);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> cacheOf(QueryExpander expander) throws Exception {
        Field field = QueryExpander.class.getDeclaredField("cache");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(expander);
    }

    private record Fixture(QueryExpander expander, ChatModel exploreChatModel) {
    }
}
