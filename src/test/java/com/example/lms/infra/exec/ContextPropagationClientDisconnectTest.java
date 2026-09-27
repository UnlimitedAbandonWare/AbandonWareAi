package com.example.lms.infra.exec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextPropagationClientDisconnectTest {

    @Test
    void runnableSuppressesTomcatAsyncDisconnectRace() {
        Runnable wrapped = ContextPropagation.wrap(() -> {
            throw new IllegalStateException(
                    "A non-container (application) thread attempted to use the AsyncContext after an error had occurred");
        });

        assertDoesNotThrow(wrapped::run);
    }

    @Test
    void runnableStillPropagatesOrdinaryIllegalStateException() {
        Runnable wrapped = ContextPropagation.wrap(() -> {
            throw new IllegalStateException("ordinary failure");
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class, wrapped::run);
        assertEquals("ordinary failure", thrown.getMessage());
    }

    @Test
    void mvcSseIOExceptionIsTreatedAsClientDisconnect() throws Exception {
        IOException disconnect = new IOException("client closed connection");
        disconnect.setStackTrace(new StackTraceElement[] {
                new StackTraceElement(
                        "org.springframework.web.servlet.mvc.method.annotation.ReactiveTypeHandler$SseEmitterSubscriber",
                        "send",
                        "ReactiveTypeHandler.java",
                        389)
        });
        Runnable wrapped = ContextPropagation.wrap(() -> sneakyThrow(disconnect));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                wrapped.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "mvc-async-test");

        worker.start();
        worker.join(2_000);

        assertEquals(null, failure.get());
    }

    @Test
    void ordinaryIOExceptionStillPropagates() {
        IOException failure = new IOException("ordinary io");
        Runnable wrapped = ContextPropagation.wrap(() -> sneakyThrow(failure));

        IOException thrown = assertThrows(IOException.class, wrapped::run);
        assertEquals("ordinary io", thrown.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
