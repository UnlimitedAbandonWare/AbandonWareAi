package com.example.lms.service.soak.runner;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.search.TraceStore;
import com.example.lms.service.soak.SoakQuickReport;
import com.example.lms.service.soak.SoakReport;
import com.example.lms.service.soak.SoakTestService;
import com.example.lms.service.soak.metrics.SoakMetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoakQuickRunnerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void runOnceCancellationUsesOperationalGateReasonWithoutRawExceptionClass() throws Exception {
        SoakQuickRunnerProperties props = new SoakQuickRunnerProperties();
        props.setProviders(List.of("NAVER"));
        props.setOutputPath(tempDir.resolve("quick_report.json").toString());
        SoakTestService service = new SoakTestService() {
            @Override
            public SoakReport run(int k, String topic) {
                throw new UnsupportedOperationException("unused");
            }

            @Override
            public SoakQuickReport runQuick(int k, String topic) {
                throw new CancellationException("cancelled ownerToken=secret");
            }
        };
        SoakMetricRegistry metricRegistry = new SoakMetricRegistry();
        SoakQuickRunner runner = new SoakQuickRunner(
                service, new ObjectMapper().findAndRegisterModules(), props, metricRegistry);

        SoakQuickBundleReport bundle = runner.runOnce("unit");

        assertEquals(1, bundle.providers.size());
        List<String> reasons = bundle.providers.get(0).gate.reasons;
        assertEquals(List.of("exception:cancelled"), reasons);
        assertFalse(String.valueOf(reasons).contains("CancellationException"));
        assertFalse(String.valueOf(reasons).contains("ownerToken"));
        assertEquals(Boolean.TRUE, TraceStore.get("soak.quickRunner.suppressed.providerRun"));
        assertEquals("cancelled", TraceStore.get("soak.quickRunner.suppressed.providerRun.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("CancellationException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
        assertEquals(0, retainedSidCount(metricRegistry));
    }

    @Test
    void runOnceReleasesProviderMetricsAfterCopyingTheirSnapshot() throws Exception {
        SoakQuickRunnerProperties props = new SoakQuickRunnerProperties();
        props.setProviders(List.of("NAVER"));
        props.setOutputPath(tempDir.resolve("quick_report_metrics.json").toString());
        SoakMetricRegistry metricRegistry = new SoakMetricRegistry();
        SoakTestService service = new SoakTestService() {
            @Override
            public SoakReport run(int k, String topic) {
                throw new UnsupportedOperationException("unused");
            }

            @Override
            public SoakQuickReport runQuick(int k, String topic) {
                metricRegistry.recordWebCall(true);
                SoakQuickReport report = new SoakQuickReport();
                SoakQuickReport.Item item = new SoakQuickReport.Item();
                item.success = true;
                item.evidence = true;
                report.items.add(item);
                return report;
            }
        };
        SoakQuickRunner runner = new SoakQuickRunner(
                service, new ObjectMapper().findAndRegisterModules(), props, metricRegistry);

        SoakQuickBundleReport bundle = runner.runOnce("unit");

        assertEquals(1L, bundle.providers.get(0).metrics.webCalls);
        assertEquals(1L, bundle.providers.get(0).metrics.webCallsWithNaver);
        assertEquals(0, retainedSidCount(metricRegistry));
    }

    @Test
    void runOnceWrappedCancellationUsesCauseTypeWithoutRawExceptionClass() throws Exception {
        SoakQuickRunnerProperties props = new SoakQuickRunnerProperties();
        props.setProviders(List.of("NAVER"));
        props.setOutputPath(tempDir.resolve("quick_report_wrapped.json").toString());
        SoakTestService service = new SoakTestService() {
            @Override
            public SoakReport run(int k, String topic) {
                throw new UnsupportedOperationException("unused");
            }

            @Override
            public SoakQuickReport runQuick(int k, String topic) {
                throw new RuntimeException(
                        "wrapper ownerToken=secret",
                        new CancellationException("cancelled ownerToken=secret"));
            }
        };
        SoakQuickRunner runner = new SoakQuickRunner(service, new ObjectMapper().findAndRegisterModules(), props, null);

        SoakQuickBundleReport bundle = runner.runOnce("unit");

        assertEquals(1, bundle.providers.size());
        List<String> reasons = bundle.providers.get(0).gate.reasons;
        assertEquals(List.of("exception:cancelled"), reasons);
        assertFalse(String.valueOf(reasons).contains("RuntimeException"));
        assertFalse(String.valueOf(reasons).contains("CancellationException"));
        assertFalse(String.valueOf(reasons).contains("ownerToken"));
    }

    @Test
    void scheduledFailureLogDoesNotRenderRawThrowablePath() throws Exception {
        Path fileInsteadOfDirectory = Files.writeString(tempDir.resolve("private-soak-query-ownerToken-secret"), "x");

        SoakQuickRunnerProperties props = new SoakQuickRunnerProperties();
        props.setScheduled(true);
        props.setProviders(List.of("NAVER"));
        props.setOutputPath(fileInsteadOfDirectory.resolve("quick_report.json").toString());
        SoakTestService service = new SoakTestService() {
            @Override
            public SoakReport run(int k, String topic) {
                throw new UnsupportedOperationException("unused");
            }

            @Override
            public SoakQuickReport runQuick(int k, String topic) {
                SoakQuickReport report = new SoakQuickReport();
                SoakQuickReport.Item item = new SoakQuickReport.Item();
                item.success = true;
                item.evidence = true;
                report.items.add(item);
                return report;
            }
        };
        SoakQuickRunner runner = new SoakQuickRunner(service, new ObjectMapper().findAndRegisterModules(), props, null);

        Logger logger = (Logger) LoggerFactory.getLogger(SoakQuickRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ERROR);
        try {
            runner.scheduled();

            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertFalse(rendered.isBlank());
            assertTrue(rendered.contains("[SOAK] scheduled quick_report failed"));
            assertTrue(rendered.contains("failureClass="));
            assertFalse(rendered.contains("private-soak-query"));
            assertFalse(rendered.contains("ownerToken"));
            assertFalse(rendered.contains("java.nio.file.FileAlreadyExistsException"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    private static int retainedSidCount(SoakMetricRegistry registry) {
        @SuppressWarnings("unchecked")
        Map<String, ?> bySid = (Map<String, ?>) ReflectionTestUtils.getField(registry, "bySid");
        return bySid == null ? 0 : bySid.size();
    }
}
