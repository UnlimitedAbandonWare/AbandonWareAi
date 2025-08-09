package com.example.lms.service.reinforcement;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 스니펫에서 핵심 문장만 추출(Pruning)하는 서비스입니다.
 * <p>
 * <b>주요 전략 (Primary Strategy):</b> 쿼리와 각 문장의 코사인 유사도를 계산하는 <b>임베딩 기반 방식</b>을 사용합니다.
 * <p>
 * <b>대체 전략 (Fallback Strategy):</b> 임베딩 모델 호출 실패 시, 시스템 안정성을 위해 <b>키워드 토큰 매칭 방식</b>으로 자동 전환됩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnippetPruner {

    private final EmbeddingModel embeddingModel;

    // --- 설정 값 ---
    @Value("${memory.reinforce.pruning.enabled:true}")
    private boolean enabled;

    @Value("${memory.reinforce.pruning.sentence-sim-threshold:0.6}")
    private double sentenceSimThreshold;

    @Value("${memory.reinforce.pruning.min-sentences:1}")
    private int minSentences;

    /**
     * 프루닝(가지치기) 결과 구조체
     *
     * @param refined        정제된 최종 텍스트
     * @param avgSimilarity  유지된 문장들의 평균 유사도
     * @param coverage       원본 대비 유지된 문장 수의 비율
     * @param keptSentences  유지된 문장 수
     * @param totalSentences 원본의 전체 문장 수
     */
    public static record Result(
            String refined,
            double avgSimilarity,
            double coverage,
            int keptSentences,
            int totalSentences
    ) {
        /** 원본 텍스트를 그대로 통과시키는 결과를 생성합니다. */
        public static Result passThrough(String s) {
            String text = (s == null) ? "" : s.trim();
            // Jsoup으로 한번 정제하여 HTML 태그를 제거해준다.
            String plainText = stripHtml(text);
            return new Result(plainText, 1.0, 1.0, 1, 1);
        }
    }

    /** 서비스 활성화 여부 확인 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 스니펫을 정제하는 메인 메서드.
     * @param query   사용자 쿼리
     * @param snippet 검색 결과 스니펫
     * @return 정제 결과(Result)
     */
    public Result prune(String query, String snippet) {
        if (!enabled) {
            return Result.passThrough(snippet);
        }
        String q = safe(query);
        String rawSnippet = safe(snippet);

        if (q.isBlank() || rawSnippet.isBlank()) {
            return Result.passThrough(snippet);
        }

        try {
            // 1. 주요 전략: 임베딩 기반 프루닝 시도
            return pruneByEmbedding(q, rawSnippet);
        } catch (Exception e) {
            // 2. 대체 전략: 임베딩 실패 시 토큰 기반 프루닝으로 자동 전환
            log.warn("[Pruner] Embedding-based pruning failed. Falling back to token-based method. Reason: {}", e.getMessage());
            return pruneByTokenMatching(q, rawSnippet);
        }
    }

    // ===================================================================================
    // 1. 주요 전략 (Primary Strategy): 임베딩 기반 프루닝
    // ===================================================================================
    private Result pruneByEmbedding(String query, String snippet) {
        String plainText = stripHtml(snippet);
        List<String> sentences = splitSentences(plainText);
        int totalSentences = sentences.size();

        if (totalSentences == 0) {
            return Result.passThrough(snippet);
        }

        // 쿼리와 문장들을 임베딩합니다.
        float[] queryVector = embeddingModel.embed(query).content().vector();
        List<TextSegment> segments = sentences.stream().map(TextSegment::from).collect(Collectors.toList());
        List<Embedding> sentenceEmbeddings = embeddingModel.embedAll(segments).content();

        // 문장별 코사인 유사도를 계산하여 임계값 이상인 문장만 유지합니다.
        List<String> keptSentences = new ArrayList<>();
        double sumOfSimilarities = 0.0;

        for (int i = 0; i < sentenceEmbeddings.size(); i++) {
            float[] sentenceVector = sentenceEmbeddings.get(i).vector();
            double similarity = cosine(queryVector, sentenceVector);
            if (similarity >= sentenceSimThreshold) {
                keptSentences.add(sentences.get(i));
                sumOfSimilarities += similarity;
            }
        }

        int keptCount = keptSentences.size();
        if (keptCount == 0 || keptCount < minSentences) {
            return new Result("", 0.0, 0.0, 0, totalSentences); // 유지할 문장이 없으면 비움
        }

        double coverage = (double) keptCount / totalSentences;
        double avgSimilarity = sumOfSimilarities / keptCount;
        String refinedText = String.join(" ", keptSentences).trim();

        return new Result(refinedText, clamp01(avgSimilarity), clamp01(coverage), keptCount, totalSentences);
    }

    // ===================================================================================
    // 2. 대체 전략 (Fallback Strategy): 키워드 토큰 기반 프루닝
    // ===================================================================================
    private Result pruneByTokenMatching(String query, String snippet) {
        String plainText = stripHtml(snippet);
        Set<String> queryTokens = Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\W+"))
                .filter(t -> !t.isBlank() && t.length() > 1)
                .collect(Collectors.toSet());

        List<String> sentences = splitSentences(plainText);
        int totalSentences = sentences.size();
        if (totalSentences == 0) {
            return Result.passThrough(snippet);
        }

        List<String> keptSentences = new ArrayList<>();
        // 쿼리 토큰을 포함하는 문장만 유지합니다.
        for (String sentence : sentences) {
            String lowerCaseSentence = sentence.toLowerCase(Locale.ROOT);
            boolean isMatch = queryTokens.isEmpty() || queryTokens.stream().anyMatch(lowerCaseSentence::contains);
            if (isMatch) {
                keptSentences.add(sentence.trim());
            }
        }

        // 유지할 문장이 없으면 원본을 반환하여 정보 손실을 최소화합니다.
        if (keptSentences.isEmpty()) {
            return Result.passThrough(snippet);
        }

        String refinedText = String.join(" ", keptSentences).trim();
        double coverage = Math.min(1.0, (double) keptSentences.size() / totalSentences);

        // 유사도는 매칭된 토큰 비율로 근사치를 계산합니다.
        long matchedTokenCount = queryTokens.stream().filter(refinedText.toLowerCase(Locale.ROOT)::contains).count();
        double approxSimilarity = queryTokens.isEmpty() ? 1.0 : (double) matchedTokenCount / queryTokens.size();

        return new Result(refinedText, clamp01(approxSimilarity), coverage, keptSentences.size(), totalSentences);
    }

    // ===================================================================================
    // 헬퍼 메서드 (Helper Methods)
    // ===================================================================================

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    private static String stripHtml(String html) {
        if (!StringUtils.hasText(html)) return "";
        try {
            return Jsoup.parse(html).text();
        } catch (Throwable t) {
            // Jsoup 실패 시 간단한 정규식으로 대체
            return html.replaceAll("<[^>]+>", " ");
        }
    }

    private static List<String> splitSentences(String text) {
        if (!StringUtils.hasText(text)) return Collections.emptyList();

        // 문장 분리 정규식: 마침표, 물음표, 느낌표, 또는 '다.' 뒤의 공백을 기준으로 분리
        String[] parts = text.split("(?<=[.?!？！…]|다\\.)\\s+");
        List<String> sentences = Arrays.stream(parts)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        // 한 문장도 못 나눴다면, 전체를 하나의 문장으로 취급
        if (sentences.isEmpty() && !text.isBlank()) {
            sentences.add(text.trim());
        }
        return sentences;
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dotProduct = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static double clamp01(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val)) return 0.0;
        return Math.max(0.0, Math.min(1.0, val));
    }
}