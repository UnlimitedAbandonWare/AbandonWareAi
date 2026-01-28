package com.example.lms.service.rag.learn;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * CFVM 기반 TopK/KAllocation 온라인 튜닝 설정.
 *
 * <p>UAW.txt에서 제안한 "CFVM 매트릭스(9-tile)" 개념을 최소 단위로 구현해,
 * KAllocator의 (web/vector/kg) TopK 분배를 온라인(밴딧) 방식으로 조절하는 뼈대다.
 *
 * <p>기본값은 <b>비활성화</b>이며, 활성화 시에도 fail-soft를 유지한다.
 */
@Validated
@ConfigurationProperties(prefix = "retrieval.kalloc.cfvm")
public class CfvmKallocLearningProperties {

    /** 전체 스위치 */
    private boolean enabled = false;

    /** epsilon-greedy 탐험 확률 */
    private double epsilon = 0.05;

    /** UCB 탐험 계수(보너스) */
    private double ucbC = 1.4;

    /** 밴딧 상태 저장 파일(상대/절대 경로 모두 허용) */
    private String storePath = "cfvm-raw/records/kalloc_bandit.json";

    /** flush 최소 간격(ms). 빈번한 I/O 방지용 */
    private long flushIntervalMs = 30_000L;

    /** 실패 패턴 쿨다운 시 강제로 해당 소스 TopK를 줄이는 안전장치 */
    private boolean overrideOnCooldown = true;

    /** 쿼리 복잡도에 따라 totalK를 스케일링할지 */
    private boolean scaleTotalKByComplexity = true;

    /** SIMPLE 쿼리 totalK 비율 */
    private double simpleScale = 0.55;

    /** AMBIGUOUS 쿼리 totalK 비율 */
    private double ambiguousScale = 0.80;

    /** COMPLEX 쿼리 totalK 비율 */
    private double complexScale = 1.00;

    // === Reward Model (학습 신호) ===

    /** 문서 증가량 보상 스케일(크면 완만, 작으면 민감) */
    private double docRewardScale = 8.0;

    /** latency budget(ms). 이 이상이면 패널티가 1.0에 수렴 */
    private long latencyBudgetMs = 2_000L;

    /** latency 패널티 가중치 */
    private double latencyPenaltyWeight = 0.25;

    /** failure 패널티 가중치 */
    private double failurePenaltyWeight = 0.40;

    /** authorityAvg(0..1) 보너스 가중치 */
    private double authorityWeight = 0.15;

    /** coverageScore(0..1) 보너스 가중치 */
    private double coverageWeight = 0.10;

    /** duplicateRatio(0..1) 패널티 가중치 */
    private double duplicatePenaltyWeight = 0.10;

    /** needleContribution(0..1) 보너스 가중치 */
    private double needleContributionWeight = 0.20;

    /** reward 하한 */
    private double minReward = -1.0;

    /** reward 상한 */
    private double maxReward = 1.0;

    // --- Needle contribution reward shaping ---
    // "Needle" is a 2nd-pass probe that can inject additional authoritative web evidence.
    // We expose its contribution (needle.keptRatio) to the bandit as a small reward term so
    // arms that better utilize needle improve over time.
    private boolean needleRewardEnabled = true;

    /** Reward weight applied to (keptRatio - baseline). */
    private double needleRewardWeight = 0.15;

    /** Baseline contribution ratio to treat as neutral (no bonus/penalty). */
    private double needleRewardBaseline = 0.0;

    /** Hard cap for the needle bonus/penalty magnitude (safety clamp). */
    private double needleRewardCap = 0.2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getEpsilon() {
        return epsilon;
    }

    public void setEpsilon(double epsilon) {
        this.epsilon = epsilon;
    }

    public double getUcbC() {
        return ucbC;
    }

    public void setUcbC(double ucbC) {
        this.ucbC = ucbC;
    }

    public String getStorePath() {
        return storePath;
    }

