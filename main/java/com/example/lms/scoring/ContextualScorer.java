package com.example.lms.scoring;

import lombok.Builder;
import lombok.Value;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.regex.Pattern;




/**
 * 컨텍스트에 대한 '사실성/품질/신규성'을 간단히 추정하는 경량 스코어러.
 * - 사실성: 답변 키워드가 서로 다른 컨텍스트 라인에 N회 이상 등장하는지(grounding)
 * - 품질: 길이/문장수/불필요한 경고 비중 등 휴리스틱
 * - 신규성: 질문 대비 답변에만 등장하는 정보량(토큰 다양성) 근사
 * 점수는 0~1 사이로 반환.
 */
@Component
@RequiredArgsConstructor
public class ContextualScorer {

    /**
     * Path alignment scorer injected to adjust contextual scores based on the
     * similarity between previously observed navigation paths and the current
     * predicted path. When null, no multiplier is applied.
     */
    private final PathAlignedScorer pathAlignedScorer;

    @Value
    @Builder
    public static class ScoreReport {
        double factuality;
        double quality;
        double novelty;
        /** The multiplier applied based on path alignment. */
        double pathMultiplier;
        public double overall() {
            double base = 0.5 * factuality + 0.35 * quality + 0.15 * novelty;
            // Bound the result to [0,1] after applying the multiplier. A missing multiplier defaults to 1.0.
            double multiplier = (pathMultiplier > 0) ? pathMultiplier : 1.0;
            return Math.max(0.0, Math.min(1.0, base * multiplier));
        }
    }

    public ScoreReport score(String question, String unifiedContext, String answer) {
        return score(question, unifiedContext, answer, List.of(), List.of());
    }

    /**
     * Score the answer taking into account a predicted path and historic path information.
     *
     * @param question       the user question
     * @param unifiedContext the unified context provided to the model
     * @param answer         the generated answer
     * @param pastPath       a flattened historic path sequence
     * @param predictedPath  the path predicted for the current turn
     * @return a ScoreReport containing individual metrics and the overall score
     */
    public ScoreReport score(String question, String unifiedContext, String answer,
                             List<String> pastPath, List<String> predictedPath) {
        double factuality = groundingScore(unifiedContext, answer, 2);
        double quality    = qualityScore(answer);
        double novelty    = noveltyScore(question, answer);
        double multiplier = 1.0;
        if (pathAlignedScorer != null) {
            multiplier = pathAlignedScorer.score(pastPath, predictedPath);
        }
        return ScoreReport.builder()
                .factuality(factuality)
                .quality(quality)
                .novelty(novelty)
                .pathMultiplier(multiplier)
                .build();
    }

    /* --- 내부 휴리스틱 --- */
    private static double groundingScore(String ctx, String ans, int minLines) {
        if (!StringUtils.hasText(ctx) || !StringUtils.hasText(ans)) return 0;
        var entities = extractHeadTerms(ans);
        if (entities.isEmpty()) return 0.2; // 실명 키워드가 없으면 약한 점수
        String[] lines = ctx.split("\\R+");
        int ok = 0;
        for (String e : entities) {
            int c = 0;
            for (String ln : lines) if (ln.toLowerCase().contains(e)) c++;
            if (c >= minLines) ok++;
        }
        return Math.min(1.0, ok / (double) Math.max(1, entities.size()));
    }

    private static double qualityScore(String ans) {
        if (!StringUtils.hasText(ans)) return 0;
        int len = ans.length();
        int sentences = ans.split("[.!?\\n]").length;
        double lenOk = (len < 50) ? 0.2 : (len > 3000 ? 0.4 : 1.0);
        double sentOk = Math.min(1.0, sentences / 6.0);
        boolean hasApology = Pattern.compile("죄송|유감|정보 없음").matcher(ans).find();
        return Math.max(0, Math.min(1, 0.6*lenOk + 0.4*sentOk - (hasApology ? 0.2 : 0.0)));
    }

    private static double noveltyScore(String q, String a) {
        if (!StringUtils.hasText(a)) return 0;
        Set<String> qt = toTokens(q);
        Set<String> at = toTokens(a);
        if (at.isEmpty()) return 0;
        at.removeAll(qt);
        return Math.min(1.0, at.size() / 80.0); // 80개 이상이면 1.0 상한
    }

    private static Set<String> toTokens(String s) {
        if (!StringUtils.hasText(s)) return new HashSet<>();
        return new HashSet<>(Arrays.asList(s.toLowerCase().split("[^a-z0-9가-힣]+")));
    }
    private static List<String> extractHeadTerms(String s) {
        if (!StringUtils.hasText(s)) return List.of();
        var m = Pattern.compile("(?i)[A-Za-z가-힣0-9]{2,}").matcher(s);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group().toLowerCase());
        return out.stream().distinct().limit(20).toList();
    }
}