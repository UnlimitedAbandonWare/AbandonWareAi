package com.example.lms.service.soak;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.repository.SampleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SoakFailSoftLogRedactionTest {

    @TempDir
    Path tempDir;

    @Test
    void datasetIngestFailureLogDoesNotRenderRawThrowableMessage() {
        SampleRepository repository = mock(SampleRepository.class);
        when(repository.findBySourceHash(anyString()))
                .thenThrow(new RuntimeException("raw-soak-query ownerToken=secret"));

        Logger logger = (Logger) LoggerFactory.getLogger(SoakDatasetIngestService.class);
        ListAppender<ILoggingEvent> appender = attachDebugAppender(logger);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            new SoakDatasetIngestService(repository).ingestQuick(report("raw-soak-query"));

            String rendered = rendered(appender);
            assertTrue(rendered.contains("[SOAK] dataset ingest failed"));
            assertTrue(rendered.contains("failureClass="));
            assertFalse(rendered.contains("raw-soak-query"));
            assertFalse(rendered.contains("ownerToken"));
            assertFalse(rendered.contains("RuntimeException: raw-soak-query"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void jsonlExportFailureLogDoesNotRenderRawThrowableMessageOrPath() throws Exception {
        Path fileInsteadOfDirectory = Files.writeString(tempDir.resolve("not-a-directory"), "x");

        SoakQuickJsonlExporter exporter = new SoakQuickJsonlExporter();
        ReflectionTestUtils.setField(exporter, "dir", fileInsteadOfDirectory.toString());
        ReflectionTestUtils.setField(exporter, "fileName", "raw-soak-query-ownerToken-secret.jsonl");

        Logger logger = (Logger) LoggerFactory.getLogger(SoakQuickJsonlExporter.class);
        ListAppender<ILoggingEvent> appender = attachDebugAppender(logger);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            exporter.append(report("raw-soak-query"));

            String rendered = rendered(appender);
            assertTrue(rendered.contains("[SOAK] jsonl export failed"));
            assertTrue(rendered.contains("failureClass="));
            assertFalse(rendered.contains("raw-soak-query"));
            assertFalse(rendered.contains("ownerToken"));
            assertFalse(rendered.contains("java.io.FileNotFoundException"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    private static SoakQuickReport report(String query) {
        SoakQuickReport report = new SoakQuickReport();
        SoakQuickReport.Item item = new SoakQuickReport.Item();
        item.query = query;
        item.evidence = true;
        item.topSnippet = "evidence snippet";
        report.items.add(item);
        return report;
    }

    private static ListAppender<ILoggingEvent> attachDebugAppender(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static String rendered(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }
}
