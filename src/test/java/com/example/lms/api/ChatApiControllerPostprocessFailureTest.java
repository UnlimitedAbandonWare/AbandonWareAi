package com.example.lms.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.llm.ModelSelectionException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class ChatApiControllerPostprocessFailureTest {
    @Test
    void streamFailureLogIncludesCauseAndAppFrameWithoutUpstreamMessage() throws Exception {
        IllegalStateException cause = new IllegalStateException("private-upstream-text");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.util.concurrent.CompletableFuture", "join", "CompletableFuture.java", 1),
                new StackTraceElement("com.example.lms.service.guard.CitationGate", "verify", "CitationGate.java", 37)});
        CompletionException failure = new CompletionException(cause);
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.example.lms.api.ChatApiController", "stream", "ChatApiController.java", 2897)});
        Logger logger = (Logger) LoggerFactory.getLogger(ChatApiController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Method method = ChatApiController.class.getDeclaredMethod("logStreamFailureDiagnostics", Throwable.class);
            method.setAccessible(true);
            method.invoke(null, failure);
            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.get(0);
            String message = event.getFormattedMessage();
            assertTrue(message.contains("errorHash=hash:"));
            assertTrue(message.contains("errorLength="));
            assertTrue(message.contains("java.util.concurrent.CompletionException>java.lang.IllegalStateException"));
            assertTrue(message.contains("com.example.lms.service.guard.CitationGate#verify:37"));
            assertFalse(message.contains("private-upstream-text"));
            assertNull(event.getThrowableProxy(), "raw throwable messages must never reach the logger");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void diagnosticsBoundCauseTraversalAndRecognizeAbandonwareFrames() throws Exception {
        Throwable failure = new IllegalStateException("private-upstream-text");
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.abandonware.guard.Guard", "check", "Guard.java", 11)});
        for (int i = 0; i < 8; i++) failure = new CompletionException(failure);
        Method method = ChatApiController.class.getDeclaredMethod("streamFailureCauseClasses", Throwable.class);
        method.setAccessible(true);
        String classes = (String) method.invoke(null, failure);
        assertEquals(5, classes.split(">").length);
        Method frame = ChatApiController.class.getDeclaredMethod("streamFailureAppFrame", Throwable.class);
        frame.setAccessible(true);
        Throwable direct = new CompletionException(new IllegalStateException("private-upstream-text"));
        direct.getCause().setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.abandonware.guard.Guard", "check", "Guard.java", 11)});
        assertEquals("com.abandonware.guard.Guard#check:11", frame.invoke(null, direct));
    }

    @Test
    void wrappedKnownFailuresKeepExistingPublicCodes() {
        assertEquals("backend_timeout", ModelSelectionException.streamFailureCode(
                new CompletionException(new ExecutionException(new ModelSelectionException("backend_timeout")))));
        assertEquals("backend_unavailable", ModelSelectionException.streamFailureCode(
                new CompletionException(new NoClassDefFoundError("private-upstream-text"))));
        assertEquals("stream_failed", ModelSelectionException.streamFailureCode(
                new CompletionException(new IllegalStateException("private-upstream-text"))));
    }
}
