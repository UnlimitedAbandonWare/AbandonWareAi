package com.example.lms.service.rag.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Domain allow-list for outbound retrieval/citations.
 * Property: 'domains.whitelist' comma-separated suffixes. ex) gov.kr,go.kr,ac.kr
 * Fallback defaults if property missing.
 */
@Component
public class DomainWhitelist {
    private final Set<String> hosts = new HashSet<>();

    public DomainWhitelist(
            @Value("#{'${domains.whitelist:gov.kr,go.kr,ac.kr,who.int,nature.com}'.split(',')}")
            List<String> list) {
        for (String s : list) {
            if (s != null) {
                String t = s.trim().toLowerCase();
                if (!t.isEmpty()) hosts.add(t);
            }
        }
    }

    public boolean allow(String url) {
        if (url == null) return false;
        try {
            String h = URI.create(url).getHost();
            if (h == null) return false;
            h = h.toLowerCase();
            for (String suffix : hosts) {
                if (h.endsWith(suffix)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public Set<String> snapshot() {
        return java.util.Collections.unmodifiableSet(hosts);
    }
}
