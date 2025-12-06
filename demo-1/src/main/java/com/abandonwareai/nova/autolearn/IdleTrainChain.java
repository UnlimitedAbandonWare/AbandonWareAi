package com.abandonwareai.nova.autolearn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.abandonwareai.nova.config.IdleTrainProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "idletrain", name = "enabled", havingValue = "true", matchIfMissing = false)
public class IdleTrainChain {

    private static final Logger log = LoggerFactory.getLogger(IdleTrainChain.class);

    private final IdleTrainProperties props;
    private final AutolearnRagRetrainOrchestrator orchestrator;

    public IdleTrainChain(IdleTrainProperties props, AutolearnRagRetrainOrchestrator orchestrator) {
        this.props = props;
        this.orchestrator = orchestrator;
    }

    // Cron 1: 00:30 daily
    @Scheduled(cron = "0 30 0 * * *")
    public void nightlyRound1() {
        trigger("cron-00:30");
    }

    // Cron 2: 04:30 daily
    @Scheduled(cron = "0 30 4 * * *")
    public void nightlyRound2() {
        trigger("cron-04:30");
    }

    private void trigger(String reason) {
        if (!props.isEnabled()) {
            log.debug("IdleTrain disabled; skip round ({})", reason);
            return;
        }
        // We don't depend on Autolearn summary here; use a conservative acceptedCount surrogate (min threshold)
        orchestrator.maybeRetrainByFileSize(props.getMinQaAcceptedToTrain());
    }
}