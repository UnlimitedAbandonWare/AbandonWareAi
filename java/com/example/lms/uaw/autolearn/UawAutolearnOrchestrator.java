package com.example.lms.uaw.autolearn;

import com.example.lms.uaw.presence.UserAbsenceGate;
import com.example.lms.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.management.ManagementFactory;
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
    private final UawAutolearnService autolearnService;
    private final AutolearnRagRetrainOrchestrator retrainOrchestrator;
    private final AutoLearnBudgetManager budgetManager;

    private final ReentrantLock lock = new ReentrantLock();

    public UawAutolearnOrchestrator(Environment env,
                                   UawAutolearnProperties props,
                                   UserAbsenceGate userAbsenceGate,
                                   UawAutolearnService autolearnService,
                                   AutolearnRagRetrainOrchestrator retrainOrchestrator,
                                   AutoLearnBudgetManager budgetManager) {
        this.env = env;
        this.props = props;
        this.userAbsenceGate = userAbsenceGate;
        this.autolearnService = autolearnService;
        this.retrainOrchestrator = retrainOrchestrator;
        this.budgetManager = budgetManager;
    }

    @Scheduled(fixedDelayString = "${uaw.autolearn.tickMs:${idle.pollMillis:60000}}", scheduler = "uawAutolearnTaskScheduler")
    public void tick() {
        if (!isEnabled()) return;

        if (!userAbsenceGate.isUserAbsentNow()) {
            log.debug("[UAW] Skip: not user-absent.");
            return;
        }
        if (!hasSpareCapacity()) {
            log.debug("[UAW] Skip: no spare capacity (cpu high).");
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

    private boolean hasSpareCapacity() {
        double threshold = props.getIdle().getCpuThreshold();
        if (threshold < 0) return true;
        double cpu = systemCpuLoad();
        // If unavailable (-1), don't block.
        return cpu < 0 || cpu <= threshold;
    }

    private static double systemCpuLoad() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean mx = (com.sun.management.OperatingSystemMXBean) os;
                double sys = normalizeCpu(mx.getSystemCpuLoad());
                double proc = normalizeCpu(mx.getProcessCpuLoad());
                if (sys >= 0 && proc >= 0) return Math.max(sys, proc);
                if (sys >= 0) return sys;
                if (proc >= 0) return proc;
            }
        } catch (Throwable ignored) {
        }
        return -1.0;
    }

    private static double normalizeCpu(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return -1.0;
        if (v < 0) return -1.0;
        // Some platforms report 0..100 (percent)
        if (v > 1.0) v = v / 100.0;
        return v;
    }
}
