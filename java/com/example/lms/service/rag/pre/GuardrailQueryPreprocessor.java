package com.example.lms.service.rag.pre;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * '가드레일' 역할을 수행하는 기본 쿼리 전처리기입니다.
 * <p>
 * 이 클래스는 잠재적으로 위험하거나 검색 품질을 저해할 수 있는 요소를 제거하고,
 * 일관된 검색을 위해 쿼리를 정규화합니다. 또한, 도메인 특화적인 오타 교정 및
 * 고유명사 보호 기능을 포함합니다.
 * </p>
 * <ul>
 * <li>간단한 오타 교정 (예: "푸르나" → "푸리나")</li>
 * <li>핵심 고유명사 보호 (예: "원신"이 "원숭이"로 바뀌는 것 방지)</li>
 * <li>불필요한 접미사/공손어 제거 (예: "알려주세요", "요약")</li>
 * <li>디버그용 태그, 제어 문자, 검색 연산자 제거</li>
 * <li>불필요한 공백 및 기호 정규화</li>
 * <li>쿼리 길이 제한</li>
 * </ul>
 */
@Component("guardrailQueryPreprocessor")
@Primary // 여러 QueryContextPreprocessor 구현체 중 이 클래스를 기본으로 사용
public class GuardrailQueryPreprocessor implements QueryContextPreprocessor {

    // 간단 오타 사전(필요 시 Settings로 이관)
    private static final Map<String, String> TYPO = Map.of(
            "푸르나", "푸리나",
            "호요버스", "호요버스", // 예: 보호(그대로 유지)도 함께 처리
            "아를레키노", "아를레키노" // 사소한 표기 통일
    );

    // 보호(고유명사)는 교정 대상에서 제외
    private static final Set<String> PROTECT = Set.of(
            "푸리나", "호요버스", "HOYOVERSE", "Genshin", "원신", "Arlecchino", "아를레키노"
    );

    // 과한 공손어/불필요 접미
    private static final Pattern HONORIFICS = Pattern.compile("(님|해주세요|해 주세요|알려줘|정리|요약)$");

    /**
     * 원본 쿼리 문자열을 받아 정제하고 정규화된 문자열을 반환합니다.
     *
     * @param original 사용자가 입력한 원본 쿼리 문자열
     * @return 정제 및 정규화가 완료된 쿼리 문자열
     */
    @Override
    public String enrich(String original) {
        if (!StringUtils.hasText(original)) {
            return "";
        }

        String s = original.trim();

        // 1. 일반 정규화 (디버그 태그, 제어 문자, 검색 연산자 제거)
        s = s.replaceAll("^\\[(?:mode|debug)=[^\\]]+\\]\\s*", "")
                .replaceAll("\\p{Cntrl}+", " ")
                .replaceAll("(?i)\\bsite:[^\\s]+", "");

        // 2. 공손어/불필요 꼬리표 축소
        s = HONORIFICS.matcher(s).replaceAll("").trim();

        // 3. 토큰 분할 후 오타 교정 (보호어는 그대로 유지)
        StringBuilder out = new StringBuilder();
        for (String tok : s.split("\\s+")) {
            String t = tok;
            // 보호어 목록에 포함되지 않은 경우에만 오타 교정 시도 (람다 대신 헬퍼로 대체)
            if (!containsIgnoreCase(PROTECT, t)) {
                t = TYPO.getOrDefault(t, tok);
            }
            out.append(t).append(' ');
        }
        s = out.toString().trim();

        // 4. 여분 공백/기호 최종 정리
        s = s.replaceAll("\\s{2,}", " ")
                .replaceAll("[\"“”\"'`]+", "")
                .replaceAll("\\s*\\?+$", "")
                .trim();

        // 5. 길이 제한 (검색엔진 QoS 보호)
        if (s.length() > 120) {
            s = s.substring(0, 120);
        }

        // 6. 매우 짧은 단어가 아니면, 검색 일관성을 위해 소문자로 변환
        return s.length() <= 2 ? s : s.toLowerCase(Locale.ROOT);
    }

    // ── NEW: 대소문자 무시 포함 여부 체크(람다 캡처 회피)
    private static boolean containsIgnoreCase(Set<String> set, String value) {
        if (value == null) return false;
        for (String p : set) {
            if (p.equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}