package com.example.lms.service.rag.overdrive;

import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.service.rag.auth.AuthorityScorer;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.util.regex.Pattern;




/** 희소성/권위/모순 신호를 합성해 Overdrive 발동 여부를 판단 */
@Component
public class OverdriveGuard {

    private final AuthorityScorer authority;
    private final ContradictionScorer contradiction;

    public OverdriveGuard(AuthorityScorer authority, ContradictionScorer contradiction) {
        this.authority = authority;
        this.contradiction = contradiction;
    }

    @Value("${rag.overdrive.enabled:true}") private boolean enabled;
    @Value("${rag.overdrive.trigger.min-pool:4}") private int minPool;
    @Value("${rag.overdrive.trigger.min-authority:0.55}") private double minAuthorityAvg;
    @Value("${rag.overdrive.trigger.contradiction-th:0.60}") private double contradictionTh;
    @Value("${rag.overdrive.trigger.score-th:0.55}") private double scoreThreshold;

    public boolean shouldActivate(String query, List<Content> candidates) {
        if (!enabled) return false;
        if (candidates == null) candidates = List.of();

        // 1) 희소성 (풀 사이즈가 작을수록 ↑)
        double sparse = candidates.size() < minPool
                ? (minPool - candidates.size()) / (double) Math.max(1, minPool)
                : 0.0;

        // 2) 권위 평균
        double avgAuth = candidates.stream()
                .map(c -> decayForUrl(extractUrl(text(c))))
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average().orElse(0.25);
        double lowAuthority = avgAuth < minAuthorityAvg
                ? (minAuthorityAvg - avgAuth) / minAuthorityAvg : 0.0;

        // 3) 상호 모순 (상위 몇 쌍만 간단 체크; LLM 없으면 0)
        double contrad = pairwiseContradictionMean(candidates, 3);
        double highContrad = contrad > contradictionTh ? (contrad - contradictionTh) / (1.0 - contradictionTh) : 0.0;

        // 4) 합성 점수
        double score = 0.5 * sparse + 0.3 * lowAuthority + 0.2 * highContrad;
        return score >= scoreThreshold;
    }

    private static String text(Content c) {
        return c == null || c.textSegment() == null ? null : c.textSegment().text();
    }

    private static String extractUrl(String text) {
        if (text == null) return null;
        int i = text.indexOf("http");
        if (i < 0) return null;
        int sp = text.indexOf(' ', i);
        return sp > i ? text.substring(i, sp) : text.substring(i);
    }

    private Double decayForUrl(String url) {
        if (url == null) return 0.25;
        var cred = authority.getSourceCredibility(url);
        return authority.decayFor(cred);
    }

    private double pairwiseContradictionMean(List<Content> cs, int maxPairs) {
        List<String> texts = cs.stream().map(OverdriveGuard::text).filter(Objects::nonNull).toList();
        int n = texts.size();
        if (n < 2) return 0.0;
        int used = 0; double sum = 0.0;
        for (int i = 0; i < n && used < maxPairs; i++) {
            for (int j = i + 1; j < n && used < maxPairs; j++) {
                sum += contradiction.score(texts.get(i), texts.get(j));
                used++;
            }
        }
        return used == 0 ? 0.0 : (sum / used);
    }
}