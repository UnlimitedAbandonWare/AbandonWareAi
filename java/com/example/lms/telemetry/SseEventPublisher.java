package com.example.lms.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple interface for publishing Server‑Sent Events (SSE) to front‑end clients.
 * Implementations may choose to actually send events over a network or simply
 * log them for debugging.  The generic payload parameter allows arbitrary
 * objects to be attached to an event.  A small helper class {@link Payload}
 * provides a fluent API for building a map payload.
 */
/**
 * Interface for a Server‑Sent Event publisher.  Concrete implementations
 * should be annotated with {@code @Component} so that Spring will detect
 * them and register them as beans.  Do not annotate this interface with
 * {@code @Component} because Spring cannot instantiate an interface.
 */
public interface SseEventPublisher {
    /**
     * Emit an SSE with a given type and payload.  The interpretation of
     * {@code type} depends on the consuming front‑end; examples include
     * "MOE_ROUTE" for model routing decisions or "ui" for generative UI
     * fragments.
     *
     * @param type    event type identifier
     * @param payload payload to send (may be a simple String, Map, etc.)
     */
    void emit(String type, Object payload);

    /**
     * Builder for structured SSE payloads.  Internally backed by a
     * {@link LinkedHashMap} to preserve insertion order when serialized.
     */
    class Payload {
        private final Map<String, Object> map = new LinkedHashMap<>();

        /**
         * Add a key/value pair to the payload.  Null values are permitted and
         * retained.
         *
         * @param k key
         * @param v value
         * @return this builder for chaining
         */
        public Payload kv(String k, Object v) {
            map.put(k, v);
            return this;
        }

        /**
         * Return the underlying map representing this payload.
         *
         * @return an unmodifiable view of the map
         */
        public Map<String, Object> build() {
            return java.util.Collections.unmodifiableMap(map);
        }
    }
}