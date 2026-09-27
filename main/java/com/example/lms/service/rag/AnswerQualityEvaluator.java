package com.example.lms.service.rag;

import com.example.lms.metrics.FaithfulnessMetricSnapshotStore;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;




@Component
@RequiredArgsConstructor
public class AnswerQualityEvaluator {
    private static final Logger log = LoggerFactory.getLogger(AnswerQualityEvaluator.class);
    private final EmbeddingModel embeddingModel;

    public enum Decision {
        ACCEPT,
        REPAIR_WITH_WEB,
        REWRITE_QUERY,
        ABSTAIN
    }

    public record RetrievalEvaluation(
            Decision decision,
            double confidence,
            int docCount,
            int distinctSources,
            String reason
    ) {
    }

    private enum Sufficiency {
        SUFFICIENT,
        INSUFFICIENT,
        UNAVAILABLE
    }

    /**
     * 로컬 문서 집합이 충분한지 점검합니다.
     * 기준: 최소 문서 수(minDocs) + 평균 코사인 유사도(minAvgScore)
     */
    public boolean isSufficient(String query, List<Content> docs, int minDocs, double minAvgScore) {
        return evaluateSufficiency(query, docs, minDocs, minAvgScore) == Sufficiency.SUFFICIENT;
    }

    private Sufficiency evaluateSufficiency(String query, List<Content> docs, int minDocs, double minAvgScore) {
        if (docs == null || docs.size() < Math.max(1, minDocs)) return Sufficiency.INSUFFICIENT;

        try {
            float[] queryVector = embeddingModel.embed(query).content().vector();
            List<TextSegment> segments = docs.stream()
                    .map(c -> Optional.ofNullable(c.textSegment()).orElse(TextSegment.from(c.toString())))
                    .toList();

            Response<List<Embedding>> resp = embeddingModel.embedAll(segments);
            List<Embedding> docVectors = resp.content();

            if (docVectors == null || docVectors.size() != segments.size()) return Sufficiency.UNAVAILABLE;

            double sum = 0.0;
            for (Embedding e : docVectors) {
                sum += cosineSimilarity(queryVector, e.vector());
            }
            double avg = sum / docVectors.size();
            return avg >= minAvgScore ? Sufficiency.SUFFICIENT : Sufficiency.INSUFFICIENT;

        } catch (Exception ex) {
            traceSuppressed("isSufficient", ex);
            return Sufficiency.UNAVAILABLE;
        }
    }

    public RetrievalEvaluation evaluateRetrieval(
            String query,
            List<Content> docs,
            int minDocs,
            double minAvgScore,
            int minDistinctSources) {
        int docCount = docs == null ? 0 : docs.size();
        int distinctSources = distinctSources(docs);
        RetrievalEvaluation ev;
        if (query == null || query.trim().length() < 2) {
            ev = new RetrievalEvaluation(Decision.REWRITE_QUERY, 0.2, docCount, distinctSources, "query_too_short");
        } else if (docCount == 0) {
            ev = new RetrievalEvaluation(Decision.REPAIR_WITH_WEB, 0.1, 0, 0, "empty_docs");
        } else {
            boolean enoughDocs = docCount >= Math.max(1, minDocs);
            boolean enoughSources = distinctSources >= Math.max(1, minDistinctSources);
            Sufficiency sufficiency = evaluateSufficiency(query, docs, minDocs, minAvgScore);
            if (enoughDocs && enoughSources && sufficiency == Sufficiency.SUFFICIENT) {
                ev = new RetrievalEvaluation(Decision.ACCEPT, 0.85, docCount, distinctSources, "sufficient");
            } else if (!enoughSources) {
                ev = new RetrievalEvaluation(Decision.REPAIR_WITH_WEB, 0.45, docCount, distinctSources, "low_source_diversity");
            } else if (!enoughDocs) {
                ev = new RetrievalEvaluation(Decision.REPAIR_WITH_WEB, 0.4, docCount, distinctSources, "weak_relevance");
            } else if (sufficiency == Sufficiency.UNAVAILABLE) {
                ev = new RetrievalEvaluation(Decision.ABSTAIN, 0.25, docCount, distinctSources, "evaluation_unavailable");
            } else {
                ev = new RetrievalEvaluation(Decision.REPAIR_WITH_WEB, 0.4, docCount, distinctSources, "weak_relevance");
            }
        }
        log.debug("[CRAG][eval] decision={}, confidence={}, docs={}, sources={}, reason={}",
                ev.decision(), ev.confidence(), ev.docCount(), ev.distinctSources(), ev.reason());
        putEvalTrace(ev);
        return ev;
    }

    private static void putEvalTrace(RetrievalEvaluation ev) {
        if (ev == null) {
            return;
        }
        putMetricTrace("rag.answerQuality.decision",
                ev.decision() == null ? "UNKNOWN" : ev.decision().name());
        putMetricTrace("rag.answerQuality.confidence", ev.confidence());
        Decision decision = ev.decision() == null ? Decision.ABSTAIN : ev.decision();
        double faithScore = switch (decision) {
            case ACCEPT -> ev.confidence();
            case REPAIR_WITH_WEB -> ev.confidence() * 0.6d;
            case REWRITE_QUERY -> ev.confidence() * 0.4d;
            case ABSTAIN -> ev.confidence() * 0.2d;
        };
        putMetricTrace("rag.answerQuality.faithfulnessScore", faithScore);
        putMetricTrace("rag.answerQuality.docCount", Math.max(0, ev.docCount()));
        putMetricTrace("rag.answerQuality.distinctSources", Math.max(0, ev.distinctSources()));
        putMetricTrace("rag.answerQuality.reason",
                SafeRedactor.traceLabelOrFallback(ev.reason(), "unknown"));
    }

    private static void putMetricTrace(String key, Object value) {
        TraceStore.put(key, value);
        FaithfulnessMetricSnapshotStore.put(key, value);
    }

    private static int distinctSources(List<Content> docs) {
        if (docs == null || docs.isEmpty()) return 0;
        Set<String> sources = docs.stream()
                .map(AnswerQualityEvaluator::sourceKey)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());
        return sources.size();
    }

    private static String sourceKey(Content c) {
        try {
            if (c != null && c.textSegment() != null && c.textSegment().metadata() != null) {
                Map<String, Object> md = c.textSegment().metadata().toMap();
                for (String key : List.of("url", "uri", "source", "provider", "doc_id", "docId")) {
                    Object v = md.get(key);
                    if (v != null && !String.valueOf(v).isBlank()) {
                        return String.valueOf(v).trim().toLowerCase();
                    }
                }
            }
        } catch (Exception ex) {
            traceSuppressed("metadata_source_key", ex);
        }
        return null;
    }

    private static void traceSuppressed(String stage, Exception ex) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = ex == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown");
        TraceStore.put("rag.answerQuality.suppressed.stage", safeStage);
        TraceStore.put("rag.answerQuality.suppressed.errorType", errorType);
        TraceStore.put("rag.answerQuality.suppressed." + safeStage, true);
        TraceStore.put("rag.answerQuality.suppressed." + safeStage + ".errorType", errorType);
        TraceStore.put("rag.answerQuality." + safeStage + ".errorType", errorType);
        log.debug("[CRAG][eval] fail-soft stage={} errorType={}", safeStage, errorType);
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;

        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0.0 ? 0.0 : dot / denom;
    }
}
