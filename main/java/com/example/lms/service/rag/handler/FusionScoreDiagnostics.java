package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.util.MetadataUtils;
import dev.langchain4j.rag.content.Content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FusionScoreDiagnostics {
    private FusionScoreDiagnostics() {
    }

    static void putScoreMeans(Map<String, Object> scorecard,
                              List<Content> web,
                              List<Content> vector,
                              List<Content> kg) {
        if (scorecard == null) {
            return;
        }
        scorecard.put("webScoreMean", round4(sourceScoreMean(web, "score", "web_score", "webScore")));
        scorecard.put("vectorScoreMean", round4(sourceScoreMean(vector,
                "vector_score", "vectorScore", "similarityScore", "score")));
        scorecard.put("kgScoreMean", round4(sourceScoreMean(kg,
                "kg_score", "kgScore", "graph_score", "graphScore", "kg_path_score", "kgPathScore", "score")));
    }

    static void traceScoreMeans(Map<String, Object> scorecard) {
        if (scorecard == null) {
            return;
        }
        TraceStore.put("rag.fusion.score.mean.web", numberOrZero(scorecard.get("webScoreMean")));
        TraceStore.put("rag.fusion.score.mean.vector", numberOrZero(scorecard.get("vectorScoreMean")));
        TraceStore.put("rag.fusion.score.mean.kg", numberOrZero(scorecard.get("kgScoreMean")));
    }

    private static double sourceScoreMean(List<Content> contents, String... keys) {
        if (contents == null || contents.isEmpty()) {
            return 0.0d;
        }
        double sum = 0.0d;
        int count = 0;
        for (Content content : contents) {
            double score = contentScore(content, keys);
            if (Double.isFinite(score)) {
                sum += score;
                count++;
            }
        }
        return count == 0 ? 0.0d : sum / count;
    }

    private static double contentScore(Content content, String... keys) {
        if (content == null || keys == null || keys.length == 0) {
            return Double.NaN;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(toMap(content.metadata()));
        if (content.textSegment() != null) {
            metadata.putAll(toMap(content.textSegment().metadata()));
        }
        Object raw = firstNonBlank(metadata, keys);
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof CharSequence text) {
            try {
                return Double.parseDouble(text.toString().trim());
            } catch (NumberFormatException ignored) {
                TraceStore.put("retrieval.handler.suppressed.fusionScore.parse", true); RetrievalHandlerTraceSuppressions.trace("fusionScore.parse", ignored); return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static Object firstNonBlank(Map<String, Object> metadata, String... keys) {
        if (metadata == null || metadata.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, Object> toMap(Object meta) {
        if (meta instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return out;
        }
        return meta instanceof dev.langchain4j.data.document.Metadata md ? MetadataUtils.toMap(md) : Map.of();
    }

    private static double numberOrZero(Object value) {
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? numeric : 0.0d;
        }
        return 0.0d;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }
}
