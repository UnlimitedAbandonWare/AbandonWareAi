package com.example.lms.service.rag.heuristics;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;



/**
 * A simple heuristic extractor used when the self-ask LLM fails or times
 * out.  It strips out common corporate suffixes and punctuation and
 * returns the first token as a conservative estimate of the primary
 * entity.  This implementation is intentionally naïve; callers should
 * treat the output as a hint rather than a definitive keyword list.
 */
public class KeywordHeuristics {

    private static final Pattern PUNCT = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern KOREAN_PARTICLE =
            Pattern.compile("(은|는|이|가|을|를|에|에서|으로|로|과|와|랑|에게|께|도|만|까지|부터)$");
    private static final Set<String> STOPWORDS = Set.of(
            "정보", "알려줘", "알려", "검색", "찾아줘", "찾아",
            "대한", "관련", "설명", "소개", "요약", "정리", "대해", "좀"
    );

    /**
     * Extract the most salient keyword from the provided text.  The
     * implementation removes non-alphanumeric characters, trims common
     * corporate suffixes and splits on whitespace.  If no tokens remain an
     * empty list is returned.
     *
     * @param text the input query (may be null)
     * @return a list containing zero or one keyword
     */
    public List<String> extractCoreKeywords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 1) 과도한 특수문자만 제거 (영문+숫자, 한글 조합은 유지)
        String cleaned = PUNCT.matcher(text).replaceAll(" ");
        // 2) 회사/법인 접미사 제거
        cleaned = cleaned.replaceAll("(주식회사|㈜|Inc\\.|Co\\.|Ltd\\.|Technologies|테크놀로지스)", " ").trim();
        String[] rawTokens = cleaned.split("\\s+");
        if (rawTokens.length == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String tok : rawTokens) {
            if (tok.length() <= 1) {
                continue; // 한 글자 토큰은 대부분 의미 없음
            }
            // 3) 한국어 조사 제거 (너무 짧은 토큰은 건너뜀)
            String core = tok;
            if (core.length() >= 3) {
                core = KOREAN_PARTICLE.matcher(core).replaceAll("");
            }
            if (core.length() <= 1) {
                continue;
            }
            if (STOPWORDS.contains(core)) {
                continue;
            }
            out.add(core);
        }
        if (out.isEmpty() && rawTokens.length > 0) {
            // 최후의 안전장치: 첫 원시 토큰이라도 반환
            out.add(rawTokens[0]);
        }
        return out;
    }
}