// src/main/java/com/example/lms/repository/RuleRepository.java
package com.example.lms.repository;

import com.example.lms.domain.TranslationRule;
import com.example.lms.domain.enums.RulePhase;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;




/**
 * {@link TranslationRule} 전용 JPA 리포지토리
 *
 * <pre>
 * ┌─ 제공 메서드 ─────────────────────────────────────────────┐
 * │ 1) findByLangAndPhaseAndEnabled  : 규칙 조회 (활성 필터)  │
 * │ 2) existsByPatternAndLangAndPhase: 중복 패턴 존재 여부 확인│
 * └──────────────────────────────────────────────────────────┘
 * </pre>
 */
public interface RuleRepository extends JpaRepository<TranslationRule, Long> {

    /** 언어·단계·활성여부 조건으로 규칙 목록 조회 */
    List<TranslationRule> findByLangAndPhaseAndEnabled(String lang,
                                                       RulePhase phase,
                                                       boolean enabled);

    /** 동일 (pattern, lang, phase) 조합이 이미 존재하는지 여부 */
    boolean existsByPatternAndLangAndPhase(String pattern,
                                           String lang,
                                           RulePhase phase);
}