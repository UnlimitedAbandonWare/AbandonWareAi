package com.example.lms.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TraceLoggerConfigurationTest {
    private boolean previousEnabled;
    private double previousSample;
    private int previousPreview;
    private final Map<String, String> previousProperties = new LinkedHashMap<>();

    @BeforeEach
    void snapshot() {
        previousEnabled = TraceLogger.enabled;
        previousSample = TraceLogger.sample;
        previousPreview = TraceLogger.PREVIEW;
        for (String key : new String[]{"lms.trace.enabled", "lms.trace.sample", "lms.trace.preview"}) {
            previousProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        TraceLogger.enabled = true;
        TraceLogger.sample = 1.0;
        TraceLogger.PREVIEW = 240;
    }

    @AfterEach
    void restore() {
        TraceLogger.enabled = previousEnabled;
        TraceLogger.sample = previousSample;
        TraceLogger.PREVIEW = previousPreview;
        previousProperties.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
        com.example.lms.search.TraceStore.clear();
    }

    private static ApplicationContextRunner context() {
        // Absent binding is the baseline under test, not a compile failure.
        Class<?>[] bindings;
        try {
            bindings = new Class<?>[]{Class.forName("com.example.lms.trace.TraceLoggerConfiguration")};
        } catch (ClassNotFoundException absentBinding) {
            bindings = new Class<?>[0];
        }
        return new ApplicationContextRunner().withUserConfiguration(bindings);
    }

    @Test
    void springTracePropertiesReachStaticLogger() {
        context().withPropertyValues("lms.trace.enabled=false", "lms.trace.sample=0.1", "lms.trace.preview=80")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertEquals(0.1, TraceLogger.sample);
                    assertFalse(TraceLogger.enabled);
                    assertEquals(80, TraceLogger.PREVIEW);
                });
    }

    @Test
    void systemPropertiesTakePriorityOverSpringProperties() {
        System.setProperty("lms.trace.enabled", "false");
        System.setProperty("lms.trace.sample", "0.7");
        System.setProperty("lms.trace.preview", "96");
        context().withPropertyValues("lms.trace.enabled=true", "lms.trace.sample=0.1", "lms.trace.preview=80")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertEquals(0.7, TraceLogger.sample);
                    assertFalse(TraceLogger.enabled);
                    assertEquals(96, TraceLogger.PREVIEW);
                });
    }

    @Test
    void absentPropertiesRetainOriginalDefaults() {
        context().run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertTrue(TraceLogger.enabled);
            assertEquals(1.0, TraceLogger.sample);
            assertEquals(240, TraceLogger.PREVIEW);
        });
    }
}
