package com.example.lms.service.rag.pre;
import org.springframework.context.annotation.Primary;   // ✅ 추가

import org.springframework.stereotype.Component;
import java.util.Locale;

/**
 * 간단한 '가드레일' 전처리기.
 *  – site: 제한, ❝..❞ 따옴표 등 잠재적으로 위험한 검색 연산자를 제거한다.<br>
 *  – 공백을 Trim하고 중복 공백을 하나로 축소한다.
 */
@Component
@Primary            // ✅ 이 빈이 기본 주입 대상
public class GuardrailQueryPreprocessor implements QueryContextPreprocessor {

    @Override
    public String enrich(String original) {
        if (original == null || original.isBlank()) return "";
        String q = original
                .replaceAll("(?i)\\bsite:[^\\s]+", "")   // site: 제거
                .replace("\"", "")                       // 큰따옴표 제거
                .replaceAll("\\s{2,}", " ")              // 다중 공백 → 1칸
                .trim();
        // 너무 짧으면 그대로, 그렇지 않으면 소문자/트림 정규화
        return q.length() <= 2 ? q : q.toLowerCase(Locale.ROOT);
    }
}
