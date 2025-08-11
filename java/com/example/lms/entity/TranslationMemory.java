package com.example.lms.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import org.apache.commons.codec.digest.DigestUtils;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "translation_memory",
        indexes = {
                @Index(name = "idx_tm_source_hash", columnList = "source_hash"),
                @Index(name = "idx_tm_session",     columnList = "session_id"),
                // [추가] 볼츠만 강화 검색 최적화를 위해 에너지 컬럼에 인덱스 추가
                @Index(name = "idx_tm_energy",      columnList = "energy")
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
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 엔티티 UPDATE 시 자동 갱신 (DB 컬럼: last_used_at) */
    @UpdateTimestamp
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    /* ===== 본문/메타 ===== */
    @Column(name = "session_id")
    private String sessionId;

    private String query;
    private String source;

    @Lob
    private String content;

    @Lob
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

    /** [추가] 볼츠만 에너지 (낮을수록 좋음) */
    @Column(name = "energy")
    private Double energy;

    /** [추가] 담금질(Annealing) 스케줄에 사용될 온도 */
    @Column(name = "temperature")
    private Double temperature;

    /* ===== 상태 ===== */
    @Enumerated(EnumType.ORDINAL)
    @Column(columnDefinition = "tinyint(1)", nullable = false)
    @Builder.Default
    private MemoryStatus status = MemoryStatus.ACTIVE;

    public enum MemoryStatus { ACTIVE, STABLE, STALE, EXPORTED }

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
}