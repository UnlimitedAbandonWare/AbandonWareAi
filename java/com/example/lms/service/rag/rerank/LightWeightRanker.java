package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** 질의-문서 토큰 교집합 기반 경량 1차 랭커 */
@Component
public class LightWeightRanker {

    /** candidates 중 상위 limit 개만 반환 */
    public List<Content> rank(List<Content> candidates, String query, int limit) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();

        final Set<String> qTokens = tokens(query);
        record Scored(Content c, double s) {}

        List<Scored> scored = new ArrayList<>();
        int pos = 0;

        for (Content c : candidates) {
            String text = (c.textSegment() != null) ? c.textSegment().text() : String.valueOf(c);
            Set<String> t = tokens(text);

            if (qTokens.isEmpty() || t.isEmpty()) {
                // 질의/문서 토큰이 없으면 원순서 보존 가중
                scored.add(new Scored(c, 1.0 / (++pos)));
                continue;
            }
            long matches = t.stream().filter(qTokens::contains).count();
            double score = matches / (double) (qTokens.size() + 5.0); // 간단 스무딩
            scored.add(new Scored(c, score));
        }

        scored.sort((a, b) -> Double.compare(b.s(), a.s()));
        return scored.stream()
                .map(Scored::c)
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());
    }

    private Set<String> tokens(String s) {
        if (s == null || s.isBlank()) return Collections.emptySet();
        String[] raw = s.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}\\s]", " ")
                .split("\\s+");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : raw) {
            if (t.length() > 1) out.add(t); // 두 글자 이상
        }
        return out;
    }
}
