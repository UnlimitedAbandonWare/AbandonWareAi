
package com.example.lms.service.rag.pre;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.Locale;

/**
 * '가드레일' 역할을 수행하는 기본 쿼리 전처리기입니다.
 * <p>
 * 이 클래스는 잠재적으로 위험하거나 검색 품질을 저해할 수 있는 요소를 제거하고,
 * 일관된 검색을 위해 쿼리를 정규화합니다.
 * </p>
 * <ul>
 * <li>디버그용 태그([mode=...]) 제거</li>
 * <li>제어 문자(Control Characters) 제거</li>
 * <li>검색 엔진 연산자(예: site:, ") 제거</li>
 * <li>불필요한 공백 정규화</li>
 * <li>일관된 비교를 위한 소문자 변환</li>
 * </ul>
 */
@Component("guardrailQueryPreprocessor")
@Primary // 여러 QueryContextPreprocessor 구현체 중 이 클래스를 기본으로 사용
public class GuardrailQueryPreprocessor implements QueryContextPreprocessor {

    /**
     * 원본 쿼리 문자열을 받아 정제하고 정규화된 문자열을 반환합니다.
     *
     * @param original 사용자가 입력한 원본 쿼리 문자열
     * @return 정제 및 정규화가 완료된 쿼리 문자열
     */
    @Override
    public String enrich(String original) {
        // 1. 입력값이 null이거나 비어있으면 빈 문자열 반환
        if (original == null || original.isBlank()) {
            return "";
        }

        // 2. 여러 정제 규칙을 순차적으로 적용
        String q = original
                // [mode=...] 또는 [debug=...] 같은 디버그 태그 제거
                .replaceAll("^\\[(?:mode|debug)=[^\\]]+\\]\\s*", "")
                // 제어 문자(예: \n, \t 등)를 공백으로 변환
                .replaceAll("\\p{Cntrl}+", " ")
                // 'site:...' 검색 연산자 제거 (대소문자 미구분)
                .replaceAll("(?i)\\bsite:[^\\s]+", "")
                // 큰따옴표(") 제거
                .replace("\"", "")
                // 두 칸 이상의 연속된 공백을 한 칸으로 축소
                .replaceAll("\\s{2,}", " ")
                // 앞뒤 공백 제거
                .trim();

        // 3. 매우 짧은 단어가 아니면, 검색 일관성을 위해 소문자로 변환
        return q.length() <= 2 ? q : q.toLowerCase(Locale.ROOT);
    }
}