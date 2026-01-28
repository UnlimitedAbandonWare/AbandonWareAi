package com.example.lms.service.rag.pre;

import java.util.Map;

/**
 * Marker interface for preprocessors that require caller-provided metadata.
 *
 * <p>This exists to keep the chain extensible without hard-coding concrete
 * preprocessor types inside the composite.</p>
 */
public interface MetaAwareQueryContextPreprocessor extends QueryContextPreprocessor {

    @Override
    String enrich(String original, Map<String, Object> meta);
}
