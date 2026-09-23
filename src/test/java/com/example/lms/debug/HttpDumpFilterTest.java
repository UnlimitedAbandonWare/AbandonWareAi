package com.example.lms.debug;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpDumpFilterTest {

    @Test
    void traceLogUsesRedactedPathDiagnostics() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpDumpFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        try {
            String rawPath = "/api/debug/raw-user-question/sk-" + "httpdumptrace0123456789012345";
            MockHttpServletRequest request = new MockHttpServletRequest("GET", rawPath);

            new HttpDumpFilter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(rendered.contains(rawPath));
            assertFalse(rendered.contains("raw-user-question"));
            assertFalse(rendered.contains("httpdumptrace0123456789012345"));
            assertTrue(rendered.contains("hash12="));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }
}
