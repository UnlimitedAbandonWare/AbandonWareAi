package com.example.lms.service.rag;

import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.filter.GenericDocClassifier;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.service.rag.rerank.RelationshipRuleScorer;
import com.example.lms.service.scoring.AdaptiveScoringService;
import com.example.lms.service.config.HyperparameterService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;




/**
 * A simple bi-encoder reranker that embeds the query and each candidate document
 * separately and scores them by cosine similarity.  This implementation does
 * not apply additional heuristics or domain-specific scoring but preserves the
 * constructor signature of the previous embedding reranker to minimise wiring
 * changes.  If topN is non-positive or larger than the number of candidates
 * the full list is returned.
 */
public class BiEncoderReranker implements CrossEncoderReranker {

    private final EmbeddingModel embeddingModel;
    private final KnowledgeBaseService knowledgeBase;
    private final GameDomainDetector domainDetector;
    private final AdaptiveScoringService adaptiveScorer;
    private final RelationshipRuleScorer ruleScorer;
    private final AuthorityScorer authorityScorer;
    private final HyperparameterService hyperparameters;
    private final GenericDocClassifier genericClassifier;

    public BiEncoderReranker(EmbeddingModel embeddingModel,
                             KnowledgeBaseService knowledgeBase,
                             GameDomainDetector domainDetector,
                             AdaptiveScoringService adaptiveScorer,
                             RelationshipRuleScorer ruleScorer,
                             AuthorityScorer authorityScorer,
                             HyperparameterService hyperparameters,
                             GenericDocClassifier genericClassifier) {
        this.embeddingModel = embeddingModel;
        this.knowledgeBase = knowledgeBase;
        this.domainDetector = domainDetector;
        this.adaptiveScorer = adaptiveScorer;
        this.ruleScorer = ruleScorer;
        this.authorityScorer = authorityScorer;
        this.hyperparameters = hyperparameters;
        this.genericClassifier = genericClassifier;
    }

    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            // Compute query embedding
            float[] qv = embeddingModel.embed(TextSegment.from(query)).content().vector();
            int n = candidates.size();
            int k = topN <= 0 ? n : Math.min(topN, n);
            // Snapshot and embed documents
            List<Content> snapshot = new ArrayList<>(candidates);
            List<TextSegment> segments = new ArrayList<>(n);
            for (Content c : snapshot) {
                if (c.textSegment() != null) {
                    segments.add(c.textSegment());
                } else {
                    segments.add(TextSegment.from(String.valueOf(c)));
                }
            }
            Response<List<Embedding>> batch = embeddingModel.embedAll(segments);
            List<Embedding> docEmbeddings = batch == null ? null : batch.content();
            if (docEmbeddings == null || docEmbeddings.size() != n) {
                return new ArrayList<>(snapshot.subList(0, k));
            }
            record Scored(int idx, double score) {}
            List<Scored> scored = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                float[] dv = docEmbeddings.get(i).vector();
                double sim = cosine(qv, dv);
                scored.add(new Scored(i, sim));
            }
            scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
            List<Content> result = new ArrayList<>(k);
            for (int i = 0; i < k; i++) {
                result.add(snapshot.get(scored.get(i).idx));
            }
            return result;
        } catch (Exception e) {
            // On any error fall back to original ordering
            int n = candidates.size();
            int k = topN <= 0 ? n : Math.min(topN, n);
            return new ArrayList<>(candidates.subList(0, k));
        }
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return -1.0;
        }
        double dot = 0.0;
        double n1 = 0.0;
        double n2 = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            n1 += a[i] * a[i];
            n2 += b[i] * b[i];
        }
        double denom = Math.sqrt(n1) * Math.sqrt(n2);
        return denom == 0.0 ? -1.0 : dot / denom;
    }
}