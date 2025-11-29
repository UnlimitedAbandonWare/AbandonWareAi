package com.example.lms.service.rag.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;
import com.example.lms.guard.rulebreak.RuleBreakContext;



/**
 * Simple domain whitelist used by web search retrievers to filter results
 * down to a trusted set of host suffixes.  When the filter is disabled
 * all domains are permitted.  The suffix list is configured via
 * `naver.filters.domain-allowlist` in application.yml.  This class lives
 * under service.rag.auth to avoid circular dependencies on the search
 * module.
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
     * @return true if official, false otherwise
     */
    public boolean isOfficial(String url) {
        if (!enableDomainFilter) {
            return true;
        }
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            for (String suf : domainAllowlist) {
                if (host.endsWith(suf.trim())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
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
}