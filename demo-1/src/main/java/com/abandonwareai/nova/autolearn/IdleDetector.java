package com.abandonwareai.nova.autolearn;

import com.sun.management.OperatingSystemMXBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Instant;

/**
 * Detects whether the system is idle enough to run background AutoLearn.
 *
 * <p>Definition = user absent + spare capacity:
 * <ul>
 *   <li>activeRequests == 0</li>
 *   <li>now - lastRequestFinishedAt >= idleQuietMillis</li>
 *   <li>(optional) system CPU load <= maxCpuLoad</li>
 * </ul>
 */
@Component
public class IdleDetector {
    private final long idleQuietMillis;
    private final double maxCpuLoad;
    private final ActiveRequestsCounter counter;

    public IdleDetector(ActiveRequestsCounter counter,
                        @Value("${idle.quietMillis:90000}") long idleQuietMillis,
                        @Value("${idle.maxCpuLoad:0.35}") double maxCpuLoad) {
        this.counter = counter;
        this.idleQuietMillis = idleQuietMillis;
        this.maxCpuLoad = maxCpuLoad;
    }

    public boolean isIdle() {
        long now = System.currentTimeMillis();
        long sinceLastFinished = now - counter.getLastRequestFinishedAt();
        if (counter.get() > 0) return false;
        if (sinceLastFinished < idleQuietMillis) return false;

        double load = systemCpuLoad(); // -1 if unavailable
        return load < 0 || load <= maxCpuLoad;
    }

    public String snapshot() {
        double load = systemCpuLoad();
        return "activeRequests=" + counter.get() +
                ", idleQuietMillis=" + idleQuietMillis +
                ", maxCpuLoad=" + maxCpuLoad +
                ", cpuLoad=" + String.format("%.2f", load) +
                ", lastFinishedAt=" + Instant.ofEpochMilli(counter.getLastRequestFinishedAt());
    }

    private static double systemCpuLoad() {
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return os.getSystemCpuLoad();
        } catch (Throwable ignored) {
            return -1.0;
        }
    }
}
