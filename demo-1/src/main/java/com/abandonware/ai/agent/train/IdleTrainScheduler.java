package com.abandonware.ai.agent.train;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Legacy placeholder (demo-1).
 *
 * <p>Cron-based scheduling was removed in favour of a single idle-poll entry point
 * in {@code com.abandonwareai.nova.autolearn.AutolearnScheduler}.
 */
@Component
@Deprecated
public class IdleTrainScheduler {
    private static final Logger log = LoggerFactory.getLogger(IdleTrainScheduler.class);

    public void runScheduledTraining() {
        log.info("IdleTrainScheduler is deprecated; no scheduled trigger. Use AutolearnScheduler.");
    }
}
