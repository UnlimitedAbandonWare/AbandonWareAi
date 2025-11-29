package com.example.lms.service.rag.auth;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.example.lms.service.rag.model.ContextSlice;

/**
 * Minimal allowlist filter. If no allowlist provided, returns input unchanged.
 */
public class DomainWhitelist {
    private final Set<String> allowSuffixes = new HashSet<>();
    private final boolean enabled;

    public DomainWhitelist() {
        this.enabled = false;
    }

    public DomainWhitelist(List<String> suffixes) {
        if (suffixes != null) allowSuffixes.addAll(suffixes);
        this.enabled = allowSuffixes.size() > 0;
    }

    public List<ContextSlice> filter(List<ContextSlice> in) {
        if (!enabled) return in;
        List<ContextSlice> out = new ArrayList<>();
        for (ContextSlice c : in) {
            try {
                String host = URI.create(c.source()).getHost();
                if (host == null) continue;
                for (String s : allowSuffixes) {
                    if (host.equals(s) || host.endsWith("." + s) || host.endsWith(s)) {
                        out.add(c);
                        break;
                    }
                }
            } catch (Exception ignore) {}
        }
        return out;
    }
}