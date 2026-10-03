package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.Zero100EngineProperties;
import ai.abandonware.nova.orch.zero100.Zero100SessionRegistry;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class Zero100AdmissionContractTest {
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void saturationReturnsTheExistingEmptyShapeWithoutStartingProviderOrClaimingTimeout(boolean withTrace)
            throws Throwable {
        var executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                occupied.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(occupied.await(1, TimeUnit.SECONDS));
            var props = new Zero100EngineProperties();
            props.setEngineEnabled(true);
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("syntheticExecutor", executor);
            var aspect = new Zero100WebTimeboxAspect(props, new Zero100SessionRegistry(props),
                    beans.getBeanProvider(java.util.concurrent.ExecutorService.class));
            var pjp = mock(ProceedingJoinPoint.class);
            TraceStore.put("zero100.enabled", true);
            Object result = assertDoesNotThrow(() -> withTrace
                    ? aspect.aroundHybridSearchWithTrace(pjp) : aspect.aroundHybridSearch(pjp));
            if (withTrace) {
                assertEquals(List.of(), assertInstanceOf(NaverSearchService.SearchResult.class, result).snippets());
            } else {
                assertEquals(List.of(), result);
            }
            verify(pjp, never()).proceed();
            assertEquals("executor_saturated", TraceStore.get("zero100.webTimebox.reason"));
            assertEquals(Boolean.FALSE, TraceStore.get("zero100.webTimebox.hit"));
            assertEquals(0L, TraceStore.getLong("zero100.webTimebox.timeout.count"));
            assertEquals(1L, TraceStore.getLong("zero100.webTimebox.rejected.count"));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
