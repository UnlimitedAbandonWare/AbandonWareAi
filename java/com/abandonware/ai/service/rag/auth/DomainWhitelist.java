package com.abandonware.ai.service.rag.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

@Component
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
            if (host == null) {
                return false;
            }
            List<String> suffixes = loader.getProfile(profileName);
            // If no suffixes are configured, treat the filter as disabled (allow all).
            if (suffixes == null || suffixes.isEmpty()) {
                return true;
            }
            for (String s : suffixes) {
                if (s != null && !s.isBlank() && host.endsWith(s)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}