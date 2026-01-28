// src/main/java/com/example/lms/entity/ApiKeyUsage.java
package com.example.lms.entity;

import com.example.lms.entity.ApiKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;



/**
 * API 키별 일일 사용량을 기록하는 엔티티
 */
@Entity
@Table(name = "api_key_usage")
@Getter
@Setter
@NoArgsConstructor
public class ApiKeyUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 어떤 ApiKey 인스턴스인지 매핑 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_key_id", nullable = false)
    private ApiKey apiKey;

    /** 사용 일자 (키별로 날짜 누적) */
    @Column(name = "usage_date", nullable = false)
    private LocalDate date;

    /** 해당 날짜에 사용된 총 문자(코드포인트) */
    @Column(name = "used_chars", nullable = false)
    private Long usedChars;

    public ApiKeyUsage(ApiKey apiKey, LocalDate date, Long usedChars) {
        this.apiKey    = apiKey;
        this.date      = date;
        this.usedChars = usedChars;
    }
}