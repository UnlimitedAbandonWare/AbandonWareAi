package com.abandonwareai.nova.autolearn;

import com.abandonwareai.nova.autolearn.ingest.TrainRagIngestService;
import com.abandonwareai.nova.config.IdleTrainProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AutolearnRagRetrainOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AutolearnRagRetrainOrchestrator.class);

    private final TrainRagIngestService ingestService;
    private final IdleTrainProperties props;

    private LocalDate lastTrainDate;
    private final AtomicInteger todayTrainCount = new AtomicInteger(0);

    public AutolearnRagRetrainOrchestrator(TrainRagIngestService ingestService,
                                          IdleTrainProperties props) {
        this.ingestService = ingestService;
        this.props = props;
    }

    public void maybeRetrainByFileSize(int acceptedCount, PreemptionToken token) {
        if (props == null || !props.isAutoTrainEnabled()) {
            log.debug("Auto-train disabled");
            return;
        }
        if (acceptedCount < props.getMinQaAcceptedToTrain()) {
            log.info("Accepted QA {} < threshold {} — skip", acceptedCount, props.getMinQaAcceptedToTrain());
            return;
        }
        if (token != null && token.shouldAbort()) {
            log.info("User returned; abort retrain.");
            return;
        }

        LocalDate today = LocalDate.now();
        if (lastTrainDate == null || !lastTrainDate.equals(today)) {
            lastTrainDate = today;
            todayTrainCount.set(0);
        }
        if (todayTrainCount.get() >= props.getMaxTrainRunsPerDay()) {
            log.info("Reached daily train cap {}", props.getMaxTrainRunsPerDay());
            return;
        }

        Path jsonl = Path.of(props.getTrainRagJsonlPath());
        int n = ingestService.ingestNewSamples(jsonl, props.getTrainRagDatasetName(), token);
        if (n > 0) {
            todayTrainCount.incrementAndGet();
            log.info("Ingested {} samples from {}", n, jsonl.toAbsolutePath());
        } else {
            log.info("No new samples to ingest from {}", jsonl.toAbsolutePath());
        }
    }
}
