package ai.abandonware.nova.boot.exec.zombie;

import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZombieBreederContainmentAspectTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void detectorFailureFallsBackToOriginalProceedOnceAndRedactsError() throws Throwable {
        String secret = "sk-" + "zombieAspectSecret1234567890";
        ZombieBreederDetector failingDetector = new ZombieBreederDetector() {
            @Override
            public ContaminationVerdict assess(String ownerHint, int currentInflight) {
                throw new IllegalStateException("ownerToken=" + secret);
            }

            @Override
            public void recordVerdict(ContaminationVerdict verdict, String ownerHint) {
                throw new AssertionError("recordVerdict should not run after assess failure");
            }
        };
        ZombieBreederProperties props = new ZombieBreederProperties();
        props.setDrainTimeoutMs(100);
        ZombieBreederContainmentAspect aspect = new ZombieBreederContainmentAspect(
                failingDetector,
                new CleanPoolFactory(props, () -> 4),
                new ZombieContainmentMigrator(props),
                props);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        List<Callable<String>> tasks = List.of(() -> "clean");
        when(pjp.getArgs()).thenReturn(new Object[]{tasks});
        when(pjp.proceed()).thenReturn("original");

        Object result = aspect.containInvokeAll(pjp);

        assertEquals("original", result);
        verify(pjp, times(1)).proceed();
        verify(pjp, never()).proceed(any(Object[].class));
        assertEquals("IllegalStateException", TraceStore.get("zombie.aspect.error"));
        assertNotNull(TraceStore.get("zombie.aspect.errorHash"));
        assertEquals(("ownerToken=" + secret).length(), TraceStore.get("zombie.aspect.errorLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(secret));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken="));
    }

    @Test
    void contaminatedInvokeAllReplaysOnCleanPoolWithoutOriginalProceed() throws Throwable {
        ZombieBreederDetector contaminatedDetector = new ZombieBreederDetector() {
            @Override
            public ContaminationVerdict assess(String ownerHint, int currentInflight) {
                return ContaminationVerdict.CONTAMINATED;
            }

            @Override
            public void recordVerdict(ContaminationVerdict verdict, String ownerHint) {
                TraceStore.put("zombie.detector.verdict", verdict.name());
            }
        };
        ZombieBreederProperties props = new ZombieBreederProperties();
        props.setMaxContainmentPoolSize(2);
        props.setDrainTimeoutMs(500);
        ZombieBreederContainmentAspect aspect = new ZombieBreederContainmentAspect(
                contaminatedDetector,
                new CleanPoolFactory(props, () -> 4),
                new ZombieContainmentMigrator(props),
                props);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        List<Callable<String>> tasks = List.of(() -> "clean-result");
        when(pjp.getArgs()).thenReturn(new Object[]{tasks});
        when(pjp.proceed()).thenReturn("original");

        Object result = aspect.containInvokeAll(pjp);

        assertTrue(result instanceof List<?>);
        List<?> futures = (List<?>) result;
        assertEquals(1, futures.size());
        assertEquals("clean-result", ((Future<?>) futures.get(0)).get(1, TimeUnit.SECONDS));
        verify(pjp, never()).proceed();
        verify(pjp, never()).proceed(any(Object[].class));
        assertEquals("clean_pool_replay", TraceStore.get("zombie.aspect.fallback.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("zombie.aspect.cleanPoolReplay.used"));
        assertEquals(1, TraceStore.get("zombie.aspect.cleanPoolReplay.inflight"));
        assertEquals(0L, TraceStore.get("zombie.aspect.originalProceed.count"));
        assertEquals(1, TraceStore.get("zombie.migrator.migratedCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("zombie.migrator.pipelineSafe"));
        assertEquals(Boolean.TRUE, TraceStore.get("zombie.cleanPool.retired"));
    }
}
