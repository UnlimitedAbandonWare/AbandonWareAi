package com.example.lms.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class SelectedModelWarmupTest {
    private static final String ENDPOINT = "http://127.0.0.1:11434/v1";

    @SuppressWarnings("unchecked")
    private CompletableFuture<Boolean> warm(LocalLlmProcessManager manager) {
        return ReflectionTestUtils.invokeMethod(manager, "requestModelWarmup", "qwen3.5:9b", ENDPOINT);
    }

    private LocalLlmProcessManager manager(Runtime runtime, boolean enabled) {
        var manager = new LocalLlmProcessManager(new MockEnvironment()
                .withProperty("local-llm.enabled", String.valueOf(enabled))
                .withProperty("local-llm.autostart", "false")
                .withProperty("local-llm.warmup.enabled", "true")
                .withProperty("local-llm.warmup.keep-alive", "5m"), runtime);
        ReflectionTestUtils.invokeMethod(manager, "loadFromEnvironment");
        return manager;
    }

    @Test
    void selectedModelIsAsynchronousAndDeduplicatedWithoutAutostart() {
        Runtime runtime = new Runtime();
        var manager = manager(runtime, true);
        var first = warm(manager);
        var second = warm(manager);
        assertSame(first, second);
        assertFalse(first.isDone());
        assertEquals(1, runtime.work.size());
        assertEquals(0, runtime.posts);
        runtime.work.remove(0).run();
        assertTrue(first.join());
        assertEquals(1, runtime.posts);
        assertEquals("qwen3.5:9b", runtime.body.get("model"));
        assertEquals(List.of(), runtime.body.get("messages"));
        assertEquals("5m", runtime.body.get("keep_alive"));
    }

    @Test
    void loadedModelDoesNotGenerateAPreloadRequest() {
        Runtime runtime = new Runtime();
        runtime.loaded = true;
        var future = warm(manager(runtime, true));
        runtime.work.remove(0).run();
        assertTrue(future.join());
        assertEquals(0, runtime.posts);
    }

    @Test
    void disabledOrStoppedManagerNeverSchedulesLoad() {
        Runtime runtime = new Runtime();
        assertNull(warm(manager(runtime, false)));
        var manager = manager(runtime, true);
        manager.stop();
        assertNull(warm(manager));
        assertEquals(0, runtime.work.size());
    }

    @Test
    void remoteOrOperatorDisabledRouteNeverSchedulesLoad() {
        Runtime runtime = new Runtime();
        var manager = manager(runtime, true);
        assertNull(ReflectionTestUtils.invokeMethod(manager, "requestModelWarmup",
                "qwen3.5:9b", "https://example.org/v1"));
        manager.setEnvironment(new MockEnvironment()
                .withProperty("llmrouter.models.test.name", "qwen3.5:9b")
                .withProperty("llmrouter.models.test.base-url", ENDPOINT)
                .withProperty("llmrouter.models.test.stage", "draft")
                .withProperty("llmrouter.models.test.device-role", "primary")
                .withProperty("llmrouter.models.test.enabled", "false"));
        assertNull(warm(manager));
        assertEquals(0, runtime.work.size());
    }

    @Test
    void unavailableModelOrPreloadFailureIsQuietAndDoesNotPull() {
        Runtime runtime = new Runtime();
        runtime.fail = true;
        var future = warm(manager(runtime, true));
        runtime.work.remove(0).run();
        assertFalse(future.join());
        assertEquals(1, runtime.posts);
    }

    @Test void queuedLoadRechecksDispatchAdmissionBeforePosting() {
        Runtime runtime = new Runtime();
        java.util.concurrent.atomic.AtomicBoolean admitted = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.function.Predicate<Runnable> gate = work -> {
            if (!admitted.get()) return false;
            work.run(); return true;
        };
        CompletableFuture<Boolean> load = ReflectionTestUtils.invokeMethod(manager(runtime, true),
                "requestModelWarmup", "qwen3.5:9b", ENDPOINT, gate);
        admitted.set(false);
        runtime.work.remove(0).run();
        assertFalse(load.join());
        assertEquals(0, runtime.posts);
    }

    @Test void stoppingCompletesQueuedLoadsWithoutPosting() {
        Runtime runtime = new Runtime();
        var manager = manager(runtime, true);
        var load = warm(manager);
        manager.stop();
        assertTrue(load.isDone());
        assertFalse(load.join());
        runtime.work.remove(0).run();
        assertEquals(0, runtime.posts);
    }

    @Test void errorEnvelopeIsNeverReadyEvenWhenDoneIsTrue() {
        Runtime runtime = new Runtime();
        runtime.errorEnvelope = true;
        var load = warm(manager(runtime, true));
        runtime.work.remove(0).run();
        assertFalse(load.join());
    }

    @Test void rejectedSubmissionDoesNotLeaveAnIncompleteLoad() {
        Runtime runtime = new Runtime();
        runtime.reject = true;
        var load = warm(manager(runtime, true));
        assertNotNull(load);
        assertFalse(load.join());
        assertEquals(0, runtime.work.size());
    }

    @Test void blockedPreloadDoesNotBlockLifecycleRecoveryWorker() throws Exception {
        var runtime = new LocalLlmProcessManager.SystemStartupRuntime();
        var loading = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var recovery = new java.util.concurrent.CountDownLatch(1);
        try {
            ReflectionTestUtils.invokeMethod(runtime, "executeWarmup", (Runnable) () -> {
                loading.countDown();
                try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            });
            assertTrue(loading.await(1, java.util.concurrent.TimeUnit.SECONDS));
            runtime.execute(recovery::countDown);
            assertTrue(recovery.await(1, java.util.concurrent.TimeUnit.SECONDS));
        } finally { release.countDown(); runtime.close(); }
    }

    @Test void queuedLoadUsesCurrentServiceEndpointAtExecution() {
        Runtime runtime = new Runtime();
        var environment = new MockEnvironment().withProperty("local-llm.enabled", "true")
                .withProperty("local-llm.autostart", "false").withProperty("local-llm.warmup.enabled", "true");
        var manager = new LocalLlmProcessManager(environment, runtime) {
            @Override public String resolveServiceUrl(String requested) { return runtime.currentEndpoint; }
        };
        ReflectionTestUtils.invokeMethod(manager, "loadFromEnvironment");
        var load = warm(manager);
        runtime.currentEndpoint = "http://127.0.0.1:11435";
        runtime.work.remove(0).run();
        assertTrue(load.join());
        assertEquals(runtime.currentEndpoint + "/api/chat", runtime.postedUrl);
    }

    static final class Runtime implements LocalLlmProcessManager.StartupRuntime {
        final List<Runnable> work = new ArrayList<>();
        Map<String, Object> body;
        int posts;
        boolean loaded;
        boolean fail;
        boolean reject;
        boolean errorEnvelope;
        String currentEndpoint = "http://127.0.0.1:11434";
        String postedUrl;
        final ObjectMapper mapper = new ObjectMapper();
        @Override public void execute(Runnable task) {
            if (reject) throw new java.util.concurrent.RejectedExecutionException("synthetic stop");
            work.add(task);
        }
        @Override public JsonNode getJson(String url, int timeoutMs) throws IOException {
            return mapper.readTree(url.endsWith("/api/ps") && !loaded
                    ? "{\"models\":[]}" : "{\"models\":[{\"name\":\"qwen3.5:9b\"}]}");
        }
        @Override public JsonNode postJson(String url, Map<String, Object> value, long timeoutMs) throws IOException {
            assertTrue(url.endsWith("/api/chat"));
            posts++;
            postedUrl = url;
            body = value;
            if (fail) throw new IOException("synthetic preload failure");
            if (errorEnvelope) return mapper.readTree("{\"done\":true,\"error\":\"synthetic device failure\"}");
            return mapper.readTree("{\"done\":true}");
        }
        @Override public LocalLlmProcessManager.ProbeResult probe(String url, int timeout) {
            return new LocalLlmProcessManager.ProbeResult(LocalLlmProcessManager.ProbeState.HEALTHY,
                    200, 1, "ollama", 11434, "version_ok");
        }
        @Override public String resolveOllamaExecutable(int timeout) { return null; }
        @Override public List<String> discoverGpuUuids(int timeout) { return List.of(); }
        @Override public LocalLlmProcessManager.LaunchResult launch(LocalLlmProcessManager.LaunchRequest request) {
            throw new AssertionError("preload must not launch a server");
        }
        @Override public long nowMillis() { return 0; }
        @Override public void sleepMillis(long millis) { }
    }
}
