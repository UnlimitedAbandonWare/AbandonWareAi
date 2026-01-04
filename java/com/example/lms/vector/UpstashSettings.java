package com.example.lms.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration holder for the Upstash vector store.
 *
 * <p>Preferred keys:
 * <ul>
 *   <li>upstash.vector.rest-url</li>
 *   <li>upstash.vector.api-key</li>
 *   <li>upstash.vector.namespace</li>
 * </ul>
 *
 * <p>Legacy compatibility:
 * <ul>
 *   <li>vector.upstash.url</li>
 *   <li>vector.upstash.token</li>
 *   <li>vector.upstash.index</li>
 * </ul>
 */
@Component
public record UpstashSettings(
        @Value("${upstash.vector.rest-url:${vector.upstash.url:}}") String url,
        @Value("${upstash.vector.api-key:${vector.upstash.token:}}") String token,
        @Value("${upstash.vector.namespace:${vector.upstash.index:rag}}") String index,
        @Value("${upstash.vector.connect-timeout-ms:${vector.upstash.connect-timeout-ms:1500}}") int connectTimeoutMs,
        @Value("${upstash.vector.read-timeout-ms:${vector.upstash.read-timeout-ms:2500}}") int readTimeoutMs,
        @Value("${upstash.vector.enabled:${vector.upstash.enabled:true}}") boolean enabled
) {}
