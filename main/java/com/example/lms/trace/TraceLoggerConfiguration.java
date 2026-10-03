package com.example.lms.trace;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Bind the existing trace controls at startup; explicit JVM properties win. */
@Component
public class TraceLoggerConfiguration {
    public TraceLoggerConfiguration(Environment environment) {
        TraceLogger.enabled = Boolean.parseBoolean(property(environment, "lms.trace.enabled", "true"));
        TraceLogger.sample = TraceLogger.parseDoubleProperty(
                property(environment, "lms.trace.sample", "1.0"), 1.0d);
        TraceLogger.PREVIEW = TraceLogger.parseIntProperty(
                property(environment, "lms.trace.preview", "240"), 240);
    }

    private static String property(Environment environment, String key, String fallback) {
        String systemValue = System.getProperty(key);
        return systemValue != null ? systemValue : environment.getProperty(key, fallback);
    }
}
