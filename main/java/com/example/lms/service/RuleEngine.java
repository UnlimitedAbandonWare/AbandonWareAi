// ✅  src/main/java/com/example/lms/service/RuleEngine.java
package com.example.lms.service;

import com.example.lms.domain.enums.RulePhase;
import com.example.lms.repository.RuleRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;



/** 전/후처리 룰 엔진 */
@Component
@RequiredArgsConstructor
public class RuleEngine {
    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleRepository ruleRepo;

    public String apply(String text, String lang, RulePhase phase) {
        if (text == null) return "";
        try {
            return ruleRepo.findByLangAndPhaseAndEnabled(lang, phase, true)
                    .stream()
                    .reduce(text,
                            (acc, rule) -> acc.replaceAll(rule.getPattern(), rule.getReplacement()),
                            (l, r) -> r);
        } catch (Exception e) {
            traceSuppressed(text, lang, phase, e);
            // 패턴 오류 등 예외 발생 시 원문 반환 (서비스 연속성 우선)
            return text;
        }
    }

    private static void traceSuppressed(String text, String lang, RulePhase phase, Throwable failure) {
        try {
            TraceStore.put("ruleEngine.apply.suppressed", true);
            TraceStore.put("ruleEngine.apply.suppressed.phase",
                    SafeRedactor.traceLabelOrFallback(phase == null ? "unknown" : phase.name(), "unknown"));
            TraceStore.put("ruleEngine.apply.suppressed.errorType",
                    failure == null ? "unknown" : failure.getClass().getSimpleName());
            TraceStore.put("ruleEngine.apply.suppressed.langLength", lang == null ? 0 : lang.length());
            TraceStore.put("ruleEngine.apply.suppressed.textLength", text == null ? 0 : text.length());
        } catch (RuntimeException traceFailure) {
            log.debug("[RuleEngine] suppression trace failed errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(traceFailure)), String.valueOf(traceFailure).length());
        }
    }
}
