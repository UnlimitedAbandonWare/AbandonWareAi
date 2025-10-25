package com.example.patch;


/** Reflective OpenTelemetry bridge; uses GlobalOpenTelemetry if available. */
public class OtelTracerBridge {
    public static void inSpan(String name, Runnable r) {
        Object span = null;
        try {
            Class<?> go = Class.forName("io.opentelemetry.api.GlobalOpenTelemetry");
            Object otel = go.getMethod("get").invoke(null);
            Object tracer = otel.getClass().getMethod("getTracer", String.class).invoke(otel, "rag-agent");
            Class<?> spanBuilderClass = Class.forName("io.opentelemetry.api.trace.SpanBuilder");
            Object builder = tracer.getClass().getMethod("spanBuilder", String.class).invoke(tracer, name);
            span = spanBuilderClass.getMethod("startSpan").invoke(builder);
        } catch (Throwable ignored) {}
        try { r.run(); } finally {
            try {
                if (span != null) {
                    Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span");
                    spanClass.getMethod("end").invoke(span);
                }
            } catch (Throwable ignored) {}
        }
    }
}