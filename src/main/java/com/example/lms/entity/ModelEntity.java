// src/main/java/com/example/lms/entity/ModelEntity.java
package com.example.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * JPA 엔티티: OpenAI 모델 메타데이터 저장용
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "models")
public class ModelEntity {

    /** OpenAI 모델 ID(PK) – 예: "gpt-4" */
    @Id
    @Column(name = "model_id", length = 100, nullable = false)
    private String modelId;

    /** 소유자(owned_by) – NOT NULL */
    @Column(nullable = false, length = 64)
    private String owner;

    /** 릴리스 날짜 */
    @Column(name = "release_date")
    private LocalDate releaseDate;

    /** 컨텍스트 윈도우(토큰) */
    @Column(name = "ctx_window")
    private Integer ctxWindow;

    /** 입력 토큰 단가($) */
    @Column(name = "price_in", precision = 10, scale = 6)
    private BigDecimal priceIn;

    /** 출력 토큰 단가($) */
    @Column(name = "price_out", precision = 10, scale = 6)
    private BigDecimal priceOut;

    /** 특징/설명 */
    @Column(length = 512)
    private String features;
}
