package com.example.lms.llm.gateway;

import com.example.lms.llm.ModelRuntimeHealthTracker;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SelectedModelPreloadAdmissionTest {
    private static final String ENDPOINT = "http://127.0.0.1:11434";
    private final LlmGatewayProperties properties = new LlmGatewayProperties();
    private final ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();

    private HybridLlmGatewayProbeService service() {
        properties.getLocalDeviceFailover().setEnabled(true);
        properties.getLocalDeviceFailover().setEnforcement(LlmGatewayProperties.Enforcement.ENFORCE);
        return new HybridLlmGatewayProbeService(properties, tracker, null, new LlmRouteScorer());
    }
    private boolean preload(HybridLlmGatewayProbeService service, Runnable work) {
        return ReflectionTestUtils.invokeMethod(service, "preloadIfIdle", ENDPOINT, work);
    }

    @Test void quarantinedEndpointNeverPostsAndNeverClaimsRecovery() {
        var service = service();
        tracker.recordEndpointDeviceLoss("local", ENDPOINT,
                properties.getLocalDeviceFailover().toEndpointQuarantinePolicy(), System.currentTimeMillis());
        AtomicInteger calls = new AtomicInteger();
        assertFalse(preload(service, calls::incrementAndGet));
        assertEquals(0, calls.get());
        assertEquals(ModelRuntimeHealthTracker.EndpointState.OPEN,
                tracker.endpointSnapshot("local", ENDPOINT, System.currentTimeMillis()).orElseThrow().state());
    }

    @Test void sharedDispatchRemainsHeldUntilActualLoadExits() throws Exception {
        var service = service();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = worker.submit(() -> preload(service, () -> {
                entered.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException stopped) { throw new RuntimeException(stopped); }
            }));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            AtomicInteger calls = new AtomicInteger();
            assertFalse(preload(service, calls::incrementAndGet));
            assertEquals(0, calls.get());
            release.countDown();
            assertTrue(first.get(1, TimeUnit.SECONDS));
            assertTrue(preload(service, calls::incrementAndGet));
            assertEquals(1, calls.get());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test void failedLoadReleasesDispatchForNextCaller() {
        var service = service();
        assertThrows(IllegalStateException.class, () -> preload(service, () -> { throw new IllegalStateException("fixture"); }));
        AtomicInteger calls = new AtomicInteger();
        assertTrue(preload(service, calls::incrementAndGet));
        assertEquals(1, calls.get());
    }
}
