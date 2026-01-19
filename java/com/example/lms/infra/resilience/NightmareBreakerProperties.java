package com.example.lms.infra.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "nightmare.breaker")
public class NightmareBreakerProperties {

    private boolean enabled = true;
    private Duration openDuration = Duration.ofSeconds(30);

    /**
     * Open duration override for CONFIG-type failures (ex: missing model).
     *
     * <p>Rationale: configuration errors are non-transient; keep the breaker OPEN longer
     * to avoid retry storms and log spam.</p>
     */
    private Duration configOpenDuration = Duration.ofMinutes(5);

    /**
     * Whether RATE_LIMIT (ex: HTTP 429) should also count towards the generic failure threshold.
     *
     * <p>If false (recommended), 429 is treated as a backpressure / "slow down" signal and only trips
     * the rate-limit threshold, avoiding breaker-poisoning where 429 inflates failure-threshold.</p>
     */
    private boolean rateLimitCountsAsFailure = false;

    /**
     * Whether TIMEOUT should also count towards the generic failure threshold.
     *
     * <p>If false (recommended), timeouts are handled via timeout-threshold and timeout-specific backoff,
     * avoiding breaker-poisoning where transient timeouts inflate failure-threshold.</p>
     */
    private boolean timeoutCountsAsFailure = false;

    /** Base open duration when tripped by RATE_LIMIT (HTTP 429). */
    private Duration rateLimitOpenDuration = Duration.ofSeconds(2);

    /** Max open duration cap when applying exponential backoff to RATE_LIMIT. */
    private Duration rateLimitMaxOpenDuration = Duration.ofSeconds(30);

    /** Base open duration when tripped by TIMEOUT. */
    private Duration timeoutOpenDuration = Duration.ofSeconds(5);

    /** Max open duration cap when applying exponential backoff to TIMEOUT. */
    private Duration timeoutMaxOpenDuration = Duration.ofSeconds(30);

    /** Exponential backoff base (e.g., 2.0 = double each trip) for RATE_LIMIT/TIMEOUT open durations. */
    private double backoffBase = 2.0d;

    private int failureThreshold = 3;
    private int timeoutThreshold = 2;
    private int rateLimitThreshold = 1;
    private int rejectedThreshold = 1;
    private int interruptThreshold = 1;
    /**
     * Whether INTERRUPTED/CANCELLED should trip the breaker.
     *
     * <p>Default: false. Interrupts are frequently local cancellation/teardown signals
     * (e.g., time budget enforcement, request cancellation) and should not poison
     * breaker state.</p>
     */
    private boolean tripOnInterrupt = false;
    private boolean tripOnBlank = true;
    private int blankThreshold = 2;
    private int maxContextChars = 240;
    private boolean logStackTrace = false;

    /**
     * (UAW: Anti-Fragile) 예외가 아니어도 "응답이 너무 느리면" open 시켜 sidetrain(우회)하도록.
     * - 로컬 LLM 다운/과부하 시 timeout 이전에 오케스트레이션이 계속 지연되는 문제 완화
     */
    private boolean tripOnSlowCall = true;
    private long slowCallThresholdMs = 2500;
    private int slowCallThreshold = 3;

    /** (UAW: FriendShield) 정상처럼 보이는 실패(회피/정보없음)를 silent failure로 누적해서 open */
    private boolean tripOnSilentFailure = true;
    private int silentFailureThreshold = 2;

    // HALF_OPEN (OPEN → HALF_OPEN → CLOSED)
    private boolean halfOpenEnabled = true;
    /** HALF_OPEN에서 허용할 최대 시도 횟수 (0 이하이면 제한 없음) */
    private int halfOpenMaxCalls = 1;
    /** HALF_OPEN → CLOSED 전환에 필요한 연속 성공 수 */
    private int halfOpenSuccessThreshold = 1;

    /**
     * Key별 오버라이드(옵션 모듈은 더 관대하게 등).
     * <p>
     * 예) pattern="query-transformer:*", weight=0.5 → 임계값이 threshold/0.5로 커져 더 관대해진다.
     */
    private List<KeyOverride> keyOverrides = new ArrayList<>();

