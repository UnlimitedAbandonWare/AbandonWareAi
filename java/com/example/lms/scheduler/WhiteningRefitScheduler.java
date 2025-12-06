package com.example.lms.scheduler;

import com.example.lms.service.rag.mp.LowRankWhiteningStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;



/**
 * Periodically recomputes the whitening transformation.  This scheduler
 * delegates to the supplied {@link LowRankWhiteningStats} to perform a
 * refit.  When the refit fails the error is logged but does not
 * propagate.
 */
public class WhiteningRefitScheduler {
    private static final Logger log = LoggerFactory.getLogger(WhiteningRefitScheduler.class);
    private final LowRankWhiteningStats stats;

    public WhiteningRefitScheduler(LowRankWhiteningStats stats) {
        this.stats = stats;
    }

    @Scheduled(cron = "${rag.mp.refit.cron:0 10 3 * * SUN}")
    public void refit() {
        try {
            stats.refit();
        } catch (Exception e) {
            log.warn("[MP] whitening refit failed", e);
        }
    }
}