    public void setStorePath(String storePath) {
        this.storePath = storePath;
    }

    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    public boolean isOverrideOnCooldown() {
        return overrideOnCooldown;
    }

    public void setOverrideOnCooldown(boolean overrideOnCooldown) {
        this.overrideOnCooldown = overrideOnCooldown;
    }

    public boolean isScaleTotalKByComplexity() {
        return scaleTotalKByComplexity;
    }

    public void setScaleTotalKByComplexity(boolean scaleTotalKByComplexity) {
        this.scaleTotalKByComplexity = scaleTotalKByComplexity;
    }

    public double getSimpleScale() {
        return simpleScale;
    }

    public void setSimpleScale(double simpleScale) {
        this.simpleScale = simpleScale;
    }

    public double getAmbiguousScale() {
        return ambiguousScale;
    }

    public void setAmbiguousScale(double ambiguousScale) {
        this.ambiguousScale = ambiguousScale;
    }

    public double getComplexScale() {
        return complexScale;
    }

    public void setComplexScale(double complexScale) {
        this.complexScale = complexScale;
    }

    public double getDocRewardScale() {
        return docRewardScale;
    }

    public void setDocRewardScale(double docRewardScale) {
        this.docRewardScale = docRewardScale;
    }

    public long getLatencyBudgetMs() {
        return latencyBudgetMs;
    }

    public void setLatencyBudgetMs(long latencyBudgetMs) {
        this.latencyBudgetMs = latencyBudgetMs;
    }

    public double getLatencyPenaltyWeight() {
        return latencyPenaltyWeight;
    }

    public void setLatencyPenaltyWeight(double latencyPenaltyWeight) {
        this.latencyPenaltyWeight = latencyPenaltyWeight;
    }

    public double getFailurePenaltyWeight() {
        return failurePenaltyWeight;
    }

    public void setFailurePenaltyWeight(double failurePenaltyWeight) {
        this.failurePenaltyWeight = failurePenaltyWeight;
    }

    public double getAuthorityWeight() {
        return authorityWeight;
    }

    public void setAuthorityWeight(double authorityWeight) {
        this.authorityWeight = authorityWeight;
    }

    public double getCoverageWeight() {
        return coverageWeight;
    }

    public void setCoverageWeight(double coverageWeight) {
        this.coverageWeight = coverageWeight;
    }

    public double getDuplicatePenaltyWeight() {
        return duplicatePenaltyWeight;
    }

    public void setDuplicatePenaltyWeight(double duplicatePenaltyWeight) {
        this.duplicatePenaltyWeight = duplicatePenaltyWeight;
    }

    public double getNeedleContributionWeight() {
        return needleContributionWeight;
    }

    public void setNeedleContributionWeight(double needleContributionWeight) {
        this.needleContributionWeight = needleContributionWeight;
    }

    public double getMinReward() {
        return minReward;
    }

    public void setMinReward(double minReward) {
        this.minReward = minReward;
    }

    public double getMaxReward() {
        return maxReward;
    }

    public void setMaxReward(double maxReward) {
        this.maxReward = maxReward;
    }

    public boolean isNeedleRewardEnabled() {
        return needleRewardEnabled;
    }

    public void setNeedleRewardEnabled(boolean needleRewardEnabled) {
        this.needleRewardEnabled = needleRewardEnabled;
    }

    public double getNeedleRewardWeight() {
        return needleRewardWeight;
    }

    public void setNeedleRewardWeight(double needleRewardWeight) {
        this.needleRewardWeight = needleRewardWeight;
    }

    public double getNeedleRewardBaseline() {
        return needleRewardBaseline;
    }

    public void setNeedleRewardBaseline(double needleRewardBaseline) {
        this.needleRewardBaseline = needleRewardBaseline;
    }

    public double getNeedleRewardCap() {
        return needleRewardCap;
    }

    public void setNeedleRewardCap(double needleRewardCap) {
        this.needleRewardCap = needleRewardCap;
    }
}