    public static class KeyOverride {
        private String pattern;
        /** 1.0=기본, 0.5=임계값 2배로 관대(=threshold/weight) */
        private Double weight = 1.0;
        private Duration openDuration;
        private Integer failureThreshold;
        private Integer timeoutThreshold;
        private Integer rateLimitThreshold;
        private Integer rejectedThreshold;
        private Integer interruptThreshold;
        private Boolean tripOnBlank;
        private Integer blankThreshold;
        private Boolean tripOnSlowCall;
        private Long slowCallThresholdMs;
        private Integer slowCallThreshold;
        private Boolean tripOnSilentFailure;
        private Integer silentFailureThreshold;

        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
        public Double getWeight() { return weight; }
        public void setWeight(Double weight) { this.weight = weight; }
        public Duration getOpenDuration() { return openDuration; }
        public void setOpenDuration(Duration openDuration) { this.openDuration = openDuration; }
        public Integer getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(Integer failureThreshold) { this.failureThreshold = failureThreshold; }
        public Integer getTimeoutThreshold() { return timeoutThreshold; }
        public void setTimeoutThreshold(Integer timeoutThreshold) { this.timeoutThreshold = timeoutThreshold; }
        public Integer getRateLimitThreshold() { return rateLimitThreshold; }
        public void setRateLimitThreshold(Integer rateLimitThreshold) { this.rateLimitThreshold = rateLimitThreshold; }
        public Integer getRejectedThreshold() { return rejectedThreshold; }
        public void setRejectedThreshold(Integer rejectedThreshold) { this.rejectedThreshold = rejectedThreshold; }
        public Integer getInterruptThreshold() { return interruptThreshold; }
        public void setInterruptThreshold(Integer interruptThreshold) { this.interruptThreshold = interruptThreshold; }
        public Boolean getTripOnBlank() { return tripOnBlank; }
        public void setTripOnBlank(Boolean tripOnBlank) { this.tripOnBlank = tripOnBlank; }
        public Integer getBlankThreshold() { return blankThreshold; }
        public void setBlankThreshold(Integer blankThreshold) { this.blankThreshold = blankThreshold; }
        public Boolean getTripOnSlowCall() { return tripOnSlowCall; }
        public void setTripOnSlowCall(Boolean tripOnSlowCall) { this.tripOnSlowCall = tripOnSlowCall; }
        public Long getSlowCallThresholdMs() { return slowCallThresholdMs; }
        public void setSlowCallThresholdMs(Long slowCallThresholdMs) { this.slowCallThresholdMs = slowCallThresholdMs; }
        public Integer getSlowCallThreshold() { return slowCallThreshold; }
        public void setSlowCallThreshold(Integer slowCallThreshold) { this.slowCallThreshold = slowCallThreshold; }
        public Boolean getTripOnSilentFailure() { return tripOnSilentFailure; }
        public void setTripOnSilentFailure(Boolean tripOnSilentFailure) { this.tripOnSilentFailure = tripOnSilentFailure; }
        public Integer getSilentFailureThreshold() { return silentFailureThreshold; }
        public void setSilentFailureThreshold(Integer silentFailureThreshold) { this.silentFailureThreshold = silentFailureThreshold; }
    }

    public record EffectivePolicy(
            Duration openDuration,
            int failureThreshold,
            int timeoutThreshold,
            int rateLimitThreshold,
            int rejectedThreshold,
            int interruptThreshold,
            boolean tripOnBlank,
            int blankThreshold,
            boolean tripOnSlowCall,
            long slowCallThresholdMs,
            int slowCallThreshold,
            boolean tripOnSilentFailure,
            int silentFailureThreshold,
            int maxContextChars,
            boolean logStackTrace,
            boolean halfOpenEnabled,
            int halfOpenMaxCalls,
            int halfOpenSuccessThreshold
    ) {}

