package com.example.lms.uaw.selfclean;

import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.orchestration.OrchStageKeys;
import com.example.lms.service.EmbeddingStoreManager;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.vector.VectorQuarantineDlqService;
import com.example.lms.service.vector.VectorShadowMergeDlqService;
import com.example.lms.uaw.orchestration.UawOrchestrationGate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Background self-clean loop:
 * <ul>
 *   <li>merge due shadow-staged vectors (when verified)</li>
 *   <li>occasionally rotate/rebuild the global sid to reduce hubness</li>
 *   <li>occasionally redrive quarantine DLQ</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "uaw.selfclean", name = "enabled", havingValue = "true")
public class UawSelfCleanOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(UawSelfCleanOrchestrator.class);

    private final UawOrchestrationGate gate;
    private final UawSelfCleanProperties props;

    @Autowired(required = false)
    private VectorShadowMergeDlqService shadowDlq;

    @Autowired(required = false)
    private EmbeddingStoreManager embeddingStoreManager;

    @Autowired(required = false)
    private VectorQuarantineDlqService quarantineDlq;

    private final AtomicBoolean warnedMissingShadowDlq = new AtomicBoolean(false);

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong lastRebuildAtMs = new AtomicLong(0L);

    @Scheduled(
            initialDelayString = "${uaw.selfclean.initial-delay-ms:60000}",
            fixedDelayString = "${uaw.selfclean.schedule-ms:60000}"
    )
    public void tick() {
        if (!lock.tryLock()) return;

        try {
            UawOrchestrationGate.Decision d = gate.decide(
                    OrchStageKeys.UAW_SELF_CLEAN,
                    props.getIdleCpuThreshold(),
                    NightmareKeys.CHAT_DRAFT,
                    NightmareKeys.FAST_LLM_COMPLETE
            );
            if (!d.allowed()) {
                log.debug("[UAW] self-clean skipped: reason={} cpu={}", d.reason(), d.cpuLoad());
                return;
            }

            if (shadowDlq == null && warnedMissingShadowDlq.compareAndSet(false, true)) {
                log.warn("[UAW] self-clean enabled but VectorShadowMergeDlqService bean is missing (shadow merge disabled)");
            }

            long pendingShadow = (shadowDlq == null) ? 0 : shadowDlq.pendingCount();

            double baseMergeProb = clamp01(props.getMergeProb());
            double mergeProb = baseMergeProb;

            // If backlog is high, bias toward merging.
            if (pendingShadow > 20) mergeProb = Math.min(0.95, mergeProb + 0.15);
            if (pendingShadow > 100) mergeProb = Math.min(0.98, mergeProb + 0.10);

            double r = ThreadLocalRandom.current().nextDouble();
            double rebuildProb = clamp01(props.getRebuildProb());
            double redriveProb = clamp01(props.getQuarantineRedriveProb());

            if (pendingShadow > 0 && r < mergeProb && shadowDlq != null) {
                VectorShadowMergeDlqService.MergeReport rep = shadowDlq.mergeDueOnce();
                log.info("[UAW] self-clean shadow-merge: {}", rep);
                return;
            }

            if (r < mergeProb + rebuildProb) {
                maybeRotateAndRebuild();
                return;
            }

            if (r < mergeProb + rebuildProb + redriveProb) {
                if (quarantineDlq != null) {
                    var rep = quarantineDlq.redriveDueOnce("uaw-selfclean");
                    log.info("[UAW] self-clean quarantine-redrive: {}", rep);
                }
            }
        } catch (Exception e) {
            log.warn("[UAW] self-clean tick failed: {}", e.toString());
        } finally {
            lock.unlock();
        }
    }

    private void maybeRotateAndRebuild() {
        if (embeddingStoreManager == null) return;

        long now = System.currentTimeMillis();
        long minInterval = Math.max(60_000L, props.getRebuildMinIntervalMs());
        long last = lastRebuildAtMs.get();

        if (now - last < minInterval) {
            return;
        }
        if (!lastRebuildAtMs.compareAndSet(last, now)) {
            return;
        }

        try {
            embeddingStoreManager.rotateGlobalSid();
            var rep = embeddingStoreManager.adminRebuild(
                    LangChainRAGService.GLOBAL_SID,
                    Math.max(0, props.getRebuildKbLimit()),
                    Math.max(0, props.getRebuildMemoryLimit()),
                    props.isRebuildIncludeKb()
            );
            log.info("[UAW] self-clean global rotate+rebuild: {}", rep);
        } catch (Exception e) {
            log.warn("[UAW] self-clean rebuild failed: {}", e.toString());
        }
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
