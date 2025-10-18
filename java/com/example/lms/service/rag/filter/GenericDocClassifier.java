package com.example.lms.service.rag.filter;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;



@Component
public class GenericDocClassifier {

    /**
     * 정규 표현식은 과도하게 범용적인 문서/스니펫을 식별합니다.
     * 예: "모든 캐릭터", "전 캐릭터", "최강 파티", "티어 등급", "총정리",
     *     "리세마라", "가이드 총집합" 등. 대소문자를 구분하지 않습니다.
     */
    private static final Pattern GENERIC = Pattern.compile(
            "(모든\\s*캐릭터|전\\s*캐릭터|최강\\s*파티|티어\\s*등급|총정리|리세마라|가이드\\s*총집합)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 백과/개요형 문서에 대한 화이트리스트. 위키백과나 개요/개념 정의
     * 같은 단어가 포함된 문서/스니펫은 범용으로 간주하지 않습니다.
     */
    private static final Pattern WHITELIST = Pattern.compile(
            "(위키백과|백과|encyclopedia|overview|outline|definition|개요)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 스니펫이 범용 문서인지 판별합니다. 도메인이 GENERAL 또는 EDUCATION일 경우
     * 범용 판정을 적용하지 않습니다.
     *
     * @param snippetLine 검색 스니펫 문자열
     * @param domain      추정 도메인 (GENERAL, GENSHIN, EDUCATION 등)
     * @return true if generic (to be penalized), false otherwise
     */
    public boolean isGenericSnippet(String snippetLine, String domain) {
        if (domain != null) {
            String d = domain.trim().toUpperCase();
            if ("GENERAL".equals(d) || "EDUCATION".equals(d)) {
                return false;
            }
        }
        if (snippetLine == null || snippetLine.isBlank()) return false;
        // 백과/개요 키워드는 예외 처리
        if (WHITELIST.matcher(snippetLine).find()) return false;
        return GENERIC.matcher(snippetLine).find();
    }

    /**
     * 본문 텍스트가 범용 문서인지 판별합니다. 도메인이 GENERAL 또는 EDUCATION일 경우
     * 범용 판정을 적용하지 않습니다.
     *
     * @param text   문서 텍스트
     * @param domain 추정 도메인
     * @return true if generic, false otherwise
     */
    public boolean isGenericText(String text, String domain) {
        if (domain != null) {
            String d = domain.trim().toUpperCase();
            if ("GENERAL".equals(d) || "EDUCATION".equals(d)) {
                return false;
            }
        }
        if (text == null || text.isBlank()) return false;
        // 백과/개요 키워드는 예외 처리
        if (WHITELIST.matcher(text).find()) return false;
        return GENERIC.matcher(text).find();
    }

    /**
     * 랭킹에 적용할 가벼운 페널티. GENERAL 또는 EDUCATION 도메인에서는 페널티를 0으로 설정합니다.
     *
     * @param text   평가할 텍스트
     * @param domain 추정 도메인
     * @return 페널티 (0.0~0.5 범위 권장)
     */
    public double penalty(String text, String domain) {
        if (domain != null) {
            String d = domain.trim().toUpperCase();
            if ("GENERAL".equals(d) || "EDUCATION".equals(d)) {
                return 0.0;
            }
        }
        return isGenericText(text, domain) ? 0.25 : 0.0;
    }

    // ───── 오버로드: 기존 API 유지 ─────────────────────────────────────────
    public boolean isGenericSnippet(String snippetLine) {
        return isGenericSnippet(snippetLine, "GENERAL");
    }

    public boolean isGenericText(String text) {
        return isGenericText(text, "GENERAL");
    }

    public double penalty(String text) {
        return penalty(text, "GENERAL");
    }
}