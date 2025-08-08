// src/main/java/com/example/lms/service/AdvancedTranslationService.java
package com.example.lms.service;

import com.example.lms.domain.enums.RulePhase;
import com.example.lms.service.RuleEngine;
import com.example.lms.service.translator.BasicTranslator;   // ✅ 새 위치로 import
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 1) 규칙 엔진 전·후처리 + 2) 실제 번역기(BasicTranslator) 호출을 조합한 고급 번역 서비스.
 */
@Service
@RequiredArgsConstructor
public class AdvancedTranslationService {

    private final RuleEngine ruleEngine;   // 전/후처리용
    private final BasicTranslator translator;   // 실제 번역 수행

    /**
     * @param sourceText 원문
     * @param srcLang    원문 언어 코드  (ex. "ko")
     * @param tgtLang    대상 언어 코드 (ex. "en")
     * @return 후처리까지 마친 번역 결과
     */
    public String translate(String sourceText, String srcLang, String tgtLang) {

        // 1️⃣ 전처리 규칙 적용
        String preprocessed = ruleEngine.apply(sourceText, srcLang, RulePhase.PRE);

        // 2️⃣ 실제 번역 (OpenAI·Papago·DeepL 등으로 대체 가능)
        String translated   = translator.translate(preprocessed, srcLang, tgtLang);

        // 3️⃣ 후처리 규칙 적용 후 반환
        return ruleEngine.apply(translated, tgtLang, RulePhase.POST);
    }
}
