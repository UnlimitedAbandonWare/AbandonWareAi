package com.example.lms.service.routing;

import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.search.TraceStore;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Records existing behavior; these finite counts do not certify a retention bound. */
class PolicyBasedModelRouterCacheCharacterizationTest {
    @AfterEach void clearTrace() { TraceStore.clear(); }

    @Test
    void distinctRequestedModelsRemainRetainedAndAreReusedWithoutAnotherBuild() {
        Fixture fixture = fixture();
        ChatModel sentinel = mock(ChatModel.class);
        when(fixture.factory().lcWithTimeout(anyString(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(sentinel);
        for (int i = 0; i < 256; i++) {
            assertThat(request(fixture.router(), "synthetic-chat-" + i)).isSameAs(sentinel);
            TraceStore.clear();
        }
        assertThat(cache(fixture.router())).hasSize(256);
        assertThat(request(fixture.router(), "synthetic-chat-0")).isSameAs(sentinel);
        verify(fixture.factory(), times(256)).lcWithTimeout(
                anyString(), anyDouble(), isNull(), anyInt(), anyInt());
        verifyNoInteractions(sentinel);
    }

    @Test
    void sameKeyConcurrentMissesMayBuildTwiceButReturnOneCachedModel() throws Exception {
        Fixture fixture = fixture();
        ChatModel firstBuilt = mock(ChatModel.class);
        ChatModel secondBuilt = mock(ChatModel.class);
        java.util.concurrent.atomic.AtomicInteger buildCount = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(fixture.factory().lcWithTimeout(anyString(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    int index = buildCount.getAndIncrement();
                    bothEntered.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return index == 0 ? firstBuilt : secondBuilt;
                });
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<ChatModel> first = callers.submit(() -> request(fixture.router(), "synthetic-shared-chat"));
            Future<ChatModel> second = callers.submit(() -> request(fixture.router(), "synthetic-shared-chat"));
            assertThat(bothEntered.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            ChatModel selected = first.get(2, TimeUnit.SECONDS);
            assertThat(second.get(2, TimeUnit.SECONDS)).isSameAs(selected);
            assertThat(selected).isIn(firstBuilt, secondBuilt);
            assertThat(cache(fixture.router())).hasSize(1);
            assertThat(request(fixture.router(), "synthetic-shared-chat")).isSameAs(selected);
            assertThat(buildCount.get()).isEqualTo(2);
            verifyNoInteractions(firstBuilt, secondBuilt);
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static ChatModel request(PolicyBasedModelRouter router, String model) {
        try { return router.route("qa", "low", "brief", 128, model); }
        finally { TraceStore.clear(); }
    }

    private static Map<?, ?> cache(PolicyBasedModelRouter router) {
        return (Map<?, ?>) ReflectionTestUtils.getField(router, "requestedCache");
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
