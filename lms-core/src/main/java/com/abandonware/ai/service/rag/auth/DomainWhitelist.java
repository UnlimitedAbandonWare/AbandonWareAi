package com.abandonware.ai.service.rag.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.auth.DomainWhitelist
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.auth.DomainWhitelist
role: config
*/
public class DomainWhitelist {
    private final DomainProfileLoader loader;

    @Value("${filters.domain-allowlist.profile:default}")
    private String profileName;

    public DomainWhitelist(DomainProfileLoader loader) {
        this.loader = loader;
    }

    public boolean isAllowed(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            List<String> suffixes = loader.getProfile(profileName);
            for (String s : suffixes) {
                if (host.endsWith(s)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}