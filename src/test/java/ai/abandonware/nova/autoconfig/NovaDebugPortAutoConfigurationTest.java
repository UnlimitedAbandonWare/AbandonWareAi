package ai.abandonware.nova.autoconfig;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovaDebugPortAutoConfigurationTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
        MDC.clear();
    }

    @Test
    void novaDebugPortAutoConfigurationDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/autoconfig/NovaDebugPortAutoConfiguration.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "debug port auto-configuration needs redacted breadcrumbs instead of exact empty catch bodies");
        assertFalse(source.contains("catch (Throwable ignore)"),
                "debug port auto-configuration must name suppressed failures and pass them to redacted breadcrumbs");
        assertTrue(source.contains("ctx.debugPort.suppressed.errorType"),
                "suppressed debug-port failures should record redacted error type");
    }

    @Test
    void numericDebugPortParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/autoconfig/NovaDebugPortAutoConfiguration.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String parserCall = "return Double.parseDouble(raw.trim());";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "debug-port double parser should be locatable");
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Throwable"),
                "debug-port parser fallback must not swallow Throwable");
        assertTrue(window.contains("catch (NumberFormatException"),
                "debug-port parser fallback should catch only NumberFormatException");
        assertTrue(window.contains("logSuppressed(\"parseDouble\", parseError)"),
                "debug-port parser fallback should leave a redacted breadcrumb for invalid numeric config");

        java.lang.reflect.Method errorType = NovaDebugPortAutoConfiguration.class.getDeclaredMethod(
                "errorType", Throwable.class);
        errorType.setAccessible(true);
        assertEquals("invalid_number", errorType.invoke(null, new NumberFormatException("private-token")));
    }

    @Test
    void correlationDebugEventSinkFailureLeavesRedactedTraceBreadcrumb() {
        DebugEventStore throwingStore = new DebugEventStore() {
            @Override
            public void emit(DebugProbeType probe,
                    DebugEventLevel level,
                    String fingerprint,
                    String message,
                    String where,
                    Map<String, Object> data,
                    Throwable error) {
                throw new IllegalStateException("debug sink failed ownerToken=secret-debug-port-event");
            }
        };
        ExchangeFilterFunction filter = ReflectionTestUtils.invokeMethod(
                NovaDebugPortAutoConfiguration.class,
                "correlationInjectionFilter",
                new FixedProvider<>(throwingStore),
                true,
                0.03d);
        assertNotNull(filter);
        ClientRequest request = ClientRequest.create(HttpMethod.GET,
                URI.create("https://example.com/path?token=secret-debug-port-url"))
                .build();
        AtomicReference<ClientRequest> forwarded = new AtomicReference<>();

        ClientResponse response = filter.filter(request, r -> {
            forwarded.set(r);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }).block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.statusCode());
        assertNotNull(forwarded.get().headers().getFirst("X-Request-Id"));
        assertNotNull(forwarded.get().headers().getFirst("X-Session-Id"));
        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(trace.contains("ctx.debugPort.debugEvent.emit.failed"), trace);
        assertTrue(trace.contains("debug_event_emit_failed"), trace);
        assertFalse(trace.contains("IllegalStateException"), trace);
        assertFalse(trace.contains("secret-debug-port-event"), trace);
        assertFalse(trace.contains("secret-debug-port-url"), trace);
    }

    @Test
    void correlationDebugEventProviderFailureLeavesRedactedTraceBreadcrumb() {
        ExchangeFilterFunction filter = ReflectionTestUtils.invokeMethod(
                NovaDebugPortAutoConfiguration.class,
                "correlationInjectionFilter",
                new ThrowingProvider<DebugEventStore>(),
                true,
                0.03d);
        assertNotNull(filter);
        ClientRequest request = ClientRequest.create(HttpMethod.GET,
                URI.create("https://example.com/path?token=secret-debug-port-url"))
                .build();
        AtomicReference<ClientRequest> forwarded = new AtomicReference<>();

        ClientResponse response = assertDoesNotThrow(() -> filter.filter(request, r -> {
            forwarded.set(r);
            return Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).build());
        }).block());

        assertNotNull(response);
        assertEquals(HttpStatus.ACCEPTED, response.statusCode());
        assertNotNull(forwarded.get().headers().getFirst("X-Request-Id"));
        assertNotNull(forwarded.get().headers().getFirst("X-Session-Id"));
        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(trace.contains("ctx.debugPort.debugEvent.resolve.failed"), trace);
        assertTrue(trace.contains("debug_event_resolve_failed"), trace);
        assertFalse(trace.contains("IllegalStateException"), trace);
        assertFalse(trace.contains("secret-debug-port-provider"), trace);
        assertFalse(trace.contains("secret-debug-port-url"), trace);
    }

    @Test
    void mdcBridgeDebugEventProviderFailureLeavesRedactedTraceBreadcrumb() {
        ExchangeFilterFunction filter = ReflectionTestUtils.invokeMethod(
                NovaDebugPortAutoConfiguration.class,
                "mdcBridgeFilter",
                new ThrowingProvider<DebugEventStore>(),
                0.03d);
        assertNotNull(filter);
        ClientRequest request = ClientRequest.create(HttpMethod.GET,
                URI.create("https://example.com/path?token=secret-debug-port-url"))
                .header("X-Request-Id", "safe-rid")
                .header("X-Session-Id", "safe-sid")
                .build();
        AtomicReference<ClientRequest> forwarded = new AtomicReference<>();

        ClientResponse response = assertDoesNotThrow(() -> filter.filter(request, r -> {
            forwarded.set(r);
            return Mono.just(ClientResponse.create(HttpStatus.CREATED).build());
        }).block());

        assertNotNull(response);
        assertEquals(HttpStatus.CREATED, response.statusCode());
        assertEquals("safe-rid", forwarded.get().headers().getFirst("X-Request-Id"));
        assertEquals("safe-sid", forwarded.get().headers().getFirst("X-Session-Id"));
        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(trace.contains("ctx.debugPort.debugEvent.resolve.failed"), trace);
        assertTrue(trace.contains("debug_event_resolve_failed"), trace);
        assertFalse(trace.contains("IllegalStateException"), trace);
        assertFalse(trace.contains("secret-debug-port-provider"), trace);
        assertFalse(trace.contains("secret-debug-port-url"), trace);
    }

    private record FixedProvider<T>(T value) implements ObjectProvider<T> {
        @Override
        public T getObject(Object... args) throws BeansException {
            return value;
        }

        @Override
        public T getIfAvailable() throws BeansException {
            return value;
        }

        @Override
        public T getIfUnique() throws BeansException {
            return value;
        }

        @Override
        public T getObject() throws BeansException {
            return value;
        }
    }

    private static final class ThrowingProvider<T> implements ObjectProvider<T> {
        @Override
        public T getObject(Object... args) throws BeansException {
            throw failure();
        }

        @Override
        public T getIfAvailable() throws BeansException {
            throw failure();
        }

        @Override
        public T getIfUnique() throws BeansException {
            throw failure();
        }

        @Override
        public T getObject() throws BeansException {
            throw failure();
        }

        private IllegalStateException failure() {
            return new IllegalStateException("debug provider failed ownerToken=secret-debug-port-provider");
        }
    }
}
