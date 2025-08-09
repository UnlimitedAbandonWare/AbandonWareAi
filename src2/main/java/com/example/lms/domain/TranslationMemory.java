// ======================================================================
// TranslationMemory.java  (final – getVector()/setVector() 지원)
// 위치: src/main/java/com/example/lms/domain
// ======================================================================
package com.example.lms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 번역 메모리(TMX) 엔티티.<br>
 * ● Optimistic-Locking(@Version)·Bandit 통계·상태 관리 포함<br>
 * ● <b>getVector()/setVector()</b>는 레거시 코드 호환을 위한
 *   @Transient 편의 메서드(= DB 칼럼 X) 입니다.
 */
@Entity
@Table(
        name = "translation_memory",
        indexes = @Index(name = "idx_tm_source_hash", columnList = "sourceHash", unique = true)
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TranslationMemory {

    /* ─────── 기본키 & 버전 ─────── */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic-locking */
    @Version
    private Long version;

    /* ─────── 언어·텍스트 ─────── */
    private String srcLang;
    private String tgtLang;

    /** SHA-256 해시(64자) – 원문 중복 탐지 */
    @Column(length = 64, nullable = false, unique = true)
    private String sourceHash;

    /** 후편집(교정) 번역문 */
    @Lob
    private String corrected;

    /* ─────── Bandit 지표 ─────── */
    @Builder.Default private double qValue       = 0.0;
    @Builder.Default private double rewardMean   = 0.0;
    /** 보상 분산 계산용 2차 모멘트 */
    @Builder.Default private double rewardM2     = 0.0;
    @Builder.Default private int    hitCount     = 0;
    @Builder.Default private int    successCount = 0;
    @Builder.Default private int    failureCount = 0;

    /* ─────── 상태·유사도 ─────── */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private MemoryStatus status = MemoryStatus.ACTIVE;

    private Double cosineSimilarity;
    private Double cosineCorrection;

    /* ─────── 메타데이터 ─────── */
    @UpdateTimestamp
    private LocalDateTime lastUsedAt;

    /* =====  레거시 호환: Vector 필드 ===== */
    /**
     * 임베딩 벡터(JSON·BLOB 등) 컬럼이 실제 DB에 없으므로
     * 메모리 전용(@Transient) 필드로만 두어 컴파일 오류를 방지합니다.
     * <p> → 향후 Milvus / PGVector 등으로 옮길 때 실제 컬럼을 만들면 됩니다.</p>
     */
    @Transient
    private double[] vector;                 // ← 이 필드는 DB에 저장되지 않음

    /** ★ 호출부 호환용 Getter */
    @Transient
    public double[] getVector() {
        return vector;
    }
    /** ★ 호출부 호환용 Setter */
    public void setVector(double[] v) {
        this.vector = v;
    }

    /* ─────── 상태 Enum ─────── */
    public enum MemoryStatus { ACTIVE, STABLE, STALE, EXPORTED }

    /* ─────── 편의 생성자 ─────── */
    public TranslationMemory(String sourceHash) {
        this.sourceHash = sourceHash;
        this.status     = MemoryStatus.ACTIVE;
    }

    /* ─────── 통계 헬퍼 ─────── */
    @Transient
    public double getRewardVariance() {
        return (hitCount < 2) ? 0.0 : rewardM2 / (hitCount - 1);
    }
    @Transient
    public double getRewardStdDev() {
        return Math.sqrt(getRewardVariance());
    }
}
