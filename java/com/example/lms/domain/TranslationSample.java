// src/main/java/com/example/lms/domain/TranslationSample.java
package com.example.lms.domain;

import com.example.lms.domain.enums.TranslationRoute;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;




/**
 * 번역 샘플 & 교정 데이터 & 학습 로그 통합 엔티티
 *
 *  • sourceHash  : 중복 검출 및 빠른 조회
 *  • corrected   : 사람이 교정한 문장 (nullable)
 *  • qError      : 품질 지표(0-1)  ┐
 *  • similarity  : TM 선정 당시 코사인 유사도 ┘  --► 새로 추가
 */
@Entity
@Table(
        name = "translation_samples",
        indexes = @Index(name = "idx_sample_source_hash", columnList = "sourceHash", unique = true)
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TranslationSample {

    /* ─────────── 기본 키 ─────────── */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* ─────────── 언어 & 라우트 ─────────── */
    private String srcLang;           // e.g. "ko"
    private String tgtLang;           // e.g. "en"

    @Enumerated(EnumType.STRING)
    private TranslationRoute route;   // GT, GEMINI, MEMORY /* ... */

    /* ─────────── 본문 & 해시 ─────────── */
    @Lob
    @Column(nullable = false)
    private String sourceText;        // 원문

    @Lob
    private String translated;        // 기계 번역 결과

    @Lob
    private String corrected;         // 사람이 교정한 최종 결과 (nullable)

    /** SHA-256(sourceText) - 64 chars */
    @Column(length = 64, nullable = false, unique = true)
    private String sourceHash;

    /* ─────────── 품질 / 통계 ─────────── */
    private Double qError;            // 품질 오차(0-1)

    /** ✨ TM 선정 시 코사인 유사도 (없으면 null) */
    private Double similarity;

    /* ─────────── 메타 데이터 ─────────── */
    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}