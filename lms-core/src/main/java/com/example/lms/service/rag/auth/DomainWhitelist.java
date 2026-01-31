package com.example.lms.service.rag.auth;

import com.example.lms.service.rag.detector.RiskBand;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.example.lms.guard.rulebreak.RuleBreakContext;

/**
 * Simple domain whitelist used by web search retrievers to filter results
 * down to a trusted set of host suffixes. When the filter is disabled
 * all domains are permitted. The suffix list is configured via
 * `naver.filters.domain-allowlist` in application.yml.
 */
@Component
@ConfigurationProperties(prefix = "naver.filters")
public class DomainWhitelist {

    /** When true the whitelist is enforced; otherwise all domains pass. */
    private boolean enableDomainFilter = false;

    /** List of allowed domain suffixes (e.g. "go.kr", "ac.kr"). */
    private List<String> domainAllowlist = new ArrayList<>();

    /**
     * Returns true if the given URL belongs to an allowed domain or if
     * filtering is disabled.
     *
     * @param url the URL to check; may be null or blank
     * @return true if official/trusted, false otherwise
     */
    public boolean isOfficial(String url) {
        String host = extractHost(url);
        return isOfficialHost(host);
    }

    /**
     * Internal helper to check if a host string is official/trusted.
     */
    public boolean isOfficialHost(String host) {
        // When filtering is disabled or no allowlist is configured we treat all domains as official.
        if (!enableDomainFilter || domainAllowlist == null || domainAllowlist.isEmpty()) {
            return true;
        }
        if (host == null || host.isBlank()) {
            return false;
        }

        // 게임/커뮤니티용 신뢰 출처는 community로 분류하되, 공식 취급 점수에는 포함.
        if (isCommunity(host)) {
            return true;
        }

        for (String suf : domainAllowlist) {
            if (suf != null && !suf.isBlank() && host.endsWith(suf.trim())) {
                return true;
            }
        }
        return false;
    }

    public boolean isEnableDomainFilter() {
        return enableDomainFilter;
    }

    public void setEnableDomainFilter(boolean v) {
        this.enableDomainFilter = v;
    }

    public List<String> getDomainAllowlist() {
        return domainAllowlist;
    }

    public void setDomainAllowlist(List<String> v) {
        this.domainAllowlist = Objects.requireNonNullElseGet(v, ArrayList::new);
    }

    /**
     * Domain-based score used by retrieval policy / guards.
     */
    public double getDomainScore(String url, RiskBand risk) {
        if (url == null || risk == null) return 0.0;
        String host = extractHost(url);

        double score = 0.0;

        // 공식/신뢰 도메인 가중치
        if (isOfficialHost(host)) {
            score += (risk == RiskBand.HIGH ? 3.0 : 2.0);
        }

        // 커뮤니티/위키 도메인 가중치
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
     * RiskBand에 따른 허용 여부 판단.
     */
    public boolean isAllowed(String url, RiskBand risk) {
        String host = extractHost(url);
        if (risk == RiskBand.HIGH && isCommunity(host)) {
            return false;
        }
        if (isBanned(host)) {
            return false;
        }
        return true;
    }

    // --- Helper methods below ---

    /**
     * Safely extracts the host from a given URL string.
     */
    private String extractHost(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            // 일반적인 http/https URL을 가정. 스키마가 없으면 host가 null일 수 있다.
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks if the host belongs to known community/wiki sites.
     */
    private boolean isCommunity(String host) {
        if (host == null) return false;
        // 기존 정책에 따라 나무위키, 티스토리를 대표 커뮤니티로 취급
        return host.endsWith("namu.wiki") || host.endsWith("tistory.com");
    }

    /**
     * Checks if the host is explicitly banned.
     * 현재는 별도 차단 리스트가 없으므로 항상 false.
     */
    private boolean isBanned(String host) {
        return false;
    }
}

// PATCH_MARKER: DomainWhitelist updated per latest spec.
