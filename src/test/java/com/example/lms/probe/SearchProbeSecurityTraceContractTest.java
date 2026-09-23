package com.example.lms.probe;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.service.rag.auth.DomainWhitelist;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SearchProbeSecurityTraceContractTest {

    @Test
    void fallbackCatchesLeaveRedactedBreadcrumbsWithoutRawUrls() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/probe/SearchProbeSecurity.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("[AWX][probe][security] official check skipped errorType={}"));
        assertTrue(source.contains("[AWX][probe][security] finance-noise check skipped errorType={}"));
        assertTrue(source.contains("errorType(ignore)"));
        assertTrue(source.contains("errorType(e)"));
        assertFalse(source.contains("log.debug(\"{}\", url"));
    }

    @Test
    void malformedFinanceNoiseUrlLogsStableInvalidUrlReasonWithoutRawUrl() {
        SearchProbeSecurity security = new SearchProbeSecurity(mock(DomainWhitelist.class));
        Logger logger = (Logger) LoggerFactory.getLogger(SearchProbeSecurity.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            assertFalse(security.isFinanceNoise("http://[broken-private-token"));

            String rendered = appender.list.toString();
            assertTrue(rendered.contains("errorType=invalid_url"), rendered);
            assertFalse(rendered.contains("URISyntaxException"), rendered);
            assertFalse(rendered.contains("broken-private-token"), rendered);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }
}
