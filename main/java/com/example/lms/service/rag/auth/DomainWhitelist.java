package com.example.lms.service.rag.auth;

import com.example.lms.service.rag.detector.RiskBand;
import com.example.lms.search.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
@ConfigurationProperties(prefix = "naver.filters")
public class DomainWhitelist {
    private static final Logger log = LoggerFactory.getLogger(DomainWhitelist.class);

    private boolean enableDomainFilter = false;
    private List<String> domainAllowlist = new ArrayList<>();

    public boolean isOfficial(String url) {
        return isOfficialHost(extractHost(url));
    }

    public boolean isOfficialHost(String host) {
        if (!enableDomainFilter || domainAllowlist == null || domainAllowlist.isEmpty()) {
            return true;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        if (isCommunity(host)) {
            return true;
        }
        for (String suffix : domainAllowlist) {
            if (matchesAllowlistHost(host, suffix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchesAllowlistHost(String host, String suffix) {
        if (host == null || host.isBlank() || suffix == null || suffix.isBlank()) {
            return false;
        }
        String safeHost = host.trim().toLowerCase(Locale.ROOT);
        String safeSuffix = suffix.trim().toLowerCase(Locale.ROOT);
        return safeHost.equals(safeSuffix) || safeHost.endsWith("." + safeSuffix);
    }

    public boolean isEnableDomainFilter() {
        return enableDomainFilter;
    }

    public void setEnableDomainFilter(boolean enableDomainFilter) {
        this.enableDomainFilter = enableDomainFilter;
    }

    public List<String> getDomainAllowlist() {
        return domainAllowlist;
    }

    public void setDomainAllowlist(List<String> domainAllowlist) {
        this.domainAllowlist = Objects.requireNonNullElseGet(domainAllowlist, ArrayList::new);
    }

    public double getDomainScore(String url, RiskBand risk) {
        if (url == null || risk == null) {
            return 0.0d;
        }
        String host = extractHost(url);
        double score = 0.0d;
        if (isOfficialHost(host)) {
            score += risk == RiskBand.HIGH ? 3.0d : 2.0d;
        }
        if (isCommunity(host)) {
            if (risk == RiskBand.HIGH) {
                score -= 5.0d;
            } else if (risk == RiskBand.LOW) {
                score += 2.0d;
            } else {
                score += 0.5d;
            }
        }
        return score;
    }

    public boolean isAllowed(String url, RiskBand risk) {
        String host = extractHost(url);
        return !(risk == RiskBand.HIGH && isCommunity(host)) && !isBanned(host);
    }

    public String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException ex) {
            String errorType = ex.getClass().getSimpleName();
            TraceStore.put("domainWhitelist.extractHost.suppressed", true);
            TraceStore.put("domainWhitelist.extractHost.suppressed.stage", "extractHost");
            TraceStore.put("domainWhitelist.extractHost.suppressed.errorType", errorType);
            TraceStore.put("web.domainWhitelist.extractHost.failed", true);
            TraceStore.put("web.domainWhitelist.extractHost.errorType", errorType);
            log.debug("[DomainWhitelist] fail-soft stage={} errorType={}", "extractHost", errorType);
            return null;
        }
    }

    public boolean isCommunity(String host) {
        return host != null && (host.endsWith("namu.wiki") || host.endsWith("tistory.com"));
    }

    public boolean isBanned(String host) {
        return false;
    }
}
