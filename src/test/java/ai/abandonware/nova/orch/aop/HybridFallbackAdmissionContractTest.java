package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchResult;
import com.example.lms.service.web.BraveSearchService;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class HybridFallbackAdmissionContractTest {
    private static final String PREFIX = "web.failsoft.hybridEmptyFallback.";

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void saturatedPoolDoesNotEscapeOrRunEitherProviderOnCaller() throws Exception {
        var naver = mock(NaverSearchService.class);
        var brave = mock(BraveSearchService.class);
        when(naver.isEnabled()).thenReturn(true);
        when(brave.isEnabled()).thenReturn(true);
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        try {
            pool.execute(() -> {
                occupied.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(occupied.await(1, TimeUnit.SECONDS));
            Object result = assertDoesNotThrow(() -> attempt(naver, brave, pool));
            assertEquals(List.of(), field(result, "merged"));
            assertEquals("executor_saturated", TraceStore.get(PREFIX + "naver.status"));
            assertEquals("executor_saturated", TraceStore.get(PREFIX + "brave.status"));
            assertEquals(0L, TraceStore.getLong(PREFIX + "completionPoll.pollCount"));
            verify(naver, never()).searchSnippetsSync(anyString(), anyInt(), any(Duration.class));
            verify(brave, never()).searchWithMeta(anyString(), anyInt());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void oneRejectedProviderDoesNotDiscardTheOtherAdmittedResult(int rejectedOrdinal) throws Exception {
        var naver = mock(NaverSearchService.class);
        var brave = mock(BraveSearchService.class);
        when(naver.isEnabled()).thenReturn(true);
        when(brave.isEnabled()).thenReturn(true);
        var caller = Thread.currentThread();
        var providerThread = new AtomicReference<Thread>();
        when(naver.searchSnippetsSync(anyString(), anyInt(), any(Duration.class))).thenAnswer(call -> {
            providerThread.set(Thread.currentThread());
            return List.of("synthetic naver result");
        });
        when(brave.searchWithMeta(anyString(), anyInt())).thenAnswer(call -> {
            providerThread.set(Thread.currentThread());
            return BraveSearchResult.ok(List.of("synthetic brave result"), 1L);
        });
        var admission = new AtomicInteger();
        var pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), new ThreadPoolExecutor.AbortPolicy()) {
            @Override
            public void execute(Runnable command) {
                if (admission.getAndIncrement() == rejectedOrdinal) {
                    throw new RejectedExecutionException("synthetic admission refusal");
                }
                super.execute(command);
            }
        };
        try {
            Object result = assertDoesNotThrow(() -> attempt(naver, brave, pool));
            assertEquals(List.of(rejectedOrdinal == 0 ? "synthetic brave result" : "synthetic naver result"),
                    field(result, "merged"));
            assertEquals("executor_saturated",
                    TraceStore.get(PREFIX + (rejectedOrdinal == 0 ? "naver" : "brave") + ".status"));
            assertNotNull(providerThread.get());
            assertNotSame(caller, providerThread.get());
            verify(naver, times(rejectedOrdinal == 0 ? 0 : 1))
                    .searchSnippetsSync(anyString(), anyInt(), any(Duration.class));
            verify(brave, times(rejectedOrdinal == 1 ? 0 : 1)).searchWithMeta(anyString(), anyInt());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static Object attempt(NaverSearchService naver, BraveSearchService brave, ThreadPoolExecutor pool) {
        var aspect = new HybridWebSearchEmptyFallbackAspect(new MockEnvironment(),
                new FixedProvider<>(naver), new FixedProvider<>(brave), new FixedProvider<>(pool),
                new FixedProvider<>(null), new FixedProvider<>(null));
        return ReflectionTestUtils.invokeMethod(aspect, "attemptFallback",
                "synthetic-admission", naver, brave, pool, null, "synthetic bounded query", 3,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
                true, false, 0L, 0L, 0L, 0L, 0L, false, "synthetic", "synthetic", "synthetic");
    }

    private static Object field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private record FixedProvider<T>(T value) implements ObjectProvider<T> {
        public T getObject(Object... args) { return value; }
        public T getObject() { return value; }
        public T getIfAvailable() { return value; }
        public T getIfUnique() { return value; }
    }
}
