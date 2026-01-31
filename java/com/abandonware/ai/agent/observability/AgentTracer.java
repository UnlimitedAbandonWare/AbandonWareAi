package com.abandonware.ai.agent.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Lightweight tracer that measures the duration of operations.  Each span
 * logs its start and completion time to the application logger.  This
 * implementation does not propagate context and is not a substitute for
 * OpenTelemetry; it merely provides visibility into the agent's internal
 * processing when more sophisticated tracing is unavailable.
 */
public class AgentTracer {
    private static final Logger log = LoggerFactory.getLogger(AgentTracer.class);

    public Span start(String name) {
        return new Span(name);
    }

    public static class Span implements AutoCloseable {
        private final String name;
        private final long startTime;

        private Span(String name) {
            this.name = name;
            this.startTime = java.time.Instant.now().toEpochMilli();
            log.debug("[Tracer] start span {}", name);
        }

        @Override
        public void close() {
            long duration = java.time.Instant.now().toEpochMilli() - startTime;
            log.debug("[Tracer] end span {} ({} ms)", name, duration);
        }
    }
}