    /** Key별 EffectivePolicy를 계산한다. */
    public EffectivePolicy policyFor(String key) {
        KeyOverride ov = match(key);
        double w = (ov != null && ov.getWeight() != null) ? ov.getWeight() : 1.0d;

        Duration od = (ov != null && ov.getOpenDuration() != null) ? ov.getOpenDuration() : this.openDuration;

        int ft = scale((ov != null && ov.getFailureThreshold() != null) ? ov.getFailureThreshold() : this.failureThreshold, w);
        int tt = scale((ov != null && ov.getTimeoutThreshold() != null) ? ov.getTimeoutThreshold() : this.timeoutThreshold, w);
        int rt = scale((ov != null && ov.getRateLimitThreshold() != null) ? ov.getRateLimitThreshold() : this.rateLimitThreshold, w);
        int rjt = scale((ov != null && ov.getRejectedThreshold() != null) ? ov.getRejectedThreshold() : this.rejectedThreshold, w);
        int it = scale((ov != null && ov.getInterruptThreshold() != null) ? ov.getInterruptThreshold() : this.interruptThreshold, w);

        boolean tob = (ov != null && ov.getTripOnBlank() != null) ? ov.getTripOnBlank() : this.tripOnBlank;
        int bt = scale((ov != null && ov.getBlankThreshold() != null) ? ov.getBlankThreshold() : this.blankThreshold, w);

        boolean tos = (ov != null && ov.getTripOnSlowCall() != null) ? ov.getTripOnSlowCall() : this.tripOnSlowCall;
        long sctMs = (ov != null && ov.getSlowCallThresholdMs() != null) ? ov.getSlowCallThresholdMs() : this.slowCallThresholdMs;
        int sct = scale((ov != null && ov.getSlowCallThreshold() != null) ? ov.getSlowCallThreshold() : this.slowCallThreshold, w);

        boolean tosf = (ov != null && ov.getTripOnSilentFailure() != null) ? ov.getTripOnSilentFailure() : this.tripOnSilentFailure;
        int sft = scale((ov != null && ov.getSilentFailureThreshold() != null) ? ov.getSilentFailureThreshold() : this.silentFailureThreshold, w);

        return new EffectivePolicy(
                od,
                ft,
                tt,
                rt,
                rjt,
                it,
                tob,
                bt,
                tos,
                sctMs,
                sct,
                tosf,
                sft,
                this.maxContextChars,
                this.logStackTrace,
                this.halfOpenEnabled,
                this.halfOpenMaxCalls,
                this.halfOpenSuccessThreshold
        );
    }

    private int scale(int threshold, double weight) {
        if (threshold <= 0) return 1;
        if (weight <= 0.0d || weight == 1.0d) return threshold;
        // weight < 1.0 → 더 관대: threshold / weight
        return Math.max(1, (int) Math.ceil(threshold / weight));
    }

    private KeyOverride match(String key) {
        if (key == null || keyOverrides == null || keyOverrides.isEmpty()) return null;
        for (KeyOverride ov : keyOverrides) {
            if (ov == null || ov.getPattern() == null) continue;
            String p = ov.getPattern().trim();
            if (p.isEmpty()) continue;
            if ("*".equals(p)) return ov;
            if (p.endsWith("*")) {
                String prefix = p.substring(0, p.length() - 1);
                if (key.startsWith(prefix)) return ov;
            } else if (key.equals(p)) {
                return ov;
            }
        }
        return null;
    }

    public List<KeyOverride> getKeyOverrides() {
        return keyOverrides;
    }

