package com.example.lms.service.vector;

import com.example.lms.service.VectorMetaKeys;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight "can we embed + upsert" probe.
 *
 * <p>
 * This is intentionally conservative: it uses strict_write=true metadata so that
 * fail-soft upsert wrappers can rethrow, allowing us to gate DLQ redrive.
 */
@Service
@RequiredArgsConstructor
public class VectorBackendHealthService {

    private static final Logger log = LoggerFactory.getLogger(VectorBackendHealthService.class);

    private final EmbeddingModel embeddingModel;

    @Qualifier("federatedEmbeddingStore")
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Value("${vector.dlq.health.probe-enabled:false}")
    private boolean probeEnabled;

    @Value("${vector.dlq.health.interval-ms:30000}")
    private long intervalMs;

    @Value("${vector.dlq.health.ok-streak:3}")
    private int okStreakTarget;

    @Value("${vector.dlq.health.probe-sid:__HEALTH__}")
    private String probeSid;

    @Value("${vector.dlq.health.probe-id:__SYS__:VECTOR_HEALTH_PROBE}")
    private String probeId;

    @Value("${vector.dlq.health.probe-text:vector-backend-health-probe}")
    private String probeText;

    private volatile boolean lastHealthy = false;
    private volatile int okStreak = 0;
    private volatile long lastProbeEpochMs = 0L;
    private volatile String lastError = null;

    @Scheduled(fixedDelayString = "${vector.dlq.health.interval-ms:30000}")
    public void scheduledProbe() {
        if (!probeEnabled) return;
        probeNow();
    }

    /**
     * Perform a probe immediately.
     */
    public synchronized boolean probeNow() {
        if (!probeEnabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        lastProbeEpochMs = now;

        try {
            TextSegment seg = probeSegment();
            Embedding embedding = probeEmbedding();

            embeddingStore.addAll(List.of(probeId), List.of(embedding), List.of(seg));

            markHealthy();
            return true;
        } catch (Exception e) {
            markUnhealthy(e);
            log.warn("[VectorBackendHealth] probe failed errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return false;
        }
    }

    /**
     * Probe only the embedding path without writing a health row to the vector store.
     */
    public synchronized boolean probeEmbeddingOnlyNow() {
        long now = System.currentTimeMillis();
        lastProbeEpochMs = now;

        try {
            probeEmbedding();
            markHealthy();
            return true;
        } catch (Exception e) {
            markUnhealthy(e);
            log.warn("[AWX2AF2][rag][starvation] embeddingReady=false reasonHash={} reasonLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return false;
        }
    }

    public boolean isLastHealthy() {
        return lastHealthy;
    }

    public boolean isStableHealthy() {
        return lastHealthy && okStreakTarget > 0 && okStreak >= okStreakTarget;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probeEnabled", probeEnabled);
        out.put("intervalMs", intervalMs);
        out.put("okStreakTarget", okStreakTarget);
        out.put("okStreak", okStreak);
        out.put("lastHealthy", lastHealthy);
        out.put("lastProbeEpochMs", lastProbeEpochMs);
        out.put("lastError", lastError);
        out.put("probeSidHash", com.example.lms.trace.SafeRedactor.hashValue(probeSid));
        out.put("probeSidLength", probeSid == null ? 0 : probeSid.length());
        out.put("probeIdHash", com.example.lms.trace.SafeRedactor.hashValue(probeId));
        out.put("probeIdLength", probeId == null ? 0 : probeId.length());
        out.put("now", LocalDateTime.now().toString());
        return out;
    }

    private TextSegment probeSegment() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(VectorMetaKeys.META_SID, probeSid);
        meta.put(VectorMetaKeys.META_DOC_TYPE, "HEALTH");
        meta.put(VectorMetaKeys.META_ORIGIN, "HEALTH_PROBE");
        meta.put(VectorMetaKeys.META_STRICT_WRITE, "true");
        return TextSegment.from(probeText, Metadata.from(meta));
    }

    private Embedding probeEmbedding() {
        var resp = embeddingModel.embedAll(List.of(probeSegment()));
        var embeds = (resp == null) ? null : resp.content();
        if (embeds == null || embeds.isEmpty() || embeds.get(0) == null
                || embeds.get(0).vector() == null || embeds.get(0).vector().length == 0) {
            throw new IllegalStateException("Embedding probe returned empty vector");
        }
        if (isAllZero(embeds.get(0).vector())) {
            throw new IllegalStateException("Embedding probe returned all-zero vector");
        }
        return embeds.get(0);
    }

    private void markHealthy() {
        lastHealthy = true;
        okStreak = Math.min(okStreak + 1, Math.max(okStreakTarget, 1));
        lastError = null;
    }

    private void markUnhealthy(Exception e) {
        lastHealthy = false;
        okStreak = 0;
        lastError = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getMessage(), "");
    }

    private static boolean isAllZero(float[] v) {
        if (v == null || v.length == 0) return true;
        for (float x : v) {
            if (x != 0.0f) return false;
        }
        return true;
    }

    private static String messageOf(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message.length();
    }
}
