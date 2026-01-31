package com.example.lms.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * EmbeddingFingerprint provides a stable identifier for the currently
 * configured embedding model.
 *
 * <p>
 * Stored/retrieved vectors must be produced with the same embedding
 * configuration.
 * If an index built with a different embedding model is queried with the
 * current model,
 * similarities become meaningless and retrieval may look "random" (off-topic
 * context).
 *
 * <p>
 * This component enables stamping stored segments with a fingerprint and
 * filtering
 * retrieval results to prevent cross-model contamination.
 */
@Component
public class EmbeddingFingerprint {

    public static final String META_EMB_FP = "emb_fp";
    public static final String META_EMB_ID = "emb_id";
    public static final String META_EMB_PROVIDER = "emb_provider";
    public static final String META_EMB_MODEL = "emb_model";
    public static final String META_EMB_DIM = "emb_dim";

    @Value("${embedding.provider:ollama}")
    private String provider;

    @Value("${embedding.model:qwen3-embedding}")
    private String model;

    @Value("${embedding.dimensions:1536}")
    private int dimensions;

    @Value("${vector.fingerprint.enabled:true}")
    private boolean enabled;

    /**
     * Allow returning legacy segments without emb_fp metadata. Default: false
     * (safe).
     */
    @Value("${vector.fingerprint.allow-legacy:false}")
    private boolean allowLegacy;

    /**
     * When the backing vector store does not preserve metadata, emb_fp may be
     * missing for all matches.
     * In that case fingerprint filtering is impossible. If this is true, we
     * automatically bypass the fp-filter
     * (legacy mode) rather than spamming warnings and returning unstable results.
     */
    @Value("${vector.fingerprint.bypass-if-metadata-missing:true}")
    private boolean bypassIfMetadataMissing;

    public String provider() {
        return norm(provider);
    }

    public String model() {
        return norm(model);
    }

    public int dimensions() {
        return dimensions;
    }

    public boolean allowLegacy() {
        return allowLegacy;
    }

    public boolean bypassIfMetadataMissing() {
        return bypassIfMetadataMissing;
    }

    /** Human-readable embedding id: provider|model|dimensions */
    public String embId() {
        return provider() + "|" + model() + "|" + dimensions;
    }

    /** Fingerprint used for strict equality checks. */
    public String fingerprint() {
        return embId();
    }

    /** Returns the length of the fingerprint string. */
    public int getFingerprintLength() {
        return fingerprint().length();
    }

    /** Returns whether fingerprint checking is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    public boolean matches(String fpValue) {
        if (fpValue == null || fpValue.isBlank())
            return false;
        return Objects.equals(fingerprint(), fpValue.trim());
    }

    private static String norm(String s) {
        if (s == null)
            return "";
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
