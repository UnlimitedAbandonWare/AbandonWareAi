package com.example.lms.search.probe;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuration properties for needle probe (2-pass retrieval) feature.
 */
@Component
@ConfigurationProperties(prefix = "needle.probe")
public class NeedleProbeProperties {

    private boolean enabled = false;
    private int webTopK = 5;
    private long webBudgetMs = 2000;
    private double authorityMin = 0.3;
    private int maxCandidatePool = 20;
    private int secondPassCandidateCap = 10;
    private int maxExtraQueries = 2;
    private boolean allowDynamicDomains = true;
    private boolean autoYear = true;
    private List<String> siteHints = List.of("wikipedia.org", "namu.wiki");

    // Trigger thresholds
    private int triggerMinTopDocs = 2;
    private double triggerMinAuthorityAvg = 0.3;
    private double triggerMaxDuplicateRatio = 0.7;
    private double triggerMinCoverage = 0.3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public double getAuthorityMin() {
        return authorityMin;
    }

    public void setAuthorityMin(double authorityMin) {
        this.authorityMin = authorityMin;
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

    public int getMaxExtraQueries() {
        return maxExtraQueries;
    }

    public void setMaxExtraQueries(int maxExtraQueries) {
        this.maxExtraQueries = maxExtraQueries;
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

    public List<String> getSiteHints() {
        return siteHints;
    }

    public void setSiteHints(List<String> siteHints) {
        this.siteHints = siteHints;
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
}
