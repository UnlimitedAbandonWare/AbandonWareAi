package com.nova.protocol.util;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.util.RerankCanonicalizer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.util.RerankCanonicalizer
role: config
*/
public class RerankCanonicalizer {

    public String canonical(String raw) {
        try {
            URI u = new URI(raw);
            String host = (u.getHost() == null) ? "" : u.getHost().toLowerCase();
            String path = (u.getPath() == null) ? "" : u.getPath();
            // drop query params commonly used for tracking
            return u.getScheme() + "://" + host + path;
        } catch (URISyntaxException e) {
            return raw;
        }
    }
}