package ai.abandonware.nova.boot.reactor;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovaReactorDroppedErrorHookTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void droppedWebClientErrorDiagnosticsDoNotStoreRawUriQueryOrBodyPreview() throws Exception {
        String rawQuery = "private dropped query";
        String apiKey = "sk-reactor-secret123";
        String body = "{\"error\":\"failed for " + rawQuery + " api_key=" + apiKey + "\"}";
        URI uri = URI.create("https://api.example.test/search?q=private%20dropped%20query&api_key=" + apiKey);

        DebugEventStore store = enabledDebugEventStore();
        NovaReactorDroppedErrorHook hook = new NovaReactorDroppedErrorHook(
                new MockEnvironment(),
                provider((FaultMaskingLayerMonitor) null),
                provider(store));

        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, uri);
        WebClientResponseException error = WebClientResponseException.create(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8,
                request);

        Method method = NovaReactorDroppedErrorHook.class.getDeclaredMethod(
                "handleDroppedError", Throwable.class, int.class, boolean.class);
        method.setAccessible(true);
        method.invoke(hook, error, 768, true);

        List<DebugEvent> events = store.list(1);
        assertEquals(1, events.size());
        Map<String, Object> data = events.get(0).data();

        assertEquals(429, data.get("status"));
        assertEquals("api.example.test/search", data.get("target"));
        assertEquals(Boolean.TRUE, data.get("queryPresent"));
        assertTrue(String.valueOf(data.get("queryHash")).startsWith("hash:"));
        assertTrue(String.valueOf(data.get("bodyHash")).startsWith("hash:"));
        assertEquals(body.length(), data.get("bodyLength"));
        assertFalse(data.containsKey("uri"));
        assertFalse(data.containsKey("bodyPreview"));

        String dump = events.get(0) + "\n" + TraceStore.getAll();
        assertFalse(dump.contains(rawQuery));
        assertFalse(dump.contains(apiKey));
        assertFalse(dump.contains("api_key"));
    }

    @Test
    void debugEventSinkFailureLeavesRedactedTraceBreadcrumb() throws Exception {
        DebugEventStore throwingStore = new DebugEventStore() {
            @Override
            public void emit(DebugProbeType probe,
                    DebugEventLevel level,
                    String fingerprint,
                    String message,
                    String where,
                    Map<String, Object> data,
                    Throwable error) {
                throw new IllegalStateException("debug sink failed ownerToken=secret-reactor-event");
            }
        };
        NovaReactorDroppedErrorHook hook = new NovaReactorDroppedErrorHook(
                new MockEnvironment(),
                provider((FaultMaskingLayerMonitor) null),
                provider(throwingStore));

        Method method = NovaReactorDroppedErrorHook.class.getDeclaredMethod(
                "emitDebugEvent", String.class, Map.class);
        method.setAccessible(true);
        method.invoke(hook, "onErrorDropped.test", Map.of("status", "safe"));

        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(trace.contains("reactor.debugEvent.emit.failed"), trace);
        assertTrue(trace.contains("reactor_debug_event_emit_failed"), trace);
        assertFalse(trace.contains("IllegalStateException"), trace);
        assertFalse(trace.contains("secret-reactor-event"), trace);
    }

    @Test
    void droppedErrorBodyLengthParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                java.nio.file.Path.of("main/java/ai/abandonware/nova/boot/reactor/NovaReactorDroppedErrorHook.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String parserCall = "return Integer.parseInt(v.trim());";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "reactor dropped-error integer parser should be locatable");
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Throwable"),
                "reactor dropped-error parser fallback must not swallow Throwable");
        assertTrue(window.contains("catch (NumberFormatException"),
                "reactor dropped-error parser fallback should catch only NumberFormatException");
        assertTrue(window.contains("traceSuppressed(\"parseInt\", parseError)"),
                "reactor dropped-error parser fallback should leave a redacted breadcrumb");
    }

    @Test
    void invalidDroppedErrorBodyLengthUsesStableReasonCodeWithoutRawValue() throws Exception {
        Method method = NovaReactorDroppedErrorHook.class.getDeclaredMethod(
                "parseInt", String.class, int.class);
        method.setAccessible(true);

        int value = (Integer) method.invoke(null, "ownerToken-not-an-int", 768);

        assertEquals(768, value);
        assertEquals("parseInt", TraceStore.get("reactor.telemetry.skipped.stage"));
        assertEquals("invalid_number", TraceStore.get("reactor.telemetry.skipped.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken-not-an-int"));
        assertFalse(trace.contains("NumberFormatException"));
    }

    @Test
    void droppedErrorHookDoesNotUseSilentIgnoreCatches() throws Exception {
        String source = Files.readString(
                java.nio.file.Path.of("main/java/ai/abandonware/nova/boot/reactor/NovaReactorDroppedErrorHook.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("catch (Throwable ignore)"),
                "Reactor dropped-error hook should record redacted breadcrumbs for suppressed failures");
        assertTrue(source.contains("reactor.telemetry.skipped.errorType"),
                "Reactor dropped-error hook should keep redacted error type for suppressed telemetry failures");
        assertTrue(source.contains("[Nova] reactor debug event trace skipped"),
                "Reactor dropped-error hook should log a non-recursive debug breadcrumb if debug event trace fails");
        assertTrue(source.contains("[Nova] reactor telemetry trace skipped"),
                "Reactor dropped-error hook should log a non-recursive debug breadcrumb if TraceStore fallback fails");
    }

    private static DebugEventStore enabledDebugEventStore() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 15_000L);
        ReflectionTestUtils.setField(store, "ndjsonEnabled", false);
        return store;
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
