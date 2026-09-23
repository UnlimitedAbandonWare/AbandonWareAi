package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.autolearn.ingest.TrainRagIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
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
        // - acceptedCount >= minAcceptedToTrain : ingest this verified batch.
        // - acceptedCount == 0 : occasionally probe ingest (cooldown) in case
        //   there are un-ingested samples written by other workers or delayed I/O.
        // - 0 < acceptedCount < minAcceptedToTrain : keep accumulating; do not
        //   retrain on tiny batches.
        int minAcceptedToTrain = props.getRetrain().getMinAcceptedToTrain();
        int maxRunsPerDay = props.getRetrain().getMaxRunsPerDay();
        String datasetName = props.getDataset().getName();
        traceCheck(jsonl, acceptedCount, minAcceptedToTrain, maxRunsPerDay, datasetName);

        boolean hasNewAccepted = acceptedCount > 0;
        if (hasNewAccepted && acceptedCount < minAcceptedToTrain) {
            log.info("[UAW] retrain skipped: acceptedCount={} minAcceptedToTrain={}",
                    acceptedCount, minAcceptedToTrain);
            traceSkip("below_min_accepted");
            return 0;
        }
        if (!hasNewAccepted) {
            long now = System.currentTimeMillis();
            long cooldownMs = 10L * 60L * 1000L; // 10 minutes
            if (now - lastProbeEpochMs < cooldownMs) {
                traceSkip("probe_cooldown");
                return 0;
            }
            lastProbeEpochMs = now;
        }

        LocalDate today = LocalDate.now();
        if (lastTrainDate == null || !lastTrainDate.equals(today)) {
            lastTrainDate = today;
            todayTrainCount.set(0);
        }
        if (todayTrainCount.get() >= maxRunsPerDay) {
            log.info("[UAW] reached retrain daily cap={}", maxRunsPerDay);
            traceSkip("daily_cap");
            return 0;
        }

        int n = ingestService.ingestNewSamples(jsonl, datasetName, token);
        TraceStore.put("uaw.retrain.ingest.count", n);
        if (n > 0) {
            todayTrainCount.incrementAndGet();
            log.info("[UAW] ingested {} new samples from datasetFileHash={} datasetFileLength={} datasetPathHash={}",
                    n, datasetFileHash(jsonl), datasetFileLength(jsonl), hashOrEmpty(jsonl));
        } else {
            log.debug("[UAW] no new samples to ingest from datasetFileHash={} datasetFileLength={} datasetPathHash={}",
                    datasetFileHash(jsonl), datasetFileLength(jsonl), hashOrEmpty(jsonl));
        }
        return n;
    }

    private static void traceCheck(Path jsonl,
                                   int acceptedCount,
                                   int minAcceptedToTrain,
                                   int maxRunsPerDay,
                                   String datasetName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("acceptedCount", acceptedCount);
        data.put("minAcceptedToTrain", minAcceptedToTrain);
        data.put("maxRunsPerDay", maxRunsPerDay);
        data.put("datasetName", safe(datasetName));
        data.put("fileNameHash", datasetFileHash(jsonl));
        data.put("fileNameLength", datasetFileLength(jsonl));
        data.put("filePresent", jsonl != null && Files.exists(jsonl));
        TraceStore.put("uaw.retrain.check", data);
        TraceStore.put("uaw.retrain.skip.reason", "");
    }

    private static void traceSkip(String reason) {
        TraceStore.put("uaw.retrain.skip.reason", safe(reason));
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() <= 96 ? v : v.substring(0, 96);
    }

    private static String datasetFileName(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        return safe(path.getFileName().toString());
    }

    private static String datasetFileHash(Path path) {
        String fileName = datasetFileName(path);
        if (fileName.isEmpty()) {
            return "";
        }
        String hash = SafeRedactor.hashValue(fileName);
        return hash == null ? "" : hash;
    }

    private static int datasetFileLength(Path path) {
        return datasetFileName(path).length();
    }

    private static String hashOrEmpty(Path path) {
        if (path == null) {
            return "";
        }
        String hash = SafeRedactor.hashValue(path.toString());
        return hash == null ? "" : hash;
    }
}
