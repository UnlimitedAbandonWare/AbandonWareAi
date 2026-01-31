package com.example.lms.domain; 
import lombok.*;
import jakarta.persistence.*;
// 메인 패키지를 'domain'으로 지정


/**
 * 'configuration_settings' 테이블과 매핑되는 JPA 엔티티.
 * 시스템의 다양한 설정을 Key-Value 형태로 저장합니다.
 */
@Entity
@Table(name = "configuration_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ConfigurationSetting {

    /**
     * 설정 키 (Primary Key).
     * 예: "SYSTEM_PROMPT", "TEMPERATURE"
     */
    @Id
    @Column(name = "setting_key", nullable = false, length = 100)
    private String settingKey; // 필드명을 settingKey로 수정

    /**
     * 설정 값.
     * JPA 표준인 @Lob을 사용하여 긴 텍스트를 지원합니다.
     */
    @Lob
    @Column(name = "setting_value", columnDefinition = "LONGTEXT")
    private String settingValue;
}