package com.example.lms.uaw.selfclean;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties for the UAW self-clean orchestrator.
 */
@ConfigurationProperties(prefix = "uaw.selfclean")
public class UawSelfCleanProperties {

    /**
     * Enable the self-clean orchestrator.
     */
    private boolean enabled = false;

    /**
     * CPU idle threshold (0..1). If negative, CPU gating is disabled.
     */
    private double idleCpuThreshold = 0.70;

    /**
     * Probability to attempt shadow-merge when there are pending items.
     */
    private double mergeProb = 0.75;

    /**
     * Probability to attempt global rotate/rebuild when merge isn't selected.
     */
    private double rebuildProb = 0.15;

    /**
     * Probability to redrive quarantine DLQ when neither merge nor rebuild is selected.
     */
    private double quarantineRedriveProb = 0.10;

    /**
     * Minimum interval between expensive rebuilds.
     */
    private long rebuildMinIntervalMs = 6 * 60 * 60 * 1000L; // 6h

    /**
     * Limits for adminRebuild.
     */
    private int rebuildKbLimit = 500;
    private int rebuildMemoryLimit = 500;
    private boolean rebuildIncludeKb = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getIdleCpuThreshold() {
        return idleCpuThreshold;
    }

    public void setIdleCpuThreshold(double idleCpuThreshold) {
        this.idleCpuThreshold = idleCpuThreshold;
    }

    public double getMergeProb() {
        return mergeProb;
    }

    public void setMergeProb(double mergeProb) {
        this.mergeProb = mergeProb;
    }

    public double getRebuildProb() {
        return rebuildProb;
    }

    public void setRebuildProb(double rebuildProb) {
        this.rebuildProb = rebuildProb;
    }

    public double getQuarantineRedriveProb() {
        return quarantineRedriveProb;
    }

    public void setQuarantineRedriveProb(double quarantineRedriveProb) {
        this.quarantineRedriveProb = quarantineRedriveProb;
    }

    public long getRebuildMinIntervalMs() {
        return rebuildMinIntervalMs;
    }

    public void setRebuildMinIntervalMs(long rebuildMinIntervalMs) {
        this.rebuildMinIntervalMs = rebuildMinIntervalMs;
    }

    public int getRebuildKbLimit() {
        return rebuildKbLimit;
    }

    public void setRebuildKbLimit(int rebuildKbLimit) {
        this.rebuildKbLimit = rebuildKbLimit;
    }

    public int getRebuildMemoryLimit() {
        return rebuildMemoryLimit;
    }

    public void setRebuildMemoryLimit(int rebuildMemoryLimit) {
        this.rebuildMemoryLimit = rebuildMemoryLimit;
    }

    public boolean isRebuildIncludeKb() {
        return rebuildIncludeKb;
    }

    public void setRebuildIncludeKb(boolean rebuildIncludeKb) {
        this.rebuildIncludeKb = rebuildIncludeKb;
    }
}
