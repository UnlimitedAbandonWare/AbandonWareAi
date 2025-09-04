package com.example.lms.service.rag.whiten;

import com.example.lms.llm.LowRankWhiteningTransform;
import dev.langchain4j.data.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QueryWhiteningService {

    private static final Logger log = LoggerFactory.getLogger(QueryWhiteningService.class);

    private final boolean enabled;
    private final LowRankWhiteningTransform transform;

    public QueryWhiteningService(
            @Value("${rag.whiten.enabled:false}") boolean enabled,
            LowRankWhiteningTransform transform
    ) {
        this.enabled = enabled;
        this.transform = transform;
    }

    /** Apply whitening if enabled; otherwise return input. Fail-soft on any error. */
    public Embedding maybeWhiten(Embedding embedding) {
        if (!enabled || embedding == null) return embedding;
        try {
            float[] out = transform.apply(embedding.vector());
            return Embedding.from(out);
        } catch (Throwable e) {
            log.warn("[Whiten] whitening failed – returning original: {}", e.toString());
            return embedding;
        }
    }
}
