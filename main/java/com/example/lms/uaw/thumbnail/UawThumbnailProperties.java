package com.example.lms.uaw.thumbnail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * UAW Thumbnail(1-line 요약) 백그라운드 작업 설정.
 *
 * <p>기본값은 "꺼짐"(enabled=false)이며, 켜면 유저 부재 + CPU 여유 조건에서
 * 최근 user 질문을 골라 근거를 수집하고, 1-line 캡션을 생성해 KnowledgeBase에 적재합니다.</p>
 */
@ConfigurationProperties(prefix = "uaw.thumbnail")
public class UawThumbnailProperties {

    /** enable/disable */
    private boolean enabled = false;

    /** scheduler tick (ms) */
    private long tickMs = 300_000L;

    /** 최소 실행 간격 (sec) */
    private long minIntervalSeconds = 900L;

    /** 일 실행 최대 횟수 */
    private int maxRunsPerDay = 48;

    /** base backoff (sec) */
    private long baseBackoffSeconds = 900L;

    /** max backoff (sec) */
    private long maxBackoffSeconds = 6 * 3600L;

    /** user absent일 때만 실행: CPU 임계치(0.0~1.0) */
    private double idleCpuThreshold = 0.70;

    /** 후보 토픽 검색 범위 (최근 user 메시지 N개) */
    private int candidateLookback = 50;

    /** topic 최대 길이 (chars). 길면 앞부분을 잘라서 key로 사용 */
    private int maxTopicChars = 160;

    /** classpath plan id (plans/<planId>.yaml) */
    private String planId = "UAW_thumbnail.v1";

    /** KnowledgeBase domain */
    private String knowledgeDomain = "UAW_THUMB";

    /**
     * When enabled, the retrieval pipeline will try to recall previously generated UAW thumbnails
     * (stored in the knowledge base / vector store) as a high-priority RAG source.
     */
    private boolean recallEnabled = true;

    /**
     * How many thumbnail knowledge snippets to recall at most.
     */
    private int recallTopK = 3;

    /**
     * Minimum semantic similarity score for a thumbnail snippet to be recalled.
     */
    private double recallMinScore = 0.62;

    /**
     * How many candidates to pull from the vector store before filtering to the thumbnail domain.
     */
    private int recallPoolK = 40;

    /** KnowledgeBase entityType */
    private String entityType = "THUMBNAIL";

    /** plan/LLM confidence가 이것보다 낮으면 저장하지 않음 */
    private double minConfidence = 0.55;

    /** budget/backoff 상태 파일 */
    private String statePath = "data/uaw/thumbnail_state.json";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTickMs() {
        return tickMs;
    }

    public void setTickMs(long tickMs) {
        this.tickMs = tickMs;
    }

    public long getMinIntervalSeconds() {
        return minIntervalSeconds;
    }

    public void setMinIntervalSeconds(long minIntervalSeconds) {
        this.minIntervalSeconds = minIntervalSeconds;
    }

    public int getMaxRunsPerDay() {
        return maxRunsPerDay;
    }

    public void setMaxRunsPerDay(int maxRunsPerDay) {
        this.maxRunsPerDay = maxRunsPerDay;
    }

    public long getBaseBackoffSeconds() {
        return baseBackoffSeconds;
    }

    public void setBaseBackoffSeconds(long baseBackoffSeconds) {
        this.baseBackoffSeconds = baseBackoffSeconds;
    }

    public long getMaxBackoffSeconds() {
        return maxBackoffSeconds;
    }

    public void setMaxBackoffSeconds(long maxBackoffSeconds) {
        this.maxBackoffSeconds = maxBackoffSeconds;
    }

    public double getIdleCpuThreshold() {
        return idleCpuThreshold;
    }

    public void setIdleCpuThreshold(double idleCpuThreshold) {
        this.idleCpuThreshold = idleCpuThreshold;
    }

    public int getCandidateLookback() {
        return candidateLookback;
    }

    public void setCandidateLookback(int candidateLookback) {
        this.candidateLookback = candidateLookback;
    }

    public int getMaxTopicChars() {
        return maxTopicChars;
    }

    public void setMaxTopicChars(int maxTopicChars) {
        this.maxTopicChars = maxTopicChars;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getKnowledgeDomain() {
        return knowledgeDomain;
    }

    public void setKnowledgeDomain(String knowledgeDomain) {
        this.knowledgeDomain = knowledgeDomain;
    }

    public boolean isRecallEnabled() {
        return recallEnabled;
    }

    public void setRecallEnabled(boolean recallEnabled) {
        this.recallEnabled = recallEnabled;
    }

    public int getRecallTopK() {
        return recallTopK;
    }

    public void setRecallTopK(int recallTopK) {
        this.recallTopK = recallTopK;
    }

    public double getRecallMinScore() {
        return recallMinScore;
    }

    public void setRecallMinScore(double recallMinScore) {
        this.recallMinScore = recallMinScore;
    }

    public int getRecallPoolK() {
        return recallPoolK;
    }

    public void setRecallPoolK(int recallPoolK) {
        this.recallPoolK = recallPoolK;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public String getStatePath() {
        return statePath;
    }

    public void setStatePath(String statePath) {
        this.statePath = statePath;
    }
}
