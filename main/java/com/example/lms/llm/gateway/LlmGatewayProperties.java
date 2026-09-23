package com.example.lms.llm.gateway;

import com.example.lms.llm.ModelRuntimeHealthTracker;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llm.gateway")
public class LlmGatewayProperties {

    public enum Enforcement {
        OBSERVE,
        ENFORCE
    }

    private boolean enabled = true;
    private Enforcement enforcement = Enforcement.OBSERVE;
    private int minRouteScore = 55;
    private boolean throwOnEmptyFailure = false;
    private Probe probe = new Probe();
    private Cloud cloud = new Cloud();
    private LocalDeviceFailover localDeviceFailover = new LocalDeviceFailover();
    private SpecRegistry specRegistry = new SpecRegistry();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Enforcement getEnforcement() {
        return enforcement;
    }

    public void setEnforcement(Enforcement enforcement) {
        this.enforcement = enforcement == null ? Enforcement.OBSERVE : enforcement;
    }

    public boolean isEnforce() {
        return enabled && enforcement == Enforcement.ENFORCE;
    }

    public int getMinRouteScore() {
        return minRouteScore;
    }

    public void setMinRouteScore(int minRouteScore) {
        this.minRouteScore = minRouteScore;
    }

    public boolean isThrowOnEmptyFailure() {
        return throwOnEmptyFailure;
    }

    public void setThrowOnEmptyFailure(boolean throwOnEmptyFailure) {
        this.throwOnEmptyFailure = throwOnEmptyFailure;
    }

    public Probe getProbe() {
        return probe;
    }

    public void setProbe(Probe probe) {
        this.probe = probe == null ? new Probe() : probe;
    }

    public Cloud getCloud() {
        return cloud;
    }

    public void setCloud(Cloud cloud) {
        this.cloud = cloud == null ? new Cloud() : cloud;
    }

    public LocalDeviceFailover getLocalDeviceFailover() {
        return localDeviceFailover;
    }

    public void setLocalDeviceFailover(LocalDeviceFailover localDeviceFailover) {
        this.localDeviceFailover = localDeviceFailover == null
                ? new LocalDeviceFailover()
                : localDeviceFailover;
    }

    public SpecRegistry getSpecRegistry() {
        return specRegistry;
    }

    public void setSpecRegistry(SpecRegistry specRegistry) {
        this.specRegistry = specRegistry == null ? new SpecRegistry() : specRegistry;
    }

    public static class Probe {
        private boolean enabled = true;
        private long timeoutMs = 1500L;
        private long ttlMs = 30000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getTtlMs() {
            return ttlMs;
        }

        public void setTtlMs(long ttlMs) {
            this.ttlMs = ttlMs;
        }
    }

    public static class Cloud {
        private boolean enabled = false;
        private String routeKey = "api3";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRouteKey() {
            return routeKey;
        }

        public void setRouteKey(String routeKey) {
            this.routeKey = routeKey;
        }
    }

    public static class LocalDeviceFailover {
        private boolean enabled = false;
        private Enforcement enforcement = Enforcement.OBSERVE;
        private long hardCooldownMs = 600_000L;
        private long runnerFailureWindowMs = 60_000L;
        private int runnerFailureThreshold = 2;
        private int recoverySuccesses = 2;
        private int maxEndpoints = 16;
        private int transientFailureThreshold = 3;
        private long transientCooldownMs = 60_000L;
        private long recoveryStableMs = 30_000L;
        private long probeTimeoutMs = 60_000L;
        private long healthSampleIntervalMs = 10_000L;

        public int getTransientFailureThreshold() { return transientFailureThreshold; }
        public void setTransientFailureThreshold(int value) { transientFailureThreshold = Math.max(1, value); }
        public long getTransientCooldownMs() { return transientCooldownMs; }
        public void setTransientCooldownMs(long value) { transientCooldownMs = Math.max(1, value); }
        public long getRecoveryStableMs() { return recoveryStableMs; }
        public void setRecoveryStableMs(long value) { recoveryStableMs = Math.max(0, value); }
        public long getProbeTimeoutMs() { return probeTimeoutMs; }
        public void setProbeTimeoutMs(long value) { probeTimeoutMs = Math.max(1, value); }
        public long getHealthSampleIntervalMs() { return healthSampleIntervalMs; }
        public void setHealthSampleIntervalMs(long value) { healthSampleIntervalMs = Math.max(0, value); }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Enforcement getEnforcement() {
            return enforcement;
        }

        public void setEnforcement(Enforcement enforcement) {
            this.enforcement = enforcement == null ? Enforcement.OBSERVE : enforcement;
        }

        public boolean isEnforce() {
            return enabled && enforcement == Enforcement.ENFORCE;
        }

        public long getHardCooldownMs() {
            return hardCooldownMs;
        }

        public void setHardCooldownMs(long hardCooldownMs) {
            this.hardCooldownMs = Math.max(1L, hardCooldownMs);
        }

        public long getRunnerFailureWindowMs() {
            return runnerFailureWindowMs;
        }

        public void setRunnerFailureWindowMs(long runnerFailureWindowMs) {
            this.runnerFailureWindowMs = Math.max(1L, runnerFailureWindowMs);
        }

        public int getRunnerFailureThreshold() {
            return runnerFailureThreshold;
        }

        public void setRunnerFailureThreshold(int runnerFailureThreshold) {
            this.runnerFailureThreshold = Math.max(1, runnerFailureThreshold);
        }

        public int getRecoverySuccesses() {
            return recoverySuccesses;
        }

        public void setRecoverySuccesses(int recoverySuccesses) {
            this.recoverySuccesses = Math.max(1, recoverySuccesses);
        }

        public int getMaxEndpoints() {
            return maxEndpoints;
        }

        public void setMaxEndpoints(int maxEndpoints) {
            this.maxEndpoints = Math.max(1, Math.min(256, maxEndpoints));
        }

        public ModelRuntimeHealthTracker.EndpointQuarantinePolicy toEndpointQuarantinePolicy() {
            return new ModelRuntimeHealthTracker.EndpointQuarantinePolicy(
                    enabled,
                    isEnforce(),
                    hardCooldownMs,
                    runnerFailureWindowMs,
                    runnerFailureThreshold,
                    recoverySuccesses,
                    maxEndpoints,
                    transientFailureThreshold,
                    transientCooldownMs,
                    recoveryStableMs,
                    probeTimeoutMs,
                    healthSampleIntervalMs);
        }
    }

    public static class SpecRegistry {
        private boolean enabled = true;
        private String path = "./data/model-spec-registry.json";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }
}
