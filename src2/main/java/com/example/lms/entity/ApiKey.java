// src/main/java/com/example/lms/entity/ApiKey.java
package com.example.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Google 번역 API 키 & 일일 문자 한도 관리용 엔티티
 */
@Entity
@Table(name = "api_key")
@Getter
@Setter
@NoArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 실제 Google 번역 API 키 문자열 */
    @Column(name = "key_value", nullable = false, length = 128, unique = true)
    private String keyValue;

    /** 이 키로 하루에 번역할 수 있는 최대 코드포인트 수 */
    @Column(name = "daily_limit", nullable = false)
    private Long dailyLimit;

    public ApiKey(String keyValue, Long dailyLimit) {
        this.keyValue   = keyValue;
        this.dailyLimit = dailyLimit;
    }
}
