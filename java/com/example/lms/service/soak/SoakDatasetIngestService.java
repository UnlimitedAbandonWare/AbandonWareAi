package com.example.lms.service.soak;

import com.example.lms.domain.TranslationSample;
import com.example.lms.domain.enums.TranslationRoute;
import com.example.lms.repository.SampleRepository;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Soak → Dataset automatic accumulation.
 *
 * <p>
 * - If quick report meets quality thresholds, ingest evidence-backed items as
 *   "trained-ready" samples (corrected filled).
 * - Otherwise, ingest as needs_review records (corrected NULL) to encourage
 *   human curation.
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "soak.dataset", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SoakDatasetIngestService {

    private static final Logger log = LoggerFactory.getLogger(SoakDatasetIngestService.class);

    private final SampleRepository sampleRepository;

    @Value("${soak.dataset.auto-accept.min-evidence-rate:0.35}")
    private double minEvidenceRate;

    @Value("${soak.dataset.auto-accept.min-success-rate:0.60}")
    private double minSuccessRate;

    public SoakDatasetIngestService(SampleRepository sampleRepository) {
        this.sampleRepository = sampleRepository;
    }

    public void ingestQuick(SoakQuickReport rep) {
        if (rep == null || rep.items == null || sampleRepository == null) return;
        boolean accept = rep.metrics != null
                && rep.metrics.evidenceRate >= minEvidenceRate
                && rep.metrics.successRate >= minSuccessRate;

        for (SoakQuickReport.Item it : rep.items) {
            if (it == null || it.query == null || it.query.isBlank()) continue;

            String sourceText = it.query.trim();
            String hash = DigestUtils.sha256Hex(sourceText);

            boolean hasEvidence = it.evidence && it.topSnippet != null && !it.topSnippet.isBlank();
            boolean toTraining = accept && hasEvidence;

            try {
                Optional<TranslationSample> existing = sampleRepository.findBySourceHash(hash);
                TranslationSample s = existing.orElseGet(() -> TranslationSample.builder()
                        .sourceText(sourceText)
                        .translated(null)
                        .corrected(null)
                        .srcLang("auto")
                        .tgtLang("auto")
                        .route(TranslationRoute.MEMORY)
                        .sourceHash(hash)
                        .qError(null)
                        .similarity(null)
                        .needsReview(true)
                        .build());

                // Idempotent update policy:
                // - never overwrite human corrected text
                // - prefer evidence snippet when available
                if (toTraining) {
                    if (s.getCorrected() == null || s.getCorrected().isBlank()) {
                        s.setCorrected(it.topSnippet);
                    }
                    if (s.getTranslated() == null || s.getTranslated().isBlank()) {
                        s.setTranslated(it.topSnippet);
                    }
                    s.setNeedsReview(false);
                } else {
                    // needs_review bucket
                    if ((s.getTranslated() == null || s.getTranslated().isBlank())
                            && it.topSnippet != null && !it.topSnippet.isBlank()) {
                        s.setTranslated(it.topSnippet);
                    }
                    if (s.getCorrected() == null || s.getCorrected().isBlank()) {
                        s.setNeedsReview(true);
                    }
                }

                sampleRepository.save(s);
            } catch (Exception e) {
                // fail-soft: continue other items
                log.debug("[SOAK] dataset ingest failed: {}", e.toString());
            }
        }
    }
}
