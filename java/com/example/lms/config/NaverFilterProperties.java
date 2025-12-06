package com.example.lms.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Getter;
import lombok.Setter;



/**
 * Centralised configuration properties for web search filtering.
 *
 * <p>Existing @Value injections for the Naver search filters were
 * scattered across various services.  This bean consolidates those
 * properties under the {@code naver.filters} prefix and can be
 * injected wherever needed.  Defaults mirror the previous inline
 * definitions so upgrading existing configurations is non-breaking.</p>
 */
@Getter
@Setter
@ConfigurationProperties("naver.filters")
public class NaverFilterProperties {
    /**
     * When true the hard domain whitelist is enforced.  Turning this off
     * allows all domains to pass through.  Defaults to {@code true}
     * for safety.
     */
    private boolean enableDomainFilter = false; // 기본값: 필터 비활성 (모든 도메인 허용)
    /**
     * Whether to apply keyword filtering.  When false only domain
     * restrictions apply.  Defaults to {@code true} to preserve
     * conservative behaviour.
     */
    private boolean enableKeywordFilter = true;
    /**
     * List of allowed domain suffixes (e.g. "go.kr", "ac.kr",
     * "lenovo.com").  When empty or null all domains are permitted.
     * This list is used only when {@link #enableDomainFilter} is true.
     */
    private List<String> domainAllowlist;
    /**
     * Minimum number of keyword hits required when keyword filtering is
     * enabled.  A value of 1 relaxes the filter to require at least
     * one matching keyword.  Defaults to 2 to reduce noise.
     */
    private int keywordMinHits = 2;
    /**
     * Domain policy used when evaluating search results.  Valid values
     * include <code>filter</code>, <code>boost</code> and <code>none</code>.
     * The <code>filter</code> policy strictly removes non-conforming
     * domains whereas <code>boost</code> merely promotes preferred
     * domains in ranking.  The <code>none</code> option disables both
     * filtering and boosting and is useful during troubleshooting.
     * Defaults to {@code boost} to reduce accidental blocking in new environments.
     */
    private String domainPolicy = "boost";
}