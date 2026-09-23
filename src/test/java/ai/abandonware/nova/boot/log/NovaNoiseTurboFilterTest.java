package ai.abandonware.nova.boot.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NovaNoiseTurboFilterTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidSafeIntUsesStableReasonCodeWithoutRawValue() throws Exception {
        Method method = NovaNoiseTurboFilter.class.getDeclaredMethod("safeInt", Object.class, int.class);
        method.setAccessible(true);

        int value = (Integer) method.invoke(null, "ownerToken-not-an-int", -1);

        assertEquals(-1, value);
        assertEquals("safe_int", TraceStore.get("nova.noiseFilter.fallback.stage"));
        assertEquals("invalid_number", TraceStore.get("nova.noiseFilter.fallback.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken-not-an-int"));
        assertFalse(trace.contains("NumberFormatException"));
    }

    @Test
    void deniesTomcatDispatcherServletSseClientDisconnectError() {
        LoggerContext context = new LoggerContext();
        try {
            Logger logger = context.getLogger(
                    "org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]");
            IOException disconnect = new IOException("client closed connection");
            disconnect.setStackTrace(new StackTraceElement[] {
                    new StackTraceElement(
                            "org.springframework.web.servlet.mvc.method.annotation.ReactiveTypeHandler$SseEmitterSubscriber",
                            "send",
                            "ReactiveTypeHandler.java",
                            389)
            });

            FilterReply reply = new NovaNoiseTurboFilter().decide(
                    null,
                    logger,
                    Level.ERROR,
                    "Servlet.service() for servlet [dispatcherServlet] threw exception",
                    null,
                    disconnect);

            assertEquals(FilterReply.DENY, reply);
        } finally {
            context.stop();
        }
    }

    @Test
    void keepsOrdinaryDispatcherServletIoErrorsVisible() {
        LoggerContext context = new LoggerContext();
        try {
            Logger logger = context.getLogger(
                    "org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]");

            FilterReply reply = new NovaNoiseTurboFilter().decide(
                    null,
                    logger,
                    Level.ERROR,
                    "Servlet.service() for servlet [dispatcherServlet] threw exception",
                    null,
                    new IOException("ordinary io failure"));

            assertEquals(FilterReply.NEUTRAL, reply);
        } finally {
            context.stop();
        }
    }
}
