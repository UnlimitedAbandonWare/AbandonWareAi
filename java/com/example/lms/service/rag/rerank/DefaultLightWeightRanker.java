// src/main/java/com/example/lms/service/rag/rerank/DefaultLightWeightRanker.java
package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;




/** 질의-문서 토큰 교집합 기반 경량 1차 랭커 구현 */
@Component
public class DefaultLightWeightRanker implements LightWeightRanker {

    @Override
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
                // 질의/문서 토큰이 비어 있으면 임시로만 보관(폴백 대비)
                scored.add(new Scored(c, 1.0 / (++pos)));
                continue;
            }
            long matches = t.stream().filter(qTokens::contains).count();
            if (matches == 0) {
                // 핵심어 0 교집합 → 1차에서 제외
                continue;
            }
            double score = matches / (double) (qTokens.size() + 5.0);
            scored.add(new Scored(c, score));
        }

        if (scored.isEmpty()) {
            // 전부 탈락했으면 안전 폴백(원순서 상위)
            int k = Math.max(1, limit);
            List<Content> fallback = new ArrayList<>(candidates);
            return fallback.subList(0, Math.min(k, fallback.size()));
        }
        scored.sort((a, b) -> Double.compare(b.s(), a.s()));
        return scored.stream().map(Scored::c).limit(Math.max(1, limit)).collect(Collectors.toList());
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