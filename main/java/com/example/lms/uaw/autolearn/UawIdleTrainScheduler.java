package com.example.lms.uaw.autolearn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * UAW.txt에서 언급한 "idle train" 크론 스케줄러.
 * 기본 꺼짐: train_idle.cron.enabled=false
 */
@Component
public class UawIdleTrainScheduler {

    private static final Logger log = LoggerFactory.getLogger(UawIdleTrainScheduler.class);

    private final UawAutolearnOrchestrator orchestrator;

    @Value("${train_idle.enabled:false}")
    private boolean trainIdleEnabled;

    @Value("${train_idle.cron.enabled:false}")
    private boolean cronEnabled;

    public UawIdleTrainScheduler(UawAutolearnOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(cron = "${train_idle.cron.am:0 30 0 * * *}", zone = "${train_idle.cron.zone:Asia/Seoul}")
    public void runAt0030() {
        if (!trainIdleEnabled || !cronEnabled) return;
        log.info("[UAW] cron trigger @00:30");
        orchestrator.tick();
    }

    @Scheduled(cron = "${train_idle.cron.pm:0 30 4 * * *}", zone = "${train_idle.cron.zone:Asia/Seoul}")
    public void runAt0430() {
        if (!trainIdleEnabled || !cronEnabled) return;
        log.info("[UAW] cron trigger @04:30");
        orchestrator.tick();
    }
}
