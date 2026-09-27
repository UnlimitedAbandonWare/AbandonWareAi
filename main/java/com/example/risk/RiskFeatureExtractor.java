package com.example.risk;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;



/**
 * Extracts numerical features from a {@link ListingContext} for risk classification.
 * Features consist of the embedding vector of the concatenated signal texts combined with
 * log-scaled scalars capturing the total length and number of signals.
 * When no signals are present three zero-valued features are returned.
 */
@Component
@RequiredArgsConstructor
public class RiskFeatureExtractor {
    private static final Logger log = LoggerFactory.getLogger(RiskFeatureExtractor.class);

    private final EmbeddingModel embeddingModel;

    /**
     * Generates a feature vector for the given context.
     *
     * @param ctx listing context; may be null
     * @return feature vector
     */
    public double[] featuresOf(ListingContext ctx) {
        if (ctx == null || ctx.signals() == null || ctx.signals().isEmpty()) {
            return new double[]{0.0, 0.0, 0.0};
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Content c : ctx.signals()) {
            String text = safeText(c);
            if (!text.isBlank()) {
                sb.append(text).append('\n');
                count++;
            }
            if (sb.length() > 8000) break; // guardrail to avoid extremely long inputs
        }
        String joined = sb.toString();
        Response<Embedding> r = embeddingModel.embed(TextSegment.from(joined));
        float[] v = r.content().vector();
        int d = v.length;
        double[] x = new double[d + 2];
        for (int i = 0; i < d; i++) {
            x[i] = v[i];
        }
        x[d] = Math.log(1.0 + joined.length()); // text length (log-scale)
        x[d + 1] = Math.log(1.0 + count);        // number of signals (log-scale)
        return x;
    }

    private String safeText(Content c) {
        try {
            return c == null || c.textSegment() == null ? "" : c.textSegment().text();
        } catch (Throwable error) {
            logFailSoft("content.text", error);
            return c == null ? "" : c.toString();
        }
    }

    private static void logFailSoft(String stage, Throwable error) {
        if (log.isDebugEnabled()) {
            log.debug("[RiskFeatureExtractor] fail-soft stage={} errorType={}", stage, errorType(error));
        }
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
