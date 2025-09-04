package com.example.lms.domain;

import com.example.lms.domain.enums.RulePhase;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "rules") // 'rule'은 DB 예약어일 수 있으므로 'rules' 사용을 권장
@Getter
@Setter
public class Rule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String lang;

    @Enumerated(EnumType.STRING)
    private RulePhase phase;

    @Column(length = 500)
    private String pattern;

    @Column(length = 500)
    private String replacement;

    private String description;

    private boolean enabled = true;
}