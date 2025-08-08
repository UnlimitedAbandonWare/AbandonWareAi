// src/main/java/com/example/lms/service/translator/EchoTranslator.java
package com.example.lms.service.translator;

import com.example.lms.service.translator.BasicTranslator;
import org.springframework.stereotype.Component;

/**
 * ⭐️ 데모용 구현:
 *   - 그냥 입력 그대로 돌려보냅니다.
 *   - 추후 실제 번역 API 호출 로직으로 교체하세요.
 */
@Component            // → Spring Bean 으로 등록
public class EchoTranslator implements BasicTranslator {

    @Override
    public String translate(String text, String srcLang, String tgtLang) {
        // TODO: OpenAI 번역 호출 등으로 대체
        return text;
    }
}
