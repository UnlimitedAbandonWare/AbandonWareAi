package com.example.lms.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.ToString;
import org.hibernate.annotations.UpdateTimestamp;
import org.apache.commons.codec.digest.DigestUtils;
import java.time.LocalDateTime;


// import org.hibernate.annotations.CreationTimestamp; // (선택) Hibernate @CreationTimestamp 사용 가능


@Entity
@Table(
        name = "translation_memory",
        indexes = {
                @Index(name = "idx_tm_session",     columnList = "session_id"),
                // [추가] 볼츠만 강화 검색 최적화를 위해 에너지 컬럼에 인덱스 추가
                @Index(name = "idx_tm_energy",      columnList = "energy"),
                @Index(name = "idx_tm_last_used_at", columnList = "last_used_at"), //  최근 사용 정렬 최적화
                // [PENDING→ACTIVE] promote scan optimization
                // - PendingMemorySoakScheduler queries: status = PENDING ORDER BY created_at LIMIT N
                // - Composite index avoids full scan + filesort on write-heavy tables.
                @Index(name = "idx_tm_status_created_at", columnList = "status, created_at"),
                // [Lease] multi-instance claim support
                @Index(name = "idx_tm_status_locked_at", columnList = "status, locked_at")
        }
)
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class TranslationMemory {

    /* ===== PK & 버전 ===== */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    /* ===== 핵심 키 ===== */
    @Column(name = "source_hash", unique = true, nullable = false, length = 64)
    private String sourceHash;

    /* ===== 카운터/타임스탬프 ===== */
    @Column(name = "hit_count", nullable = false)
    @Builder.Default
    private Integer hitCount = 0;

    @Column(name = "created_at", nullable = false)
    // @CreationTimestamp //  Hibernate 사용 시 자동 생성시간 주입(선택) :contentReference[oaicite:0]{index=0}
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Multi-instance lease fields for PENDING→ACTIVE promotion.
     *
     * <p>
     * When multiple schedulers run concurrently, rows are claimed by setting
     * locked_at/locked_by. Expired leases may be reclaimed.
     * </p>
     */
    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_by", length = 64)
    private String lockedBy;

    /** 엔티티 UPDATE 시 자동 갱신 (DB 컬럼: last_used_at) */
    @UpdateTimestamp
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    /* ===== 본문/메타 ===== */
    @Column(name = "session_id")
    private String sessionId;

    private String query;
    private String source;
    //  답변/스니펫 출처 태그(ASSISTANT, USER_CORRECTION, SMART_FALLBACK 등)
    @Column(name = "source_tag", length = 32)
    private String sourceTag;


@Column(name = "model_id")
private String modelId;

@Column(name = "embedding_model")
private String embeddingModel;

@Column(name = "memory_profile")
private String memoryProfile;

    @Lob
    @ToString.Exclude //  대용량 로그/GC 보호
    private String content;
    @Lob
    @ToString.Exclude //  대용량 로그/GC 보호
    private String corrected;

    /* ===== 점수/유사도 ===== */
    @Column(nullable = false)
    @Builder.Default
    private Double score = 0.0;

    private Double cosineSimilarity;
    private Double cosineCorrection;

    /* ===== 밴딧 상태 ===== */
    @Builder.Default private double qValue       = 0.0;
    @Builder.Default private double rewardMean   = 0.0;
    @Builder.Default private double rewardM2     = 0.0;
    @Builder.Default private int    successCount = 0;
    @Builder.Default private int    failureCount = 0;

        // ═══════════════════════════════════════════════════════════════════════
    // PROTECTED FIELDS (Hard Invariant)
    // 아래 필드들은 MemoryReinforcementService, StrategyDecisionTracker 등
    // 여러 서비스가 reflection 또는 직접 접근하므로 삭제/리네임/타입 변경 금지.
    // - qValue
    // - energy
    // - temperature
    // - confidenceScore
    // - lastUsedAt
    // 위 필드를 수정할 경우 전체 강화 학습/메모리 파이프라인을 함께 점검해야 합니다.
    // ═══════════════════════════════════════════════════════════════════════

/** [추가] 볼츠만 에너지 (낮을수록 좋음) */
    @Column(name = "energy")
    private Double energy;

    /** [추가] 담금질(Annealing) 스케줄에 사용될 온도 */
    @Column(name = "temperature")
    private Double temperature;

    /* ===== 신뢰도(검증 단계) ===== */
    @Column(name = "confidence_score")
    private Double confidenceScore; //  FactVerify/ContextScore 집계값 저장

    /* ===== 상태 ===== */
    @Enumerated(EnumType.ORDINAL)
    @Column(columnDefinition = "tinyint(1)", nullable = false)
    @Builder.Default
    private MemoryStatus status = MemoryStatus.ACTIVE;

    /**
     * Memory lifecycle state.
     *
     * IMPORTANT: persisted using EnumType.ORDINAL.
     * Always append new values to the end.
     */
    public enum MemoryStatus {
        ACTIVE,
        STABLE,
        STALE,
        EXPORTED,
        /** Candidate memory saved for later verification (idle soak). */
        PENDING,
        /**
         * Quarantined memory (suspected log/trace/diagnostic dump).
         *
         * <p>
         * IMPORTANT:
         * - EnumType.ORDINAL 저장 시에도 "맨 끝에 추가"는 기존 값 밀림이 없어 안전합니다.
         * </p>
         */
        QUARANTINED
    }

    /* ===== 비영속 ===== */
    @Transient
    private double[] vector;

    /* ===== JPA 콜백 ===== */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (sourceHash == null && content != null) {
            this.sourceHash = DigestUtils.sha256Hex(content);
        }
        // [추가] 온도의 기본값 설정
        if (temperature == null) temperature = 1.0;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /* ===== 비즈니스 로직 ===== */
    public void applyReward(double reward) {
        if (reward > 0) this.successCount++;
        else if (reward < 0) this.failureCount++;

        int n0 = (this.hitCount == null ? 0 : this.hitCount);
        int n1 = n0 + 1;
        double delta  = reward - this.rewardMean;
        double mean1  = this.rewardMean + delta / n1;
        double m2_1   = this.rewardM2 + delta * (reward - mean1);

        this.hitCount   = n1;
        this.rewardMean = mean1;
        this.rewardM2   = m2_1;
        this.qValue     = mean1;
                // + 보상 관측과 함께 confidence 희석(EMA) 업데이트(있으면)
                        if (this.confidenceScore != null) {
                        this.confidenceScore = 0.8 * this.confidenceScore + 0.2 * Math.max(0.0, Math.min(1.0, reward));
                    }
    }


    /* ================= 9. 헬퍼 메서드 (Transient) ================= */

    /** (score × cosine) 빠른 계산용 헬퍼 */
    @Transient
    public double relevance(double cosSim) {
        return (this.score != null ? this.score : 0.0) * cosSim;
    }

    @Transient
    public double getUcbScore(double exploration) {
        int h = (this.hitCount == null ? 0 : this.hitCount);
        if (h == 0) return Double.MAX_VALUE;
        return qValue + exploration * Math.sqrt(Math.log(h + 1.0) / h);
    }

    /** [추가] 볼츠만 가중치 w = exp(-E/T) 계산 메서드 */
    @Transient
    public double getBoltzmannWeight() {
        double e = (energy == null ? 0.0 : energy);
        double t = (temperature == null || temperature <= 0.0) ? 1.0 : temperature;
        return Math.exp(-e / t);
    }

    @Transient
    public double getRewardVariance() {
        int h = (this.hitCount == null ? 0 : this.hitCount);
        return (h < 2) ? 0.0 : rewardM2 / (h - 1);
    }

    @Transient
    public double getRewardStdDev() {
        return Math.sqrt(getRewardVariance());
    }

    public TranslationMemory(String sourceHash) {
        this.sourceHash = sourceHash;
        this.hitCount   = 0;
        LocalDateTime now = LocalDateTime.now();
        this.createdAt  = now;
        this.updatedAt  = now;
        this.status     = MemoryStatus.ACTIVE;
    }
    /* ===== Manual getters/setters for environments without Lombok processing ===== */
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getCorrected() { return corrected; }
    public void setCorrected(String corrected) { this.corrected = corrected; }

    public Integer getHitCount() { return hitCount; }
    public void setHitCount(Integer hitCount) { this.hitCount = hitCount; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public Double getCosineSimilarity() { return cosineSimilarity; }
    public void setCosineSimilarity(Double cosineSimilarity) { this.cosineSimilarity = cosineSimilarity; }

    public double getQValue() { return qValue; }
    public void setQValue(double qValue) { this.qValue = qValue; }

    public String getSourceTag() { return sourceTag; }
    public void setSourceTag(String sourceTag) { this.sourceTag = sourceTag; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public Double getEnergy() { return energy; }
    public void setEnergy(Double energy) { this.energy = energy; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

}