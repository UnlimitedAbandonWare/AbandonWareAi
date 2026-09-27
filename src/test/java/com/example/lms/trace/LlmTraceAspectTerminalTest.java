package com.example.lms.trace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.aicore.domain.model.GenerationParams;
import com.acme.aicore.domain.model.Prompt;
import com.acme.aicore.domain.model.TokenChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmTraceAspectTerminalTest {

    @Test
    void completedStreamEmitsOneStopTerminal() throws Throwable {
        CapturedTrace trace = capture(Flux.just(TokenChunk.of("ok")), TerminalAction.COMPLETE);

        assertThat(trace.finishHashes()).containsExactly(SafeRedactor.hash12("stop"));
    }

    @Test
    void cancelledStreamEmitsOneCancelTerminal() throws Throwable {
        CapturedTrace trace = capture(Flux.never(), TerminalAction.CANCEL);

        assertThat(trace.finishHashes()).containsExactly(SafeRedactor.hash12("cancel"));
    }

    @Test
    void failedStreamEmitsOneErrorTerminalWithoutRawFailureText() throws Throwable {
        String rawFailure = "private-provider-failure-4711";
        CapturedTrace trace = capture(
                Flux.error(new IllegalStateException(rawFailure)),
                TerminalAction.ERROR);

        assertThat(trace.finishHashes()).containsExactly(SafeRedactor.hash12("error"));
        assertThat(trace.rendered()).doesNotContain(rawFailure);
    }

    private static CapturedTrace capture(
            Flux<TokenChunk> source,
            TerminalAction action) throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger("TRACE_JSON");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        boolean previousEnabled = TraceLogger.enabled;
        double previousSample = TraceLogger.sample;
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        try {
            TraceLogger.enabled = true;
            TraceLogger.sample = 1.0d;
            ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
            when(joinPoint.proceed()).thenReturn(source);
            LlmTraceAspect aspect = new LlmTraceAspect();
            @SuppressWarnings("unchecked")
            Flux<TokenChunk> wrapped = (Flux<TokenChunk>) aspect.aroundStream(
                    joinPoint,
                    new Prompt(null, null, null),
                    GenerationParams.streaming());

            if (action == TerminalAction.CANCEL) {
                Disposable subscription = wrapped.subscribe();
                subscription.dispose();
            } else if (action == TerminalAction.ERROR) {
                assertThatThrownBy(() -> wrapped.collectList().block())
                        .isInstanceOf(RuntimeException.class);
            } else {
                wrapped.collectList().block();
            }

            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            List<String> finishHashes = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .map(message -> readEvent(mapper, message))
                    .filter(event -> "llm_resp".equals(event.get("type")))
                    .map(event -> (Map<?, ?>) event.get("kv"))
                    .map(kv -> (Map<?, ?>) kv.get("finish"))
                    .map(finish -> String.valueOf(finish.get("hash12")))
                    .toList();
            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + right);
            return new CapturedTrace(finishHashes, rendered);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            TraceLogger.enabled = previousEnabled;
            TraceLogger.sample = previousSample;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readEvent(ObjectMapper mapper, String message) {
        try {
            return mapper.readValue(message, Map.class);
        } catch (Exception error) {
            throw new AssertionError("trace event must be valid JSON", error);
        }
    }

    private enum TerminalAction {
        COMPLETE,
        CANCEL,
        ERROR
    }

    private record CapturedTrace(List<String> finishHashes, String rendered) {
    }
}
