package com.example.lms.service.llm;

import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;




/**
 * Centralises selection of the cross‑encoder reranker implementation based on
 * configuration.  The ChatService previously embedded this logic directly
 * which made it hard to unit test and reason about.  Extracting the
 * selection into its own component improves separation of concerns.
 *
 * <p>The available backends are determined by the Spring application
 * context.  This component looks up beans by conventional names and falls
 * back gracefully when the configured backend is unavailable.  Supported
 * backend identifiers are:
 *
 * <ul>
 *   <li>{@code embedding-model} – uses the existing embedding based
 *     cross‑encoder reranker;</li>
 *   <li>{@code onnx-runtime} – uses the local ONNX runtime via
 *     {@link com.example.lms.service.onnx.OnnxRuntimeService};</li>
 *   <li>{@code noop} – disables re‑ranking entirely.</li>
 * </ul>
 *
 * The default backend is {@code embedding-model} when unspecified.  Any
 * unexpected value also falls back to the embedding backend.
 */
@Component
public class RerankerSelector {

    private static final Logger log = LoggerFactory.getLogger(RerankerSelector.class);

    /** Map of available rerankers keyed by bean name. */
    private final Map<String, CrossEncoderReranker> rerankers;

    /** Configured backend identifier. */
    private final String backend;

    public RerankerSelector(Map<String, CrossEncoderReranker> rerankers,
                            @Value("${abandonware.reranker.backend:embedding-model}") String backend) {
        this.rerankers = rerankers;
        this.backend = backend == null ? "embedding-model" : backend.trim().toLowerCase();
    }

    /**
     * Return the active reranker.  When the configured backend is not found
     * this method falls back to the embedding based reranker if available.
     * When no rerankers are present a no‑op implementation is returned.
     *
     * @return a non‑null {@link CrossEncoderReranker}
     */
    public CrossEncoderReranker select() {
        if (rerankers == null || rerankers.isEmpty()) {
            log.warn("No CrossEncoderReranker beans found; falling back to noop reranker");
            return new com.example.lms.service.rag.rerank.NoopCrossEncoderReranker();
        }
        String beanName;
        switch (backend) {
            case "onnx-runtime" -> beanName = "onnxCrossEncoderReranker";
            case "noop"        -> beanName = "noopCrossEncoderReranker";
            default             -> beanName = "embeddingCrossEncoderReranker";
        }
        CrossEncoderReranker r = rerankers.get(beanName);
        if (r != null) {
            return r;
        }
        // fallback: use embedding if present
        CrossEncoderReranker embedding = rerankers.get("embeddingCrossEncoderReranker");
        if (embedding != null) {
            log.info("Reranker backend '{}' not found, falling back to embedding backend", backend);
            return embedding;
        }
        // otherwise return any available reranker
        return rerankers.values().iterator().next();
    }
}