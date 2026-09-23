package ai.abandonware.nova.boot.exec.zombie;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

public class DefaultZombieBreederDetector implements ZombieBreederDetector {
    private final ZombieBreederProperties props;
    private final IntSupplier blockedThreadCounter;
    private final LongSupplier clockMs;

    private long windowStartedMs = Long.MIN_VALUE;
    private long invokeAllBaseline;
    private volatile Snapshot lastSnapshot = new Snapshot(0, 0, ContaminationVerdict.CLEAN);

    public DefaultZombieBreederDetector(ZombieBreederProperties props) {
        this(props, DefaultZombieBreederDetector::blockedThreadCount, System::currentTimeMillis);
    }

    DefaultZombieBreederDetector(ZombieBreederProperties props, IntSupplier blockedThreadCounter, LongSupplier clockMs) {
        this.props = Objects.requireNonNullElseGet(props, ZombieBreederProperties::new);
        this.blockedThreadCounter = Objects.requireNonNull(blockedThreadCounter, "blockedThreadCounter");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
    }

    @Override
    public synchronized ContaminationVerdict assess(String ownerHint, int currentInflight) {
        long now = Math.max(0L, clockMs.getAsLong());
        long windowMs = Math.max(1L, props.getDetectionWindowMs());
        long totalInvokeAll = totalInvokeAllCount();
        int safeInflight = Math.max(0, currentInflight);

        if (windowStartedMs == Long.MIN_VALUE || now - windowStartedMs > windowMs) {
            windowStartedMs = now;
            invokeAllBaseline = Math.max(0L, totalInvokeAll - safeInflight);
        }

        int blockedCount = Math.max(0, blockedThreadCounter.getAsInt());
        long spawnRate = Math.max(safeInflight, Math.max(0L, totalInvokeAll - invokeAllBaseline));
        boolean blockedPressure = blockedCount >= Math.max(1, props.getBlockedThreadThreshold());
        boolean spawnPressure = spawnRate >= Math.max(1, props.getSpawnRateThreshold());
        ContaminationVerdict verdict;
        if (blockedPressure && spawnPressure) {
            verdict = ContaminationVerdict.CONTAMINATED;
        } else if (blockedPressure || spawnPressure) {
            verdict = ContaminationVerdict.SUSPECT;
        } else {
            verdict = ContaminationVerdict.CLEAN;
        }
        lastSnapshot = new Snapshot(blockedCount, spawnRate, verdict);
        return verdict;
    }

    @Override
    public void recordVerdict(ContaminationVerdict verdict, String ownerHint) {
        Snapshot snapshot = lastSnapshot;
        String prefix = tracePrefix();
        TraceStore.put(prefix + ".detector.verdict", verdict == null ? ContaminationVerdict.CLEAN.name() : verdict.name());
        TraceStore.put(prefix + ".detector.blockedCount", snapshot.blockedCount());
        TraceStore.put(prefix + ".detector.spawnRateInWindow", snapshot.spawnRateInWindow());
        TraceStore.put(prefix + ".detector.ownerHash", ownerHash(ownerHint));
    }

    private static long totalInvokeAllCount() {
        return TraceStore.getLong("ops.cancelShield.invokeAll.used")
                + TraceStore.getLong("ops.cancelShield.invokeAll.timeout.used");
    }

    private String tracePrefix() {
        String raw = props.getTraceKeyPrefix();
        if (raw == null || raw.isBlank()) {
            return "zombie";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_.-]{1,48}")) {
            return "zombie";
        }
        return normalized;
    }

    private static String ownerHash(String ownerHint) {
        String hash = SafeRedactor.hashValue(ownerHint);
        return hash == null || hash.isBlank() ? "hash:unknown" : hash;
    }

    private static int blockedThreadCount() {
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            long[] ids = bean.getAllThreadIds();
            ThreadInfo[] infos = bean.getThreadInfo(ids, 0);
            int count = 0;
            if (infos != null) {
                for (ThreadInfo info : infos) {
                    if (info != null && info.getThreadState() == Thread.State.BLOCKED) {
                        count++;
                    }
                }
            }
            return count;
        } catch (Throwable error) {
            TraceStore.put("zombie.detector.error", SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown"));
            TraceStore.put("zombie.detector.errorHash", SafeRedactor.hashValue(messageOf(error)));
            TraceStore.put("zombie.detector.errorLength", messageLength(error));
            return 0;
        }
    }

    private static String messageOf(Throwable error) {
        return error == null ? null : error.getMessage();
    }

    private static int messageLength(Throwable error) {
        String message = messageOf(error);
        return message == null ? 0 : message.length();
    }

    private record Snapshot(int blockedCount, long spawnRateInWindow, ContaminationVerdict verdict) {
    }
}
