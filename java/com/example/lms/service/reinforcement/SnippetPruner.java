package com.example.lms.service.reinforcement;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jsoup.Jsoup;                   // html → text 정제
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 문장 단위 절삭(계층적 프루닝) 서비스.
 * 1) HTML/앵커 등 노이즈 제거
 * 2) 문장 분리 → embedAll
 * 3) 질의-문장 코사인 유사도 ≥ threshold 인 문장만 유지
 * 4) coverage/avgSimilarity 반환
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnippetPruner {

    private final EmbeddingModel embeddingModel;

    @Value("${memory.reinforce.pruning.enabled:true}")
    private boolean enabled;

    /** 문장 유지 임계값 (예: 0.6 이상만 keep) */
    @Value("${memory.reinforce.pruning.sentence-sim-threshold:0.6}")
    private double sentenceSimThreshold;

    /** 최소로 남겨야 할 문장 수(0이면 완전절삭 허용) */
    @Value("${memory.reinforce.pruning.min-sentences:1}")
    private int minSentences;

    /** 결과 구조체 */
    public static record Result(String refined, double avgSimilarity, double coverage,
                                int keptSentences, int totalSentences) {
        public static Result passThrough(String s) {
            String t = s == null ? "" : s.trim();
            return new Result(t, 1.0, 1.0, 1, 1);
        }
    }

    /** 외부에서 on/off 확인이 필요할 때 */
    public boolean isEnabled() { return enabled; }

    public Result prune(String query, String snippet) {
        if (!enabled) {
            return Result.passThrough(snippet);
        }
        String q = safe(query);
        String raw = safe(snippet);
        if (q.isBlank() || raw.isBlank()) {
            return Result.passThrough(snippet);
        }

        // 1) HTML 제거 후 문장 분리
        String plain = stripHtml(raw);
        List<String> sents = splitSentences(plain);
        int total = sents.size();
        if (total == 0) {
            return Result.passThrough(snippet);
        }

        // 2) 임베딩
        float[] qVec;
        try {
            qVec = embeddingModel.embed(q).content().vector();
        } catch (Exception e) {
            log.debug("[Pruner] embed(query) 실패 → passthrough: {}", e.toString());
            return Result.passThrough(snippet);
        }

        List<TextSegment> segs = new ArrayList<>(total);
        for (String s : sents) segs.add(TextSegment.from(s));

        List<Embedding> embs;
        try {
            embs = embeddingModel.embedAll(segs).content();
        } catch (Exception e) {
            log.debug("[Pruner] embedAll(sentences) 실패 → passthrough: {}", e.toString());
            return Result.passThrough(snippet);
        }

        // 3) 문장별 코사인 유사도 계산 & 임계값 이상 keep
        List<String> kept = new ArrayList<>();
        double sumSim = 0.0;
        for (int i = 0; i < embs.size(); i++) {
            float[] v = embs.get(i).vector();
            double sim = cosine(qVec, v);
            if (sim >= sentenceSimThreshold) {
                kept.add(sents.get(i));
                sumSim += sim;
            }
        }

        int keptN = kept.size();
        if (keptN == 0 || keptN < Math.max(0, minSentences)) {
            return new Result("", 0.0, 0.0, 0, total); // 완전 절삭
        }

        double coverage = (double) keptN / (double) total;
        double avgSim   = sumSim / keptN;
        String refined  = String.join(" ", kept).trim();

        return new Result(refined, clamp01(avgSim), clamp01(coverage), keptN, total);
    }

    /* ─────────────── helpers ─────────────── */

    private static String safe(String s) { return s == null ? "" : s; }

    private static String stripHtml(String html) {
        if (!StringUtils.hasText(html)) return "";
        try { return Jsoup.parse(html).text(); }
        catch (Throwable t) { return html.replaceAll("<[^>]+>", " "); }
    }

    /** 한국어 포함 단순 문장 분리(휴리스틱) */
    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(text)) return out;

        // 줄바꿈 우선 분해 후 마침표/물음표/느낌표/…/“다.”
        String[] lines = text.split("\\r?\\n+");
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty()) continue;

            String[] parts = s.split("(?<=[\\.?!？！…]|다\\.)\\s+");
            for (String p : parts) {
                String t = p.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        // 한 문장도 못 뽑았으면 전체를 1문장으로 취급
        if (out.isEmpty() && !text.isBlank()) {
            out.add(text.trim());
        }
        return out;
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null) return 0.0;
        int n = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }

    private static double clamp01(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0.0;
        return Math.max(0.0, Math.min(1.0, x));
    }
}
