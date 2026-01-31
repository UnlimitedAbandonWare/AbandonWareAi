package com.example.lms.uaw.autolearn;

import com.example.lms.uaw.autolearn.ingest.TrainRagIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the "closed-loop" step: ingest newly appended train_rag.jsonl into the vector store.
 */
@Component
public class AutolearnRagRetrainOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AutolearnRagRetrainOrchestrator.class);

    private final TrainRagIngestService ingestService;
    private final UawAutolearnProperties props;

    private LocalDate lastTrainDate;
    private final AtomicInteger todayTrainCount = new AtomicInteger(0);

    // When acceptedCount==0 we still sometimes "probe" ingest to flush any backlog
    // created by delayed persistence or cross-process writers.
    private volatile long lastProbeEpochMs = 0L;

    public AutolearnRagRetrainOrchestrator(TrainRagIngestService ingestService,
                                          UawAutolearnProperties props) {
        this.ingestService = ingestService;
        this.props = props;
    }

    public int maybeRetrain(Path jsonl, int acceptedCount, PreemptionToken token) {
        // Policy:
        // - acceptedCount > 0 : ingest immediately (even if below minAcceptedToTrain)
        //   so the dataset does not "accumulate silently".
        // - acceptedCount == 0 : occasionally probe ingest (cooldown) in case
        //   there are un-ingested samples written by other workers or delayed I/O.
        boolean hasNewAccepted = acceptedCount > 0;
        if (!hasNewAccepted) {
            long now = System.currentTimeMillis();
            long cooldownMs = 10L * 60L * 1000L; // 10 minutes
            if (now - lastProbeEpochMs < cooldownMs) {
                return 0;
            }
            lastProbeEpochMs = now;
        }

        LocalDate today = LocalDate.now();
        if (lastTrainDate == null || !lastTrainDate.equals(today)) {
            lastTrainDate = today;
            todayTrainCount.set(0);
        }
        if (todayTrainCount.get() >= props.getRetrain().getMaxRunsPerDay()) {
            log.info("[UAW] reached retrain daily cap={}", props.getRetrain().getMaxRunsPerDay());
            return 0;
        }

        if (hasNewAccepted && acceptedCount < props.getRetrain().getMinAcceptedToTrain()) {
            log.debug("[UAW] ingest trigger: acceptedCount={} (<minAcceptedToTrain={}), ingesting anyway to avoid backlog", acceptedCount,
                    props.getRetrain().getMinAcceptedToTrain());
        }

        int n = ingestService.ingestNewSamples(jsonl, props.getDataset().getName(), token);
        if (n > 0) {
            todayTrainCount.incrementAndGet();
            log.info("[UAW] ingested {} new samples from {}", n, jsonl.toAbsolutePath());
        } else {
            log.debug("[UAW] no new samples to ingest from {}", jsonl.toAbsolutePath());
        }
        return n;
    }
}
