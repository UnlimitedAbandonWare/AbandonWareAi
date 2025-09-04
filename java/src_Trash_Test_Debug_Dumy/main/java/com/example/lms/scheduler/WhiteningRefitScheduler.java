package com.example.lms.scheduler;

import com.example.lms.service.rag.stats.LowRankWhiteningStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WhiteningRefitScheduler {
    private final LowRankWhiteningStats stats;

    @Scheduled(cron = "0 10 3 * * SUN")
    public void refit() {
        try { stats.refit(); }
        catch (Exception e) { log.warn("[Whiten] refit failed", e); }
    }
}
