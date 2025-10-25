// src/main/java/com/example/lms/model/ModelInfo.java
package com.example.lms.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;




/**
 * OpenAI 모델 메타데이터 저장용 JPA 엔티티
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "model_info")
public class ModelInfo {

    /** 내부 PK (시퀀스) */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** OpenAI에서 내려주는 모델 식별자 (예: "gpt-4") */
    @Column(name = "model_id", unique = true, nullable = false, length = 100)
    private String modelId;

    /** OpenAI 응답의 "object" 필드 (예: "model") */
    @Column(name = "object_type", length = 50)
    private String objectType;

    /** 생성된 UNIX 타임스탬프 */
    @Column(name = "created_timestamp")
    private long created;

    /** OpenAI 응답의 "owned_by" 필드 */
    @Column(name = "owned_by", length = 100)
    private String ownedBy;

    /** 모델 패밀리 (예: "gpt-4") */
    @Column(name = "family", length = 100)
    private String family;

    /** 릴리즈 일자 (YYYY-MM-DD) */
    @Column(name = "release_date")
    private LocalDate releaseDate;

    /** 입력 토큰 단가 ($) */
    @Column(name = "price_in", precision = 10, scale = 6)
    private BigDecimal priceIn;

    /** 출력 토큰 단가 ($) */
    @Column(name = "price_out", precision = 10, scale = 6)
    private BigDecimal priceOut;

    /** 컨텍스트 윈도우 크기 (토큰 단위) */
    @Column(name = "ctx_window")
    private Integer ctxWindow;

    /** 주요 기능/설명 */
    @Column(name = "features", length = 512)
    private String features;
}