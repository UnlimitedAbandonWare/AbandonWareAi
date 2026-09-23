package com.example.lms.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReqLogInterceptorTest {

    @Test
    void preHandleLogsQueryStringAsHashOnlyDiagnostics() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReqLogInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/probe/search");
            String rawQuery = "q=raw-user-question&api_key=abcdsecret";
            request.setQueryString(rawQuery);

            new ReqLogInterceptor().preHandle(request, new MockHttpServletResponse(), new Object());

            String rendered = appender.list.get(0).getFormattedMessage();
            assertFalse(rendered.contains(rawQuery));
            assertFalse(rendered.contains("raw-user-question"));
            assertFalse(rendered.contains("abcdsecret"));
            assertTrue(rendered.contains("queryStringHash=" + SafeRedactor.hashValue(rawQuery)));
            assertTrue(rendered.contains("queryStringLength=38"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void preHandleLogsRequestPathAsHashOnlyDiagnostics() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReqLogInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            String rawPath = "/api/debug/raw-user-question/sk-" + "reqlogpathtrace0123456789012345";
            MockHttpServletRequest request = new MockHttpServletRequest("GET", rawPath);

            new ReqLogInterceptor().preHandle(request, new MockHttpServletResponse(), new Object());

            String rendered = appender.list.get(0).getFormattedMessage();
            assertFalse(rendered.contains(rawPath));
            assertFalse(rendered.contains("raw-user-question"));
            assertFalse(rendered.contains("reqlogpathtrace0123456789012345"));
            assertTrue(rendered.contains("hash12="));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }
}
