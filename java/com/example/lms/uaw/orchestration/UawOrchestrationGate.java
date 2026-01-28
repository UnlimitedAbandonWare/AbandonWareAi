package com.example.lms.uaw.orchestration;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.orchestration.StagePolicyProperties;
import com.example.lms.uaw.presence.UserAbsenceGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * Common gate for UAW/background orchestrators.
 *
 * <p>Motivation (DROP.txt):
 * - Background jobs should not amplify transient front-path failures.
 * - When key breakers are open, or the user is present, skip work.
 * - Optional: skip when CPU is above an idle threshold.
 */
@Component
public class UawOrchestrationGate {

    public record Decision(boolean allowed, String reason, double cpuLoad) {
    }

    @Autowired(required = false)
    private StagePolicyProperties stagePolicy;

    private final UserAbsenceGate absenceGate;

    @Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    public UawOrchestrationGate(UserAbsenceGate absenceGate) {
        this.absenceGate = absenceGate;
    }

    /**
     * Decide whether a UAW stage is allowed to run now.
     *
     * @param stageKey          stage key (see OrchStageKeys)
     * @param idleCpuThreshold  cpu threshold (0..1). negative disables the check.
     * @param breakerKeys       if any of these breakers are open, skip.
     */
    public Decision decide(String stageKey, double idleCpuThreshold, String... breakerKeys) {
        // Stage policy: if configured and disabled -> skip
        try {
            if (stagePolicy != null && stagePolicy.isEnabled()) {
                boolean enabled = stagePolicy.isStageEnabled(stageKey, "UAW", true);
                if (!enabled) {
                    return new Decision(false, "stage_disabled", -1.0);
                }
            }
        } catch (Exception ignore) {
            // fail-soft: ignore stage policy errors
        }

        // User presence gate
        try {
            if (absenceGate != null && !absenceGate.isUserAbsentNow()) {
                return new Decision(false, "user_present", -1.0);
            }
        } catch (Exception ignore) {
            // fail-soft: if presence tracker fails, don't block
        }

        // CPU idle check
        double cpu = systemCpuLoad();
        if (idleCpuThreshold >= 0 && cpu >= 0 && cpu > idleCpuThreshold) {
            return new Decision(false, "cpu_high", cpu);
        }

        // Breaker gate
        try {
            if (nightmareBreaker != null && breakerKeys != null && breakerKeys.length > 0) {
                boolean open = nightmareBreaker.isAnyOpen(breakerKeys);
                if (!open) {
                    // Support wildcard-ish keys like chat:draft:<model> by prefix matching.
                    for (String k : breakerKeys) {
                        if (k == null || k.isBlank()) continue;
                        try {
                            if (nightmareBreaker.isAnyOpenPrefix(k)) {
                                open = true;
                                break;
                            }
                        } catch (Throwable ignore) {
                            // fail-soft
                        }
                    }
                }
                if (open) {
                    return new Decision(false, "breaker_open", cpu);
                }
            }
        } catch (Exception ignore) {
            // fail-soft
        }

        return new Decision(true, "ok", cpu);
    }

    private static double systemCpuLoad() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean mx) {
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
