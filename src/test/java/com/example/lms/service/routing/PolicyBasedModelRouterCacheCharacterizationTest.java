package com.example.lms.service.routing;

import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.search.TraceStore;
import com.github.benmanes.caffeine.cache.Cache;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic construction only; no provider generation or client resource disposal. */
class PolicyBasedModelRouterCacheCharacterizationTest {
    @AfterEach void clearTrace() { TraceStore.clear(); }

    @Test void highCardinalityRequestedClientsHaveBoundedRetention() {
        Fixture fixture = fixture();
        ChatModel sentinel = mock(ChatModel.class);
        when(fixture.factory().lcWithTimeout(anyString(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(sentinel);
        for (int i = 0; i < 1024; i++) request(fixture.router(), "synthetic-chat-" + i);
        assertThat(cache(fixture.router()).size()).isLessThanOrEqualTo(256);
        verify(fixture.factory(), times(1024)).lcWithTimeout(anyString(), anyDouble(), isNull(), anyInt(), anyInt());
        verifyNoInteractions(sentinel);
    }

    @Test void sameKeyConcurrentMissesBuildOnceAndReturnSameClient() throws Exception {
        Fixture fixture = fixture();
        ChatModel selected = mock(ChatModel.class);
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        CountDownLatch duplicateBuild = new CountDownLatch(1), release = new CountDownLatch(1);
        when(fixture.factory().lcWithTimeout(anyString(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    if (builds.incrementAndGet() > 1) duplicateBuild.countDown();
                    firstEntered.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return selected;
                });
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<ChatModel> first = callers.submit(() -> request(fixture.router(), "synthetic-shared-chat"));
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<ChatModel> second = callers.submit(() -> {
                secondStarted.countDown();
                return request(fixture.router(), "synthetic-shared-chat");
            });
            assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
            boolean duplicateEntered = duplicateBuild.await(1, TimeUnit.SECONDS);
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isSameAs(selected);
            assertThat(second.get(2, TimeUnit.SECONDS)).isSameAs(selected);
            assertThat(duplicateEntered).isFalse();
            assertThat(builds.get()).isEqualTo(1);
            assertThat(cache(fixture.router())).hasSize(1);
            assertThat(request(fixture.router(), "synthetic-shared-chat")).isSameAs(selected);
            assertThat(builds.get()).isEqualTo(1);
            verifyNoInteractions(selected);
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void factoryFailureIsNotCachedAndNextAttemptCanRecover() {
        Fixture fixture = fixture();
        ChatModel selected = mock(ChatModel.class);
        when(fixture.factory().lcWithTimeout(anyString(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("synthetic build failure")).thenReturn(selected);
        assertThat(request(fixture.router(), "synthetic-retry-chat")).isNotSameAs(selected);
        assertThat(cache(fixture.router())).isEmpty();
        assertThat(request(fixture.router(), "synthetic-retry-chat")).isSameAs(selected);
        assertThat(request(fixture.router(), "synthetic-retry-chat")).isSameAs(selected);
        verify(fixture.factory(), times(2)).lcWithTimeout(anyString(), anyDouble(), isNull(), anyInt(), anyInt());
    }

    @Test void currentPolicyExpiresIdleEntriesAndRestartUsesAFreshCache() {
        Fixture fixture = fixture();
        Object raw = ReflectionTestUtils.getField(fixture.router(), "requestedCache");
        assertThat(raw).isInstanceOf(Cache.class);
        Cache<?, ?> cache = (Cache<?, ?>) raw;
        assertThat(cache.policy().expireAfterAccess().orElseThrow().getExpiresAfter()).isEqualTo(Duration.ofMinutes(30));
        assertThat(cache.policy().eviction().orElseThrow().getMaximum()).isEqualTo(256);
        assertThat(ReflectionTestUtils.getField(fixture().router(), "requestedCache")).isNotSameAs(raw);
    }

    private static ChatModel request(PolicyBasedModelRouter router, String model) {
        try { return router.route("qa", "low", "brief", 128, model); }
        finally { TraceStore.clear(); }
    }
    private static Map<?, ?> cache(PolicyBasedModelRouter router) {
        Object raw = ReflectionTestUtils.getField(router, "requestedCache");
        if (raw instanceof Cache<?, ?> cache) { cache.cleanUp(); return cache.asMap(); }
        return (Map<?, ?>) raw; // permits the unchanged preimage to demonstrate RED
    }
    private static Fixture fixture() {
        ChatModel base = mock(ChatModel.class);
        DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class);
        PolicyBasedModelRouter router = spy(new PolicyBasedModelRouter(base, null, null,
                mock(RouterPolicy.class), factory));
        doReturn(base).when(router).route(anyString(), anyString(), anyString(), anyInt());
        ReflectionTestUtils.setField(router, "fastTimeoutSeconds", 3);
        ReflectionTestUtils.setField(router, "requestedModelTimeoutSeconds", 3);
        when(factory.canServe(anyString())).thenReturn(true);
        return new Fixture(router, factory);
    }
    private record Fixture(PolicyBasedModelRouter router, DynamicChatModelFactory factory) { }
}
