// src/main/java/com/example/lms/entity/CurrentModel.java
package com.example.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;



/**
 * 애플리케이션에서 현재 선택된 모델 ID를 한 건만 저장하는 테이블용 엔티티
 */
@Entity
@Table(name = "current_model")
@Getter
@Setter
@NoArgsConstructor
public class CurrentModel {

    /** 항상 하나의 행만 유지 (PK=1) */
    @Id
    private Long id = 1L;

    /** 선택된 모델의 ID */
    @Column(name = "model_id", nullable = false, length = 120)
    private String modelId;
}