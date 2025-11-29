package com.example.lms.service.reinforcement;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import dev.langchain4j.data.message.UserMessage;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * 스니펫에서 핵심 문장만 추출(Pruning)하는 서비스입니다.
 * <p>
 * <b>주요 전략 (Primary Strategy):</b> 쿼리와 각 문장의 코사인 유사도를 계산하는 <b>임베딩 기반 방식</b>을 사용합니다.
 * <p>
 * <b>대체 전략 (Fallback Strategy):</b> 임베딩 모델 호출 실패 시, 시스템 안정성을 위해 <b>키워드 토큰 매칭 방식</b>으로 자동 전환됩니다.
 */
@Service
@RequiredArgsConstructor
public class SnippetPruner {
    private static final Logger log = LoggerFactory.getLogger(SnippetPruner.class);

    private final EmbeddingModel embeddingModel;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private dev.langchain4j.model.chat.ChatModel chatModel; // (옵션) LLM 절사용



    // --- 설정 값 ---
    @Value("${memory.reinforce.pruning.enabled:true}")
    private boolean enabled;

    @Value("${memory.reinforce.pruning.sentence-sim-threshold:0.6}")
    private double sentenceSimThreshold;

    @Value("${memory.reinforce.pruning.min-sentences:1}")
    private int minSentences;
    @Value("${memory.reinforce.pruning.llm.enabled:false}")
    private boolean llmPruningEnabled;

    // [HARDENING] Prompt injection pattern; drop snippets containing these triggers
    private static final java.util.regex.Pattern BLOCK =
            java.util.regex.Pattern.compile(
                    "(?i)\\b(ignore\\s+previous|system\\s*:|##\\s*시스템|do\\s*not\\s*follow\\s*above)\\b");


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

        // [HARDENING] drop snippet if it contains prompt-injection patterns
        try {
            String plain = stripHtml(rawSnippet);
            if (BLOCK.matcher(plain).find()) {
                return new Result("", 0.0, 0.0, 0, 0);
            }
        } catch (Exception ignore) {}

        try {
            // 1) 임베딩 기반
            Result r = pruneByEmbedding(q, rawSnippet);
            if (r.keptSentences() >= Math.max(1, minSentences)) return r;
        } catch (Exception e) {
            log.warn("[Pruner] embed pruning failed → {}", e.toString());
        }
        // 2) (옵션) LLM 절사
        if (llmPruningEnabled && chatModel != null) {
            try {
                Result r = pruneByLLM(q, rawSnippet);
                if (r.keptSentences() > 0) return r;
            } catch (Exception ignore) {}
        }
        // 3) 토큰 기반
        return pruneByTokenMatching(q, rawSnippet);
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
        String[] parts = text.split("(?<=[.?!？！/* ... *&#47;]|다\\.)\\s+");
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

    // ----------------------------------------------------------
    // LLM 기반 절사 (옵션)
    // ----------------------------------------------------------
    private Result pruneByLLM(String query, String snippet) {
        if (chatModel == null) return Result.passThrough(snippet);
        String prompt = """
            아래 "문서"에서 질문과 직접 관련된 핵심 문장만 남겨 한 단락으로 요약해 주세요.
            - 문서의 사실만 사용 (추가 추론 금지)
            - 중요 문장이 없으면 빈 문자열
            질문: %s
            문서:
            %s
            """.formatted(query, stripHtml(snippet));
        String refined = chatModel
                .chat(java.util.List.of(UserMessage.from(prompt)))
                .aiMessage()
                .text();
        refined = safe(refined).trim();
        if (refined.isBlank()) return new Result("", 0.0, 0.0, 0, 1);
        // 근사치 메타
        List<String> ss = splitSentences(refined);
        double approx = Math.min(1.0, Math.max(0.0, (double) ss.size() / Math.max(1, splitSentences(stripHtml(snippet)).size())));
        return new Result(refined, 0.8, approx, ss.size(), Math.max(1, splitSentences(stripHtml(snippet)).size()));
    }
}