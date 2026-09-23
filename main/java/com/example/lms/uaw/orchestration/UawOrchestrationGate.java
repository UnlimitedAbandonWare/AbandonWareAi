package com.example.lms.uaw.orchestration;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.orchestration.StagePolicyProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
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

    private static final System.Logger LOG = System.getLogger(UawOrchestrationGate.class.getName());

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
        } catch (Exception e) {
            // fail-soft: ignore stage policy errors
            TraceStore.put("uaw.orchestrationGate.suppressed.stagePolicy", true);
            traceFailSoft("stagePolicy", stageKey, e);
        }

        // User presence gate
        try {
            if (absenceGate != null && !absenceGate.isUserAbsentNow()) {
                return new Decision(false, "user_present", -1.0);
            }
        } catch (Exception e) {
            // fail-soft: if presence tracker fails, don't block
            TraceStore.put("uaw.orchestrationGate.suppressed.presence", true);
            traceFailSoft("presence", stageKey, e);
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
                        } catch (Throwable e) {
                            // fail-soft
                            TraceStore.put("uaw.orchestrationGate.suppressed.breakerPrefix", true);
                            traceFailSoft("breakerPrefix", stageKey, e);
                        }
                    }
                }
                if (open) {
                    return new Decision(false, "breaker_open", cpu);
                }
            }
        } catch (Exception e) {
            // fail-soft
            TraceStore.put("uaw.orchestrationGate.suppressed.breaker", true);
            traceFailSoft("breaker", stageKey, e);
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
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.DEBUG,
                    "UAW orchestration gate CPU probe skipped stage=system_cpu_load errorType="
                            + SafeRedactor.traceLabelOrFallback(errorType(t), "unknown"));
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

    private static void traceFailSoft(String source, String stageKey, Throwable error) {
        try {
            String prefix = "uaw.orchestrationGate." + source + ".";
            TraceStore.put(prefix + "failSoft", true);
            TraceStore.put(prefix + "stageKeyHash", hashOrEmpty(stageKey));
            TraceStore.put(prefix + "errorClass", error == null ? "" : error.getClass().getSimpleName());
            TraceStore.put(prefix + "errorType",
                    SafeRedactor.traceLabelOrFallback(errorType(error), "unknown"));
        } catch (Throwable ignored) {
            LOG.log(System.Logger.Level.DEBUG,
                    "UAW orchestration gate trace skipped source="
                            + SafeRedactor.traceLabelOrFallback(source, "unknown"));
        }
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private static String errorType(Throwable t) {
        return t == null ? null : t.getClass().getSimpleName();
    }
}
