package com.abandonware.ai.agent.train;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * merge130: IdleTrainScheduler
 * - Disabled by default (train_idle.enabled=false)
 * - Intended to orchestrate Soak/Probe -> DatasetWriter(train_rag.jsonl) -> AutoLearn
 * - Safe no-op if dependent beans are absent.
 */
@Component
@ConditionalOnProperty(name = "train_idle.enabled", havingValue = "true")
public class IdleTrainScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdleTrainScheduler.class);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public IdleTrainScheduler() {
        log.info("[IdleTrainScheduler] initialized (enabled=true)");
    }

    // Run at 03:00 every day (server local time)
    @Scheduled(cron = "0 0 3 * * *")
    public void runNightly() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            log.info("[IdleTrainScheduler] nightly start at {}", Instant.now());

            // 1) Probe system load (placeholder). Replace with actual metrics.
            double cpuLoad = 0.10; // TODO: wire to metrics
            double gpuLoad = 0.10; // TODO: wire to metrics
            if (cpuLoad > 0.4 || gpuLoad > 0.4) {
                log.info("[IdleTrainScheduler] skip: not idle (cpu={}, gpu={})", cpuLoad, gpuLoad);
                return;
            }

            // 2) Soak/Probe (placeholder)
            log.info("[IdleTrainScheduler] Soak/Probe suite would run here (disabled by default)");

            // 3) DatasetWriter -> train_rag.jsonl (placeholder)
            log.info("[IdleTrainScheduler] DatasetWriter would append curated samples to /data/train_rag.jsonl");

            // 4) AutoLearn trigger (placeholder)
            boolean autoTuneEnabled = Boolean.getBoolean("auto_tune.enabled"); // also support JVM flag
            if (autoTuneEnabled) {
                log.info("[IdleTrainScheduler] AutoLearn trigger would start here (auto_tune.enabled=true)");
            } else {
                log.info("[IdleTrainScheduler] AutoLearn is disabled (auto_tune.enabled=false)");
            }

        } catch (Throwable t) {
            log.warn("[IdleTrainScheduler] error: {}", t.toString());
        } finally {
            running.set(false);
            log.info("[IdleTrainScheduler] done in {} ms", System.currentTimeMillis() - start);
        }
    }
}
