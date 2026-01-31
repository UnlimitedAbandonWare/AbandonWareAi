// src/main/java/com/example/lms/service/rag/EmbeddingModelCrossEncoderReranker.java
package com.example.lms.service.rag;

import com.example.lms.domain.enums.RerankSourceCredibility;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.service.rag.rerank.RelationshipRuleScorer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



import java.util.Map;       // ✅
import java.util.Objects;  // ✅
import java.util.Set;      // ✅

/**
 * 임베딩 기반 Cross-Encoder 스타일 리랭커(관계 규칙 지원).
 * - 신뢰도 감쇠(AuthorityScorer)
 * - 동적 시너지 가중치(HyperparameterService)
 */
@RequiredArgsConstructor
public class EmbeddingModelCrossEncoderReranker implements CrossEncoderReranker {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelCrossEncoderReranker.class);

    private final EmbeddingModel embeddingModel;
    private final com.example.lms.service.knowledge.KnowledgeBaseService knowledgeBase;
    private final com.example.lms.service.rag.detector.GameDomainDetector domainDetector;
    private final com.example.lms.service.scoring.AdaptiveScoringService adaptiveScorer;
    private final RelationshipRuleScorer ruleScorer;
    private final AuthorityScorer authorityScorer;         // credibility & decay
    private final HyperparameterService hyperparameters;   // runtime synergy weight
    private final com.example.lms.service.rag.filter.GenericDocClassifier genericClassifier;

    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        try {
            // 쿼리 임베딩
            final float[] qv = embeddingModel.embed(TextSegment.from(query)).content().vector();
            final int n = candidates.size();
            final int k = Math.max(1, Math.min(topN, n));

            // Content → TextSegment 스냅샷
            final List<Content> snapshot = new ArrayList<>(candidates);
            final List<TextSegment> segments = snapshot.stream()
                    .map(c -> Optional.ofNullable(c.textSegment())
                            .orElseGet(() -> TextSegment.from(String.valueOf(c))))
                    .collect(Collectors.toList());

            // 배치 임베딩 + 시간 로깅
            long t0 = System.nanoTime();
            Response<List<Embedding>> batch = embeddingModel.embedAll(segments);
            long tEmbedMs = (System.nanoTime() - t0) / 1_000_000L;

            List<Embedding> docEmbeddings = (batch != null) ? batch.content() : null;
            if (docEmbeddings == null || docEmbeddings.size() != n) {
                log.warn("embedAll() returned {} embeddings for {} candidates; fallback to original order.",
                        (docEmbeddings == null ? 0 : docEmbeddings.size()), n);
                return new ArrayList<>(snapshot.subList(0, k));
            }

            String domain = domainDetector.detect(query);
            String subject = com.example.lms.service.subject.SubjectResolver
                    .guessSubjectFromQueryStatic(knowledgeBase, domain, query);

            record ScoredContent(Content content, double score) {}

            final double synergyWeight = clamp01to2(hyperparameters.getRerankSynergyWeight());
            if (log.isDebugEnabled()) {
                log.debug("[Rerank] synergyWeight(runtime)={}", synergyWeight);
            }
            final List<ScoredContent> scored = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Content c = snapshot.get(i);
                TextSegment seg = segments.get(i);
                String text = seg.text();

                double sim = cosine(qv, docEmbeddings.get(i).vector()); // ✅ float[] 서명 일치
                boolean hasSubject = !subject.isBlank() && text.toLowerCase().contains(subject.toLowerCase());

                // 도메인 인식: GENERAL 또는 PRODUCT 는 완화, GENSHIN/EDUCATION 은 기존 유지
                boolean isGeneral = "GENERAL".equalsIgnoreCase(domain) || "PRODUCT".equalsIgnoreCase(domain);
                double subjectTerm = isGeneral
                        ? (hasSubject ? 0.10 : 0.0)      // GENERAL: 미검출 페널티 제거
                        : (hasSubject ? 0.15 : -0.10);   // 특화 도메인: 완화

                // genericTerm: GENSHIN/EDUCATION 도메인만 페널티 적용
                double genericTerm;
                if ("GENSHIN".equalsIgnoreCase(domain) || "EDUCATION".equalsIgnoreCase(domain)) {
                    boolean isGen = genericClassifier.isGenericText(text, domain);
                    genericTerm = isGen ? -0.25 : 0.0;
                } else {
                    genericTerm = 0.0;
                }

                // 파트너 추출 → 적응형 궁합 보너스
                String partner = knowledgeBase.findFirstMentionedEntityExcluding(domain, text, subject).orElse("");
                double synergyBonus = adaptiveScorer.getSynergyScore(domain, subject, partner); // [-0.05, +0.10]

                // 🆕 출처 신뢰도 감쇠 적용
                String url = safeUrl(c, seg);
                RerankSourceCredibility cred = authorityScorer.getSourceCredibility(url);
                double authorityDecayMultiplier = authorityScorer.decayFor(cred);
                if (log.isTraceEnabled()) {
                    log.trace("[Rerank] url={} cred={} decay={}", url, cred, authorityDecayMultiplier);
                }

                double score = (sim + subjectTerm + genericTerm + (synergyBonus * synergyWeight))
                        * authorityDecayMultiplier;

                if (log.isDebugEnabled()) {
                    log.debug("[Rerank] domain={} subjectTerm={} genericTerm={} decay={}", domain, subjectTerm, genericTerm, authorityDecayMultiplier);
                }
                scored.add(new ScoredContent(c, score));
            }

            long ts = System.nanoTime();
            scored.sort(Comparator.comparingDouble(ScoredContent::score).reversed());
            long tSortMs = (System.nanoTime() - ts) / 1_000_000L;

            if (log.isDebugEnabled()) {
                log.debug("[Rerank] embedAll.count={} embed.ms={} sort.ms={} topN/total={}/{}",
                        n, tEmbedMs, tSortMs, k, n);
            }

            return scored.stream()
                    .limit(k)
                    .map(ScoredContent::content)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("임베딩 기반 리랭킹 실패. 원본 순서로 대체합니다. Query: '{}', Error: {}", query, e.getMessage());
            int k = Math.max(1, Math.min(topN, candidates.size()));
            return new ArrayList<>(candidates.subList(0, k));
        }
    }

    /** 관계 규칙을 고려한 오버로드(스코어에 ruleDelta 반영) */
    @Override
    public List<Content> rerank(String query,
                                List<Content> candidates,
                                int topN,
                                Map<String, Set<String>> interactionRules) {
        if (interactionRules == null || interactionRules.isEmpty()) {
            return rerank(query, candidates, topN);
        }
        try {
            final float[] qv = embeddingModel.embed(TextSegment.from(query)).content().vector();
            final int n = (candidates == null ? 0 : candidates.size());
            if (n == 0) return List.of();
            final int k = Math.max(1, Math.min(topN, n));

            final List<Content> snapshot = new ArrayList<>(candidates);
            final List<TextSegment> segments = snapshot.stream()
                    .map(c -> Optional.ofNullable(c.textSegment()).orElseGet(() -> TextSegment.from(String.valueOf(c))))
                    .collect(Collectors.toList());

            Response<List<Embedding>> batch = embeddingModel.embedAll(segments);
            List<Embedding> docEmbeddings = (batch == null) ? null : batch.content();
            if (docEmbeddings == null || docEmbeddings.size() != n) {
                int k2 = Math.max(1, Math.min(topN, candidates.size()));
                return new ArrayList<>(candidates.subList(0, k2));
            }

            String domain = domainDetector.detect(query);
            String subject = com.example.lms.service.subject.SubjectResolver
                    .guessSubjectFromQueryStatic(knowledgeBase, domain, query);

            record SC(Content c, double s) {}
            final double synergyWeight = clamp01to2(hyperparameters.getRerankSynergyWeight());

            List<SC> scored = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Content c = snapshot.get(i);
                TextSegment seg = segments.get(i);
                String text = seg.text();

                double sim = cosine(Objects.requireNonNull(qv), docEmbeddings.get(i).vector());
                boolean hasSubject = !subject.isBlank() && text.toLowerCase().contains(subject.toLowerCase());
                double ruleDelta = ruleScorer.deltaForText(text, interactionRules); // 관계 규칙 점수

                // 도메인 인식: GENERAL/PRODUCT 완화
                boolean isGeneral = "GENERAL".equalsIgnoreCase(domain) || "PRODUCT".equalsIgnoreCase(domain);
                double subjectTerm = isGeneral
                        ? (hasSubject ? 0.10 : 0.0)
                        : (hasSubject ? 0.15 : -0.10);
                double genericTerm;
                if ("GENSHIN".equalsIgnoreCase(domain) || "EDUCATION".equalsIgnoreCase(domain)) {
                    boolean isGen = genericClassifier.isGenericText(text, domain);
                    genericTerm = isGen ? -0.25 : 0.0;
                } else {
                    genericTerm = 0.0;
                }

                String partner = knowledgeBase.findFirstMentionedEntityExcluding(domain, text, subject).orElse("");
                double synergyBonus = adaptiveScorer.getSynergyScore(domain, subject, partner);

                // 🆕 출처 신뢰도 감쇠 적용
                String url = safeUrl(c, seg);
                RerankSourceCredibility cred = authorityScorer.getSourceCredibility(url);
                double authorityDecayMultiplier = authorityScorer.decayFor(cred);

                double score = (sim + subjectTerm + genericTerm + ruleDelta + (synergyBonus * synergyWeight))
                        * authorityDecayMultiplier;

                if (log.isDebugEnabled()) {
                    log.debug("[Rerank] domain={} subjectTerm={} genericTerm={} ruleDelta={} decay={}", domain, subjectTerm, genericTerm, ruleDelta, authorityDecayMultiplier);
                }
                scored.add(new SC(c, score));
            }
            scored.sort(Comparator.comparingDouble(SC::s).reversed());
            return scored.stream().limit(k).map(SC::c).toList();
        } catch (Exception e) {
            int k = Math.max(1, Math.min(topN, candidates.size()));
            return new ArrayList<>(candidates.subList(0, k));
        }
    }

    private static double cosine(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length || v1.length == 0) return 0.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            na  += v1[i] * v1[i];
            nb  += v2[i] * v2[i];
        }
        double den = Math.sqrt(na) * Math.sqrt(nb);
        return den == 0 ? 0.0 : dot / den;
    }

    // clamp to [0, 2], default 1.0 on NaN/Infinity
    private static double clamp01to2(double w) {
        if (Double.isNaN(w) || Double.isInfinite(w)) return 1.0;
        return Math.max(0.0, Math.min(2.0, w));
    }

    // 🆕 메타데이터/세그먼트에서 URL 안전 추출 (LangChain4j 1.0.1: Metadata#getString만 사용)
    private static String safeUrl(Content c, TextSegment seg) {
        try {
            if (seg == null || seg.metadata() == null) return null;
            var md = seg.metadata();
            String v = md.getString("url");
            if (isBlank(v)) v = md.getString("sourceUrl");
            if (isBlank(v)) v = md.getString("link");
            if (isBlank(v)) v = md.getString("source");
            // 필요 시 Document.URL 키를 쓰고 싶다면 아래 한 줄 해제:
            // if (isBlank(v)) v = md.getString(dev.langchain4j.data.document.Document.URL);
            return isBlank(v) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}