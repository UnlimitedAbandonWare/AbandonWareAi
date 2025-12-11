package com.example.lms.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;



/**
 * Configuration holder for the Upstash vector store.  This class exposes
 * common properties required for connecting to the Upstash Vector REST API.
 * When the {@code enabled} flag is false the application will skip any
 * network calls to Upstash and fall back to the primary vector store.  All
 * values are injected from the Spring environment via the
 * {@code vector.upstash.*} namespace.  See the README for details.
 */
@Component
public record UpstashSettings(
        @Value("${vector.upstash.url:}") String url,
        @Value("${vector.upstash.token:}") String token,
        @Value("${vector.upstash.index:rag}") String index,
        @Value("${vector.upstash.connect-timeout-ms:1500}") int connectTimeoutMs,
        @Value("${vector.upstash.read-timeout-ms:2500}") int readTimeoutMs,
        @Value("${vector.upstash.enabled:true}") boolean enabled
) {
}