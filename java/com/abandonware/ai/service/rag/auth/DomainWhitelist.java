package com.abandonware.ai.service.rag.auth;

import com.example.lms.service.rag.detector.RiskBand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/**
 * Domain allow-list and scoring helper used by legacy AbandonWare modules.
 * Profile-based suffixes are loaded from {@link DomainProfileLoader}.
 */
@Component
public class DomainWhitelist {

    private final DomainProfileLoader loader;

    @Value("${filters.domain-allowlist.profile:default}")
    private String profileName;

    public DomainWhitelist(DomainProfileLoader loader) {
        this.loader = loader;
    }

    /**
     * Basic allow-check used by older components.
     * If no profile is configured, the filter is treated as disabled (allow all).
     */
    public boolean isAllowed(String url) {
        String host = extractHost(url);
        if (host == null) {
            return false;
        }
        List<String> suffixes = loader.getProfile(profileName);
        if (suffixes == null || suffixes.isEmpty()) {
            // no profile → filter disabled
            return true;
        }
        for (String suf : suffixes) {
            if (suf != null && !suf.isBlank() && host.endsWith(suf.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if a URL belongs to an official/trusted domain according to the profile
     * and community heuristics.
     */
    public boolean isOfficial(String url) {
        String host = extractHost(url);
        return isOfficialHost(host);
    }

    /**
     * Internal helper to check official domains using the configured profile.
     */
    private boolean isOfficialHost(String host) {
        List<String> suffixes = loader.getProfile(profileName);
        if (suffixes == null || suffixes.isEmpty()) {
            // open policy when no profile is configured
            return true;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        if (isCommunity(host)) {
            return true;
        }
        for (String suf : suffixes) {
            if (suf != null && !suf.isBlank() && host.endsWith(suf.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Domain-based score compatible with the com.example.lms implementation.
     */
    public double getDomainScore(String url, RiskBand risk) {
        if (url == null || risk == null) return 0.0;
        String host = extractHost(url);

        double score = 0.0;

        if (isOfficialHost(host)) {
            score += (risk == RiskBand.HIGH ? 3.0 : 2.0);
        }

        if (isCommunity(host)) {
            if (risk == RiskBand.HIGH) {
                score -= 5.0;
            } else if (risk == RiskBand.LOW) {
                score += 2.0;
            } else {
                score += 0.5;
            }
        }
        return score;
    }

    /**
     * Risk-aware allow-check mirroring the example.lms version.
     */
    public boolean isAllowed(String url, RiskBand risk) {
        String host = extractHost(url);
        if (risk == RiskBand.HIGH && isCommunity(host)) {
            return false;
        }
        if (isBanned(host)) {
            return false;
        }
        // fall back to basic profile-based allow-check
        return isAllowed(url);
    }

    // --- Helper methods below ---

    private String extractHost(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isCommunity(String host) {
        if (host == null) return false;
        return host.endsWith("namu.wiki") || host.endsWith("tistory.com");
    }

    private boolean isBanned(String host) {
        return false;
    }
}

// PATCH_MARKER: DomainWhitelist updated per latest spec.
