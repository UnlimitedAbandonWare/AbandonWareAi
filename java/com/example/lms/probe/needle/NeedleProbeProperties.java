package com.example.lms.probe.needle;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Needle probe configuration.
 *
 * <p>Needle probe is a tiny 2-pass web detour: when pass-1 evidence quality looks weak
 * (authority/duplicate/coverage heuristics), we run a small second-pass retrieval using
 * 1~2 short, site-filtered queries and then merge+rerank.
 */
@ConfigurationProperties(prefix = "probe.needle")
public class NeedleProbeProperties {

    /** Master switch. */
    private boolean enabled = true;

    /** Maximum number of extra needle queries (keep tiny to avoid burst). */
    private int maxExtraQueries = 2;

    /** Trigger needle if pass-1 topDocs count is below this. */
    private int triggerMinTopDocs = 2;

    /** Trigger needle if average authority score is below this. */
    // Ops-log tuned: slightly more conservative to avoid over-triggering.
    private double triggerMinAuthorityAvg = 0.72;

    /** Trigger needle if duplicate ratio exceeds this. */
    // Ops-log tuned: allow a bit more duplication before triggering.
    private double triggerMaxDuplicateRatio = 0.60;

    /** Trigger needle if query-term coverage across evidence is below this. */
    // Ops-log tuned: lower the min coverage threshold to reduce false triggers.
    private double triggerMinCoverage = 0.32;

    /** Filter: keep needle docs with authority >= this (official/trusted). */
    private double authorityMin = 0.78;

    /** WebTopK used for each needle query. */
    private int webTopK = 6;

    /** Time budget (ms) used for needle web searches. */
    private long webBudgetMs = 1200L;

    /** Maximum merged candidate pool for the second pass. */
    private int maxCandidatePool = 24;

    /** Cross-encoder candidate cap for the second pass. */
    private int secondPassCandidateCap = 18;

    /**
     * Fallback (GENERAL) pool used for adding {@code site:} constraints in needle probing.
     * <p>
     * Keep this list broad/neutral. Domain-specific authorities should be placed in
     * {@link #siteHintsByDomainProfile} so we don't accidentally constrain non-health queries
     * to medical domains (e.g., {@code site:cdc.gov}).
     */
    private List<String> siteHints = new ArrayList<>(List.of(
            "wikipedia.org",
            "britannica.com",
            "arxiv.org",
            "nature.com",
            "reuters.com",
            "apnews.com"
    ));

    /**
     * domainProfile(SelectedTerms.domainProfile) specific pools.
     * <p>
     * Keys are expected to be UPPERCASE.
     */
    private Map<String, List<String>> siteHintsByDomainProfile = new HashMap<>(Map.of(
            // Tech/product: prefer vendor/authority sources.
            "TECH", List.of("samsung.com", "news.samsung.com", "gsmarena.com", "androidauthority.com", "theverge.com"),
            "PRODUCT", List.of("samsung.com", "news.samsung.com", "gsmarena.com", "androidauthority.com", "theverge.com"),
            // Education: broad reference + academic.
            "EDUCATION", List.of("wikipedia.org", "britannica.com", "arxiv.org", "nature.com"),
            "EDU", List.of("wikipedia.org", "britannica.com", "arxiv.org", "nature.com"),
            // Health: official health authorities.
            "HEALTH", List.of("cdc.gov", "who.int", "nih.gov")
    ));

    /** If true, include domains produced by SelectedTerms in the site hint pool. */
    private boolean allowDynamicDomains = true;

    /** If true, add current year when query looks like it asks for latest/recent. */
    private boolean autoYear = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxExtraQueries() {
        return maxExtraQueries;
    }

    public void setMaxExtraQueries(int maxExtraQueries) {
        this.maxExtraQueries = maxExtraQueries;
    }

    public int getTriggerMinTopDocs() {
        return triggerMinTopDocs;
    }

    public void setTriggerMinTopDocs(int triggerMinTopDocs) {
        this.triggerMinTopDocs = triggerMinTopDocs;
    }

    public double getTriggerMinAuthorityAvg() {
        return triggerMinAuthorityAvg;
    }

    public void setTriggerMinAuthorityAvg(double triggerMinAuthorityAvg) {
        this.triggerMinAuthorityAvg = triggerMinAuthorityAvg;
    }

    public double getTriggerMaxDuplicateRatio() {
        return triggerMaxDuplicateRatio;
    }

    public void setTriggerMaxDuplicateRatio(double triggerMaxDuplicateRatio) {
        this.triggerMaxDuplicateRatio = triggerMaxDuplicateRatio;
    }

    public double getTriggerMinCoverage() {
        return triggerMinCoverage;
    }

    public void setTriggerMinCoverage(double triggerMinCoverage) {
        this.triggerMinCoverage = triggerMinCoverage;
    }

    public double getAuthorityMin() {
        return authorityMin;
    }

    public void setAuthorityMin(double authorityMin) {
        this.authorityMin = authorityMin;
    }

    public int getWebTopK() {
        return webTopK;
    }

    public void setWebTopK(int webTopK) {
        this.webTopK = webTopK;
    }

    public long getWebBudgetMs() {
        return webBudgetMs;
    }

    public void setWebBudgetMs(long webBudgetMs) {
        this.webBudgetMs = webBudgetMs;
    }

    public int getMaxCandidatePool() {
        return maxCandidatePool;
    }

    public void setMaxCandidatePool(int maxCandidatePool) {
        this.maxCandidatePool = maxCandidatePool;
    }

    public int getSecondPassCandidateCap() {
        return secondPassCandidateCap;
    }

    public void setSecondPassCandidateCap(int secondPassCandidateCap) {
        this.secondPassCandidateCap = secondPassCandidateCap;
    }

    public List<String> getSiteHints() {
        return siteHints;
    }

    public void setSiteHints(List<String> siteHints) {
        this.siteHints = siteHints;
    }

    public Map<String, List<String>> getSiteHintsByDomainProfile() {
        return siteHintsByDomainProfile;
    }

    public void setSiteHintsByDomainProfile(Map<String, List<String>> siteHintsByDomainProfile) {
        this.siteHintsByDomainProfile = siteHintsByDomainProfile;
    }

    public boolean isAllowDynamicDomains() {
        return allowDynamicDomains;
    }

    public void setAllowDynamicDomains(boolean allowDynamicDomains) {
        this.allowDynamicDomains = allowDynamicDomains;
    }

    public boolean isAutoYear() {
        return autoYear;
    }

    public void setAutoYear(boolean autoYear) {
        this.autoYear = autoYear;
    }
}
