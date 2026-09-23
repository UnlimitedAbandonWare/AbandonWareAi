package com.example.lms.logging;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogbackTraceFileEncoderTest {

    @Test
    void traceFileAppenderUsesClasspathSafeNdjsonPatternEncoder() throws Exception {
        assertClasspathSafeTraceEncoder(Path.of("main/resources/logback-spring.xml"));
        assertClasspathSafeTraceEncoder(Path.of("app/src/main/resources/logback-spring.xml"));
    }

    private static void assertClasspathSafeTraceEncoder(Path path) throws Exception {
        String xml = Files.readString(path);
        assertFalse(xml.contains("net.logstash.logback.encoder"),
                () -> path + " must not require logstash-logback-encoder at boot");
        assertTrue(xml.contains("<logger name=\"TRACE_JSON\""),
                () -> path + " should still route structured trace events");
        assertTrue(xml.contains("<pattern>%msg%n</pattern>"),
                () -> path + " should preserve one pre-serialized JSON event per line");
    }
}
