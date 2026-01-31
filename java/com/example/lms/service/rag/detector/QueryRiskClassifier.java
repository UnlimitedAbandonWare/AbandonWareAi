// src/main/java/com/example/lms/service/rag/detector/QueryRiskClassifier.java
package com.example.lms.service.rag.detector;

import java.util.Locale;

/**
 * Lightweight classifier that maps a free-form user query (and optionally a
 * coarse domain code) to a {@link RiskBand}.
 *
 * <p>이 클래스는 정규식/키워드 기반의 보수적인 휴리스틱만 사용합니다.
 * 외부 모델 호출이나 Spring 빈 의존성이 없기 때문에 어디서든
 * 정적 메서드로 안전하게 사용할 수 있습니다.</p>
 */
public final class QueryRiskClassifier {

    private QueryRiskClassifier() {
    }

    /**
     * Classify the query into a {@link RiskBand}.
     *
     * @param query      user query text (may be {@code null})
     * @param domainCode optional coarse domain code such as
     *                   "MEDICINE", "LAW", "FINANCE", "GAME", "GENERAL".
     *                   May be {@code null}.
     */
    public static RiskBand classify(String query, String domainCode) {
        // 0) Defensive defaults
        if (query == null || query.isBlank()) {
            return RiskBand.LOW;
        }

        String q = query.toLowerCase(Locale.ROOT);

        // 1) Domain code 기반 우선 판정
        if (domainCode != null && !domainCode.isBlank()) {
            String d = domainCode.toUpperCase(Locale.ROOT);

            if (containsAny(d, "MED", "HEALTH", "CLINIC", "HOSPITAL")
                    || containsAny(d, "LAW", "LEGAL")
                    || containsAny(d, "FINANCE", "INVEST", "STOCK")) {
                return RiskBand.HIGH;
            }

            if (containsAny(d, "GAME", "GENSHIN", "SUBCULTURE")) {
                return RiskBand.LOW;
            }
        }

        // 2) 의료/법률/투자 키워드 → HIGH
        String[] highKeywords = {
                // 의료
                "진단", "증상", "치료", "약", "복용", "부작용", "처방전", "의사", "의원",
                "병원", "의학", "암", "종양", "수술", "장기이식",
                // 법률
                "법률", "소송", "고소", "고발", "고소장", "피고", "원고",
                "형법", "민법", "노동법", "손해배상", "위자료", "계약서", "법원",
                // 투자/재무
                "투자", "주식", "코인", "비트코인", "펀드", "선물", "옵션",
                "대출", "금리", "연금", "보험", "세금", "절세"
        };
        if (containsAny(q, highKeywords)) {
            return RiskBand.HIGH;
        }

        // 3) 게임/서브컬쳐/잡담 → LOW
        String[] lowKeywords = {
                "게임", "원신", "genshin", "롤", "리그 오브 레전드", "lol",
                "발로란트", "valorant", "디아블로", "와우", "오버워치",
                "가챠", "리세마라", "뽑기", "메타", "티어", "덱", "빌드", "공략",
                "애니", "애니메이션", "아이돌", "아이돌마스터", "러브라이브",
                "웹툰", "망가", "라노벨", "서브컬쳐"
        };
        if (containsAny(q, lowKeywords)) {
            return RiskBand.LOW;
        }

        // 4) 그 외는 NORMAL
        return RiskBand.NORMAL;
    }

    private static boolean containsAny(String text, String... tokens) {
        if (text == null || text.isEmpty() || tokens == null) {
            return false;
        }
        for (String t : tokens) {
            if (t != null && !t.isEmpty() && text.contains(t.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
