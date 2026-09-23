package com.example.lms.service.rag.burst;


@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "extremez")
public class ExtremeZProperties {
    private int enabled = 1;
    private int minBaseDocs = 3;
    private double minAuthority = 0.45d;
    private double contradictionThreshold = 0.60d;
    private int maxSubQueries = 12;
    private int maxMergedDocs = 24;
    private long parallelTimeoutMs = 2_500L;

    public int getEnabled(){ return enabled; }
    public void setEnabled(int v){ this.enabled = v; }

    public int getMinBaseDocs() {
        return minBaseDocs;
    }

    public void setMinBaseDocs(int minBaseDocs) {
        this.minBaseDocs = Math.max(1, minBaseDocs);
    }

    public double getMinAuthority() {
        return minAuthority;
    }

    public void setMinAuthority(double minAuthority) {
        this.minAuthority = clamp01(minAuthority);
    }

    public double getContradictionThreshold() {
        return contradictionThreshold;
    }

    public void setContradictionThreshold(double contradictionThreshold) {
        this.contradictionThreshold = clamp01(contradictionThreshold);
    }

    public boolean isEnabledFlag() {
        return enabled > 0;
    }

    public int getMaxSubQueries() {
        return maxSubQueries;
    }

    public void setMaxSubQueries(int maxSubQueries) {
        this.maxSubQueries = Math.max(1, Math.min(32, maxSubQueries));
    }

    public int getMaxMergedDocs() {
        return maxMergedDocs;
    }

    public void setMaxMergedDocs(int maxMergedDocs) {
        this.maxMergedDocs = Math.max(1, Math.min(64, maxMergedDocs));
    }

    public long getParallelTimeoutMs() {
        return parallelTimeoutMs;
    }

    public void setParallelTimeoutMs(long parallelTimeoutMs) {
        this.parallelTimeoutMs = Math.max(1L, Math.min(30_000L, parallelTimeoutMs));
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
