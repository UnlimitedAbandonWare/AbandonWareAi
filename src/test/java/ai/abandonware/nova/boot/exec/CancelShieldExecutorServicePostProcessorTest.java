package ai.abandonware.nova.boot.exec;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancelShieldExecutorServicePostProcessorTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
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
                throw new IllegalStateException("debug sink failed ownerToken=secret-cancel-shield-event");
            }
        };
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        try {
            CancelShieldExecutorServicePostProcessor postProcessor =
                    new CancelShieldExecutorServicePostProcessor(new MockEnvironment(), provider(throwingStore));

            Object wrapped = postProcessor.postProcessAfterInitialization(delegate, "searchIoExecutor");

            assertInstanceOf(CancelShieldExecutorService.class, wrapped);
            String trace = String.valueOf(TraceStore.getAll());
            assertTrue(trace.contains("ops.cancelShield.debugEvent.emit.failed"), trace);
            assertTrue(trace.contains("cancel_shield_debug_event_emit_failed"), trace);
            assertFalse(trace.contains("IllegalStateException"), trace);
            assertFalse(trace.contains("secret-cancel-shield-event"), trace);
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void postProcessorDoesNotUseSilentIgnoreCatch() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/exec/CancelShieldExecutorServicePostProcessor.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("catch (Throwable ignore)"),
                "CancelShield post-processor should name suppressed failures and record redacted type");
        assertTrue(source.contains("traceCancelShieldSkipped(\"debug_event_failure_trace\", traceFailure)"),
                "CancelShield post-processor should breadcrumb trace-write failures without raw messages");
        assertTrue(source.contains("traceCancelShieldSkipped(\"bulk_max_inflight_parse\", nfe)"),
                "CancelShield post-processor should breadcrumb invalid numeric config without raw values");
        assertTrue(source.contains("ops.cancelShield.debugEvent.emit.traceSkipped.errorType"),
                "CancelShield post-processor should expose redacted trace-skip error type");
        assertTrue(source.contains("ops.cancelShield.debugEvent.emit.errorType"),
                "CancelShield post-processor should retain redacted failure type for suppressed debug-event failures");
    }

    @Test
    void numericConfigParseFailureUsesStableInvalidNumberLabel() throws Exception {
        java.lang.reflect.Method method = CancelShieldExecutorServicePostProcessor.class.getDeclaredMethod(
                "errorType", Throwable.class);
        method.setAccessible(true);

        assertEquals("invalid_number", method.invoke(null, new NumberFormatException("private-token")));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
