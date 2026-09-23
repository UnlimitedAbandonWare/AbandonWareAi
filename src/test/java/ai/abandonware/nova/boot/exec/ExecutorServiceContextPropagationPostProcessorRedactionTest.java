package ai.abandonware.nova.boot.exec;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorServiceContextPropagationPostProcessorRedactionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void traceStoresBeanHashAndLengthInsteadOfRawBeanName() {
        String beanName = "customerSecretExecutor";
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        try {
            ExecutorServiceContextPropagationPostProcessor postProcessor =
                    new ExecutorServiceContextPropagationPostProcessor(new MockEnvironment(), null);

            Object wrapped = postProcessor.postProcessAfterInitialization(delegate, beanName);

            assertInstanceOf(ContextPropagatingExecutorService.class, wrapped);
            Map<String, Object> trace = TraceStore.getAll();
            String snapshot = String.valueOf(trace);
            assertFalse(snapshot.contains(beanName));
            assertFalse(trace.containsKey("ctx.exec.propagation.wrap.last"));
            assertNotNull(trace.get("ctx.exec.propagation.wrap.beanHash"));
            assertEquals(beanName.length(), trace.get("ctx.exec.propagation.wrap.beanLength"));
            assertEquals("executor", trace.get("ctx.exec.propagation.wrap.kind"));
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void debugEventPayloadUsesHashOnlyBeanAndClassMetadata() {
        String beanName = "customerSecretExecutor";
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        DebugEventStore capturingStore = new DebugEventStore() {
            @Override
            public void emit(DebugProbeType probe,
                    DebugEventLevel level,
                    String fingerprint,
                    String message,
                    String where,
                    Map<String, Object> data,
                    Throwable error) {
                captured.set(Map.copyOf(data));
            }
        };
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        try {
            ExecutorServiceContextPropagationPostProcessor postProcessor =
                    new ExecutorServiceContextPropagationPostProcessor(new MockEnvironment(), capturingStore);

            Object wrapped = postProcessor.postProcessAfterInitialization(delegate, beanName);

            assertInstanceOf(ContextPropagatingExecutorService.class, wrapped);
            Map<String, Object> data = captured.get();
            assertNotNull(data);
            String snapshot = String.valueOf(data);
            assertFalse(snapshot.contains(beanName), snapshot);
            assertFalse(snapshot.contains("java.util.concurrent"), snapshot);
            assertFalse(data.containsKey("bean"), snapshot);
            assertFalse(data.containsKey("class"), snapshot);
            assertFalse(data.containsKey("allowNames"), snapshot);
            assertTrue(String.valueOf(data.get("beanHash")).startsWith("hash:"), snapshot);
            assertEquals(beanName.length(), data.get("beanLength"));
            assertTrue(String.valueOf(data.get("classHash")).startsWith("hash:"), snapshot);
            assertTrue(((Number) data.get("classLength")).intValue() > 0, snapshot);
            assertNotNull(data.get("allowNamesCount"));
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void debugEventSinkFailureLeavesRedactedTraceBreadcrumb() {
        DebugEventStore throwingStore = new DebugEventStore() {
            @Override
            public void emit(DebugProbeType probe,
                    DebugEventLevel level,
                    String fingerprint,
                    String message,
                    String where,
                    Map<String, Object> data,
                    Throwable error) {
                throw new IllegalStateException("debug sink failed ownerToken=secret-context-propagation-event");
            }
        };
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        try {
            ExecutorServiceContextPropagationPostProcessor postProcessor =
                    new ExecutorServiceContextPropagationPostProcessor(new MockEnvironment(), throwingStore);

            Object wrapped = postProcessor.postProcessAfterInitialization(delegate, "searchIoExecutor");

            assertInstanceOf(ContextPropagatingExecutorService.class, wrapped);
            String trace = String.valueOf(TraceStore.getAll());
            assertTrue(trace.contains("ctx.exec.propagation.debugEvent.emit.failed"), trace);
            assertTrue(trace.contains("ctx_exec_propagation_debug_event_emit_failed"), trace);
            assertFalse(trace.contains("IllegalStateException"), trace);
            assertFalse(trace.contains("secret-context-propagation-event"), trace);
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void postProcessorDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/exec/ExecutorServiceContextPropagationPostProcessor.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}", Pattern.DOTALL)
                .matcher(source)
                .find());
    }
}