    public void setKeyOverrides(List<KeyOverride> keyOverrides) {
        this.keyOverrides = (keyOverrides == null) ? new ArrayList<>() : keyOverrides;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getOpenDuration() {
        return openDuration;
    }

    public void setOpenDuration(Duration openDuration) {
        this.openDuration = openDuration;
    }

    public Duration getConfigOpenDuration() {
        return configOpenDuration;
    }

    public void setConfigOpenDuration(Duration configOpenDuration) {
        this.configOpenDuration = configOpenDuration;
    }


    public boolean isRateLimitCountsAsFailure() {
        return rateLimitCountsAsFailure;
    }

    public void setRateLimitCountsAsFailure(boolean rateLimitCountsAsFailure) {
        this.rateLimitCountsAsFailure = rateLimitCountsAsFailure;
    }

    public boolean isTimeoutCountsAsFailure() {
        return timeoutCountsAsFailure;
    }

    public void setTimeoutCountsAsFailure(boolean timeoutCountsAsFailure) {
        this.timeoutCountsAsFailure = timeoutCountsAsFailure;
    }

    public Duration getRateLimitOpenDuration() {
        return rateLimitOpenDuration;
    }

    public void setRateLimitOpenDuration(Duration rateLimitOpenDuration) {
        this.rateLimitOpenDuration = rateLimitOpenDuration;
    }

    public Duration getRateLimitMaxOpenDuration() {
        return rateLimitMaxOpenDuration;
    }

    public void setRateLimitMaxOpenDuration(Duration rateLimitMaxOpenDuration) {
        this.rateLimitMaxOpenDuration = rateLimitMaxOpenDuration;
    }

    public Duration getTimeoutOpenDuration() {
        return timeoutOpenDuration;
    }

    public void setTimeoutOpenDuration(Duration timeoutOpenDuration) {
        this.timeoutOpenDuration = timeoutOpenDuration;
    }

    public Duration getTimeoutMaxOpenDuration() {
        return timeoutMaxOpenDuration;
    }

    public void setTimeoutMaxOpenDuration(Duration timeoutMaxOpenDuration) {
        this.timeoutMaxOpenDuration = timeoutMaxOpenDuration;
    }

    public double getBackoffBase() {
        return backoffBase;
    }

    public void setBackoffBase(double backoffBase) {
        this.backoffBase = backoffBase;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public int getTimeoutThreshold() {
        return timeoutThreshold;
    }

    public void setTimeoutThreshold(int timeoutThreshold) {
        this.timeoutThreshold = timeoutThreshold;
    }

    public int getRateLimitThreshold() {
        return rateLimitThreshold;
    }

    public void setRateLimitThreshold(int rateLimitThreshold) {
        this.rateLimitThreshold = rateLimitThreshold;
    }

    public int getRejectedThreshold() {
        return rejectedThreshold;
    }

    public void setRejectedThreshold(int rejectedThreshold) {
        this.rejectedThreshold = rejectedThreshold;
    }

    public int getInterruptThreshold() {
        return interruptThreshold;
    }

    public void setInterruptThreshold(int interruptThreshold) {
        this.interruptThreshold = interruptThreshold;
    }

    public boolean isTripOnInterrupt() {
        return tripOnInterrupt;
    }

    public void setTripOnInterrupt(boolean tripOnInterrupt) {
        this.tripOnInterrupt = tripOnInterrupt;
    }

    public boolean isTripOnBlank() {
        return tripOnBlank;
    }

    public void setTripOnBlank(boolean tripOnBlank) {
        this.tripOnBlank = tripOnBlank;
    }

    public int getBlankThreshold() {
        return blankThreshold;
    }

    public void setBlankThreshold(int blankThreshold) {
        this.blankThreshold = blankThreshold;
    }

    public int getMaxContextChars() {
        return maxContextChars;
    }

    public void setMaxContextChars(int maxContextChars) {
        this.maxContextChars = maxContextChars;
    }

    public boolean isLogStackTrace() {
        return logStackTrace;
    }

    public void setLogStackTrace(boolean logStackTrace) {
        this.logStackTrace = logStackTrace;
    }

    public boolean isTripOnSlowCall() {
        return tripOnSlowCall;
    }

    public void setTripOnSlowCall(boolean tripOnSlowCall) {
        this.tripOnSlowCall = tripOnSlowCall;
    }

    public long getSlowCallThresholdMs() {
        return slowCallThresholdMs;
    }

    public void setSlowCallThresholdMs(long slowCallThresholdMs) {
        this.slowCallThresholdMs = slowCallThresholdMs;
    }

    public int getSlowCallThreshold() {
        return slowCallThreshold;
    }

    public void setSlowCallThreshold(int slowCallThreshold) {
        this.slowCallThreshold = slowCallThreshold;
    }

    public boolean isTripOnSilentFailure() {
        return tripOnSilentFailure;
    }

    public void setTripOnSilentFailure(boolean tripOnSilentFailure) {
        this.tripOnSilentFailure = tripOnSilentFailure;
    }

    public int getSilentFailureThreshold() {
        return silentFailureThreshold;
    }

    public void setSilentFailureThreshold(int silentFailureThreshold) {
        this.silentFailureThreshold = silentFailureThreshold;
    }

    public boolean isHalfOpenEnabled() {
        return halfOpenEnabled;
    }

    public void setHalfOpenEnabled(boolean halfOpenEnabled) {
        this.halfOpenEnabled = halfOpenEnabled;
    }

    public int getHalfOpenMaxCalls() {
        return halfOpenMaxCalls;
    }

    public void setHalfOpenMaxCalls(int halfOpenMaxCalls) {
        this.halfOpenMaxCalls = halfOpenMaxCalls;
    }

    public int getHalfOpenSuccessThreshold() {
        return halfOpenSuccessThreshold;
    }

    public void setHalfOpenSuccessThreshold(int halfOpenSuccessThreshold) {
        this.halfOpenSuccessThreshold = halfOpenSuccessThreshold;
    }
}
