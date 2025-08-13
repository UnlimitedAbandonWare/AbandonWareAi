  package com.example.lms.service.rag;

import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.PromptEngine;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContextOrchestrator {

    private final PromptEngine promptEngine;

    @Value("${orchestrator.max-docs:10}")
    private int maxDocs;
    @Value("${orchestrator.min-top-score:0.60}")
    private double minTopScore;

    // '최신성' 요구 쿼리를 감지하기 위한 정규식
    private static final Pattern TIMELY = Pattern.compile("(?i)(공지|업데이트|패치|스케줄|일정|news|update|patch|release)");

    /**
     * 여러 정보 소스를 바탕으로 최종 컨텍스트를 조율하고, 동적 규칙을 포함하여 프롬프트를 생성합니다.
     * @param query 사용자 원본 쿼리
     * @param vectorResults Vector DB 검색 결과
     * @param webResults 웹 검색 결과
     * @param interactionRules 동적 상호작용 규칙
     * @return LLM에 전달될 최종 프롬프트 문자열 또는 "정보 없음"
     */
    public String orchestrate(String query,
                              List<Content> vectorResults,
                              List<Content> webResults,
                              Map<String, Set<String>> interactionRules) {
        List<Scored> pool = new ArrayList<>();
        boolean wantsFresh = query != null && TIMELY.matcher(query).find();

        // 1. 각 소스별 결과를 점수화하여 풀(pool)에 추가
        addAll(pool, vectorResults, wantsFresh, Source.VECTOR);
        addAll(pool, webResults, wantsFresh, Source.WEB);

        if (pool.isEmpty()) {
            return "정보 없음";
        }

        // 2. 텍스트 내용 기반으로 중복 제거
        LinkedHashMap<String, Scored> uniq = new LinkedHashMap<>();
        for (Scored s : pool) {
            uniq.putIfAbsent(hashOf(s.text()), s);
        }

        // 3. 점수 내림차순으로 상위 N개 문서 선택
        List<Content> finalDocs = uniq.values().stream()
                .sorted(Comparator.comparingDouble(s -> -s.score)) // 점수 내림차순 정렬
                .limit(Math.max(1, maxDocs))
                .map(s -> s.content)
                .collect(Collectors.toList());

        // 4. 안전장치: 가장 높은 점수가 기준 미달이면 신뢰할 수 없는 정보로 판단하고 차단
        double topScore = uniq.values().stream().mapToDouble(Scored::score).max().orElse(0.0);
        if (topScore < minTopScore) {
            log.warn("[Orchestrator] Top score ({}) is below the minimum threshold ({}). Returning '정보 없음'.", String.format("%.2f", topScore), minTopScore);
            return "정보 없음";
        }

        // 5. 최종 프롬프트 생성을 위한 PromptContext 구성 및 호출
        PromptContext ctx = PromptContext.builder()
                .rag(finalDocs)
                .web(List.of()) // 웹 결과는 이미 finalDocs에 통합되었으므로 별도 전달 필요 없음
                .domain("GENERAL")
                .intent("GENERAL")
                .interactionRules(interactionRules == null ? Map.of() : interactionRules)
                .build();
        return promptEngine.createPrompt(ctx);
    }



    // NOTE: 중복 오버로드 정리 — 4인자(규칙 포함) 메서드만 유지합니다.

    /**
     * 점수화된 컨텐츠 목록을 누적기에 추가합니다.
     */
    private void addAll(List<Scored> acc, List<Content> src, boolean wantsFresh, Source source) {
        if (src == null || src.isEmpty()) return;

        int rank = 0;
        for (Content c : src) {
            rank++;
            String text = Optional.ofNullable(c.textSegment()).map(TextSegment::text).orElse(c.toString());
            if (text == null || text.isBlank()) continue;

            double baseScore = 1.0 / rank; // 순위가 높을수록 기본 점수가 높음
            double freshnessBonus = (wantsFresh && source == Source.WEB) ? 0.25 : 0.0; // 최신성 요구 시 웹 검색에 보너스
            double lengthPenalty = Math.max(0, (text.length() - 1200) / 1200.0); // 긴 텍스트에 약간의 페널티
            double score = Math.max(0.0, Math.min(1.0, baseScore + freshnessBonus - 0.1 * lengthPenalty));

            acc.add(new Scored(c, score, source));
        }
    }

    /**
     * 중복 제거를 위한 간단한 해시 생성
     */
    private static String hashOf(String s) {
        return Integer.toHexString(Objects.requireNonNullElse(s, "").hashCode());
    }

    /**
     * 점수와 출처 메타데이터를 포함하는 내부 DTO
     */
    private record Scored(Content content, double score, Source source) {
        String text() {
            return Optional.ofNullable(content.textSegment()).map(TextSegment::text).orElse(content.toString());
        }
    }

    /**
     * 정보 출처 Enum
     */
    private enum Source {
        VECTOR, WEB
    }
}