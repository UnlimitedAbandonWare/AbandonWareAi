package com.example.lms.service.rag;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;




@Component
public class SimpleReranker {

    /**
     * 후보 Content 리스트를 쿼리와의 토큰 중첩 점수를 기반으로 재순위화합니다.
     *
     * @param query      사용자 원본 쿼리
     * @param candidates 재순위화할 Content 후보 리스트
     * @param limit      반환할 최대 결과 수
     * @return 점수 순으로 정렬된 Content 리스트
     */
    public List<Content> rerank(String query, List<Content> candidates, int limit) {
        // 1. 쿼리를 토큰 Set으로 변환
        Set<String> queryTokens = tokenize(query);

        // 2. 각 후보를 순회하며 점수 계산
        Map<Content, Double> scoredCandidates = new HashMap<>();
        for (Content candidate : candidates) {
            // Content 객체의 텍스트 내용을 토큰 Set으로 변환
            Set<String> candidateTokens = tokenize(candidate.textSegment().text());
            // 쿼리와 후보 간의 중첩 점수(자카드 유사도) 계산
            double score = calculateOverlap(queryTokens, candidateTokens);
            scoredCandidates.put(candidate, score);
        }

        // 3. 계산된 점수를 기준으로 내림차순 정렬
        Comparator<Map.Entry<Content, Double>> byScoreDescending =
                Map.Entry.<Content, Double>comparingByValue().reversed();

        return scoredCandidates.entrySet().stream()
                .sorted(byScoreDescending) // 점수가 높은 순으로 정렬
                .limit(limit)               // 상위 N개로 제한
                .map(Map.Entry::getKey)     // Content 객체만 추출
                .collect(Collectors.toList());
    }

    /**
     * 문자열을 소문자 토큰 Set으로 변환합니다.
     * 한글, 영문, 숫자만 유효 토큰으로 간주하며 1글자 토큰은 제외합니다.
     */
    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }

        // 정규식을 사용해 유효한 문자(한글, 영숫자, 공백)만 남기고 모두 제거
        String cleanedText = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^0-9a-z가-힣\\s]", " ");

        // 공백을 기준으로 분리하고, 2글자 이상인 토큰만 필터링하여 Set으로 반환
        return Arrays.stream(cleanedText.split("\\s+"))
                .filter(token -> token.length() > 1)
                .collect(Collectors.toSet());
    }

    /**
     * 두 토큰 Set 간의 자카드 유사도(Jaccard Similarity)를 계산합니다.
     * (교집합 크기) / (합집합 크기)
     */
    private double calculateOverlap(Set<String> setA, Set<String> setB) {
        if (setA.isEmpty() || setB.isEmpty()) {
            return 0.0;
        }

        // 교집합 계산
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        // 합집합 계산
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);

        // 자카드 유사도 반환
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
}