package com.example.lms.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import org.apache.commons.codec.digest.DigestUtils; // 해시 생성용

import java.time.LocalDateTime;

/**
 * 사용자 대화, 웹 검색 스니펫, LLM 답변 등을 저장하는 통합 메모리 엔티티입니다.
 * 중복 저장을 방지하기 위해 내용 기반의 해시(sourceHash)에 UNIQUE 제약 조건을 사용합니다.
 */
@Entity
@Table(name = "translation_memory",
        indexes = {
                @Index(name = "idx_tm_source_hash", columnList = "sourceHash"),
                @Index(name = "idx_tm_session", columnList = "sessionId")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslationMemory {

    /* ================= G. 기본키 및 메타데이터 ================= */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @UpdateTimestamp
    private LocalDateTime lastUsedAt;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();


    /* ================= 1. 핵심 데이터 필드 ================= */

    /** 채팅 세션 ID (예: "chat-123", "GLOBAL") */
    private String sessionId;

    /** 사용자 원본 질문 (Query) */
    private String query;

    /** 데이터 출처 (예: "ASSISTANT", "RAG", "WEB_SEARCH") */
    private String source;

    /** 스니펫, LLM 답변, 교정문 등 실제 텍스트 내용 */
    @Lob
    private String content;

    /** 사용자가 수정한 교정본 (피드백용) */
    @Lob
    private String corrected;

    /**
     * 내용 기반의 SHA-256 해시값. 중복 저장을 막기 위한 UNIQUE 키입니다.
     */
    @Column(length = 64, unique = true, nullable = false)
    private String sourceHash;


    /* ================= 2. 점수 및 유사도 ================= */

    @Builder.Default
    @Column(nullable = false)
    private Double score = 0.0; // null 대신 0.0을 기본값으로 저장

    private Double cosineSimilarity;
    private Double cosineCorrection;


    /* ================= 3. Bandit 알고리즘 상태 필드 ================= */

    @Builder.Default private double qValue = 0.0;        // 행동 가치 (평균 보상)
    @Builder.Default private double rewardMean = 0.0;    // 보상 평균
    @Builder.Default private double rewardM2 = 0.0;      // 보상 값의 제곱합의 편차 (분산 계산용)
    @Builder.Default private int hitCount = 0;           // 선택 횟수 (N)
    @Builder.Default private int successCount = 0;       // 긍정 피드백 수
    @Builder.Default private int failureCount = 0;       // 부정 피드백 수


    /* ================= 4. 상태 관리 ================= */

    /** 메모리 상태 (0=활성, 1=안정, 2=오래됨, 3=내보냄) */
    @Enumerated(EnumType.ORDINAL)
    @Column(columnDefinition = "tinyint(1)", nullable = false)
    @Builder.Default
    private MemoryStatus status = MemoryStatus.ACTIVE;

    public enum MemoryStatus {
        ACTIVE, STABLE, STALE, EXPORTED
    }


    /* ================= 5. JPA 비영속 필드 (Transient) ================= */

    @Transient
    private double[] vector;


    /* ================= 6. 생성자 ================= */

    public TranslationMemory(String sourceHash) {
        this.sourceHash = sourceHash;
        this.status = MemoryStatus.ACTIVE;
    }


    /* ================= 7. JPA 생명주기 콜백 ================= */

    @PrePersist
    private void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        // content가 있고 sourceHash가 비어있으면 자동으로 해시값 생성
        if (this.sourceHash == null && this.content != null) {
            this.sourceHash = DigestUtils.sha256Hex(content);
        }
    }


    /* ================= 8. 비즈니스 로직 메서드 ================= */

    /**
     * 사용자 피드백(보상값)을 받아 통계를 O(1) 시간 복잡도로 갱신합니다.
     * Welford's online algorithm을 사용하여 평균과 분산을 누적 계산합니다.
     *
     * @param reward 실수 형태의 보상값 (예: 👍=1.0, 👎=0.0)
     */
    public void applyReward(double reward) {
        // 1. 성공/실패 카운트 업데이트
        if (reward > 0) this.successCount++;
        else if (reward < 0) this.failureCount++;

        // 2. Welford 알고리즘으로 평균, 이차모멘트 실시간 갱신
        int n0 = this.hitCount;
        int n1 = n0 + 1;
        double delta = reward - this.rewardMean;
        double mean1 = this.rewardMean + delta / n1;
        double m2_1 = this.rewardM2 + delta * (reward - mean1);

        this.hitCount = n1;
        this.rewardMean = mean1;
        this.rewardM2 = m2_1;
        this.qValue = mean1; // 현재 Q-가치는 보상 평균값으로 설정
    }


    /* ================= 9. 헬퍼 메서드 (Transient) ================= */

    /** (score × cosine) 빠른 계산용 헬퍼 */
    @Transient
    public double relevance(double cosSim) {
        return (this.score != null ? this.score : 0.0) * cosSim;
    }

    /** UCB-1 점수(탐험-이용 트레이드오프) 계산 */
    @Transient
    public double getUcbScore(double exploration) {
        if (hitCount == 0) return Double.MAX_VALUE; // 아직 선택되지 않았다면 가장 높은 우선순위 부여
        return qValue + exploration * Math.sqrt(Math.log(hitCount + 1) / hitCount);
    }

    /** 보상값의 분산 계산 */
    @Transient
    public double getRewardVariance() {
        return (hitCount < 2) ? 0.0 : rewardM2 / (hitCount - 1);
    }

    /** 보상값의 표준편차 계산 */
    @Transient
    public double getRewardStdDev() {
        return Math.sqrt(getRewardVariance());
    }
}