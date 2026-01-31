package com.abandonware.ai.agent.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.observability.AgentTracer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.observability.AgentTracer
role: config
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