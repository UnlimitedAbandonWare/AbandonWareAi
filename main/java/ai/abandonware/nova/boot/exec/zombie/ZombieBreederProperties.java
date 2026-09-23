package ai.abandonware.nova.boot.exec.zombie;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nova.zombie")
public class ZombieBreederProperties {
    private boolean enabled = false;
    private long detectionWindowMs = 30_000L;
    private int blockedThreadThreshold = 4;
    private int spawnRateThreshold = 8;
    private int maxContainmentPoolSize = 4;
    private long cleanPoolKeepAliveSec = 60L;
    private long drainTimeoutMs = 3_000L;
    private String traceKeyPrefix = "zombie";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getDetectionWindowMs() {
        return detectionWindowMs;
    }

    public void setDetectionWindowMs(long detectionWindowMs) {
        this.detectionWindowMs = detectionWindowMs;
    }

    public int getBlockedThreadThreshold() {
        return blockedThreadThreshold;
    }

    public void setBlockedThreadThreshold(int blockedThreadThreshold) {
        this.blockedThreadThreshold = blockedThreadThreshold;
    }

    public int getSpawnRateThreshold() {
        return spawnRateThreshold;
    }

    public void setSpawnRateThreshold(int spawnRateThreshold) {
        this.spawnRateThreshold = spawnRateThreshold;
    }

    public int getMaxContainmentPoolSize() {
        return maxContainmentPoolSize;
    }

    public void setMaxContainmentPoolSize(int maxContainmentPoolSize) {
        this.maxContainmentPoolSize = maxContainmentPoolSize;
    }

    public long getCleanPoolKeepAliveSec() {
        return cleanPoolKeepAliveSec;
    }

    public void setCleanPoolKeepAliveSec(long cleanPoolKeepAliveSec) {
        this.cleanPoolKeepAliveSec = cleanPoolKeepAliveSec;
    }

    public long getDrainTimeoutMs() {
        return drainTimeoutMs;
    }

    public void setDrainTimeoutMs(long drainTimeoutMs) {
        this.drainTimeoutMs = drainTimeoutMs;
    }

    public String getTraceKeyPrefix() {
        return traceKeyPrefix;
    }

    public void setTraceKeyPrefix(String traceKeyPrefix) {
        this.traceKeyPrefix = traceKeyPrefix;
    }
}
