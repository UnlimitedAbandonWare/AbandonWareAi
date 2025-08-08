package com.example.lms.entity;
//C:\AbandonWare\demo-1\demo-1\src\main\java\com\example\lms\entity\TranslationMemory.java
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
// 🆕 Bandit 보상 계산용
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.codec.digest.DigestUtils;   // 🔺 해시용

import java.time.LocalDateTime;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Column;
/**
 * 통합 Translation‑Memory 엔티티 (TMX & 세션‑스니펫 겸용)
 */
@Entity
@Table(name = "translation_memory",
        indexes = {
                @Index(name = "idx_tm_source_hash", columnList = "sourceHash"),
                @Index(name = "idx_tm_session",    columnList = "sessionId")
        })
@Getter @Setter
@Builder @NoArgsConstructor @AllArgsConstructor
public class TranslationMemory {

    /* ───── 기본키 & 버전 ───── */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    /* ───── 채팅 세션/콘텐츠 ───── */
    /** 채팅 세션 ID (예: "35") */
    private String sessionId;

    /** 스니펫·LLM 답변·교정문 등 실제 텍스트 */
    @Lob
    private String content;

    /** 사용자 쿼리 / 소스태그 / 점수 */
    private String  query;
    private String  source;


    /* ───── 번역‑Bandit 필드 ───── */
    @Column(length = 64, unique = true, nullable = false)
    private String sourceHash;           // SHA‑256 (중복검사용) – NOT NULL

    @Lob
    private String corrected;            // 후편집 번역문

    // entity/TranslationMemory.java
    @Builder.Default
    @Column(nullable = false)
    private Double score = 0.0; // null 대신 0.0 저장

    /** (score × cosine) 빠른 계산용 Helper – 영속 안 함 */
    @Transient public double relevance(double cosSim) {
        return (score == null ? 0.0 : score) * cosSim;
    }

    @Builder.Default private double qValue       = 0.0;
    @Builder.Default private double rewardMean   = 0.0;
    @Builder.Default private double rewardM2     = 0.0;
    @Builder.Default private int    hitCount     = 0;
    @Builder.Default private int    successCount = 0;
    @Builder.Default private int    failureCount = 0;
    /* ─────── 보상 적립 메서드 (Welford) ─────── */
    /** hit+1 과 평균·분산·Q-값을 동시에 갱신한다. */
    public void applyReward(double reward) {
        // ① 성공/실패 카운트
        if (reward > 0) this.successCount++;
        else if (reward < 0) this.failureCount++;

        // ② Welford(평균·이차모멘트) 실시간 갱신
        int n0 = this.hitCount;            // 갱신 전 hit 수
        int n1 = n0 + 1;                   // 갱신 후 hit 수
        double delta  = reward - this.rewardMean;
        double mean1  = this.rewardMean + delta / n1;
        double m2_1   = this.rewardM2   + delta * (reward - mean1);

        this.hitCount   = n1;
        this.rewardMean = mean1;
        this.rewardM2   = m2_1;
        this.qValue     = mean1;           // 현재 Q-값 = 평균
    }


    /* ───── Reward Update 메서드 (Bandit) ───── */
    /**
     * 사용자 피드백(보상값)을 받아 통계를 **O(1)** 로 갱신한다.
     * <p>Welford 온라인 알고리즘으로 평균·분산을 누적 계산.</p>
     *
     * @param reward 실수 보상값<br>
     *              └ 예)  👍 = 1,  👎 = 0 (또는 -1 ~ +1 범위)
     */


    /** UCB-1 Upper-Confidence-Bound(탐험 vs 이용) 점수 계산기 */
    @Transient
    public double getUcbScore(double exploration) {
        if (hitCount == 0) return Double.MAX_VALUE;
        return qValue + exploration * Math.sqrt(Math.log(hitCount + 1) / hitCount);
    }


    /* ───── 상태·유사도 ───── */
    /** 0 = ACTIVE · 1 = STABLE · 2 = STALE · 3 = EXPORTED (tinyint) */
    @Enumerated(EnumType.ORDINAL)            // ✅ 숫자로 저장
    @Column(columnDefinition = "tinyint(1)", nullable = false)
    @Builder.Default
    private MemoryStatus status = MemoryStatus.ACTIVE;

    private Double cosineSimilarity;
    private Double cosineCorrection;

    /* ───── 메타데이터 ───── */
    @UpdateTimestamp
    private LocalDateTime lastUsedAt;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();



    /* ───── 벡터 (Transient) ───── */
    @Transient private double[] vector;
    @Transient public double[] getVector() { return vector; }
    @Transient public void setVector(double[] v) { this.vector = v; }

    /* ───── Enum ───── */
    public enum MemoryStatus { ACTIVE, STABLE, STALE, EXPORTED }

    /* ───── 통계 헬퍼 ───── */
    @Transient public double getRewardVariance() {
        return (hitCount < 2) ? 0.0 : rewardM2 / (hitCount - 1);
    }
    @Transient public double getRewardStdDev() { return Math.sqrt(getRewardVariance()); }

    /* ───── 편의 생성자 ───── */
    public TranslationMemory(String sourceHash) {
        this.sourceHash = sourceHash;
        this.status     = MemoryStatus.ACTIVE;
    }

    /* 한 메서드에만 @PrePersist — 중복 예외 해결 */
    @PrePersist
    private void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (this.sourceHash == null && content != null) {
            this.sourceHash = DigestUtils.sha256Hex(content);
        }
    }
}
