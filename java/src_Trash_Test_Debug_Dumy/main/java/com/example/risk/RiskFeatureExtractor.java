package com.example.risk;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

/**
 * Extracts numerical features from a {@link ListingContext} for risk
 * classification.  Features consist of the embedding vector of the
 * concatenated signal texts combined with log-scaled scalars capturing
 * the total length and number of signals.  When no signals are present
 * three zero-valued features are returned.
 */
@Component
public class RiskFeatureExtractor {
    private final EmbeddingModel embeddingModel;

    public RiskFeatureExtractor(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Convert the signals contained in the context into a feature vector.
     *
     * @param ctx listing context containing the signals; may be null
     * @return feature array consisting of the embedding vector followed by
     *         log-scaled length and signal count; returns {0,0,0} when no
     *         signals are available
     */
    public double[] featuresOf(ListingContext ctx) {
        if (ctx == null || ctx.signals() == null || ctx.signals().isEmpty()) {
            return new double[]{0.0, 0.0, 0.0};
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Content c : ctx.signals()) {
            Object text = safeGetText(c);
            if (text != null) {
                sb.append(text).append('\n');
                count++;
            }
            // guardrail: avoid excessively long inputs
            if (sb.length() > 8000) {
                break;
            }
        }
        String joined = sb.toString();
        Response<Embedding> r = embeddingModel.embed(TextSegment.from(joined));
        float[] v = r.content().vector();
        int d = v.length;
        double[] x = new double[d + 2];
        for (int i = 0; i < d; i++) {
            x[i] = v[i];
        }
        // log-scaled length of concatenated signals
        x[d] = Math.log(1.0 + joined.length());
        // log-scaled count of individual signals
        x[d + 1] = Math.log(1.0 + count);
        return x;
    }

    // Attempt to extract a textual representation from Content using
    // common getters.  If reflection fails fall back to toString.
    private Object safeGetText(Content c) {
        try {
            return c.getClass().getMethod("text").invoke(c);
        } catch (Throwable ignore) {
            try {
                return c.getClass().getMethod("getText").invoke(c);
            } catch (Throwable ignore2) {
                return c.toString();
            }
        }
    }
}