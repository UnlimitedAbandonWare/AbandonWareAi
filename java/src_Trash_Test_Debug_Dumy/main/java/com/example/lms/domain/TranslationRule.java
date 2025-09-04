// src/main/java/com/example/lms/domain/TranslationRule.java
package com.example.lms.domain;

import com.example.lms.domain.enums.RulePhase;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "translation_rules")      // RULE 은 DB 예약어라 다르게 지정
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TranslationRule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String lang;                           // 예) "ko"

    @Enumerated(EnumType.STRING)
    private RulePhase phase;                       // PRE / POST

    // [수정 후]
    @Column(columnDefinition = "TEXT")
    private String pattern;

    @Column(columnDefinition = "TEXT")
    private String replacement;              // 치환 후 문자열

    private String description;

    @Builder.Default          // ✅ 빌더 사용 시 기본값 유지
    private boolean enabled = true;
}
