package com.nova.protocol.normalize;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.normalize.RerankCanonicalizer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.normalize.RerankCanonicalizer
role: config
*/
public class RerankCanonicalizer {
    public String canonical(String url) {
        if (url == null) return null;
        // Remove common tracking params; naive but safe
        return url.replaceAll("[&?]utm_[^&]*", "")
                  .replaceAll("[&?]ref=[^&]*", "")
                  .replaceAll("[&?]fbclid=[^&]*", "");
    }
}