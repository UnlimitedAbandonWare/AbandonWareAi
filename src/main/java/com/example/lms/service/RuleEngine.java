// ✅  src/main/java/com/example/lms/service/RuleEngine.java
package com.example.lms.service;

import com.example.lms.domain.enums.RulePhase;
import com.example.lms.repository.RuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 전/후처리 룰 엔진 */
@Component
@RequiredArgsConstructor
public class RuleEngine {

    private final RuleRepository ruleRepo;

    public String apply(String text, String lang, RulePhase phase) {
        return ruleRepo.findByLangAndPhaseAndEnabled(lang, phase, true)
                .stream()
                .reduce(text,
                        (acc, rule) -> acc.replaceAll(rule.getPattern(), rule.getReplacement()),
                        (l, r) -> r);
    }
}
