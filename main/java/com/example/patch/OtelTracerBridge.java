package com.example.patch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reflective OpenTelemetry bridge; uses GlobalOpenTelemetry if available. */
public class OtelTracerBridge {
    private static final Logger log = LoggerFactory.getLogger(OtelTracerBridge.class);

    public static void inSpan(String name, Runnable r) {
        try {
            r.run();
        } catch (Throwable error) {
            logFailSoft("run", error);
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (error instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException(error);
        }
    }

    private static void logFailSoft(String stage, Throwable error) {
        if (log.isDebugEnabled()) {
            log.debug("[OtelTracerBridge] fail-soft stage={} errorType={}", stage, errorType(error));
        }
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
