package com.abandonwareai.nova.autolearn;

import com.sun.management.OperatingSystemMXBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects whether the system is idle enough to run background AutoLearn cycles.
 * Criteria (configurable):
 * - No live requests recorded for idleQuietMillis
 * - System CPU load below maxCpuLoad (0.0~1.0)
 */
@Component
public class IdleDetector {
    private final long idleQuietMillis;
    private final double maxCpuLoad;
    private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

    public IdleDetector(
            @Value("${idle.quietMillis:90000}") long idleQuietMillis,
            @Value("${idle.maxCpuLoad:0.35}") double maxCpuLoad
    ) {
        this.idleQuietMillis = idleQuietMillis;
        this.maxCpuLoad = maxCpuLoad;
    }

    public void markActivity() {
        lastActivity.set(System.currentTimeMillis());
    }

    public boolean isIdle() {
        long since = System.currentTimeMillis() - lastActivity.get();
        if (since < idleQuietMillis) return false;
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double load = os.getSystemCpuLoad(); // -1 if unavailable
        return load >= 0 && load <= maxCpuLoad;
    }

    public String snapshot() {
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double load = os.getSystemCpuLoad();
        return "idleQuietMillis=" + idleQuietMillis + ", maxCpuLoad=" + maxCpuLoad +
               ", cpuLoad=" + String.format("%.2f", load) +
               ", lastActivity=" + Instant.ofEpochMilli(lastActivity.get());
    }
}
