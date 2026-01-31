package com.example.lms.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 미출시/미래 기술(Future Tech) 관련 쿼리를 감지하는 유틸리티.
 *
 * <p>이 판독기는 '폴드7/아이폰17/RTX50xx' 등 출시 전(또는 출시 직후로 정보가 급변하는)
 * 세대형 제품 질문을 감지하여, 시스템이 웹 근거 기반 요약 + 루머 라벨링 + 메모리 오염 방지
 * 정책을 적용할 수 있도록 돕습니다.</p>
 */
public final class FutureTechDetector {

    private static final List<Pattern> PATTERNS = List.of(
            // 삼성 폴더블 차기작
            Pattern.compile("(?i)(폴드|fold)\\s*[7-9]"),
            Pattern.compile("(?i)(플립|flip)\\s*[7-9]"),
            Pattern.compile("(?i)(z\\s*fold|z\\s*flip)\\s*[7-9]"),
            // 트라이폴드/삼단 폴드 등(루머/미출시)
            Pattern.compile("(?i)(트라이\\s*폴드|트라이폴드|삼단\\s*폴드|3단\\s*폴드)"),
            Pattern.compile("(?i)(tri\\s*-?\\s*fold|tri-fold|trifold|three\\s*-?\\s*fold|3\\s*-?\\s*fold)"),
            Pattern.compile("(?i)(갤럭시\\s*g\\s*폴드|galaxy\\s*g\\s*fold|g\\s*-?\\s*fold)"),
            // 애플 차기작
            Pattern.compile("(?i)(아이폰|iphone)\\s*(17|18)"),
            // 갤럭시 S 시리즈 차기작
            Pattern.compile("(?i)(갤럭시|galaxy)\\s*(s\\s*2[6-9]|s\\s*3[0-9])"),
            // 루머/유출/미출시 키워드
            Pattern.compile("(?i)(루머|유출|leak|rumor|미출시|출시\\s*전|사전\\s*정보|예상\\s*스펙|unreleased|prototype|concept)"),
            Pattern.compile("(?i)(아이폰|iphone)\\s*air"),
            // GPU 차기작
            Pattern.compile("(?i)rtx\\s*5\\d{2}"),
            Pattern.compile("(?i)5090|5080")
    );

    private FutureTechDetector() {
    }

    public static boolean isFutureTechQuery(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = query.trim();
        for (Pattern p : PATTERNS) {
            if (p.matcher(normalized).find()) {
                return true;
            }
        }
        return false;
    }
}
