package com.example.lms.scheduler;

import com.example.lms.service.vector.VectorBackendHealthService;
import com.example.lms.service.vector.VectorIngestProtectionService;
import com.example.lms.service.vector.VectorQuarantineDlqService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic DLQ redrive scheduler.
 */
@Component
public class VectorDlqRedriveScheduler {

    private static final Logger log = LoggerFactory.getLogger(VectorDlqRedriveScheduler.class);

    @Autowired(required = false)
    private VectorQuarantineDlqService dlqService;

    @Autowired(required = false)
    private VectorBackendHealthService backendHealth;

    @Autowired(required = false)
    private VectorIngestProtectionService ingestProtectionService;

    @Value("${vector.dlq.redrive.enabled:false}")
    private boolean enabled;

    @Value("${vector.dlq.redrive.interval-ms:60000}")
    private long intervalMs;

    @Value("${vector.dlq.redrive.clear-ingest-protection-on-healthy:true}")
    private boolean clearIngestProtectionOnHealthy;

    @Scheduled(fixedDelayString = "${vector.dlq.redrive.interval-ms:60000}")
    public void tick() {
        if (!enabled) return;
        if (dlqService == null || !dlqService.isEnabled()) return;

        if (backendHealth != null) {
            // Ensure we have a recent probe; probeNow is a no-op if probe is disabled.
            backendHealth.probeNow();

            if (!backendHealth.isStableHealthy()) {
                return;
            }
        }

        VectorQuarantineDlqService.RedriveReport r = dlqService.redriveDueOnce("scheduler");
        if (r == null || !r.enabled()) return;

        if (r.attempted() > 0) {
            log.info("[VectorDLQ] redrive tick: claimed={}, attempted={}, ok={}, blocked={}, failed={}, deferred={} (leaseKey={})",
                    r.claimed(), r.attempted(), r.succeeded(), r.blocked(), r.failed(), r.deferred(), r.leaseKey());
        }

        if (clearIngestProtectionOnHealthy && r.succeeded() > 0 && ingestProtectionService != null) {
            // Clear global quarantine once we can safely redrive.
            try {
                ingestProtectionService.clearQuarantine("");
            } catch (Exception ignore) {
                // fail-soft
            }
        }
    }
}
