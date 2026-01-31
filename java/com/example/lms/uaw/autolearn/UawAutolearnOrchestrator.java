package com.example.lms.uaw.autolearn;

import com.example.lms.uaw.presence.UserAbsenceGate;
import com.example.lms.uaw.orchestration.UawOrchestrationGate;
import com.example.lms.uaw.selfclean.UawSelfCleanOrchestrator;
import com.example.lms.orchestration.OrchStageKeys;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.trace.TraceContext;
import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single entry point orchestrator:
 * <pre>
 * user absent + spare capacity => autolearn dataset append => optional retrain ingest
 * </pre>
 */
@Component
public class UawAutolearnOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(UawAutolearnOrchestrator.class);

    private final Environment env;
    private final UawAutolearnProperties props;
    private final UserAbsenceGate userAbsenceGate;
    private final UawOrchestrationGate uawGate;
    private final UawAutolearnService autolearnService;
    private final AutolearnRagRetrainOrchestrator retrainOrchestrator;
    private final AutoLearnBudgetManager budgetManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FailurePatternOrchestrator failurePatterns;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UawSelfCleanOrchestrator selfCleanOrchestrator;

    private final ReentrantLock lock = new ReentrantLock();

    public UawAutolearnOrchestrator(Environment env,
                                   UawAutolearnProperties props,
                                   UserAbsenceGate userAbsenceGate,
                                   UawOrchestrationGate uawGate,
                                   UawAutolearnService autolearnService,
                                   AutolearnRagRetrainOrchestrator retrainOrchestrator,
                                   AutoLearnBudgetManager budgetManager) {
        this.env = env;
        this.props = props;
        this.userAbsenceGate = userAbsenceGate;
        this.uawGate = uawGate;
        this.autolearnService = autolearnService;
        this.retrainOrchestrator = retrainOrchestrator;
        this.budgetManager = budgetManager;
    }

    @Scheduled(fixedDelayString = "${uaw.autolearn.tickMs:${idle.pollMillis:60000}}", scheduler = "uawAutolearnTaskScheduler")
    public void tick() {
        if (!isEnabled()) return;

        // [PATCH] 공통 게이트: 유저 부재 + CPU 여유 + 주요 breaker OPEN 여부
        double idleThreshold = props.getIdle().getCpuThreshold();
        UawOrchestrationGate.Decision d = uawGate.decide(
                OrchStageKeys.UAW_AUTOLEARN,
                idleThreshold,
                NightmareKeys.CHAT_DRAFT,
                NightmareKeys.FAST_LLM_COMPLETE
        );
        if (!d.allowed()) {
            switch (d.reason()) {
                case "user_present" -> log.debug("[UAW] Skip: not user-absent.");
                case "cpu_high" -> log.debug("[UAW] Skip: cpu high load={} threshold={}", d.cpuLoad(), idleThreshold);
                case "breaker_open" -> log.debug("[UAW] Skip: breaker-open.");
                case "stage_disabled" -> log.debug("[UAW] Skip: stage policy disabled.");
                default -> log.debug("[UAW] Skip: {}", d.reason());
            }
            return;
        }

        if (failurePatterns != null && failurePatterns.isCoolingDown("llm")) {
            log.warn("[UAW] Skip: llm failure cooldown.");
            return;
        }

        if (!lock.tryLock()) {
            // already running
            return;
        }

        AutoLearnBudgetManager.BudgetLease lease = null;
        long startMs = System.currentTimeMillis();
        boolean success = false;
        try {
            lease = budgetManager.tryAcquire();
            if (lease == null) {
                log.debug("[UAW] Skip: budget/cooldown/backoff.");
                return;
            }

            if (lease.probe()) {
                log.info("[UAW] Probe budget lease granted (fail-soft recovery check).");
            }

            String sessionId = "uaw-idle-" + startMs;
			try (TraceContext ignored = TraceContext.attach(sessionId, "uaw-" + startMs)) {
				long deadline = System.nanoTime() + Duration.ofSeconds(Math.max(1, props.getMaxCycleSeconds())).toNanos();

				PreemptionToken token = () -> !userAbsenceGate.isUserAbsentNow();

				File dataset = new File(resolveDatasetPath());
				AutoLearnCycleResult result = autolearnService.runCycle(dataset, sessionId, token, deadline);

				if (result.abortedByUser()) {
					log.info("[UAW] User returned; abort cycle (attempted={}, accepted={}).", result.attempted(), result.acceptedCount());
					success = true; // aborted is not a failure
					return;
				}

				if (result.acceptedCount() > 0) {
					log.info("[UAW] Cycle done: attempted={}, accepted={}, dataset={}",
							result.attempted(), result.acceptedCount(), result.datasetPath());
				}

            // Closed-loop: retrain/reindex step
				retrainOrchestrator.maybeRetrain(dataset.toPath(), result.acceptedCount(), token);

				// [PATCH] Optional: run one self-clean cycle after retrain (shadow-merge/quarantine-redrive/global rebuild)
				if (selfCleanOrchestrator != null) {
					try {
						selfCleanOrchestrator.tick();
					} catch (Exception sc) {
						log.debug("[UAW] self-clean invocation failed (fail-soft): {}", sc.toString());
					}
				}

				success = true;
			}
        } catch (Exception e) {
            log.warn("[UAW] tick error: {}", e.toString());
        } finally {
            if (lease != null) {
                budgetManager.onFinish(lease, success);
            }
            lock.unlock();
            long dur = System.currentTimeMillis() - startMs;
            if (dur > 1000) {
                log.debug("[UAW] tick finished in {} ms", dur);
            }
        }
    }

    private boolean isEnabled() {
        Boolean uaw = env.getProperty("uaw.autolearn.enabled", Boolean.class);
        if (uaw != null) return uaw;
        Boolean legacy = env.getProperty("autolearn.enabled", Boolean.class);
        if (legacy != null) return legacy;
        return env.getProperty("train_idle.enabled", Boolean.class, false);
    }

    private String resolveDatasetPath() {
        String p = env.getProperty("uaw.autolearn.dataset.path");
        if (p != null && !p.isBlank()) return p;
        p = env.getProperty("autolearn.dataset.path");
        if (p != null && !p.isBlank()) return p;
        p = env.getProperty("dataset.train-file-path");
        if (p != null && !p.isBlank()) return p;
        return props.getDataset().getPath();
    }

}
