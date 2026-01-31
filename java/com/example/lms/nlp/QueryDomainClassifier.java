package com.example.lms.nlp;

import com.example.lms.rag.model.QueryDomain;

import java.util.Locale;

/**
 * Lightweight rule-based classifier for mapping a raw user query to a {@link QueryDomain}.
 * <p>
 * This is intentionally simple so it can run before any LLM calls. It can be
 * later extended to delegate to a model when available.
 */
public class QueryDomainClassifier {

    /**
     * Classify the given query into a high-level {@link QueryDomain}.
     * Never returns {@code null}; falls back to {@link QueryDomain#GENERAL}.
     */
    public QueryDomain classify(String query) {
        if (query == null || query.isBlank()) {
            return QueryDomain.safeDefault();
        }
        String q = query.toLowerCase(Locale.ROOT);

        // 1. Sensitive first (정책상 최우선)
        if (containsAny(q,
                "자살", "죽고 싶", "극단적 선택",
                "살인", "폭력", "테러",
                "야동", "포르노", "19금", "에로", "nsfw",
                "불법 다운로드", "토렌트", "크랙", "해킹", "핵 사용", "핵 다운",
                "계정 거래", "사설 서버")) {
            return QueryDomain.SENSITIVE;
        }

        // 2. Study / exam / coding
        if (containsAny(q,
                "시험", "수능", "내신", "기말", "중간고사",
                "문제 풀이", "풀이", "증명하시오", "정리하시오",
                "알고리즘", "코딩테스트", "코테", "백준", "프로그래머스",
                "자료구조", "시간 복잡도", "빅오")) {
            return QueryDomain.STUDY;
        }

        // 2-1. Employment / training / 기관(위탁) 관련: GAME 오분류 방지
        // (예: "메이크인", "국민취업지원", "내일배움카드" 등)
        if (containsAny(q,
                "취업지원금", "국민취업지원", "국취제", "국민 취업지원",
                "위탁기관", "고용센터", "직업훈련", "내일배움카드",
                "청년", "ncs", "hrd",
                "메이크인", "makein", "make in")) {
            return QueryDomain.STUDY;
        }

        // 3. Game keywords
        if (containsAny(q,
                "원신", "젠신", "genshin",
                "스커크", "skirk", "마비카", "mavuika",
                "스타레일", "붕괴", "블루 아카이브", "블루아카",
                "리그 오브 레전드", "롤", "lol",
                "발로란트", "valorant",
                "로스트아크", "lost ark",
                "던전앤파이터", "메이플스토리",
                "나선 비경", "딜사이클", "딜 사이클",
                "리세마라", "가챠", "뽑기", "천장", "픽업", "메타", "티어표")) {
            return QueryDomain.GAME;
        }

        // 4. Subculture: 애니/웹툰/아이돌/V튜버/2차창작 등
        if (containsAny(q,
                "애니", "만화", "웹툰", "라노벨",
                "오타쿠", "서브컬처", "서브컬쳐",
                "아이돌", "덕질", "굿즈",
                "v튜버", "브이튜버", "버추얼 유튜버",
                "동인지", "커미션", "커미션 가격",
                "bl", "gl", "팬픽", "커플링", "커플링명")) {
            return QueryDomain.SUBCULTURE;
        }

        // Fallback: GENERAL
        return QueryDomain.GENERAL;
    }

    private boolean containsAny(String s, String... patterns) {
        for (String p : patterns) {
            if (s.contains(p)) return true;
        }
        return false;
    }
}
